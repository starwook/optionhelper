package com.optionhelper.stockchart

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.pricing.MarketClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CandlesTest {
    // 2026-11-24(화) 정규장
    private val day = LocalDate.of(2026, 11, 24)

    private fun at(
        h: Int,
        m: Int,
    ) = ZonedDateTime.of(day, LocalTime.of(h, m), MarketClock.NEW_YORK).toInstant()

    private fun bar(
        h: Int,
        m: Int,
        o: Double,
        hi: Double,
        lo: Double,
        c: Double,
    ) = IntradayBar(at(h, m), o, hi, lo, c)

    @Test
    fun `분봉 - 정규장 밖 봉은 빼고 장 시작부터 묶는다`() {
        val bars =
            listOf(
                bar(9, 0, 1.0, 1.0, 1.0, 1.0),
                bar(9, 30, 10.0, 12.0, 9.0, 11.0),
                bar(10, 0, 11.0, 15.0, 10.0, 14.0),
                bar(10, 30, 14.0, 14.0, 8.0, 9.0),
                bar(16, 0, 1.0, 1.0, 1.0, 1.0),
            )
        val candles = Candles.intraday(bars, 60)
        assertThat(candles).containsExactly(
            Candle(at(9, 30).epochSecond, 10.0, 15.0, 9.0, 14.0),
            Candle(at(10, 30).epochSecond, 14.0, 14.0, 8.0, 9.0),
        )
    }

    @Test
    fun `월봉 - 같은 달 일봉을 합친다`() {
        val bars =
            listOf(
                DailyBar(LocalDate.of(2026, 10, 30), 1.0, 2.0, 0.5, 1.5, 10.0),
                DailyBar(LocalDate.of(2026, 11, 2), 1.5, 3.0, 1.0, 2.0, 20.0),
                DailyBar(LocalDate.of(2026, 11, 3), 2.0, 2.5, 0.8, 2.2, 30.0),
            )
        val candles = Candles.periodic(bars, ChartInterval.MONTH)
        assertThat(candles).hasSize(2)
        assertThat(
            candles[1],
        ).isEqualTo(Candle(LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 1.5, 3.0, 0.8, 2.2, 50.0))
    }

    @Test
    fun `주봉은 월요일, 년봉은 1월 1일에 놓는다`() {
        val bars = listOf(DailyBar(day, 1.0, 1.0, 1.0, 1.0))
        val epoch = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toEpochSecond() }
        assertThat(Candles.periodic(bars, ChartInterval.WEEK).single().time).isEqualTo(epoch(LocalDate.of(2026, 11, 23)))
        assertThat(Candles.periodic(bars, ChartInterval.YEAR).single().time).isEqualTo(epoch(LocalDate.of(2026, 1, 1)))
    }
}
