package com.optionhelper.stockchart

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class IndicatorsTest {
    private val eps = within(1e-9)

    @Test
    fun `단순 이동평균 - 기간이 모자란 앞자리는 null`() {
        assertThat(Indicators.sma(listOf(1.0, 2.0, 3.0, 4.0), 3)).containsExactly(null, null, 2.0, 3.0)
    }

    @Test
    fun `지수 이동평균 - 단순 평균으로 시작해 2 나누기 (기간+1) 비중으로 따라간다`() {
        val ema = Indicators.ema(listOf(1.0, 2.0, 3.0, 6.0), 3)
        assertThat(ema.take(2)).containsOnlyNulls()
        assertThat(ema[2]).isEqualTo(2.0)
        assertThat(ema[3]!!).isCloseTo(6.0 * 0.5 + 2.0 * 0.5, eps)
    }

    @Test
    fun `볼린저밴드 - 평균 ± 2 표준편차`() {
        val (upper, lower) = Indicators.bollinger(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8, 2.0)
        // 평균 5, 모집단 표준편차 2
        assertThat(upper.last()!!).isCloseTo(9.0, eps)
        assertThat(lower.last()!!).isCloseTo(1.0, eps)
    }

    @Test
    fun `RSI - 오르기만 하면 100, 오르내림이 같으면 50`() {
        assertThat(Indicators.rsi((1..20).map { it.toDouble() }, 14).last()).isEqualTo(100.0)
        val zigzag = (0..20).map { if (it % 2 == 0) 10.0 else 11.0 } // +1, -1 반복
        assertThat(Indicators.rsi(zigzag, 2)[2]!!).isCloseTo(50.0, eps)
        assertThat(Indicators.rsi(zigzag, 14).take(14)).containsOnlyNulls()
    }

    @Test
    fun `스토캐스틱 - 종가가 고가에 붙으면 100, 범위가 0이면 50`() {
        val rising = (1..10).map { Candle(it.toLong(), it.toDouble(), it.toDouble(), it - 1.0, it.toDouble()) }
        val (k, d) = Indicators.stochastic(rising, 3, 2, 2)
        assertThat(k.last()).isEqualTo(100.0)
        assertThat(d.last()).isEqualTo(100.0)
        val flat = (1..5).map { Candle(it.toLong(), 1.0, 1.0, 1.0, 1.0) }
        assertThat(Indicators.stochastic(flat, 3, 1, 1).first.last()).isEqualTo(50.0)
    }

    @Test
    fun `MACD - 가격이 일정하면 0, 히스토그램은 MACD선 빼기 시그널`() {
        val (line, signal, hist) = Indicators.macd(List(40) { 100.0 }, 12, 26, 9)
        assertThat(line.last()!!).isCloseTo(0.0, eps)
        assertThat(signal.last()!!).isCloseTo(0.0, eps)
        assertThat(line.take(25)).containsOnlyNulls()
        assertThat(signal[33]).isNotNull()
        assertThat(signal[32]).isNull()
        val up = Indicators.macd((1..60).map { it.toDouble() + it * it / 50.0 }, 12, 26, 9)
        assertThat(up.third.last()!!).isCloseTo(up.first.last()!! - up.second.last()!!, eps)
    }

    @Test
    fun `of - 모든 지표가 봉 개수와 같은 길이`() {
        val candles = (1..130).map { Candle(it.toLong(), it.toDouble(), it + 1.0, it - 1.0, it.toDouble()) }
        val set = Indicators.of(candles)
        listOf(
            set.ma5,
            set.ma20,
            set.ma60,
            set.ma120,
            set.bbUpper,
            set.rsi,
            set.stochD,
            set.macdHist,
        ).forEach { assertThat(it).hasSize(130) }
        assertThat(set.ma60[59]).isEqualTo((1..60).average())
        assertThat(set.ma120[118]).isNull()
        assertThat(set.ma120[119]).isEqualTo((1..120).average())
    }
}
