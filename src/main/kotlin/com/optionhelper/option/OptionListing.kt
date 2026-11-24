package com.optionhelper.option

import com.optionhelper.pricing.MarketClock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/**
 * 행사가 간격. [threshold] 이하는 [step], 넘으면 [stepAbove](없으면 계속 [step]).
 * 예: NVDA 주간 만기는 150$ 이하 1$, 초과 2.5$.
 */
data class StrikeStep(
    val step: Double,
    val stepAbove: Double? = null,
    val threshold: Double = 150.0,
) {
    fun at(strike: Double): Double = if (stepAbove != null && strike >= threshold - EPS) stepAbove else step

    /** [min, max] 안의 행사가, 오름차순. */
    fun strikes(
        min: Double,
        max: Double,
    ): List<Double> {
        val first =
            if (stepAbove == null || min < threshold) {
                ceil(min / step - EPS) * step
            } else {
                threshold + ceil((min - threshold) / stepAbove - EPS) * stepAbove
            }
        return generateSequence(first) { it + at(it) }
            .takeWhile { it <= max + EPS }
            .map { Math.round(it * 1000) / 1000.0 }
            .toList()
    }

    private companion object {
        const val EPS = 1e-9
    }
}

/**
 * 한 종목의 옵션 상장 규칙(만기 요일·행사가 간격). 실제 체인을 못 쓰는 공개 사이트에서 만기와 행사가를 만든다.
 * 규칙은 실측값을 적어 둔 것이라 실제 상장과 다를 수 있다(docs/데이터소스-조사.md 11-2).
 */
data class OptionListing(
    val weeklyDays: Set<DayOfWeek>,
    val weeklyStep: StrikeStep,
    val monthlyStep: StrikeStep,
) {
    /** firstDay(0DTE 포함)부터의 만기 목록: 이번 주 포함 [WEEKLY_WEEKS]주 동안의 주간 만기 + 그 뒤 [MONTHLY_COUNT]개 월물. */
    fun expirations(firstDay: LocalDate): List<LocalDate> {
        val weeklyEnd = weeklyEnd(firstDay)
        val weeklies =
            generateSequence(firstDay) { it.plusDays(1) }
                .takeWhile { !it.isAfter(weeklyEnd) }
                .filter { it.dayOfWeek in weeklyDays }
        val monthlies =
            generateSequence(firstDay.withDayOfMonth(1)) { it.plusMonths(1) }
                .map { thirdFriday(it) }
                .filter { it.isAfter(weeklyEnd) }
                .take(MONTHLY_COUNT)
        return (weeklies + monthlies)
            .map { onTradingDay(it) }
            .filter { !it.isBefore(firstDay) }
            .distinct()
            .sorted()
            .toList()
    }

    /** 그 만기의 행사가 간격. 주간 만기 구간이면 주간 간격, 그 뒤 월물은 월물 간격. */
    fun strikeStep(
        expiry: LocalDate,
        firstDay: LocalDate,
    ): StrikeStep = if (expiry.isAfter(weeklyEnd(firstDay))) monthlyStep else weeklyStep

    private fun weeklyEnd(firstDay: LocalDate): LocalDate =
        firstDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).plusWeeks(WEEKLY_WEEKS - 1)

    private fun thirdFriday(month: LocalDate): LocalDate = month.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY))

    /** 휴장일 만기는 앞 거래일로 당긴다(예: 12-25 → 12-24). */
    private fun onTradingDay(date: LocalDate): LocalDate =
        if (MarketClock.isTradingDay(date)) date else MarketClock.previousTradingDay(date)

    companion object {
        const val WEEKLY_WEEKS = 6L
        const val MONTHLY_COUNT = 3
        val MON_WED_FRI = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val FRIDAY = setOf(DayOfWeek.FRIDAY)
    }
}
