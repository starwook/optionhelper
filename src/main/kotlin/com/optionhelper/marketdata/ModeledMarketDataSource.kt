package com.optionhelper.marketdata

import com.optionhelper.option.OptionChain
import com.optionhelper.option.OptionQuote
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.PricingProperties
import com.optionhelper.pricing.TheoreticalChain
import com.optionhelper.watchlist.WatchedSymbol
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 공개 사이트의 시세: 기초자산 가격은 [UnderlyingPriceSource], 옵션 만기·행사가는 종목의 상장 규칙, 호가·IV·델타는 이론가([TheoreticalChain]).
 * 나머지 코드(대시보드·전략·차트 등)는 [MarketDataSource]만 보므로 실제 시세 소스와 똑같이 동작한다.
 *
 * TODO(로그인): 소유자가 로그인했을 때만 실제 시세(IBKR/Alpaca, 재배포 불가)를 보여 주려면 [MarketDataSource] 구현을 하나 더 두고
 *  요청마다 로그인 여부로 고른다. 그때 서비스 캐시와 DB 기록(스냅샷·daily_iv)은 소스 이름으로 나눠 실제 시세가 공개 화면에 새지 않게 한다.
 */
@Component
class ModeledMarketDataSource(
    private val underlying: UnderlyingPriceSource,
    private val pricing: PricingProperties,
    private val clock: Clock,
) : MarketDataSource {
    override val feed =
        DataFeed(
            name = "${underlying.name} · 이론가",
            kind = FeedKind.APPROXIMATE,
            note = "${underlying.note} 옵션 호가·IV·그릭스는 실제 호가가 아니라 블랙-숄즈로 계산한 근사값(이론가)이고, 만기·행사가도 상장 규칙으로 만든 것이라 실제와 다를 수 있습니다.",
        )

    override val historicalIvLabel = "이론가 ATM IV(최근 20거래일 HV 기반 근사값)"

    private data class DailyIvCache(val day: LocalDate, val atmIv: Double)

    private val atmIvCache = ConcurrentHashMap<String, DailyIvCache>()

    override fun stockQuote(symbol: String) = underlying.stockQuote(symbol)

    override fun dailyBars(
        symbol: String,
        from: LocalDate,
    ) = underlying.dailyBars(symbol, from)

    override fun intradayBars(
        symbol: String,
        date: LocalDate,
    ) = underlying.intradayBars(symbol, date)

    override fun minuteBars(
        symbol: String,
        minutes: Int,
        from: Instant,
        to: Instant,
    ) = underlying.minuteBars(symbol, minutes, from, to)

    /** 날마다 그날까지의 일봉으로 정한 이론가 ATM IV(화면 옵션과 같은 식). IV Rank·퍼센타일은 사실상 HV의 위치가 된다. */
    override fun dailyImpliedVolatility(
        symbol: String,
        from: LocalDate,
    ): List<DailyIv> {
        val bars = underlying.dailyBars(symbol, from.minusDays(HV_LOOKBACK_DAYS))
        val closes = bars.map { it.close }
        return bars.indices
            .filter { !bars[it].date.isBefore(from) }
            .mapNotNull { i -> TheoreticalChain.atmIvOrNull(closes.subList(0, i + 1))?.let { DailyIv(bars[i].date, it) } }
    }

    override fun expirations(
        symbol: String,
        spot: Double,
    ): List<LocalDate> = WatchedSymbol.of(symbol).listing.expirations(firstListedDay())

    override fun chain(
        symbol: String,
        expiry: LocalDate,
        minStrike: Double,
        maxStrike: Double,
    ): OptionChain {
        val listing = WatchedSymbol.of(symbol).listing
        val spot = underlying.stockQuote(symbol).price
        val strikes = listing.strikeStep(expiry, firstListedDay()).strikes(minStrike, maxStrike)
        val years = maxOf(MarketClock.yearsUntil(clock.instant(), expiry), MIN_YEARS)
        return TheoreticalChain.chain(symbol, expiry, strikes, spot, years, pricing.riskFreeRate, atmIv(symbol))
    }

    /** 이론가는 아무 행사가나 계산할 수 있어서, 상장 규칙이 바뀌어도(월물 → 주간 구간) 보유 옵션 값이 끊기지 않는다. */
    override fun quote(
        symbol: String,
        type: OptionType,
        strike: Double,
        expiry: LocalDate,
    ): OptionQuote {
        val spot = underlying.stockQuote(symbol).price
        val years = maxOf(MarketClock.yearsUntil(clock.instant(), expiry), MIN_YEARS)
        return TheoreticalChain.quote(type, strike, spot, years, pricing.riskFreeRate, atmIv(symbol))
    }

    /** 오늘 장이 끝났으면 다음 날부터(0DTE는 장 마감 전까지만 고를 수 있다). */
    private fun firstListedDay(): LocalDate {
        val now = clock.instant().atZone(MarketClock.NEW_YORK)
        val today = now.toLocalDate()
        val closeToday = ZonedDateTime.of(today, MarketClock.closeOf(today), MarketClock.NEW_YORK)
        return if (MarketClock.isTradingDay(today) && !now.isBefore(closeToday)) today.plusDays(1) else today
    }

    /** 하루 한 번 일봉으로 ATM IV를 정한다. */
    private fun atmIv(symbol: String): Double {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        atmIvCache[symbol]?.takeIf { it.day == today }?.let { return it.atmIv }
        val closes = underlying.dailyBars(symbol, today.minusDays(HV_LOOKBACK_DAYS)).map { it.close }
        return TheoreticalChain.atmIv(closes).also { atmIvCache[symbol] = DailyIvCache(today, it) }
    }

    private companion object {
        // 20거래일 HV에 넉넉한 달력 일수
        const val HV_LOOKBACK_DAYS = 45L

        // 만기 직전 0으로 나누지 않게 15분
        const val MIN_YEARS = 1.0 / (365 * 24 * 4)
    }
}
