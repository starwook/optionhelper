package com.optionhelper.stockchart

import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.pricing.MarketClock
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 봉 차트. intraday면 분봉(time = 봉 시작 시각), 아니면 일·주·월·년봉(time = 기간 첫날 UTC 자정).
 * previousClose는 분봉일 때 마지막 장의 전일 종가(점선). live는 마지막 봉에 오늘 장이 들어 있는지
 * (그러면 화면이 대시보드 현재가로 마지막 봉을 이어 그린다).
 */
data class StockChartResponse(
    val symbol: String,
    val interval: ChartInterval,
    val intraday: Boolean,
    val previousClose: Double?,
    val live: Boolean,
    val candles: List<Candle>,
)

class StockChartUnavailableException(message: String) : RuntimeException(message)

/** 대시보드와 따로 받는다. 차트가 느리거나 실패해도 대시보드가 늦어지지 않게. */
@Service
class StockChartService(
    private val source: MarketDataSource,
    private val dailyBars: DailyBarService,
    private val clock: Clock,
) {
    private data class Cached<T>(val at: Instant, val value: T)

    // (종목, 소스 분봉 단위) → 받은 분봉. 일·주·월·년봉의 오늘 봉은 5분봉 캐시로 만든다
    private val minuteCache = ConcurrentHashMap<Pair<String, Int>, Cached<List<IntradayBar>>>()

    private data class Price(val value: Double?)

    private val previousCloseCache = ConcurrentHashMap<Pair<String, LocalDate>, Price>()

    fun chart(
        symbol: String,
        interval: ChartInterval,
    ): StockChartResponse {
        val ticker = symbol.trim().uppercase()
        return try {
            if (interval.intraday) intraday(ticker, interval) else periodic(ticker, interval)
        } catch (e: Exception) {
            throw StockChartUnavailableException("주가 차트를 가져오지 못했습니다: ${e.message}")
        }
    }

    private fun intraday(
        symbol: String,
        interval: ChartInterval,
    ): StockChartResponse {
        val candles = Candles.intraday(minuteBars(symbol, interval.sourceMinutes!!), interval.minutes!!)
        val lastSession = candles.lastOrNull()?.date()
        val previousClose = lastSession?.let { previousClose(symbol, it) }
        return StockChartResponse(symbol, interval, true, previousClose, lastSession == today(), candles)
    }

    private fun periodic(
        symbol: String,
        interval: ChartInterval,
    ): StockChartResponse {
        val today = today()
        // 일봉은 어제까지라, 오늘 장이 열렸으면 오늘 정규장 5분봉으로 오늘 봉을 붙인다
        val todayBar =
            today.takeIf { MarketClock.lastSessionDate(clock.instant()) == it }?.let { date ->
                Candles.dailyFrom(date, Candles.intraday(minuteBars(symbol, 5), 5).filter { it.date() == date })
            }
        val bars = dailyBars.bars(symbol) + listOfNotNull(todayBar)
        return StockChartResponse(symbol, interval, false, null, todayBar != null, Candles.periodic(bars, interval))
    }

    /** 소스에서 받을 수 있는 만큼(최근 5000개) 받아 30초 동안 쓴다. */
    private fun minuteBars(
        symbol: String,
        minutes: Int,
    ): List<IntradayBar> {
        val now = clock.instant()
        val key = symbol to minutes
        minuteCache[key]?.takeIf { Duration.between(it.at, now) < CACHE_TTL }?.let { return it.value }
        val bars = source.minuteBars(symbol, minutes, now.minus(Duration.ofMinutes(minutes * MAX_BARS)), now)
        minuteCache[key] = Cached(now, bars)
        return bars
    }

    /** 그 장의 전일 종가 = 전 거래일 마지막 5분봉 종가(16:00 ET 가격). 없으면 일봉 종가. 지난 날 값이라 한 번만 받는다. */
    private fun previousClose(
        symbol: String,
        session: LocalDate,
    ): Double? =
        previousCloseCache.getOrPut(symbol to session) {
            Price(
                source.intradayBars(symbol, MarketClock.previousTradingDay(session)).lastOrNull()?.close
                    ?: dailyBars.bars(symbol).lastOrNull { it.date < session }?.close,
            )
        }.value

    private fun today() = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()

    /** 분봉의 뉴욕 날짜. */
    private fun Candle.date() = Instant.ofEpochSecond(time).atZone(MarketClock.NEW_YORK).toLocalDate()

    companion object {
        private val CACHE_TTL: Duration = Duration.ofSeconds(30)

        // Hyperliquid가 한 번에(그리고 최근 것으로) 주는 최대 봉 수
        private const val MAX_BARS = 5000L
    }
}
