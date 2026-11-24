package com.optionhelper.marketdata.hyperliquid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.marketdata.SessionClose
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.marketdata.UnderlyingPriceSource
import com.optionhelper.pricing.MarketClock
import com.optionhelper.watchlist.WatchedSymbol
import jakarta.annotation.PreDestroy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 공개 사이트의 기초자산 가격: Hyperliquid 주식 추종 선물(xyz:NVDA 등). 실제 주가가 아니라 근사값이다.
 * 현재가는 웹소켓 마크 가격(약 1초마다), 아직 없거나 오래됐으면 REST(metaAndAssetCtxs). 봉은 REST candleSnapshot.
 * 전일 종가·정규장 마감가는 그 거래일 16:00 ET(정규장 마감) 시점 선물 가격 = 그날 마지막 5분봉 종가.
 */
@Component
@ConditionalOnProperty(prefix = "optionhelper.market-data", name = ["source"], havingValue = "hyperliquid", matchIfMissing = true)
class HyperliquidPriceSource(
    private val properties: HyperliquidProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    restClientBuilder: RestClient.Builder,
) : UnderlyingPriceSource {
    override val name = "해외 가상자산 거래소"
    override val note =
        "주가는 이 거래소의 주식 추종 무기한 선물 참고시세로, 실제 주가·호가가 아니며 실제 주가와 다를 수 있습니다. " +
            "전일 종가는 전 거래일 정규장 마감(16:00 ET) 시점의 선물 가격입니다."

    private val client = restClientBuilder.baseUrl(properties.infoUrl).build()
    private val stream = HyperliquidPriceStream(properties, WatchedSymbol.SYMBOLS.map { properties.coin(it) }, objectMapper, clock)

    // (종목, 거래일) → 그날 정규장 마감가. 지난 날 값은 바뀌지 않으니 한 번만 봉을 받는다(못 구한 날도 기억해 매초 재요청하지 않는다).
    private val regularCloses = ConcurrentHashMap<Pair<String, LocalDate>, CachedClose>()

    @Scheduled(fixedDelay = RECONNECT_CHECK_MS, initialDelay = 0)
    fun keepStreamAlive() = stream.ensureConnected()

    @PreDestroy
    fun shutdown() = stream.close()

    override fun stockQuote(symbol: String): StockQuote {
        val coin = properties.coin(symbol)
        val now = clock.instant()
        val streamed = stream.mark(coin)?.takeIf { Duration.between(it.at, now) < STREAM_MAX_AGE }
        val (price, asOf) = streamed?.let { it.price to it.at } ?: (restMark(coin) to now)
        val today = now.atZone(MarketClock.NEW_YORK).toLocalDate()
        val lastDate = MarketClock.lastClosedSessionDate(now)
        val lastClose =
            regularClose(
                symbol,
                lastDate,
            )?.let { SessionClose(lastDate, it, regularClose(symbol, MarketClock.previousTradingDay(lastDate))) }
        return StockQuote(symbol.uppercase(), price, regularClose(symbol, MarketClock.previousTradingDay(today)), asOf, lastClose)
    }

    /** 그 거래일 정규장 마감 가격(마지막 5분봉 종가). 5분봉이 없으면 UTC 일봉 종가로 대신한다. */
    private fun regularClose(
        symbol: String,
        date: LocalDate,
    ): Double? {
        val key = symbol to date
        regularCloses[key]?.let { return it.close }
        val close =
            intradayBars(symbol, date).lastOrNull()?.close
                ?: dailyBars(symbol, date).firstOrNull { it.date == date }?.close
        // 마감 직후엔 마지막 봉이 아직 덜 찼을 수 있어 잠시 뒤부터 기억한다
        val settledAt = ZonedDateTime.of(date, MarketClock.closeOf(date), MarketClock.NEW_YORK).toInstant().plus(CLOSE_SETTLE)
        if (clock.instant().isAfter(settledAt)) regularCloses[key] = CachedClose(close)
        return close
    }

    /** UTC 하루 봉 중 미국 거래일만, 어제(뉴욕 날짜)까지. 종가 = UTC 자정 가격(뉴욕 19~20시). */
    override fun dailyBars(
        symbol: String,
        from: LocalDate,
    ): List<DailyBar> {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        return candles(symbol, "1d", from.atStartOfDay(ZoneOffset.UTC).toInstant(), clock.instant())
            .map { it to it.start.atZone(ZoneOffset.UTC).toLocalDate() }
            .filter { (_, date) -> date.isBefore(today) && MarketClock.isTradingDay(date) }
            .map { (c, date) -> DailyBar(date, c.open, c.high, c.low, c.close) }
    }

    /** 5분봉은 24시간 이어지므로 정규장 시간만 남긴다. */
    override fun intradayBars(
        symbol: String,
        date: LocalDate,
    ): List<IntradayBar> {
        val open = ZonedDateTime.of(date, MarketClock.OPEN, MarketClock.NEW_YORK).toInstant()
        val close = minOf(ZonedDateTime.of(date, MarketClock.closeOf(date), MarketClock.NEW_YORK).toInstant(), clock.instant())
        if (!close.isAfter(open)) return emptyList()
        return minuteBars(symbol, 5, open, close)
    }

    override fun minuteBars(
        symbol: String,
        minutes: Int,
        from: Instant,
        to: Instant,
    ): List<IntradayBar> =
        candles(symbol, "${minutes}m", from, to)
            .filter { !it.start.isBefore(from) && it.start.isBefore(to) }
            .map { IntradayBar(it.start, it.open, it.high, it.low, it.close) }

    private fun restMark(coin: String): Double =
        HyperliquidParser.assetMarks(post(mapOf("type" to "metaAndAssetCtxs", "dex" to properties.dex)))[coin]
            ?: throw IllegalStateException("참고시세에 $coin 가격이 없습니다")

    /** 요청 한 번에 최대 5000개라 넘으면 앞에서부터 이어 받는다. */
    private fun candles(
        symbol: String,
        interval: String,
        from: Instant,
        to: Instant,
    ): List<HyperliquidCandle> {
        val result = mutableListOf<HyperliquidCandle>()
        var start = from.toEpochMilli()
        while (start < to.toEpochMilli()) {
            val req = mapOf("coin" to properties.coin(symbol), "interval" to interval, "startTime" to start, "endTime" to to.toEpochMilli())
            val page = HyperliquidParser.candles(post(mapOf("type" to "candleSnapshot", "req" to req)))
            result += page.filter { c -> result.none { it.start == c.start } }
            if (page.size < CANDLES_PER_REQUEST) break
            start = page.last().start.toEpochMilli() + 1
        }
        return result
    }

    private fun post(body: Map<String, Any>): JsonNode =
        client.post()
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(String::class.java)
            .let { objectMapper.readTree(it) }

    private companion object {
        const val RECONNECT_CHECK_MS = 10_000L
        val STREAM_MAX_AGE: Duration = Duration.ofSeconds(60)
        val CLOSE_SETTLE: Duration = Duration.ofMinutes(1)
        const val CANDLES_PER_REQUEST = 5000
    }
}

private data class CachedClose(val close: Double?)
