package com.optionhelper.signal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategySignalsTest {
    private val none = SignalInput(ivHvRatio = null, expectedMovePct = null, earningsBeforeExpiry = false, pastEarningsAvgMovePct = null)

    @Test
    fun `IV가 HV보다 크게 높으면 매도형`() {
        assertThat(StrategySignals.evaluate(none.copy(ivHvRatio = 1.5)).map { it.zone }).containsExactly(StrategyZone.PREMIUM_SELLING)
    }

    @Test
    fun `IV가 HV보다 낮으면 매수형`() {
        assertThat(StrategySignals.evaluate(none.copy(ivHvRatio = 0.8)).map { it.zone }).containsExactly(StrategyZone.PREMIUM_BUYING)
    }

    @Test
    fun `중간이면 신호 없음`() {
        assertThat(StrategySignals.evaluate(none.copy(ivHvRatio = 1.1))).isEmpty()
    }

    @Test
    fun `실적 앞 예상 변동이 과거 평균보다 작으면 롱 변동성`() {
        val input = none.copy(earningsBeforeExpiry = true, expectedMovePct = 0.05, pastEarningsAvgMovePct = 0.07)
        assertThat(StrategySignals.evaluate(input).map { it.zone }).containsExactly(StrategyZone.EARNINGS_LONG_VOL)
    }

    @Test
    fun `실적 앞 예상 변동이 과거 평균의 1_2배를 넘으면 과한 프리미엄`() {
        val input = none.copy(earningsBeforeExpiry = true, expectedMovePct = 0.10, pastEarningsAvgMovePct = 0.07)
        assertThat(StrategySignals.evaluate(input).map { it.zone }).containsExactly(StrategyZone.EARNINGS_OVERPRICED)
    }

    @Test
    fun `만기 안에 실적이 없으면 실적 규칙은 보지 않는다`() {
        val input = none.copy(earningsBeforeExpiry = false, expectedMovePct = 0.05, pastEarningsAvgMovePct = 0.07)
        assertThat(StrategySignals.evaluate(input)).isEmpty()
    }
}
