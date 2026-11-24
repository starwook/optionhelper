package com.optionhelper.marketdata.alpaca

import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 공식 문서의 응답 모양(docs/데이터소스-조사.md)대로 만든 샘플. */
class AlpacaParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `옵션 스냅샷 - 호가, IV, 델타, 다음 페이지`() {
        val json =
            """
            {"snapshots": {
               "NVDA261120C00157500": {"latestQuote": {"bp": 3.10, "ap": 3.20}, "impliedVolatility": 0.51, "greeks": {"delta": 0.52}},
               "NVDA261120P00157500": {"latestQuote": {"bp": 2.40, "ap": 2.46}}
             },
             "next_page_token": "abc"}
            """.trimIndent()
        val page = AlpacaParser.optionSnapshots(mapper.readTree(json))
        val call = page.quotes.single { it.second.type == OptionType.CALL }.second
        val put = page.quotes.single { it.second.type == OptionType.PUT }.second

        assertThat(call.mid).isCloseTo(3.15, within(1e-9))
        assertThat(call.impliedVolatility).isEqualTo(0.51)
        assertThat(call.delta).isEqualTo(0.52)
        // 0DTE처럼 IV·그릭스가 없으면 null
        assertThat(put.impliedVolatility).isNull()
        assertThat(page.quotes.first().first.expiry).isEqualTo(LocalDate.of(2026, 11, 20))
        assertThat(page.nextPageToken).isEqualTo("abc")
    }

    @Test
    fun `마지막 페이지면 토큰 없음`() {
        val page = AlpacaParser.optionSnapshots(mapper.readTree("""{"snapshots": {}, "next_page_token": null}"""))
        assertThat(page.nextPageToken).isNull()
    }

    @Test
    fun `주식 스냅샷 - 현재가, 전일 종가`() {
        val json =
            """
            {"latestTrade": {"p": 158.13, "t": "2026-11-20T15:30:00Z"},
             "dailyBar": {"t": "2026-11-20T05:00:00Z", "o": 150.0}, "prevDailyBar": {"c": 151.0}}
            """.trimIndent()
        val quote = AlpacaParser.stockSnapshot("NVDA", mapper.readTree(json))
        assertThat(quote.price).isEqualTo(158.13)
        assertThat(quote.previousClose).isEqualTo(151.0)
    }

    @Test
    fun `일봉`() {
        val json = """{"bars": {"NVDA": [{"t": "2026-11-19T05:00:00Z", "o": 1, "h": 2, "l": 0.5, "c": 1.5}]}, "next_page_token": null}"""
        val page = AlpacaParser.bars("NVDA", mapper.readTree(json))
        assertThat(page.bars.single().date).isEqualTo(LocalDate.of(2026, 11, 19))
        assertThat(page.bars.single().close).isEqualTo(1.5)
    }

    @Test
    fun `옵션 일봉 - 만기된 계약도 날짜·계약별 종가로 (실측 2026-11-24 응답 모양)`() {
        val json =
            """
            {"bars": {"NVDA251219C00180000": [
               {"c": 15.25, "h": 16.79, "l": 14.25, "n": 743, "o": 14.9, "t": "2025-11-17T05:00:00Z", "v": 1262, "vw": 15.46},
               {"c": 12.7, "h": 14.43, "l": 12, "n": 1081, "o": 13.5, "t": "2025-11-18T05:00:00Z", "v": 5541, "vw": 13.17}
             ]},
             "next_page_token": null}
            """.trimIndent()
        val page = AlpacaParser.optionBars(mapper.readTree(json))
        val (key, close) = page.closes.entries.first { it.key.first == LocalDate.of(2025, 11, 18) }

        assertThat(page.closes).hasSize(2)
        assertThat(close).isEqualTo(12.7)
        assertThat(key.second.strike).isEqualTo(180.0)
        assertThat(key.second.type).isEqualTo(OptionType.CALL)
        assertThat(page.nextPageToken).isNull()
    }
}
