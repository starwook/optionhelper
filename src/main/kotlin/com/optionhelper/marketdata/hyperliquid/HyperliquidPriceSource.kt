package com.optionhelper.marketdata.hyperliquid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.marketdata.UnderlyingPriceSource
import com.optionhelper.pricing.MarketClock
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
 * 현재가는 웹소켓 중간가, 아직 없거나 오래됐으면 REST(metaAndAssetCtxs). 봉은 REST candleSnapshot.
 */
@Component
@ConditionalOnProperty(prefix = "optionhelper.market-data", name = ["source"], havingValue = "hyperliquid", matchIfMissing = true)
class HyperliquidPriceSource(
    private val properties: HyperliquidProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    restClientBuilder: RestClient.Builder,
) : UnderlyingPriceSource {
    override val name = "Hyperliquid ${properties.dex} 선물"
    override val note = "주가는 Hyperliquid의 주식 추종 선물(${properties.dex}) 중간가라 실제 주가와 조금 다를 수 있고, 전일 종가는 UTC 자정 기준 일봉 종가입니다."

    private val client = restClientBuilder.baseUrl(properties.infoUrl).build()
    private val stream = HyperliquidMidStream(properties, objectMapper, clock)

    // 종목별 (뉴욕 날짜, 전일 종가). 하루 한 번만 봉을 받는다.
    private val previousCloses = ConcurrentHashMap<String, Pair<LocalDate, Double?>>()

    @Scheduled(fixedDelay = RECONNECT_CHECK_MS, initialDelay = 0)
    fun keepStreamAlive() = stream.ensureConnected()

    @PreDestroy
    fun shutdown() = stream.close()

    override fun stockQuote(symbol: String): StockQuote {
        val coin = properties.coin(symbol)
        val now = clock.instant()
        val streamed = stream.mid(coin)?.takeIf { Duration.between(it.at, now) < STREAM_MAX_AGE }
        val (price, asOf) = streamed?.let { it.price to it.at } ?: (restMid(coin) to now)
        return StockQuote(symbol.uppercase(), price, previousClose(symbol), asOf)
    }

    private fun previousClose(symbol: String): Double? {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        previousCloses[symbol]?.takeIf { it.first == today }?.let { return it.second }
        val close = dailyBars(symbol, today.minusDays(PREVIOUS_CLOSE_LOOKBACK_DAYS)).lastOrNull()?.close
        previousCloses[symbol] = today to close
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
        return candles(symbol, "5m", open, close)
            .filter { !it.start.isBefore(open) && it.start.isBefore(close) }
            .map { IntradayBar(it.start, it.close) }
    }

    private fun restMid(coin: String): Double =
        HyperliquidParser.assetMids(post(mapOf("type" to "metaAndAssetCtxs", "dex" to properties.dex)))[coin]
            ?: throw IllegalStateException("Hyperliquid에 $coin 가격이 없습니다")

    /** 요청당 최대 500개라 앞에서부터 이어 받는다. */
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
        const val PREVIOUS_CLOSE_LOOKBACK_DAYS = 10L
        const val CANDLES_PER_REQUEST = 500
    }
}
