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
 * 웹소켓 allMids 구독으로 배포자(xyz) 전 종목 중간가를 받아 메모리에 둔다. 서버 전체가 연결 1개만 쓴다(한도: IP당 10개).
 * 끊기면 [ensureConnected]가 다시 연결한다(주기 호출은 [HyperliquidPriceSource]가 한다).
 */
class HyperliquidMidStream(
    private val properties: HyperliquidProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Mid(val price: Double, val at: Instant)

    private val mids = ConcurrentHashMap<String, Mid>()

    @Volatile private var socket: WebSocket? = null

    fun mid(coin: String): Mid? = mids[coin]

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
            webSocket.sendText("""{"method":"subscribe","subscription":{"type":"allMids","dex":"${properties.dex}"}}""", true)
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
                runCatching { HyperliquidParser.mids(objectMapper.readTree(text)) }
                    .onSuccess { received -> received?.let { m -> m.forEach { (coin, px) -> mids[coin] = Mid(px, clock.instant()) } } }
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
