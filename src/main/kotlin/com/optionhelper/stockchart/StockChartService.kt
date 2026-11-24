package com.optionhelper.stockchart

import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.pricing.MarketClock
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

data class ChartPoint(val time: Instant, val price: Double)

/**
 * 차트 선. previousClose는 DAY일 때 그 장의 전일 종가(기준선), sessionClose는 그 장 마감 시각(x축 끝). 일봉 기간이면 둘 다 null.
 * 현재가는 넣지 않는다. 화면이 대시보드 현재가(10초마다 갱신)를 선 끝에 이어 붙인다.
 */
data class StockChartResponse(
    val symbol: String,
    val range: ChartRange,
    val sessionDate: LocalDate?,
    val previousClose: Double?,
    val sessionClose: Instant?,
    val points: List<ChartPoint>,
)

class StockChartUnavailableException(message: String) : RuntimeException(message)

/** 대시보드와 따로 받는다. 차트가 느리거나 실패해도 대시보드가 늦어지지 않게. */
@Service
class StockChartService(
    private val source: MarketDataSource,
    private val dailyBars: DailyBarService,
    private val clock: Clock,
) {
    private data class Cached(val at: Instant, val value: StockChartResponse)

    private val dayCache = ConcurrentHashMap<String, Cached>()

    fun chart(
        symbol: String,
        range: ChartRange,
    ): StockChartResponse {
        val ticker = symbol.trim().uppercase()
        return try {
            if (range == ChartRange.DAY) day(ticker) else daily(ticker, range)
        } catch (e: Exception) {
            throw StockChartUnavailableException("주가 차트를 가져오지 못했습니다: ${e.message}")
        }
    }

    private fun day(symbol: String): StockChartResponse {
        val now = clock.instant()
        dayCache[symbol]?.takeIf { Duration.between(it.at, now) < DAY_TTL }?.let { return it.value }
        // 장이 막 열려 아직 봉이 없으면 그 전 거래일을 보여 준다
        var session = MarketClock.lastSessionDate(now)
        var bars = source.intradayBars(symbol, session)
        if (bars.isEmpty()) {
            session = MarketClock.previousTradingDay(session)
            bars = source.intradayBars(symbol, session)
        }
        val previousClose = dailyBars.bars(symbol).lastOrNull { it.date < session }?.close
        val close = ZonedDateTime.of(session, MarketClock.closeOf(session), MarketClock.NEW_YORK).toInstant()
        val response = StockChartResponse(symbol, ChartRange.DAY, session, previousClose, close, bars.map { ChartPoint(it.time, it.close) })
        dayCache[symbol] = Cached(now, response)
        return response
    }

    private fun daily(
        symbol: String,
        range: ChartRange,
    ): StockChartResponse {
        val points =
            dailyBars.bars(symbol).takeLast(range.tradingDays ?: 0).map {
                ChartPoint(ZonedDateTime.of(it.date, MarketClock.closeOf(it.date), MarketClock.NEW_YORK).toInstant(), it.close)
            }
        return StockChartResponse(symbol, range, null, null, null, points)
    }

    companion object {
        // 5분봉이라 자주 받을 필요 없다. 무료 한도(분당 200회)도 아낀다
        private val DAY_TTL: Duration = Duration.ofSeconds(30)
    }
}
