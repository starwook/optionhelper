package com.optionhelper.pricing

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 미국 정규장 시계. 만기는 그날 장 마감(보통 동부 16:00, 조기 마감일은 13:00)이다.
 * 블랙-숄즈에 넣는 시간은 달력 시간(연 365일, 밤·주말 포함)이다. 증권사·TradingView 등 흔히 보는 IV와 같은 기준(2026-11-24).
 * 화면에 보여주는 "만기까지 장중 시간"은 [tradingHoursUntil]로 따로 센다. 휴장일·조기 마감은 [UsMarketCalendar] 규칙을 따른다.
 */
object MarketClock {
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    val OPEN: LocalTime = LocalTime.of(9, 30)
    val CLOSE: LocalTime = LocalTime.of(16, 0)

    const val DAYS_PER_YEAR = 365.0
    const val TRADING_HOURS_PER_DAY = 6.5
    private const val SECONDS_PER_YEAR = DAYS_PER_YEAR * 24 * 3600

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

    /** 가장 최근에 정규장이 열린(또는 열려 있는) 날. 장 시작 전·휴장일이면 그 전 거래일. */
    fun lastSessionDate(now: Instant): LocalDate {
        val et = now.atZone(NEW_YORK)
        var day = if (et.toLocalTime().isBefore(OPEN)) et.toLocalDate().minusDays(1) else et.toLocalDate()
        while (!isTradingDay(day)) day = day.minusDays(1)
        return day
    }

    /** 그 거래일 바로 전 거래일. */
    fun previousTradingDay(date: LocalDate): LocalDate {
        var day = date.minusDays(1)
        while (!isTradingDay(day)) day = day.minusDays(1)
        return day
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

    /** 블랙-숄즈에 넣을 년 단위 시간: now부터 만기(그날 장 마감)까지의 달력 시간 ÷ 365일. 지났으면 0. */
    fun yearsUntil(
        now: Instant,
        expiry: LocalDate,
    ): Double = maxOf(0L, Duration.between(now, expiryAt(expiry).toInstant()).seconds) / SECONDS_PER_YEAR
}
