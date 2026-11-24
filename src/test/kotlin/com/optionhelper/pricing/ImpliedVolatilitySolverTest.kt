package com.optionhelper.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ImpliedVolatilitySolverTest {
    private val r = 0.04

    @Test
    fun `요구서 예시 - 실거래시간 12_7시간이면 IV 약 50%`() {
        val t = 12.7 / (252 * 6.5)
        val callIv = ImpliedVolatilitySolver.solve(OptionType.CALL, 3.15, 158.13, 157.5, t, r)!!
        val putIv = ImpliedVolatilitySolver.solve(OptionType.PUT, 2.43, 158.13, 157.5, t, r)!!

        assertThat(callIv).isCloseTo(0.50, within(0.05))
        assertThat(putIv).isCloseTo(0.50, within(0.05))
    }

    @Test
    fun `가격으로 역산한 IV를 다시 넣으면 같은 가격이 나온다`() {
        listOf(0.15, 0.5, 1.2).forEach { sigma ->
            val t = 0.05
            val price = BlackScholes.price(OptionType.PUT, 150.0, 145.0, t, r, sigma)
            val iv = ImpliedVolatilitySolver.solve(OptionType.PUT, price, 150.0, 145.0, t, r)!!
            assertThat(iv).isCloseTo(sigma, within(1e-4))
        }
    }

    @Test
    fun `내재가치보다 싼 가격이면 풀지 않는다`() {
        assertThat(ImpliedVolatilitySolver.solve(OptionType.CALL, 1.0, 160.0, 157.5, 0.01, r)).isNull()
    }

    @Test
    fun `만기가 지났으면 풀지 않는다`() {
        assertThat(ImpliedVolatilitySolver.solve(OptionType.CALL, 3.0, 160.0, 157.5, 0.0, r)).isNull()
    }
}
