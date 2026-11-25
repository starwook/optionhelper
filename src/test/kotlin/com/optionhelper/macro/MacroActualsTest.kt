package com.optionhelper.macro

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class MacroActualsTest {
    private val sep = YearMonth.of(2026, 9)
    private val oct = YearMonth.of(2026, 10)

    // 2026-11-26 BLS 실측 값
    private val observations =
        listOf(
            MacroObservation(MacroSeries.CPI_ALL, sep, 336.298),
            MacroObservation(MacroSeries.CPI_ALL, oct, 337.476),
            MacroObservation(MacroSeries.CPI_CORE, sep, 338.896),
            MacroObservation(MacroSeries.CPI_CORE, oct, 339.900),
            MacroObservation(MacroSeries.PPI_FINAL_DEMAND, sep, 159.699),
            MacroObservation(MacroSeries.PPI_FINAL_DEMAND, oct, 160.254),
            MacroObservation(MacroSeries.NONFARM_PAYROLLS, sep, 159128.0),
            MacroObservation(MacroSeries.NONFARM_PAYROLLS, oct, 159293.0),
            MacroObservation(MacroSeries.UNEMPLOYMENT_RATE, oct, 4.1),
        )

    @Test
    fun `CPI는 발표 전달 지수로 전월비와 근원 전월비를 계산한다`() {
        val view = MacroActuals.of(MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 11, 13)), observations)!!
        assertThat(view.referenceMonth).isEqualTo(oct)
        assertThat(view.metrics.map { it.label to it.actualText }).containsExactly("CPI 전월비" to "+0.4%", "근원 CPI 전월비" to "+0.3%")
        // 공식 값에는 예상치가 없다
        assertThat(view.metrics.map { it.verdict }).containsOnlyNulls()
    }

    @Test
    fun `PPI와 고용보고서`() {
        assertThat(MacroActuals.of(MacroEvent(MacroEventType.PPI, LocalDate.of(2026, 11, 14)), observations)!!.summary)
            .isEqualTo("+0.3%")
        assertThat(MacroActuals.of(MacroEvent(MacroEventType.NFP, LocalDate.of(2026, 11, 6)), observations)!!.summary)
            .isEqualTo("+165천 명 · 4.1%")
    }

    @Test
    fun `값이 아직 없거나 받지 않는 지표면 null`() {
        assertThat(MacroActuals.of(MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 12, 10)), observations)).isNull()
        assertThat(MacroActuals.of(MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 11, 5)), observations)).isNull()
        assertThat(MacroActuals.of(MacroEvent(MacroEventType.PCE, LocalDate.of(2026, 11, 25)), observations)).isNull()
    }

    @Test
    fun `BLS 응답에서 월간 값만 읽는다`() {
        val json =
            ObjectMapper().readTree(
                """
                {"status":"REQUEST_SUCCEEDED","message":[],"Results":{"series":[
                  {"seriesID":"CUSR0000SA0","data":[
                    {"year":"2026","period":"M10","periodName":"October","latest":"true","value":"337.476","footnotes":[{}]},
                    {"year":"2025","period":"M13","periodName":"Annual","value":"320.0","footnotes":[{}]}]},
                  {"seriesID":"UNKNOWN","data":[{"year":"2026","period":"M10","value":"1"}]}]}}
                """.trimIndent(),
            )
        assertThat(MacroActuals.parseBls(json)).containsExactly(MacroObservation(MacroSeries.CPI_ALL, oct, 337.476))
    }

    @Test
    fun `BLS 실패 응답은 예외`() {
        val json = ObjectMapper().readTree("""{"status":"REQUEST_NOT_PROCESSED","message":["limit"]}""")
        assertThatThrownBy { MacroActuals.parseBls(json) }.hasMessageContaining("BLS 응답 실패")
    }

    @Test
    fun `발표 시각이 지났는데 결과가 없는 발표만 기다린다`() {
        val cpi = MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 12, 10))
        val fomc = MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 12, 9))
        val events = listOf(cpi, fomc)
        // 08:30 ET = 13:30 UTC
        val before = Instant.parse("2026-12-10T13:00:00Z")
        val after = Instant.parse("2026-12-10T14:00:00Z")
        assertThat(MacroActuals.awaiting(events, observations, before)).isEmpty()
        // FOMC는 결과를 받지 않으므로 기다리지 않는다
        assertThat(MacroActuals.awaiting(events, observations, after)).containsExactly(cpi)
        val nov = observations + listOf(MacroObservation(MacroSeries.CPI_ALL, YearMonth.of(2026, 11), 338.0))
        assertThat(MacroActuals.awaiting(events, nov, after)).isEmpty()
        assertThat(MacroActuals.awaiting(events, observations, Instant.parse("2026-12-10T15:31:00Z"))).isEmpty()
    }
}
