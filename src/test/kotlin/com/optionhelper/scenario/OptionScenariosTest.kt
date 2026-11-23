package com.optionhelper.scenario

import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class OptionScenariosTest {
    // 2026-11-24 00:00 ET(장 시작 전), NVDA $168.27, 12/31 만기 185 콜 $6.35 (토스 화면 값)
    private val now = Instant.parse("2026-11-24T05:00:00Z")
    private val expiry = LocalDate.of(2026, 12, 31)

    private fun build(shifts: List<IvShift> = listOf(IvShift("−10%p", -0.10))) =
        OptionScenarios.build(OptionType.CALL, 168.27, 185.0, expiry, 6.35, now, 0.04, shifts)!!

    @Test
    fun `가격으로 IV를 역산하고 첫 행은 지금 가격 그대로`() {
        val s = build()
        assertThat(s.impliedVol).isBetween(0.53, 0.60)
        assertThat(s.rows.first().prices.first()).isCloseTo(6.35, within(1e-4))
        assertThat(s.delta).isBetween(0.30, 0.40)
        assertThat(s.thetaPerDay).isNegative()
    }

    @Test
    fun `주가가 그대로면 날짜가 갈수록 값이 줄고 만기엔 내재가치(0)`() {
        val prices = build().rows.map { it.prices.first() }
        assertThat(prices).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(prices.last()).isEqualTo(0.0)
    }

    @Test
    fun `IV가 10%p 빠지면 대략 베가 × 10만큼 싸진다`() {
        val s = build()
        val drop = s.rows.first().prices[0] - s.rows.first().prices[1]
        assertThat(s.levels[1].iv).isCloseTo(s.impliedVol - 0.10, within(1e-9))
        assertThat(drop).isCloseTo(s.vegaPerPoint * 10, within(0.3))
    }

    @Test
    fun `만기가 지났거나 내재가치보다 싼 가격이면 null`() {
        assertThat(OptionScenarios.build(OptionType.CALL, 168.27, 185.0, LocalDate.of(2026, 11, 20), 6.35, now, 0.04, emptyList())).isNull()
        assertThat(OptionScenarios.build(OptionType.CALL, 200.0, 185.0, expiry, 10.0, now, 0.04, emptyList())).isNull()
    }
}
