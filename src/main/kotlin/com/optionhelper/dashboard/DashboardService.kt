package com.optionhelper.dashboard

import com.optionhelper.earnings.Earnings
import com.optionhelper.earnings.EarningsService
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.option.IvHistory
import com.optionhelper.option.OptionChain
import com.optionhelper.pricing.ImpliedVolatilitySolver
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.PricingProperties
import com.optionhelper.seasonality.Seasonality
import com.optionhelper.signal.SignalInput
import com.optionhelper.signal.StrategySignals
import com.optionhelper.strategy.Strategy
import com.optionhelper.strategy.StrategyCalculator
import com.optionhelper.volatility.ExpectedMove
import com.optionhelper.volatility.RealizedVsExpected
import com.optionhelper.volatility.VolatilityMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class DashboardUnavailableException(message: String) : RuntimeException(message)

/**
 * 시세·실적·일봉·IV 기록을 모아 화면 한 장(DashboardResponse)을 만든다. 계산은 각 도메인에 맡기고 여기서는 조립만 한다.
 * 같은 요청은 5초 동안 캐시해 데이터 소스 호출 한도를 지키고, 소스가 실패하면 마지막 값을 stale로 돌려준다.
 */
@Service
class DashboardService(
    private val source: MarketDataSource,
    private val dailyBars: DailyBarService,
    private val earnings: EarningsService,
    private val ivHistory: IvHistory,
    private val pricing: PricingProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Cached<T>(val at: Instant, val value: T)

    private val cache = ConcurrentHashMap<String, Cached<DashboardResponse>>()
    private val expirationCache = ConcurrentHashMap<String, Cached<List<LocalDate>>>()

    fun dashboard(
        symbol: String,
        expiry: LocalDate?,
    ): DashboardResponse {
        val ticker = symbol.trim().uppercase()
        val key = "$ticker|$expiry"
        val now = clock.instant()
        cache[key]?.takeIf { Duration.between(it.at, now) < CACHE_TTL }?.let { return it.value }
        return try {
            build(ticker, expiry, now).also { cache[key] = Cached(now, it) }
        } catch (e: Exception) {
            log.warn("대시보드 생성 실패({}): {}", key, e.message)
            val last = cache[key]?.value ?: throw DashboardUnavailableException(e.message ?: "데이터를 가져오지 못했습니다")
            last.copy(stale = true, error = e.message)
        }
    }

    private fun build(
        symbol: String,
        requestedExpiry: LocalDate?,
        now: Instant,
    ): DashboardResponse {
        val warnings = mutableListOf<String>()
        val r = pricing.riskFreeRate
        val quote = source.stockQuote(symbol)
        val spot = quote.price
        val expirations = expirations(symbol, spot, now)
        val expiry =
            requestedExpiry?.takeIf { it in expirations }
                ?: expirations.firstOrNull { MarketClock.tradingHoursUntil(now, it) > 0 }
                ?: expirations.firstOrNull()
                ?: error("$symbol 만기 목록이 비었습니다")
        val hours = MarketClock.tradingHoursUntil(now, expiry)
        val t = MarketClock.tradingHoursToYears(hours)
        val chain = source.chain(symbol, expiry, spot * (1 - CHAIN_RANGE), spot * (1 + CHAIN_RANGE))
        val atmStrike = chain.atmStrike(spot)

        val legs = LegBuilder(chain, spot, t, r)
        val atm =
            atmStrike?.let {
                    k ->
                legs.of(OptionType.CALL, k)?.let { c -> legs.of(OptionType.PUT, k)?.let { p -> AtmView(k, c, p) } }
            }
        val straddle = atm?.let { it.call.mid + it.put.mid }
        val expectedMove = straddle?.takeIf { it > 0 }?.let { ExpectedMove.of(it, spot) }
        val realized = if (quote.open != null && expectedMove != null) RealizedVsExpected.of(quote.open, spot, expectedMove.pct) else null

        val bars = loadOrEmpty("일봉", warnings) { dailyBars.bars(symbol) }
        val events = loadOrEmpty("실적 일정", warnings) { earnings.events(symbol) }
        val today = now.atZone(MarketClock.NEW_YORK).toLocalDate()

        val atmIvCalc = average(atm?.call?.ivCalc, atm?.put?.ivCalc)
        val atmIvApi = average(atm?.call?.ivApi, atm?.put?.ivApi)
        val currentIv = atmIvCalc ?: atmIvApi
        val hv20 = VolatilityMetrics.historicalVolatility(bars.map { it.close })
        val ivHvRatio = if (currentIv != null && hv20 != null && hv20 > 0) VolatilityMetrics.ivHvRatio(currentIv, hv20) else null
        val history = runCatching { ivHistory.atmIvs(symbol, now.minus(Duration.ofDays(365))) }.getOrDefault(emptyList())
        val nextExpiry = expirations.firstOrNull { it.isAfter(expiry) }
        val nextExpiryIv = nextExpiry?.let { atmIvOf(symbol, it, spot, now, r) }
        val skew =
            atmStrike?.let { k ->
                val put = chain.strikeAway(k, -SKEW_STEPS)?.let { legs.of(OptionType.PUT, it)?.ivCalc }
                val call = chain.strikeAway(k, SKEW_STEPS)?.let { legs.of(OptionType.CALL, it)?.ivCalc }
                if (put != null && call != null) VolatilityMetrics.skew(put, call) else null
            }

        val next = Earnings.nextEvent(events, today)
        val beforeExpiry = next?.reactsBy(expiry) ?: false
        val stats = Earnings.moveStats(Earnings.reactions(events.filter { it.date.isBefore(today) }, bars))
        val signals = StrategySignals.evaluate(SignalInput(ivHvRatio, expectedMove?.pct, beforeExpiry, stats?.averageAbsMove))

        if (atm != null && atm.call.ivApi == null) warnings += "데이터 소스가 ATM IV를 주지 않았습니다(0DTE·깊은 OTM에서 흔함). 직접 계산값을 보세요."
        if (history.size < MIN_PERCENTILE_SAMPLES) warnings += "IV 기록이 ${history.size}개뿐이라 퍼센타일은 참고만 하세요(장중 1분마다 쌓입니다)."
        if (beforeExpiry) warnings += "선택한 만기 안에 실적발표(${next?.date})가 있습니다."

        return DashboardResponse(
            symbol = symbol,
            feed = FeedView(source.feed.name, source.feed.kind.name, source.feed.kind.label, source.feed.note),
            earningsSource = earnings.sourceName,
            asOf = now,
            asOfEt = format(now, MarketClock.NEW_YORK, "ET"),
            asOfKst = format(now, MarketClock.SEOUL, "KST"),
            marketOpen = MarketClock.isOpen(now),
            stale = false,
            error = null,
            expirations = expirations,
            expiry = expiry,
            expiryEt = format(MarketClock.expiryAt(expiry).toInstant(), MarketClock.NEW_YORK, "ET"),
            expiryKst = format(MarketClock.expiryAt(expiry).toInstant(), MarketClock.SEOUL, "KST"),
            hoursToExpiry = hours,
            spot = spot,
            open = quote.open,
            previousClose = quote.previousClose,
            atm = atm,
            straddle = straddle,
            expectedMove = expectedMove,
            realizedVsExpected = realized,
            volatility =
                VolatilityView(
                    atmIvApi = atmIvApi,
                    atmIvCalc = atmIvCalc,
                    hv20 = hv20,
                    ivHvRatio = ivHvRatio,
                    ivPercentile = currentIv?.let { VolatilityMetrics.ivPercentile(history, it) },
                    ivSamples = history.size,
                    nextExpiry = nextExpiry,
                    nextExpiryIv = nextExpiryIv,
                    termStructureRatio =
                        if (currentIv != null && nextExpiryIv != null) {
                            VolatilityMetrics.termStructureRatio(
                                currentIv,
                                nextExpiryIv,
                            )
                        } else {
                            null
                        },
                    skew = skew,
                ),
            chain = chainRows(chain, atmStrike, legs),
            strategies = strategies(chain, atmStrike, spot, expectedMove),
            earnings = EarningsView(next, next?.let { Earnings.daysUntil(it, today) }, beforeExpiry, stats),
            seasonality = Seasonality.monthStats(bars),
            currentMonth = today.monthValue,
            signals = signals,
            ivHistory =
                runCatching {
                    ivHistory.points(
                        symbol,
                        today.atStartOfDay(MarketClock.NEW_YORK).toInstant(),
                    )
                }.getOrDefault(emptyList()),
            warnings = warnings,
        )
    }

    /** 만기 목록은 자주 바뀌지 않아 10분 캐시. */
    private fun expirations(
        symbol: String,
        spot: Double,
        now: Instant,
    ): List<LocalDate> {
        expirationCache[symbol]?.takeIf { Duration.between(it.at, now) < EXPIRATION_TTL }?.let { return it.value }
        return source.expirations(symbol, spot).also { expirationCache[symbol] = Cached(now, it) }
    }

    /** 기간 구조 비교용: 다른 만기의 ATM IV(직접 역산). 실패해도 화면은 계속 그린다. */
    private fun atmIvOf(
        symbol: String,
        expiry: LocalDate,
        spot: Double,
        now: Instant,
        r: Double,
    ): Double? =
        runCatching {
            val chain = source.chain(symbol, expiry, spot * (1 - NARROW_RANGE), spot * (1 + NARROW_RANGE))
            val k = chain.atmStrike(spot) ?: return null
            val legs = LegBuilder(chain, spot, MarketClock.yearsUntil(now, expiry), r)
            average(legs.of(OptionType.CALL, k)?.ivCalc, legs.of(OptionType.PUT, k)?.ivCalc)
        }.getOrNull()

    private fun chainRows(
        chain: OptionChain,
        atm: Double?,
        legs: LegBuilder,
    ): List<ChainRow> {
        val strikes = chain.strikes
        val center = atm?.let { strikes.indexOf(it) } ?: (strikes.size / 2)
        return strikes.subList(maxOf(0, center - CHAIN_ROWS), minOf(strikes.size, center + CHAIN_ROWS + 1)).map { k ->
            ChainRow(k, legs.of(OptionType.CALL, k), legs.of(OptionType.PUT, k), k == atm)
        }
    }

    private fun strategies(
        chain: OptionChain,
        atm: Double?,
        spot: Double,
        move: ExpectedMove?,
    ): List<StrategyView> {
        if (atm == null) return emptyList()

        fun k(steps: Int) = chain.strikeAway(atm, steps)

        fun mid(
            type: OptionType,
            strike: Double?,
        ) = strike?.let { chain.quote(type, it)?.mid }

        fun two(
            k1: Double?,
            t1: OptionType,
            k2: Double?,
            t2: OptionType,
            make: (Double, Double, Double, Double) -> Strategy,
        ): Strategy? {
            val m1 = mid(t1, k1) ?: return null
            val m2 = mid(t2, k2) ?: return null
            return make(k1!!, m1, k2!!, m2)
        }

        val candidates =
            listOfNotNull(
                two(atm, OptionType.CALL, atm, OptionType.PUT) { k, c, _, p -> Strategy.longStraddle(k, c, p) },
                two(k(-1), OptionType.PUT, k(1), OptionType.CALL, Strategy::longStrangle),
                two(k(-2), OptionType.PUT, k(2), OptionType.CALL, Strategy::longStrangle),
                k(-1)?.let { s -> mid(OptionType.PUT, s)?.let { Strategy.shortPut(s, it) } },
                two(atm, OptionType.CALL, k(2), OptionType.CALL, Strategy::bullCallSpread),
                two(atm, OptionType.PUT, k(-2), OptionType.PUT, Strategy::bearPutSpread),
                two(k(-1), OptionType.PUT, k(-3), OptionType.PUT, Strategy::bullPutCreditSpread),
                two(k(1), OptionType.CALL, k(3), OptionType.CALL, Strategy::bearCallCreditSpread),
            )
        val width = maxOf(MIN_PAYOFF_WIDTH, PAYOFF_WIDTH_MULTIPLE * (move?.pct ?: 0.0))
        return candidates.map { strategy ->
            val result = StrategyCalculator.evaluate(strategy, spot)
            StrategyView(
                kind = strategy.kind.name,
                label = strategy.kind.label,
                legs =
                    strategy.legs.joinToString(" + ") {
                        "${if (it.quantity > 0) "매수" else "매도"} ${fmt(
                            it.strike,
                        )}${if (it.type == OptionType.CALL) "C" else "P"} @${fmt(it.premium)}"
                    },
                netPremium = result.netPremium,
                feePerShare = result.feePerShare,
                breakevens = result.breakevens,
                breakevensWithFee = result.breakevensWithFee,
                maxProfit = result.maxProfit,
                maxLoss = result.maxLoss,
                payoff = StrategyCalculator.payoffCurve(strategy, spot * (1 - width), spot * (1 + width)),
            )
        }
    }

    private fun <T> loadOrEmpty(
        what: String,
        warnings: MutableList<String>,
        load: () -> List<T>,
    ): List<T> =
        try {
            load()
        } catch (e: Exception) {
            log.warn("{} 조회 실패: {}", what, e.message)
            warnings += "$what 조회 실패: ${e.message}"
            emptyList()
        }

    private fun average(vararg values: Double?): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    private fun format(
        instant: Instant,
        zone: ZoneId,
        label: String,
    ) = instant.atZone(zone).format(TIME_FORMAT) + " $label"

    private fun fmt(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    companion object {
        private val CACHE_TTL: Duration = Duration.ofSeconds(5)
        private val EXPIRATION_TTL: Duration = Duration.ofMinutes(10)
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        private const val CHAIN_RANGE = 0.15
        private const val NARROW_RANGE = 0.03
        private const val CHAIN_ROWS = 10
        private const val SKEW_STEPS = 2
        private const val MIN_PERCENTILE_SAMPLES = 100
        private const val MIN_PAYOFF_WIDTH = 0.10
        private const val PAYOFF_WIDTH_MULTIPLE = 2.5
    }
}

/** 체인의 호가 한 줄을 화면용 LegView로(직접 역산 IV 포함). */
private class LegBuilder(
    private val chain: OptionChain,
    private val spot: Double,
    private val t: Double,
    private val r: Double,
) {
    fun of(
        type: OptionType,
        strike: Double,
    ): LegView? =
        chain.quote(type, strike)?.let { q ->
            LegView(q.bid, q.ask, q.mid, q.impliedVolatility, ImpliedVolatilitySolver.solve(type, q.mid, spot, strike, t, r), q.delta)
        }
}
