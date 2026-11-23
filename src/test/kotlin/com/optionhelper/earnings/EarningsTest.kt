package com.optionhelper.earnings

import com.optionhelper.marketdata.DailyBar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EarningsTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun bar(
        date: String,
        close: Double,
    ) = DailyBar(d(date), close, close, close, close)

    private val bars =
        listOf(
            bar("2026-08-26", 100.0),
            // 8/26 장 마감 후 실적 → 8/27 반응 +8%
            bar("2026-08-27", 108.0),
            bar("2026-10-28", 200.0),
            // 10/29 장 시작 전 실적 → 10/28 종가 대비 -5%
            bar("2026-10-29", 190.0),
        )

    @Test
    fun `장 마감 후 발표는 다음 날, 장 전 발표는 그날 반응`() {
        val events =
            listOf(
                EarningsEvent("NVDA", d("2026-08-26"), EarningsTiming.AFTER_CLOSE),
                EarningsEvent("NVDA", d("2026-10-29"), EarningsTiming.BEFORE_OPEN),
            )
        val moves = Earnings.reactions(events, bars).map { it.movePct }
        assertThat(moves[0]).isCloseTo(0.08, within(1e-9))
        assertThat(moves[1]).isCloseTo(-0.05, within(1e-9))
    }

    @Test
    fun `일봉이 없는 실적은 뺀다`() {
        val events = listOf(EarningsEvent("NVDA", d("2025-01-01"), EarningsTiming.AFTER_CLOSE))
        assertThat(Earnings.reactions(events, bars)).isEmpty()
    }

    @Test
    fun `반응 통계는 절댓값 기준`() {
        val events =
            listOf(
                EarningsEvent("NVDA", d("2026-08-26"), EarningsTiming.AFTER_CLOSE),
                EarningsEvent("NVDA", d("2026-10-29"), EarningsTiming.BEFORE_OPEN),
            )
        val stats = Earnings.moveStats(Earnings.reactions(events, bars))!!
        assertThat(stats.averageAbsMove).isCloseTo(0.065, within(1e-9))
        assertThat(stats.maxAbsMove).isCloseTo(0.08, within(1e-9))
    }

    @Test
    fun `만기까지 실적 반응이 들어오는지`() {
        val expiry = d("2026-11-20")
        assertThat(EarningsEvent("NVDA", d("2026-11-19"), EarningsTiming.AFTER_CLOSE).reactsBy(expiry)).isTrue()
        assertThat(EarningsEvent("NVDA", d("2026-11-20"), EarningsTiming.AFTER_CLOSE).reactsBy(expiry)).isFalse()
        assertThat(EarningsEvent("NVDA", d("2026-11-20"), EarningsTiming.BEFORE_OPEN).reactsBy(expiry)).isTrue()
    }

    @Test
    fun `다음 실적과 남은 일수`() {
        val events =
            listOf(
                EarningsEvent("NVDA", d("2026-08-26"), EarningsTiming.AFTER_CLOSE),
                EarningsEvent("NVDA", d("2027-02-24"), EarningsTiming.AFTER_CLOSE),
            )
        val next = Earnings.nextEvent(events, d("2026-11-20"))!!
        assertThat(next.date).isEqualTo(d("2027-02-24"))
        assertThat(Earnings.daysUntil(next, d("2026-11-20"))).isEqualTo(96)
    }
}
