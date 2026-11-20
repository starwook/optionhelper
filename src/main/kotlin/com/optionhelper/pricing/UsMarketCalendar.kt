package com.optionhelper.pricing

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * 뉴욕증권거래소 휴장일과 조기 마감(13:00) 규칙. 매년 규칙으로 계산한다.
 * - 휴장: 신정, 마틴 루서 킹 데이, 대통령의 날, 성금요일, 메모리얼 데이, 준틴스, 독립기념일, 노동절, 추수감사절, 성탄절
 *   (토요일이면 전날 금요일, 일요일이면 다음 날 월요일 휴장. 단 신정이 토요일이면 전년 12/31은 열린다)
 * - 조기 마감: 추수감사절 다음 날, 독립기념일 전날(7/3), 성탄 전날(12/24) — 평일이고 휴장이 아닐 때
 * 임시 휴장(국장 등)은 규칙으로 알 수 없어 반영하지 않는다.
 */
object UsMarketCalendar {
    val EARLY_CLOSE: LocalTime = LocalTime.of(13, 0)

    fun isHoliday(date: LocalDate): Boolean = date in holidays(date.year)

    fun isEarlyClose(date: LocalDate): Boolean {
        if (isWeekend(date) || isHoliday(date)) return false
        val thanksgiving = nthWeekday(date.year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
        return date == thanksgiving.plusDays(1) ||
            date == LocalDate.of(date.year, Month.JULY, 3) ||
            date == LocalDate.of(date.year, Month.DECEMBER, 24)
    }

    fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private fun holidays(year: Int): Set<LocalDate> =
        setOfNotNull(
            observed(LocalDate.of(year, Month.JANUARY, 1)).takeIf { it.year == year },
            nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3),
            nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3),
            easter(year).minusDays(2),
            LocalDate.of(year, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
            if (year >= 2022) observed(LocalDate.of(year, Month.JUNE, 19)) else null,
            observed(LocalDate.of(year, Month.JULY, 4)),
            nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1),
            nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4),
            observed(LocalDate.of(year, Month.DECEMBER, 25)),
            // 다음 해 신정이 토요일이어도 올해 12/31은 휴장이 아니다(위 takeIf로 처리). 일요일이면 1/2 월요일 휴장.
        )

    private fun observed(date: LocalDate): LocalDate =
        when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> date.minusDays(1)
            DayOfWeek.SUNDAY -> date.plusDays(1)
            else -> date
        }

    private fun nthWeekday(
        year: Int,
        month: Month,
        day: DayOfWeek,
        n: Int,
    ): LocalDate = LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    /** 부활절(그레고리력, 익명 알고리즘). */
    private fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
