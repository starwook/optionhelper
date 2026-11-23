package com.optionhelper.scenario

import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.ImpliedVolatilitySolver
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class OpenEstimatesTest {
    // 2026-11-24(화) 08:30 ET 프리마켓. 전일(11/23) 마감 NVDA $168.27, 12/31 만기 185 콜 $6.35
    private val now = Instant.parse("2026-11-24T13:30:00Z")
    private val expiry = LocalDate.of(2026, 12, 31)
    private val moves = listOf(-0.05, -0.03, 0.0, 0.03, 0.05)
    private val shifts = listOf(IvShift("−20%p", -0.20), IvShift("−10%p", -0.10), IvShift("+10%p", 0.10))

    private fun build(premarket: Double) =
        OpenEstimates.build(OptionType.CALL, 185.0, expiry, 6.35, 168.27, premarket, now, 0.04, moves, shifts)!!

    @Test
    fun `개장 기준 시각은 오늘 9시30분 ET, 전일 마감은 어제 16시`() {
        val e = build(168.27)
        assertThat(e.basisAt).isEqualTo(Instant.parse("2026-11-24T14:30:00Z"))
        assertThat(e.previousCloseAt).isEqualTo(Instant.parse("2026-11-23T21:00:00Z"))
    }

    @Test
    fun `주말에는 다음 월요일 개장, 전일 마감은 금요일`() {
        val saturday = Instant.parse("2026-11-28T15:00:00Z")
        assertThat(OpenEstimates.basisAt(saturday)).isEqualTo(Instant.parse("2026-11-30T14:30:00Z"))
    }

    @Test
    fun `장중이면 지금이 기준`() {
        val midday = Instant.parse("2026-11-24T17:00:00Z")
        assertThat(OpenEstimates.basisAt(midday)).isEqualTo(midday)
    }

    @Test
    fun `주가가 그대로고 IV도 그대로면 밤사이 시간이 안 흘러 전일 가격과 같다`() {
        val e = build(168.27)
        val unchanged = e.rows.first { it.move == 0.0 }.prices.first()
        // 실거래시간 기준이라 전일 마감과 오늘 개장 사이 남은 시간이 같다
        assertThat(unchanged).isCloseTo(6.35, within(1e-4))
        assertThat(e.gap).isEqualTo(0.0)
    }

    @Test
    fun `30% 갭 상승이면 모든 칸이 내재가치 이상이고 주가·IV가 높을수록 비싸다`() {
        val e = build(168.27 * 1.3)
        assertThat(e.gap).isBetween(0.2999, 0.3001)
        e.rows.forEach { row -> row.prices.forEach { assertThat(it).isGreaterThanOrEqualTo(row.intrinsic - 1e-9) } }
        val atPremarket = e.rows.map { it.prices.first() }
        assertThat(atPremarket).isSorted()
        val byIv = e.rows.first { it.move == 0.0 }.let { row -> e.levels.indices.sortedBy { e.levels[it].iv }.map { row.prices[it] } }
        assertThat(byIv).isSorted()
        assertThat(e.delta).isGreaterThan(0.8)
    }

    @Test
    fun `IV를 역산할 수 없는 가격이면 null`() {
        assertThat(OpenEstimates.build(OptionType.CALL, 150.0, expiry, 1.0, 168.27, 170.0, now, 0.04, moves, shifts)).isNull()
    }

    @Test
    fun `역산 IV로 전일 가격을 다시 만들면 같은 값`() {
        val e = build(170.0)
        val tPrev = MarketClock.yearsUntil(e.previousCloseAt, expiry)
        assertThat(BlackScholes.price(OptionType.CALL, 168.27, 185.0, tPrev, 0.04, e.impliedVol))
            .isCloseTo(6.35, within(1e-4))
        assertThat(ImpliedVolatilitySolver.solve(OptionType.CALL, 6.35, 168.27, 185.0, tPrev, 0.04)).isEqualTo(e.impliedVol)
    }
}
