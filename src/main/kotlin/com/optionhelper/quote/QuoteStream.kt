package com.optionhelper.quote

import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.watchlist.WatchedSymbol
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 화면 맨 위 현재가 한 줄. */
data class LiveQuote(val symbol: String, val price: Double, val previousClose: Double?, val asOf: Instant)

/**
 * 현재가를 브라우저로 밀어 준다(SSE). 1초마다 구독자가 있는 종목만 데이터 소스에서 현재가를 읽어, 바뀌었으면 보낸다.
 * 데이터 소스가 메모리에 든 웹소켓 값을 주므로(Hyperliquid) 1초 주기여도 외부 호출이 늘지 않는다.
 */
@Component
class QuoteStream(private val source: MarketDataSource) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()
    private val lastSent = ConcurrentHashMap<String, LiveQuote>()

    fun subscribe(symbol: String): SseEmitter {
        val emitter = SseEmitter(EMITTER_TIMEOUT_MS)
        val list = subscribers.computeIfAbsent(symbol) { CopyOnWriteArrayList() }
        list += emitter
        val remove = {
            list.remove(emitter)
            Unit
        }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        lastSent[symbol]?.let { send(emitter, it) }
        return emitter
    }

    @Scheduled(fixedDelay = PUSH_INTERVAL_MS)
    fun push() {
        subscribers.forEach { (symbol, emitters) ->
            if (emitters.isEmpty()) return@forEach
            val quote =
                runCatching { source.stockQuote(symbol) }
                    .onFailure { log.debug("{} 현재가를 못 읽음: {}", symbol, it.message) }
                    .getOrNull() ?: return@forEach
            val live = LiveQuote(symbol, quote.price, quote.previousClose, quote.asOf)
            if (live == lastSent[symbol]) return@forEach
            lastSent[symbol] = live
            emitters.forEach { send(it, live) }
        }
    }

    private fun send(
        emitter: SseEmitter,
        quote: LiveQuote,
    ) {
        runCatching { emitter.send(SseEmitter.event().data(quote, MediaType.APPLICATION_JSON)) }
            .onFailure { emitter.completeWithError(it) }
    }

    private companion object {
        const val PUSH_INTERVAL_MS = 1000L

        // 브라우저 EventSource는 끊기면 알아서 다시 연결한다
        const val EMITTER_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

@RestController
class QuoteStreamController(private val stream: QuoteStream) {
    @GetMapping("/api/quote-stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestParam symbol: String,
    ): SseEmitter = stream.subscribe(WatchedSymbol.of(symbol).name)
}
