package com.optionhelper.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

class UsMarketCalendarTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun et(text: String) = ZonedDateTime.of(LocalDateTime.parse(text), MarketClock.NEW_YORK).toInstant()

    @Test
    fun `2026년 휴장일`() {
        listOf(
            "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
            "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
        ).forEach { assertThat(UsMarketCalendar.isHoliday(d(it))).describedAs(it).isTrue() }
        assertThat(UsMarketCalendar.isHoliday(d("2026-11-20"))).isFalse()
    }

    @Test
    fun `대체 휴장 - 일요일이면 월요일, 신정이 토요일이면 전년 12월 31일은 연다`() {
        // 2027-12-25는 토요일 → 12/24 금요일 휴장
        assertThat(UsMarketCalendar.isHoliday(d("2027-12-24"))).isTrue()
        // 2028-01-01은 토요일 → 2027-12-31은 정상 개장
        assertThat(UsMarketCalendar.isHoliday(d("2027-12-31"))).isFalse()
        // 2023-01-01은 일요일 → 1/2 월요일 휴장
        assertThat(UsMarketCalendar.isHoliday(d("2023-01-02"))).isTrue()
    }

    @Test
    fun `조기 마감 - 추수감사절 다음 날, 성탄 전날`() {
        assertThat(UsMarketCalendar.isEarlyClose(d("2026-11-27"))).isTrue()
        assertThat(UsMarketCalendar.isEarlyClose(d("2026-12-24"))).isTrue()
        // 2026-07-03은 독립기념일 대체 휴장이라 조기 마감이 아니다
        assertThat(UsMarketCalendar.isEarlyClose(d("2026-07-03"))).isFalse()
        assertThat(MarketClock.closeOf(d("2026-11-27")).hour).isEqualTo(13)
    }

    @Test
    fun `추수감사절 주간 - 휴장일은 빼고 조기 마감일은 13시까지만 센다`() {
        // 수요일 장 마감 후 → 금요일(조기 마감) 만기: 금요일 9:30~13:00 = 3.5시간
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-25T17:00:00"), d("2026-11-27"))).isCloseTo(3.5, within(1e-9))
        assertThat(MarketClock.expiryAt(d("2026-11-27")).hour).isEqualTo(13)
        assertThat(MarketClock.isOpen(et("2026-11-26T11:00:00"))).isFalse()
        assertThat(MarketClock.isOpen(et("2026-11-27T13:30:00"))).isFalse()
    }
}
