package com.optionhelper.volatility

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.sqrt

class VolatilityMetricsTest {
    @Test
    fun `예상 변동폭 - 스트래들 나누기 주가와 85% 버전`() {
        val move = ExpectedMove.of(5.58, 158.13)
        assertThat(move.pct).isCloseTo(0.03529, within(1e-5))
        assertThat(move.rule85Dollars).isCloseTo(4.743, within(1e-9))
        assertThat(move.rule85Pct).isCloseTo(0.03, within(1e-4))
    }

    @Test
    fun `실현 vs 예상`() {
        val r = RealizedVsExpected.of(open = 150.0, current = 147.0, expectedPct = 0.04)
        assertThat(r.realizedPct).isCloseTo(-0.02, within(1e-9))
        assertThat(r.ratio).isCloseTo(0.5, within(1e-9))
    }

    @Test
    fun `실현변동성 - 매일 +1%와 -1%를 번갈아 움직이면 약 연 15_9%`() {
        val closes = (0..20).map { i -> 100.0 * exp(if (i % 2 == 0) 0.0 else 0.01) }
        val hv = VolatilityMetrics.historicalVolatility(closes)!!
        // 로그수익률 ±0.01 번갈아 → 표본표준편차 ≈ 0.01 × sqrt(20/19)
        assertThat(hv).isCloseTo(0.01 * sqrt(20.0 / 19.0) * sqrt(252.0), within(1e-9))
    }

    @Test
    fun `데이터가 모자라면 실현변동성 없음`() {
        assertThat(VolatilityMetrics.historicalVolatility(listOf(1.0, 2.0))).isNull()
    }

    @Test
    fun `IV 퍼센타일`() {
        val history = listOf(0.30, 0.40, 0.50, 0.60)
        assertThat(VolatilityMetrics.ivPercentile(history, 0.55)).isEqualTo(0.75)
        assertThat(VolatilityMetrics.ivPercentile(emptyList(), 0.55)).isNull()
    }
}
