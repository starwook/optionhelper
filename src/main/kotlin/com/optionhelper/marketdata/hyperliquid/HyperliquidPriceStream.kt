package com.optionhelper.marketdata.hyperliquid

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * 웹소켓으로 종목별 `activeAssetCtx`를 구독해 마크 가격(Hyperliquid 화면의 Mark)을 메모리에 둔다. 약 1초마다 온다(실측).
 * 서버 전체가 연결 1개만 쓴다(한도: IP당 연결 10개, 구독 1000개). 끊기면 [ensureConnected]가 다시 연결한다.
 */
class HyperliquidPriceStream(
    private val properties: HyperliquidProperties,
    private val coins: List<String>,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Mark(val price: Double, val at: Instant)

    private val marks = ConcurrentHashMap<String, Mark>()

    @Volatile private var socket: WebSocket? = null

    fun mark(coin: String): Mark? = marks[coin]

    @Synchronized
    fun ensureConnected() {
        val current = socket
        if (current != null && !current.isInputClosed && !current.isOutputClosed) return
        runCatching {
            socket =
                HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(properties.wsUrl), Listener())
                    .join()
        }.onFailure { log.warn("Hyperliquid 웹소켓 연결 실패: {}", it.message) }
    }

    fun close() {
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            coins.forEach { coin ->
                webSocket.sendText("""{"method":"subscribe","subscription":{"type":"activeAssetCtx","coin":"$coin"}}""", true).join()
            }
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                runCatching { HyperliquidParser.assetCtxMark(objectMapper.readTree(text)) }
                    .onSuccess { it?.let { (coin, px) -> marks[coin] = Mark(px, clock.instant()) } }
                    .onFailure { log.debug("Hyperliquid 메시지 해석 실패: {}", it.message) }
            }
            webSocket.request(1)
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            log.warn("Hyperliquid 웹소켓 오류: {}", error.message)
        }
    }
}
