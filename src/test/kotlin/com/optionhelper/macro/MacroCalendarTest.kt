package com.optionhelper.macro

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MacroCalendarTest {
    private val events =
        listOf(
            MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 12, 9)),
            MacroEvent(MacroEventType.NFP, LocalDate.of(2026, 12, 4)),
            MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 12, 10)),
            MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 11, 13)),
        )

    @Test
    fun `발표 시각은 미국 동부 기준 - FOMC 14시 ET는 다음 날 04시 KST`() {
        val at = MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 12, 9)).releaseAt()
        assertThat(at.toLocalTime().toString()).isEqualTo("14:00")
        assertThat(at.withZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toString()).startsWith("2026-12-10T04:00")
    }

    @Test
    fun `오늘 이후 일정만 날짜순으로, limit만큼`() {
        val upcoming = MacroCalendar.upcoming(events, LocalDate.of(2026, 11, 24), limit = 2)

        assertThat(upcoming.map { it.type }).containsExactly(MacroEventType.NFP, MacroEventType.FOMC)
    }

    @Test
    fun `만기 당일 발표는 포함, 만기 다음 날은 제외`() {
        val within = MacroCalendar.withinExpiry(events, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 9))

        assertThat(within.map { it.type }).containsExactly(MacroEventType.NFP, MacroEventType.FOMC)
    }

    @Test
    fun `일정 파일이 채워진 마지막 날짜`() {
        assertThat(MacroCalendar.coversUntil(events)).isEqualTo(LocalDate.of(2026, 12, 10))
        assertThat(MacroCalendar.coversUntil(emptyList())).isNull()
    }

    @Test
    fun `CSV - 헤더·주석·빈 줄은 건너뛴다`() {
        val parsed = MacroCalendar.parseCsv("type,date\n# 주석\n\nFOMC,2026-12-09\nCPI, 2026-12-10\n")

        assertThat(parsed).containsExactly(
            MacroEvent(MacroEventType.FOMC, LocalDate.of(2026, 12, 9)),
            MacroEvent(MacroEventType.CPI, LocalDate.of(2026, 12, 10)),
        )
    }

    @Test
    fun `CSV - 모르는 지표는 줄 번호와 함께 실패`() {
        assertThatThrownBy { MacroCalendar.parseCsv("type,date\nFOMC,2026-12-09\nGDP,2026-12-20") }
            .hasMessageContaining("3번째 줄")
    }

    @Test
    fun `저장소의 일정 파일을 읽을 수 있다`() {
        assertThat(MacroCalendarFile().events).hasSize(61)
    }
}
