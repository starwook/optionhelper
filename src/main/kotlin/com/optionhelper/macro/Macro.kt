package com.optionhelper.macro

import com.optionhelper.pricing.MarketClock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 시장 전체에 영향을 주는 미국 경제지표. 발표 시각은 미국 동부 기준. */
enum class MacroEventType(val label: String, val releaseTimeEt: LocalTime) {
    FOMC("FOMC 금리 결정", LocalTime.of(14, 0)),
    CPI("CPI(소비자물가)", LocalTime.of(8, 30)),
    PPI("PPI(생산자물가)", LocalTime.of(8, 30)),
    NFP("고용보고서", LocalTime.of(8, 30)),
    PCE("PCE(개인소비지출)", LocalTime.of(8, 30)),
}

data class MacroEvent(val type: MacroEventType, val date: LocalDate) {
    /** 발표 시각(미국 동부). */
    fun releaseAt(): ZonedDateTime = date.atTime(type.releaseTimeEt).atZone(MarketClock.NEW_YORK)

    /** 모든 지표가 장 마감(16:00 ET) 전에 나오므로, 만기 당일 발표까지 그 만기 가격에 반영된다. */
    fun reactsBy(expiry: LocalDate): Boolean = !date.isAfter(expiry)
}

object MacroCalendar {
    /** 오늘 이후(오늘 포함) 일정, 날짜·시각순. */
    fun upcoming(
        events: List<MacroEvent>,
        today: LocalDate,
        limit: Int = Int.MAX_VALUE,
    ): List<MacroEvent> =
        events.filter { !it.date.isBefore(today) }
            .sortedWith(compareBy({ it.date }, { it.type.releaseTimeEt }))
            .take(limit)

    /** 오늘부터 만기까지 발표되는 지표. */
    fun withinExpiry(
        events: List<MacroEvent>,
        today: LocalDate,
        expiry: LocalDate,
    ): List<MacroEvent> = upcoming(events, today).filter { it.reactsBy(expiry) }

    /** 일정 파일이 어디까지 채워져 있는가. 비어 있으면 null. */
    fun coversUntil(events: List<MacroEvent>): LocalDate? = events.maxOfOrNull { it.date }

    fun daysUntil(
        event: MacroEvent,
        today: LocalDate,
    ): Long = ChronoUnit.DAYS.between(today, event.date)

    /** `type,date` CSV. 첫 줄 헤더, 빈 줄과 `#` 주석은 건너뛴다. */
    fun parseCsv(text: String): List<MacroEvent> =
        text.lines().withIndex().drop(1).mapNotNull { (index, raw) ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val cells = line.split(",").map { it.trim() }
            val type = MacroEventType.entries.firstOrNull { it.name == cells[0] }
            val date = cells.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            require(type != null && date != null) { "경제지표 일정 ${index + 1}번째 줄을 읽지 못했습니다: $raw" }
            MacroEvent(type, date)
        }
}
