package com.optionhelper.marketdata.hyperliquid

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** 2026-11-25 실측 응답 모양(docs/외부API-명세.md 5절). */
class HyperliquidParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `웹소켓 activeAssetCtx만 읽고 구독 확인 메시지는 무시`() {
        val msg =
            """{"channel":"activeAssetCtx","data":{"coin":"xyz:NVDA",""" +
                """"ctx":{"markPx":"152.27","midPx":"152.325","prevDayPx":"165.66"}}}"""
        assertThat(HyperliquidParser.assetCtxMark(mapper.readTree(msg))).isEqualTo("xyz:NVDA" to 152.27)
        assertThat(HyperliquidParser.assetCtxMark(mapper.readTree("""{"channel":"subscriptionResponse","data":{}}"""))).isNull()
    }

    @Test
    fun `metaAndAssetCtxs - 마크 가격, 상장폐지 제외`() {
        val root =
            mapper.readTree(
                """[{"universe":[{"name":"xyz:NVDA"},{"name":"xyz:QCOM"},{"name":"xyz:SMCI","isDelisted":true}]},
                [{"midPx":"152.675","markPx":"152.67"},{"midPx":null,"markPx":"166.76"},{"midPx":null,"markPx":"24.37"}]]""",
            )
        assertThat(HyperliquidParser.assetMarks(root)).containsExactlyInAnyOrderEntriesOf(mapOf("xyz:NVDA" to 152.67, "xyz:QCOM" to 166.76))
    }

    @Test
    fun `봉 - 문자열 가격`() {
        val c =
            HyperliquidParser.candles(
                mapper.readTree(
                    """[{"t":1795478400000,"T":1795564799999,"s":"xyz:NVDA","i":"1d",""" +
                        """"o":"165.19","c":"152.47","h":"168.97","l":"150.62","v":"1234.5","n":2}]""",
                ),
            )
        assertThat(c.single().start).isEqualTo(Instant.ofEpochMilli(1795478400000))
        assertThat(c.single().close).isEqualTo(152.47)
        assertThat(c.single().volume).isEqualTo(1234.5)
    }
}
