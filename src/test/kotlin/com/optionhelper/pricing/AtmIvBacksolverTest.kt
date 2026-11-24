package com.optionhelper.pricing

import com.optionhelper.pricing.AtmIvBacksolver.Contract
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AtmIvBacksolverTest {
    private val r = 0.04

    @Test
    fun `월물 만기는 셋째 금요일, 휴장이면 목요일`() {
        assertThat(AtmIvBacksolver.monthlyExpiry(2026, 12)).isEqualTo(LocalDate.of(2026, 12, 18))
        // 2025-04-18은 성금요일 휴장
        assertThat(AtmIvBacksolver.monthlyExpiry(2025, 4)).isEqualTo(LocalDate.of(2025, 4, 17))
    }

    @Test
    fun `남은 일수가 30일에 가장 가까운 월물을 고른다`() {
        // 11/24 기준: 11/20은 지났고 12/18(24일)이 1/15(52일)보다 가깝다
        assertThat(AtmIvBacksolver.targetExpiry(LocalDate.of(2026, 11, 24))).isEqualTo(LocalDate.of(2026, 12, 18))
        // 12/3 기준: 12/18(15일)보다 1/15(43일)이 30일에 가깝다
        assertThat(AtmIvBacksolver.targetExpiry(LocalDate.of(2026, 12, 3))).isEqualTo(LocalDate.of(2027, 1, 15))
    }

    @Test
    fun `조회 계획 - 종가 근처 행사가 후보를 만기별로 모은다`() {
        val groups = AtmIvBacksolver.plan(mapOf(LocalDate.of(2026, 11, 24) to 152.3))
        val group = groups.single()

        assertThat(group.expiry).isEqualTo(LocalDate.of(2026, 12, 18))
        assertThat(group.strikes).contains(150.0, 152.5, 152.0, 153.0, 155.0)
        assertThat(group.contracts).hasSize(group.strikes.size * 2)
    }

    @Test
    fun `블랙-숄즈 가격을 넣으면 그 IV로 역산된다`() {
        val date = LocalDate.of(2026, 11, 24)
        val spot = 152.3
        val expiry = AtmIvBacksolver.targetExpiry(date)
        val t = MarketClock.yearsUntil(MarketClock.expiryAt(date).toInstant(), expiry)
        val prices =
            listOf(150.0, 155.0).flatMap { k ->
                OptionType.entries.map { type -> (date to Contract(expiry, type, k)) to BlackScholes.price(type, spot, k, t, r, 0.5) }
            }.toMap()

        val result = AtmIvBacksolver.solve(mapOf(date to spot), prices, r)

        assertThat(result.single().first).isEqualTo(date)
        assertThat(result.single().second).isCloseTo(0.5, within(1e-3))
    }

    @Test
    fun `가장 가까운 행사가에 콜이나 풋이 없으면 다음 행사가를 쓰고, 아무것도 없으면 그날은 빠진다`() {
        val date = LocalDate.of(2026, 11, 24)
        val noData = LocalDate.of(2026, 11, 23)
        val spot = 152.3
        val expiry = AtmIvBacksolver.targetExpiry(date)
        val t = MarketClock.yearsUntil(MarketClock.expiryAt(date).toInstant(), expiry)
        val prices =
            mapOf(
                // 152.5는 콜만 있음 → 건너뜀
                (date to Contract(expiry, OptionType.CALL, 152.5)) to 99.0,
                (date to Contract(expiry, OptionType.CALL, 150.0)) to BlackScholes.price(OptionType.CALL, spot, 150.0, t, r, 0.6),
                (date to Contract(expiry, OptionType.PUT, 150.0)) to BlackScholes.price(OptionType.PUT, spot, 150.0, t, r, 0.6),
            )

        val result = AtmIvBacksolver.solve(mapOf(date to spot, noData to 160.0), prices, r)

        assertThat(result.map { it.first }).containsExactly(date)
        assertThat(result.single().second).isCloseTo(0.6, within(1e-3))
    }
}
