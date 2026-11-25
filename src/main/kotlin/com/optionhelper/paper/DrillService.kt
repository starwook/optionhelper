package com.optionhelper.paper

import com.optionhelper.dashboard.ChainRow
import com.optionhelper.dashboard.LegView
import com.optionhelper.dashboard.StrategyView
import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.option.OptionChain
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.PricingProperties
import com.optionhelper.pricing.TheoreticalChain
import com.optionhelper.strategy.StrategyMenu
import com.optionhelper.watchlist.WatchedSymbol
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.random.Random

/** 빨리감기 한 판의 만기 하나. day는 게임 날짜(0 = 시작일). */
data class DrillExpiry(val day: Int, val daysLeft: Int)

/** 게임이 끝나면 공개하는 실제 종목·기간·가격. */
data class DrillReveal(val symbol: String, val startDate: LocalDate, val endDate: LocalDate, val startPrice: Double, val endPrice: Double)

data class DrillDayResponse(
    val game: String,
    val day: Int,
    val totalDays: Int,
    val finished: Boolean,
    val spot: Double,
    // 지금까지의 종가(환산값), 오래된 순. 마지막이 오늘. startIndex부터가 게임 기간
    val chart: List<Double>,
    val chartStartIndex: Int,
    val atmIv: Double,
    val expirations: List<DrillExpiry>,
    val expiry: Int?,
    // 고른 만기의 ATM 스트래들 ÷ 주가
    val expectedMovePct: Double?,
    val chain: List<ChainRow>,
    val strategies: List<StrategyView>,
    val reveal: DrillReveal?,
)

data class DrillLeg(val type: OptionType, val strike: Double, val expiry: Int)

data class DrillMarksRequest(val game: String, val day: Int, val legs: List<DrillLeg>)

/**
 * 빨리감기 연습: 과거 날짜·종목을 무작위로 골라, 실제 가격을 가린 채(시작가 100으로 환산) [TOTAL_DAYS]거래일을 넘기며 연습한다.
 * 게임 상태는 게임 ID(종목·시작일)와 날짜만으로 다시 계산할 수 있어 서버에 저장하지 않는다.
 */
@Service
class DrillService(
    private val dailyBars: DailyBarService,
    private val pricing: PricingProperties,
) {
    private data class Game(val id: String, val symbol: String, val bars: List<DailyBar>, val start: Int) {
        val scale = START_PRICE / bars[start].close

        fun index(day: Int) = start + day.coerceIn(0, TOTAL_DAYS)
    }

    fun newGame(random: Random = Random.Default): String {
        val candidates =
            WatchedSymbol.SYMBOLS.mapNotNull { symbol ->
                val bars = dailyBars.bars(symbol)
                val starts = CHART_DAYS..(bars.size - 1 - TOTAL_DAYS)
                if (starts.isEmpty()) null else symbol to bars[starts.random(random)].date
            }
        require(candidates.isNotEmpty()) { "연습에 쓸 만큼 쌓인 일봉이 없습니다" }
        val (symbol, date) = candidates.random(random)
        return encode(symbol, date)
    }

    fun day(
        gameId: String,
        day: Int,
        expiry: Int?,
    ): DrillDayResponse {
        val game = load(gameId)
        val d = day.coerceIn(0, TOTAL_DAYS)
        val i = game.index(d)
        val spot = game.bars[i].close * game.scale
        val atmIv = PaperPricing.atmIvAt(game.bars, i)
        val expirations = expirations(game, d)
        val chosen = expiry?.takeIf { e -> expirations.any { it.day == e } } ?: expirations.firstOrNull()?.day
        val chain = chosen?.let { chainAt(game, i, game.bars[game.index(it)].date, spot, atmIv) }
        val atm = chain?.atmStrike(spot)
        val straddle =
            atm?.let {
                    k ->
                listOfNotNull(chain.quote(OptionType.CALL, k)?.mid, chain.quote(OptionType.PUT, k)?.mid).takeIf { it.size == 2 }?.sum()
            }
        val chartFrom = maxOf(0, game.start - CHART_DAYS)
        val finished = d >= TOTAL_DAYS
        return DrillDayResponse(
            game = game.id,
            day = d,
            totalDays = TOTAL_DAYS,
            finished = finished,
            spot = round2(spot),
            chart = game.bars.subList(chartFrom, i + 1).map { round2(it.close * game.scale) },
            chartStartIndex = game.start - chartFrom,
            atmIv = atmIv,
            expirations = expirations,
            expiry = chosen,
            expectedMovePct = straddle?.let { it / spot },
            chain = if (chain == null || atm == null) emptyList() else rows(chain, atm),
            strategies =
                if (chain == null || atm == null || finished) {
                    emptyList()
                } else {
                    StrategyMenu.build(chain, atm).map { StrategyView.of(it, spot, PAYOFF_WIDTH) }
                },
            reveal =
                if (!finished) {
                    null
                } else {
                    DrillReveal(game.symbol, game.bars[game.start].date, game.bars[i].date, game.bars[game.start].close, game.bars[i].close)
                },
        )
    }

    fun marks(request: DrillMarksRequest): List<LegMark> {
        val game = load(request.game)
        val i = game.index(request.day)
        return request.legs.map {
            PaperPricing.markAt(game.bars, i, it.type, it.strike, game.bars[game.index(it.expiry)].date, pricing.riskFreeRate, game.scale)
        }
    }

    /** 오늘 뒤 게임 기간 안의 주말(그 주 마지막 거래일) 만기. */
    private fun expirations(
        game: Game,
        day: Int,
    ): List<DrillExpiry> =
        ((day + 1)..TOTAL_DAYS).filter { e ->
            val i = game.index(e)
            e == TOTAL_DAYS || game.bars[i + 1].date.dayOfWeek <= game.bars[i].date.dayOfWeek
        }.take(MAX_EXPIRIES).map { DrillExpiry(it, it - day) }

    private fun chainAt(
        game: Game,
        index: Int,
        expiry: LocalDate,
        spot: Double,
        atmIv: Double,
    ): OptionChain {
        // 환산 가격(시작 100)에 맞춘 간격. 크게 오르면 간격을 넓혀 전략의 ±1~3칸이 너무 좁지 않게
        val step =
            if (spot < 150) {
                1.0
            } else if (spot < 300) {
                2.5
            } else {
                5.0
            }
        val center = (spot / step).roundToInt()
        val strikes = ((center - STRIKE_RANGE)..(center + STRIKE_RANGE)).map { it * step }.filter { it > 0 }
        val years = MarketClock.yearsUntil(MarketClock.expiryAt(game.bars[index].date).toInstant(), expiry)
        return TheoreticalChain.chain(game.symbol, expiry, strikes, spot, years, pricing.riskFreeRate, atmIv)
    }

    private fun rows(
        chain: OptionChain,
        atm: Double,
    ): List<ChainRow> {
        fun leg(
            type: OptionType,
            k: Double,
        ) = chain.quote(type, k)?.let { LegView(it.bid, it.ask, it.mid, it.impliedVolatility, null, it.delta) }
        val center = chain.strikes.indexOf(atm)
        return chain.strikes.subList(maxOf(0, center - CHAIN_ROWS), minOf(chain.strikes.size, center + CHAIN_ROWS + 1))
            .map { ChainRow(it, leg(OptionType.CALL, it), leg(OptionType.PUT, it), it == atm) }
    }

    private fun load(gameId: String): Game {
        val (symbol, date) =
            runCatching {
                val (s, d) = String(Base64.getUrlDecoder().decode(gameId)).split(":")
                s to LocalDate.parse(d)
            }.getOrElse { throw IllegalArgumentException("게임 ID가 올바르지 않습니다") }
        val bars = dailyBars.bars(WatchedSymbol.of(symbol).name)
        val start = bars.indexOfFirst { it.date == date }
        require(start >= 0 && start + TOTAL_DAYS < bars.size) { "이 게임의 기간 데이터가 없습니다" }
        return Game(gameId, symbol, bars, start)
    }

    private fun encode(
        symbol: String,
        date: LocalDate,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$symbol:$date".toByteArray())

    private fun round2(value: Double) = Math.round(value * 100) / 100.0

    companion object {
        const val TOTAL_DAYS = 25
        const val START_PRICE = 100.0
        const val CHART_DAYS = 60
        const val STRIKE_RANGE = 20
        const val CHAIN_ROWS = 10
        const val MAX_EXPIRIES = 4
        const val PAYOFF_WIDTH = 0.2
    }
}
