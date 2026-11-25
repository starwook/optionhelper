package com.optionhelper.paper

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PaperPricingTest {
    // 2026-10-01부터 평일 40개, 종가가 100 ↔ 102로 출렁인다(HV가 0이 아니게)
    private val bars =
        generateSequence(LocalDate.of(2026, 10, 1)) { it.plusDays(1) }
            .filter { it.dayOfWeek.value <= 5 }
            .take(40)
            .mapIndexed { i, d -> (if (i % 2 == 0) 100.0 else 102.0).let { DailyBar(d, it, it, it, it) } }
            .toList()
    private val expiry = bars[35].date

    @Test
    fun `만기 전에는 이론가 호가, 날이 갈수록 같은 주가의 콜 값이 줄어든다`() {
        val early = PaperPricing.markAt(bars, 24, OptionType.CALL, 100.0, expiry, 0.04)
        val late = PaperPricing.markAt(bars, 34, OptionType.CALL, 100.0, expiry, 0.04)
        assertThat(early.settled).isFalse()
        assertThat(early.ask).isGreaterThan(early.bid)
        assertThat(late.mid).isLessThan(early.mid)
    }

    @Test
    fun `만기일 이후는 만기일 종가로 내재가치 정산`() {
        val m = PaperPricing.markAt(bars, 39, OptionType.CALL, 99.0, expiry, 0.04)
        assertThat(m.settled).isTrue()
        assertThat(m.settlementPrice).isEqualTo(bars[35].close)
        assertThat(m.mid).isCloseTo(bars[35].close - 99.0, within(1e-9))
    }

    @Test
    fun `배율을 곱하면 값도 같은 배율 - 빨리감기에서 가격을 가려도 IV는 그대로`() {
        val base = PaperPricing.quoteAt(bars, 30, OptionType.PUT, 101.0, expiry, 0.04)
        val scaled = PaperPricing.quoteAt(bars, 30, OptionType.PUT, 202.0, expiry, 0.04, scale = 2.0)
        assertThat(scaled.impliedVolatility).isCloseTo(base.impliedVolatility!!, within(1e-12))
        assertThat(scaled.mid).isCloseTo(base.mid * 2, within(0.02))
    }
}
