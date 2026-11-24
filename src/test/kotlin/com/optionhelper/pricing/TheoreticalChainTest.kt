package com.optionhelper.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.exp

class TheoreticalChainTest {
    private val years = 30.0 / 365

    @Test
    fun `아래 행사가일수록 IV가 높다(스큐), ATM은 그대로`() {
        val atm = 0.5
        assertThat(TheoreticalChain.strikeIv(atm, 100.0, 100.0, years)).isCloseTo(atm, within(1e-12))
        assertThat(TheoreticalChain.strikeIv(atm, 100.0, 90.0, years)).isGreaterThan(TheoreticalChain.strikeIv(atm, 100.0, 110.0, years))
    }

    @Test
    fun `일봉이 모자라면 기본 IV, 범위를 넘으면 자른다`() {
        assertThat(TheoreticalChain.atmIv(listOf(100.0, 101.0))).isEqualTo(TheoreticalChain.FALLBACK_IV)
        val flat = List(30) { 100.0 + (it % 2) * 0.0001 }
        assertThat(TheoreticalChain.atmIv(flat)).isEqualTo(TheoreticalChain.MIN_IV)
    }

    @Test
    fun `이론가 호가 - 매수 매도 간격, 같은 행사가 콜풋 패리티`() {
        val chain = TheoreticalChain.chain("NVDA", LocalDate.of(2026, 12, 24), listOf(95.0, 100.0, 105.0), 100.0, years, 0.04, 0.5)
        val call = chain.quote(OptionType.CALL, 100.0)!!
        val put = chain.quote(OptionType.PUT, 100.0)!!
        assertThat(
            call.ask - call.bid,
        ).isCloseTo(maxOf(TheoreticalChain.MIN_SPREAD, call.mid * TheoreticalChain.SPREAD_RATIO), within(0.011))
        // C − P = S − K·e^(−rt) (같은 IV라 성립, 반올림 오차만)
        assertThat(call.mid - put.mid).isCloseTo(100.0 - 100.0 * exp(-0.04 * years), within(0.02))
        assertThat(call.delta!!).isBetween(0.5, 0.6)
    }
}
