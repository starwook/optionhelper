package com.optionhelper.macro

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class MacroFlashTest {
    private val cpi = MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 12, 10))

    private fun flash(
        event: MacroEvent,
        answer: String,
    ): MacroActualView? = MacroFlash.parse(event, answer).first?.let { MacroFlash.view(event, it) }

    @Test
    fun `속보 답에서 실제값과 예상치를 뽑고 상회·하회를 판정한다`() {
        val answer =
            """Need core.{"found": true, "release_date": "2026-12-10", "reference_month": "2026-11",
              "cpi_mom_pct": 0.4, "core_cpi_mom_pct": 0.3, "expected_cpi_mom_pct": 0.3, "expected_core_cpi_mom_pct": 0.3,
              "source_url": "https://www.bls.gov/news.release/cpi.nr0.htm", "expected_source_url": "https://example.com/news"}"""
        val (values, reason) = MacroFlash.parse(cpi, answer)
        assertThat(reason).isEqualTo("ok")
        assertThat(values!!.path("source_url").asText()).isEqualTo("https://www.bls.gov/news.release/cpi.nr0.htm")
        val view = MacroFlash.view(cpi, values)!!
        assertThat(view.referenceMonth).isEqualTo(YearMonth.of(2026, 11))
        assertThat(view.metrics.map { Triple(it.actualText, it.expectedText, it.verdict) })
            .containsExactly(Triple("+0.4%", "+0.3%", "ABOVE"), Triple("+0.3%", "+0.3%", "INLINE"))
        assertThat(view.note).contains("속보")
    }

    @Test
    fun `공식 값에 속보의 예상치를 붙인다`() {
        val flashView =
            flash(
                cpi,
                """{"found": true, "release_date": "2026-12-10", "reference_month": "2026-11",
                  "cpi_mom_pct": 0.4, "expected_cpi_mom_pct": 0.5}""",
            )
        val official = MacroResultFormat.cpi(YearMonth.of(2026, 11), 0.4, 0.2, "").withExpectedFrom(flashView)
        assertThat(official.summary).isEqualTo("+0.4% · +0.2%")
        assertThat(official.metrics.map { it.verdict }).containsExactly("BELOW", null)
    }

    @Test
    fun `지난 발표 값이나 엉뚱한 값은 버린다`() {
        fun reason(json: String) = MacroFlash.parse(cpi, json).second
        assertThat(reason("""{"found": false}""")).isEqualTo("아직 못 찾음")
        assertThat(reason("검색 결과가 없습니다")).isEqualTo("JSON 아님")
        // 직전 발표(10월분)를 가져오면 버린다
        assertThat(reason("""{"found": true, "release_date": "2026-11-13", "reference_month": "2026-10", "cpi_mom_pct": 0.4}"""))
            .startsWith("발표일 다름")
        assertThat(reason("""{"found": true, "release_date": "2026-12-10", "reference_month": "2026-10", "cpi_mom_pct": 0.4}"""))
            .startsWith("월분 다름")
        assertThat(reason("""{"found": true, "release_date": "2026-12-10", "reference_month": "2026-11", "cpi_mom_pct": 4.0}"""))
            .isEqualTo("값 없음·범위 밖")
    }

    @Test
    fun `고용보고서와 FOMC`() {
        val nfp = MacroEvent(MacroEventType.NFP, LocalDate.of(2026, 12, 4))
        val nfpView =
            flash(
                nfp,
                """{"found": true, "release_date": "2026-12-04", "reference_month": "2026-11",
                  "nfp_change_thousands": -32, "unemployment_rate_pct": 4.2, "expected_nfp_change_thousands": 50}""",
            )!!
        assertThat(nfpView.summary).isEqualTo("-32천 명 · 4.2%")
        assertThat(nfpView.metrics.first().verdict).isEqualTo("BELOW")

        val fomc = MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 12, 9))
        val cut =
            flash(
                fomc,
                """{"found": true, "release_date": "2026-12-09", "change_bp": -25, "expected_change_bp": -25,
                  "target_lower_pct": 3.5, "target_upper_pct": 3.75}""",
            )!!
        assertThat(cut.metrics.single().let { it.actualText to it.verdict }).isEqualTo("0.25%p 인하" to "INLINE")
        assertThat(cut.note).startsWith("목표 범위 3.50~3.75%")
        val broken = """{"found": true, "release_date": "2026-12-09", "change_bp": -25, "target_lower_pct": 3.5, "target_upper_pct": 4.0}"""
        assertThat(MacroFlash.parse(fomc, broken).first).isNull()
    }

    @Test
    fun `속보 창은 발표 몇 분 뒤부터 정해진 시간까지`() {
        // 08:30 ET = 13:30 UTC
        assertThat(cpi.inFlashWindow(Instant.parse("2026-12-10T13:34:00Z"), 5, 55)).isFalse()
        assertThat(cpi.inFlashWindow(Instant.parse("2026-12-10T13:35:00Z"), 5, 55)).isTrue()
        assertThat(cpi.inFlashWindow(Instant.parse("2026-12-10T14:25:00Z"), 5, 55)).isFalse()
    }
}
