package com.optionhelper.stockchart

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.pricing.MarketClock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 화면에 그릴 봉 한 개. time은 epoch 초: 분봉은 봉 시작 시각, 일·주·월·년봉은 그 기간 첫날의 UTC 자정(화면이 UTC 날짜로 읽는다).
 * volume은 묶은 봉들의 거래량 합(데이터 소스 기준, 실제 주식 거래량이 아닐 수 있다).
 */
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)

/** 봉 묶기. Spring 없이 테스트할 수 있게 순수 함수로 둔다. */
object Candles {
    /** 정규장 안에서 시작한 분봉만 남겨, 장 시작(09:30 ET)부터 minutes 단위로 묶는다. */
    fun intraday(
        bars: List<IntradayBar>,
        minutes: Int,
    ): List<Candle> {
        val step = Duration.ofMinutes(minutes.toLong())
        return bars
            .mapNotNull { bar ->
                val date = bar.time.atZone(MarketClock.NEW_YORK).toLocalDate()
                if (!MarketClock.isTradingDay(date)) return@mapNotNull null
                val open = ZonedDateTime.of(date, MarketClock.OPEN, MarketClock.NEW_YORK).toInstant()
                val close = ZonedDateTime.of(date, MarketClock.closeOf(date), MarketClock.NEW_YORK).toInstant()
                if (bar.time.isBefore(open) || !bar.time.isBefore(close)) return@mapNotNull null
                val slot = Duration.between(open, bar.time).toMillis() / step.toMillis()
                open.plus(step.multipliedBy(slot)).epochSecond to Candle(0, bar.open, bar.high, bar.low, bar.close, bar.volume)
            }.let(::merge)
    }

    /** 일봉을 일·주(월요일 시작)·월·년 단위로 묶는다. */
    fun periodic(
        bars: List<DailyBar>,
        interval: ChartInterval,
    ): List<Candle> =
        bars.map { bar ->
            val start =
                when (interval) {
                    ChartInterval.WEEK -> bar.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    ChartInterval.MONTH -> bar.date.withDayOfMonth(1)
                    ChartInterval.YEAR -> bar.date.withDayOfYear(1)
                    else -> bar.date
                }
            epochDay(start) to Candle(0, bar.open, bar.high, bar.low, bar.close, bar.volume)
        }.let(::merge)

    /** 그날 정규장 분봉으로 그날 일봉을 만든다(시가 = 첫 봉 시가, 종가 = 마지막 봉 종가). */
    fun dailyFrom(
        date: LocalDate,
        bars: List<Candle>,
    ): DailyBar? =
        bars.takeIf { it.isNotEmpty() }?.let {
            DailyBar(date, it.first().open, it.maxOf { b -> b.high }, it.minOf { b -> b.low }, it.last().close, it.sumOf { b -> b.volume })
        }

    private fun epochDay(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    /** 오래된 순으로 들어온 (봉 시각, 봉)을 같은 시각끼리 합친다. */
    private fun merge(keyed: List<Pair<Long, Candle>>): List<Candle> =
        keyed.groupBy({ it.first }, { it.second }).map { (time, group) ->
            Candle(time, group.first().open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close, group.sumOf { it.volume })
        }.sortedBy { it.time }
}
