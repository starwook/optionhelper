package com.optionhelper.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class MarketClockTest {
    private fun et(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), MarketClock.NEW_YORK).toInstant()

    // 2026-11-20은 금요일
    private val friday = LocalDate.of(2026, 11, 20)

    @Test
    fun `0DTE 장중이면 마감까지 남은 시간`() {
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-20T14:30:00"), friday)).isCloseTo(1.5, within(1e-9))
    }

    @Test
    fun `장 시작 전이면 그날 정규장 전체`() {
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-20T07:00:00"), friday)).isCloseTo(6.5, within(1e-9))
    }

    @Test
    fun `전날 저녁부터 다음 날 만기까지는 하루치`() {
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-19T20:00:00"), friday)).isCloseTo(6.5, within(1e-9))
    }

    @Test
    fun `주말은 세지 않는다`() {
        // 금요일 장 마감 후 → 다음 주 월요일 만기
        val monday = LocalDate.of(2026, 11, 23)
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-20T17:00:00"), monday)).isCloseTo(6.5, within(1e-9))
    }

    @Test
    fun `만기 후에는 0`() {
        assertThat(MarketClock.tradingHoursUntil(et("2026-11-20T16:30:00"), friday)).isEqualTo(0.0)
    }

    @Test
    fun `IV용 년 단위 시간은 밤과 주말을 포함한 달력 시간 나누기 365일`() {
        val monday = LocalDate.of(2026, 11, 23)
        // 금요일 17:00 → 월요일 16:00 = 71시간
        assertThat(MarketClock.yearsUntil(et("2026-11-20T17:00:00"), monday)).isCloseTo(71.0 / (365 * 24), within(1e-12))
        // 장 시작 전 07:00 → 그날 16:00 = 9시간
        assertThat(MarketClock.yearsUntil(et("2026-11-20T07:00:00"), friday)).isCloseTo(9.0 / (365 * 24), within(1e-12))
    }

    @Test
    fun `만기가 지나면 년 단위 시간도 0`() {
        assertThat(MarketClock.yearsUntil(et("2026-11-20T16:30:00"), friday)).isEqualTo(0.0)
    }

    @Test
    fun `장 열림 판정`() {
        assertThat(MarketClock.isOpen(et("2026-11-20T09:30:00"))).isTrue()
        assertThat(MarketClock.isOpen(et("2026-11-20T16:00:00"))).isFalse()
        assertThat(MarketClock.isOpen(et("2026-11-21T12:00:00"))).isFalse()
    }

    @Test
    fun `만기 시각을 한국시간으로 표시한다`() {
        // 11월은 서머타임 해제(EST, UTC-5) → 한국 다음 날 06:00
        val seoul = MarketClock.expiryAtSeoul(friday)
        assertThat(seoul.toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 21))
        assertThat(seoul.hour).isEqualTo(6)
    }

    @Test
    fun `마지막 정규장 날짜 - 장중이면 오늘, 장 시작 전이면 전 거래일, 주말이면 금요일`() {
        assertThat(MarketClock.lastSessionDate(et("2026-11-20T10:00:00"))).isEqualTo(friday)
        assertThat(MarketClock.lastSessionDate(et("2026-11-20T08:00:00"))).isEqualTo(LocalDate.of(2026, 11, 19))
        assertThat(MarketClock.lastSessionDate(et("2026-11-22T12:00:00"))).isEqualTo(friday)
        // 2026-11-26 추수감사절 휴장
        assertThat(MarketClock.lastSessionDate(et("2026-11-26T12:00:00"))).isEqualTo(LocalDate.of(2026, 11, 25))
    }

    @Test
    fun `전 거래일 - 월요일이면 금요일`() {
        assertThat(MarketClock.previousTradingDay(LocalDate.of(2026, 11, 23))).isEqualTo(friday)
    }
}
