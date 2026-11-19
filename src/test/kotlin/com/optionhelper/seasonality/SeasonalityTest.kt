package com.optionhelper.seasonality

import com.optionhelper.marketdata.DailyBar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

class SeasonalityTest {
    private fun bar(
        date: String,
        close: Double,
    ) = DailyBar(LocalDate.parse(date), close, close, close, close)

    // 11월 말 → 12월 말 종가로 12월 수익률을 낸다. 같은 달 안의 중간 봉은 무시된다.
    private val bars =
        listOf(
            bar("2024-11-29", 100.0),
            bar("2024-12-10", 90.0),
            bar("2024-12-31", 110.0),
            bar("2025-01-31", 99.0),
            bar("2025-11-28", 200.0),
            bar("2025-12-31", 190.0),
        )

    @Test
    fun `월별 수익률은 전달 마지막 종가 대비`() {
        val returns = Seasonality.monthlyReturns(bars).associate { it.month to it.returnPct }
        assertThat(returns[YearMonth.of(2024, 12)]).isCloseTo(0.10, within(1e-9))
        assertThat(returns[YearMonth.of(2025, 1)]).isCloseTo(-0.10, within(1e-9))
        assertThat(returns).doesNotContainKey(YearMonth.of(2024, 11))
    }

    @Test
    fun `12월 통계 - 평균, 상승 확률, 연도별`() {
        val december = Seasonality.monthStats(bars).single { it.month == Month.DECEMBER }
        assertThat(december.years).isEqualTo(2)
        assertThat(december.average).isCloseTo((0.10 - 0.05) / 2, within(1e-9))
        assertThat(december.upRatio).isEqualTo(0.5)
        assertThat(december.byYear[2025]).isCloseTo(-0.05, within(1e-9))
    }
}
