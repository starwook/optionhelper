package com.optionhelper.pricing

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 과거 옵션 종가로 날짜별 "약 30일 ATM IV"를 역산한다(과거 IV를 주지 않는 데이터 소스용).
 * 날마다 남은 일수가 30일에 가장 가까운 월물(셋째 금요일)을 고르고, 기초자산 종가에 가장 가까운 행사가의 콜·풋 종가로 IV를 구해 평균한다.
 * 옵션 종가는 그날 마지막 체결가라 기초자산 종가와 시각이 어긋날 수 있다(참고용 근사).
 */
object AtmIvBacksolver {
    data class Contract(val expiry: LocalDate, val type: OptionType, val strike: Double)

    /** 같은 만기를 쓰는 날짜들과, 그 날짜들에 필요한 행사가 후보. */
    data class ExpiryGroup(val expiry: LocalDate, val dates: List<LocalDate>, val strikes: Set<Double>) {
        val contracts get() = strikes.flatMap { k -> OptionType.entries.map { Contract(expiry, it, k) } }
    }

    private const val TARGET_DAYS = 30L

    // 행사가 간격은 종목·가격마다 달라서, 흔한 간격의 위아래 행사가를 모두 후보로 둔다(상장 안 된 건 응답에서 빠진다)
    private val STRIKE_STEPS = listOf(0.5, 1.0, 2.5, 5.0)

    // ATM으로 볼 행사가 범위(종가 대비)
    private const val MAX_STRIKE_DISTANCE = 0.05

    /** 그 달 월물 만기: 셋째 금요일, 휴장이면 전날. */
    fun monthlyExpiry(
        year: Int,
        month: Int,
    ): LocalDate {
        val friday = LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY))
        return if (MarketClock.isTradingDay(friday)) friday else friday.minusDays(1)
    }

    /** date 기준 남은 일수가 30일에 가장 가까운 월물(오늘 만기는 제외). */
    fun targetExpiry(date: LocalDate): LocalDate =
        (0L..2L).map { date.plusMonths(it) }
            .map { monthlyExpiry(it.year, it.monthValue) }
            .filter { it.isAfter(date) }
            .minBy { abs(it.toEpochDay() - date.toEpochDay() - TARGET_DAYS) }

    /** 종가 맵으로 만기별 조회 계획을 만든다. */
    fun plan(closes: Map<LocalDate, Double>): List<ExpiryGroup> =
        closes.keys.sorted().groupBy { targetExpiry(it) }.map { (expiry, dates) ->
            ExpiryGroup(expiry, dates, dates.flatMap { candidateStrikes(closes.getValue(it)) }.toSet())
        }

    /**
     * 날짜별 IV(소수). prices는 (날짜, 계약) → 그날 옵션 종가. 풀 수 없는 날은 빠진다.
     */
    fun solve(
        closes: Map<LocalDate, Double>,
        prices: Map<Pair<LocalDate, Contract>, Double>,
        riskFreeRate: Double,
    ): List<Pair<LocalDate, Double>> =
        closes.keys.sorted().mapNotNull { date ->
            val spot = closes.getValue(date)
            val expiry = targetExpiry(date)
            val t = MarketClock.yearsUntil(MarketClock.expiryAt(date).toInstant(), expiry)
            val strikes =
                prices.keys.asSequence()
                    .filter { (d, c) -> d == date && c.expiry == expiry && abs(c.strike / spot - 1) <= MAX_STRIKE_DISTANCE }
                    .map { it.second.strike }
                    .distinct()
                    .sortedBy { abs(it - spot) }
            strikes.firstNotNullOfOrNull { k ->
                val call = prices[date to Contract(expiry, OptionType.CALL, k)] ?: return@firstNotNullOfOrNull null
                val put = prices[date to Contract(expiry, OptionType.PUT, k)] ?: return@firstNotNullOfOrNull null
                val ivs =
                    listOfNotNull(
                        ImpliedVolatilitySolver.solve(OptionType.CALL, call, spot, k, t, riskFreeRate),
                        ImpliedVolatilitySolver.solve(OptionType.PUT, put, spot, k, t, riskFreeRate),
                    )
                ivs.takeIf { it.isNotEmpty() }?.average()
            }?.let { date to it }
        }

    private fun candidateStrikes(spot: Double): Set<Double> =
        STRIKE_STEPS.flatMap { step -> listOf(floor(spot / step) * step, ceil(spot / step) * step) }
            .filter { it > 0 }
            .toSet()
}
