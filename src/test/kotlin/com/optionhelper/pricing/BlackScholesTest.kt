package com.optionhelper.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.exp

class BlackScholesTest {
    @Test
    fun `알려진 값과 일치한다`() {
        // Hull 예제: S=42, K=40, r=10%, sigma=20%, T=0.5 → 콜 4.76, 풋 0.81
        assertThat(BlackScholes.price(OptionType.CALL, 42.0, 40.0, 0.5, 0.10, 0.20)).isCloseTo(4.76, within(0.01))
        assertThat(BlackScholes.price(OptionType.PUT, 42.0, 40.0, 0.5, 0.10, 0.20)).isCloseTo(0.81, within(0.01))
    }

    @Test
    fun `풋-콜 패리티를 만족한다`() {
        val s = 158.13
        val k = 157.5
        val t = 0.03
        val r = 0.04
        val call = BlackScholes.price(OptionType.CALL, s, k, t, r, 0.5)
        val put = BlackScholes.price(OptionType.PUT, s, k, t, r, 0.5)
        assertThat(call - put).isCloseTo(s - k * exp(-r * t), within(1e-6))
    }

    @Test
    fun `만기가 지나면 내재가치만 남는다`() {
        assertThat(BlackScholes.price(OptionType.CALL, 160.0, 157.5, 0.0, 0.04, 0.5)).isEqualTo(2.5)
        assertThat(BlackScholes.price(OptionType.PUT, 160.0, 157.5, 0.0, 0.04, 0.5)).isEqualTo(0.0)
    }

    @Test
    fun `ATM 델타는 콜 약 0_5 풋 약 -0_5`() {
        val call = BlackScholes.greeks(OptionType.CALL, 100.0, 100.0, 0.01, 0.0, 0.5)
        val put = BlackScholes.greeks(OptionType.PUT, 100.0, 100.0, 0.01, 0.0, 0.5)
        assertThat(call.delta).isCloseTo(0.5, within(0.02))
        assertThat(put.delta).isCloseTo(-0.5, within(0.02))
        assertThat(call.gamma).isEqualTo(put.gamma)
    }
}
