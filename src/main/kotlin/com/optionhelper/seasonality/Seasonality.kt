package com.optionhelper.seasonality

import com.optionhelper.marketdata.DailyBar
import java.time.Month
import java.time.YearMonth

/** 한 달의 수익률 = 그달 마지막 종가 ÷ 전달 마지막 종가 − 1. */
data class MonthlyReturn(val month: YearMonth, val returnPct: Double)

data class MonthStats(
    val month: Month,
    val average: Double,
    val median: Double,
    // 오른 해의 비율(0~1)
    val upRatio: Double,
    val years: Int,
    val byYear: Map<Int, Double>,
)

object Seasonality {
    /** 일봉(순서 무관)으로 월별 수익률. 첫 달은 전달 종가가 없어 빠진다. */
    fun monthlyReturns(bars: List<DailyBar>): List<MonthlyReturn> {
        val monthEndCloses =
            bars.sortedBy { it.date }
                .groupBy { YearMonth.from(it.date) }
                .mapValues { (_, list) -> list.last().close }
                .toSortedMap()
        return monthEndCloses.entries.zipWithNext { prev, cur ->
            MonthlyReturn(cur.key, cur.value / prev.value - 1)
        }
    }

    /** 1~12월 각각의 통계. 데이터가 없는 달은 빠진다. */
    fun monthStats(bars: List<DailyBar>): List<MonthStats> =
        monthlyReturns(bars)
            .groupBy { it.month.month }
            .toSortedMap()
            .map { (month, returns) ->
                val values = returns.map { it.returnPct }
                MonthStats(
                    month = month,
                    average = values.average(),
                    median = median(values),
                    upRatio = values.count { it > 0 }.toDouble() / values.size,
                    years = values.size,
                    byYear = returns.associate { it.month.year to it.returnPct },
                )
            }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
