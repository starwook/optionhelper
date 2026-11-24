package com.optionhelper.marketdata.hyperliquid

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** 봉 한 개(시작 시각, 시가·고가·저가·종가). 값은 응답에서 문자열로 온다. */
data class HyperliquidCandle(
    val start: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

/** Hyperliquid 응답 해석(docs/외부API-명세.md 5절). 네트워크 없이 테스트할 수 있게 JSON 트리만 받는다. */
object HyperliquidParser {
    /** 웹소켓 allMids 메시지면 {코인: 중간가}, 다른 채널이면 null. */
    fun mids(message: JsonNode): Map<String, Double>? {
        if (message.path("channel").asText() != "allMids") return null
        return message.path("data").path("mids").fields().asSequence()
            .mapNotNull { (coin, px) -> px.asText().toDoubleOrNull()?.let { coin to it } }
            .toMap()
    }

    /** metaAndAssetCtxs 응답([meta, ctxs])에서 {코인: 중간가(없으면 마크 가격)}. 상장폐지 종목은 뺀다. */
    fun assetMids(root: JsonNode): Map<String, Double> {
        val universe = root.path(0).path("universe")
        val ctxs = root.path(1)
        return universe.mapIndexedNotNull { i, asset ->
            if (asset.path("isDelisted").asBoolean(false)) return@mapIndexedNotNull null
            val ctx = ctxs.path(i)
            val px = ctx.path("midPx").asText().toDoubleOrNull() ?: ctx.path("markPx").asText().toDoubleOrNull()
            px?.let { asset.path("name").asText() to it }
        }.toMap()
    }

    fun candles(root: JsonNode): List<HyperliquidCandle> =
        root.map {
            HyperliquidCandle(
                start = Instant.ofEpochMilli(it.path("t").asLong()),
                open = it.path("o").asText().toDouble(),
                high = it.path("h").asText().toDouble(),
                low = it.path("l").asText().toDouble(),
                close = it.path("c").asText().toDouble(),
            )
        }
}
