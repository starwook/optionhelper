package com.optionhelper.pricing

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 미국 정규장 시계. 만기는 그날 장 마감(보통 동부 16:00, 조기 마감일은 13:00)이고, 남은 시간은 장중 시간만 센다(실거래시간 기준).
 * 휴장일·조기 마감은 [UsMarketCalendar] 규칙을 따른다.
 */
object MarketClock {
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    val OPEN: LocalTime = LocalTime.of(9, 30)
    val CLOSE: LocalTime = LocalTime.of(16, 0)

    const val TRADING_DAYS_PER_YEAR = 252.0
    const val TRADING_HOURS_PER_DAY = 6.5

    fun isTradingDay(date: LocalDate): Boolean = !UsMarketCalendar.isWeekend(date) && !UsMarketCalendar.isHoliday(date)

    /** 그날 장 마감 시각(조기 마감 반영). */
    fun closeOf(date: LocalDate): LocalTime = if (UsMarketCalendar.isEarlyClose(date)) UsMarketCalendar.EARLY_CLOSE else CLOSE

    fun isOpen(now: Instant): Boolean {
        val et = now.atZone(NEW_YORK)
        val date = et.toLocalDate()
        if (!isTradingDay(date)) return false
        val time = et.toLocalTime()
        return !time.isBefore(OPEN) && time.isBefore(closeOf(date))
    }

    fun expiryAt(expiry: LocalDate): ZonedDateTime = ZonedDateTime.of(expiry, closeOf(expiry), NEW_YORK)

    fun expiryAtSeoul(expiry: LocalDate): ZonedDateTime = expiryAt(expiry).withZoneSameInstant(SEOUL)

    /** now부터 만기(그날 장 마감)까지 남은 장중 시간. */
    fun tradingHoursUntil(
        now: Instant,
        expiry: LocalDate,
    ): Double {
        val nowEt = now.atZone(NEW_YORK)
        var day = nowEt.toLocalDate()
        var seconds = 0L
        while (!day.isAfter(expiry)) {
            if (isTradingDay(day)) {
                val open = ZonedDateTime.of(day, OPEN, NEW_YORK)
                val close = ZonedDateTime.of(day, closeOf(day), NEW_YORK)
                val start = if (nowEt.isAfter(open)) nowEt else open
                if (start.isBefore(close)) seconds += Duration.between(start, close).seconds
            }
            day = day.plusDays(1)
        }
        return seconds / 3600.0
    }

    /** 블랙-숄즈에 넣을 년 단위 시간. */
    fun yearsUntil(
        now: Instant,
        expiry: LocalDate,
    ): Double = tradingHoursToYears(tradingHoursUntil(now, expiry))

    fun tradingHoursToYears(hours: Double): Double = hours / (TRADING_DAYS_PER_YEAR * TRADING_HOURS_PER_DAY)
}
