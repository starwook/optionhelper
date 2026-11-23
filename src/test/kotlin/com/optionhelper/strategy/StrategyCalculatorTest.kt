package com.optionhelper.strategy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class StrategyCalculatorTest {
    private val spot = 158.13

    @Test
    fun `스트래들 수수료는 4번 7_96달러`() {
        assertThat(TossFee.roundTrip(2)).isCloseTo(7.96, within(1e-9))
        assertThat(TossFee.roundTripPerShare(2)).isCloseTo(0.0796, within(1e-9))
    }

    @Test
    fun `롱 스트래들 손익분기 - 수수료 전후`() {
        val result = StrategyCalculator.evaluate(Strategy.longStraddle(157.5, 3.15, 2.43), spot)

        assertThat(result.netPremium).isCloseTo(5.58, within(1e-9))
        assertThat(result.breakevens.map { it.price }).containsExactly(151.92, 163.08)
        assertThat(result.breakevensWithFee[0].price).isCloseTo(151.92 - 0.0796, within(1e-9))
        assertThat(result.breakevensWithFee[1].price).isCloseTo(163.08 + 0.0796, within(1e-9))
        assertThat(result.breakevens[1].movePct).isCloseTo(163.08 / spot - 1, within(1e-9))
        assertThat(result.maxProfit).isNull()
        assertThat(result.maxLoss).isCloseTo(5.58 + 0.0796, within(1e-9))
    }

    @Test
    fun `롱 스트랭글 손익분기`() {
        val result = StrategyCalculator.evaluate(Strategy.longStrangle(155.0, 1.40, 160.0, 1.80), spot)
        assertThat(result.breakevens[0].price).isCloseTo(155.0 - 3.20, within(1e-9))
        assertThat(result.breakevens[1].price).isCloseTo(160.0 + 3.20, within(1e-9))
    }

    @Test
    fun `숏 풋 - 최대 이익은 받은 프리미엄, 손익분기는 행사가 - 프리미엄`() {
        val result = StrategyCalculator.evaluate(Strategy.shortPut(150.0, 2.0), spot)
        assertThat(result.netPremium).isEqualTo(-2.0)
        assertThat(result.breakevens.single().price).isCloseTo(148.0, within(1e-9))
        assertThat(result.maxProfit).isCloseTo(2.0 - 0.0398, within(1e-9))
        assertThat(result.maxLoss).isCloseTo(148.0 + 0.0398, within(1e-9))
    }

    @Test
    fun `불 콜 스프레드 - 최대 손익이 유한`() {
        val result = StrategyCalculator.evaluate(Strategy.bullCallSpread(155.0, 4.5, 160.0, 2.0), spot)
        assertThat(result.breakevens.single().price).isCloseTo(157.5, within(1e-9))
        assertThat(result.maxProfit).isCloseTo(5.0 - 2.5 - 0.0796, within(1e-9))
        assertThat(result.maxLoss).isCloseTo(2.5 + 0.0796, within(1e-9))
    }

    @Test
    fun `베어 콜 크레딧 스프레드 - 크레딧을 받는다`() {
        val result = StrategyCalculator.evaluate(Strategy.bearCallCreditSpread(160.0, 2.0, 165.0, 0.8), spot)
        assertThat(result.netPremium).isCloseTo(-1.2, within(1e-9))
        assertThat(result.breakevens.single().price).isCloseTo(161.2, within(1e-9))
        assertThat(result.maxLoss).isCloseTo(5.0 - 1.2 + 0.0796, within(1e-9))
    }

    @Test
    fun `손익 곡선`() {
        val curve = StrategyCalculator.payoffCurve(Strategy.longStraddle(157.5, 3.15, 2.43), 150.0, 165.0, steps = 2)
        assertThat(curve.map { it.price }).containsExactly(150.0, 157.5, 165.0)
        assertThat(curve[1].profit).isCloseTo(-5.58, within(1e-9))
    }
}
