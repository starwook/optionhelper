package com.optionhelper.scenario

import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.ImpliedVolatilitySolver
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import java.time.Instant
import java.time.LocalDate

/** 시나리오 열 하나: "IV가 이만큼 바뀌면". shift는 지금 이 옵션 IV에 더할 값(소수, −0.1 = −10%p). */
data class IvShift(val label: String, val shift: Double)

data class IvLevel(val label: String, val iv: Double)

/** 그날 장 마감 기준 옵션 가격(주당). prices는 levels 순서. */
data class ScenarioRow(val date: LocalDate, val tradingDaysLeft: Double, val prices: List<Double>)

data class OptionScenario(
    val impliedVol: Double,
    val delta: Double,
    // 실거래일 하루당 가격 변화(주당)
    val thetaPerDay: Double,
    // IV 1%p당 가격 변화(주당)
    val vegaPerPoint: Double,
    val levels: List<IvLevel>,
    val rows: List<ScenarioRow>,
)

/**
 * 보유 옵션 시나리오: 지금 가격으로 IV를 역산하고, 주가가 그대로일 때 날짜(세타)와 IV 변화에 따라 가격이 어떻게 되는지 표로 만든다.
 */
object OptionScenarios {
    private const val MIN_IV = 0.01
    private const val STEP_DAYS = 7L

    /** 가격으로 IV를 풀 수 없거나(내재가치 미만 등) 이미 만기가 지났으면 null. */
    fun build(
        type: OptionType,
        spot: Double,
        strike: Double,
        expiry: LocalDate,
        price: Double,
        now: Instant,
        rate: Double,
        shifts: List<IvShift>,
    ): OptionScenario? {
        val t = MarketClock.yearsUntil(now, expiry)
        if (t <= 0.0) return null
        val iv = ImpliedVolatilitySolver.solve(type, price, spot, strike, t, rate) ?: return null
        val greeks = BlackScholes.greeks(type, spot, strike, t, rate, iv)
        val levels = listOf(IvLevel("지금", iv)) + shifts.map { IvLevel(it.label, maxOf(MIN_IV, iv + it.shift)) }

        val rows =
            checkpoints(now, expiry).map { (date, at) ->
                val years = MarketClock.yearsUntil(at, expiry)
                ScenarioRow(
                    date,
                    MarketClock.tradingHoursUntil(at, expiry) / MarketClock.TRADING_HOURS_PER_DAY,
                    levels.map { BlackScholes.price(type, spot, strike, years, rate, it.iv) },
                )
            }
        return OptionScenario(iv, greeks.delta, greeks.theta / MarketClock.DAYS_PER_YEAR, greeks.vega, levels, rows)
    }

    /** 지금, 그 뒤 1주 간격의 장 마감, 만기 하루 전, 만기. */
    private fun checkpoints(
        now: Instant,
        expiry: LocalDate,
    ): List<Pair<LocalDate, Instant>> {
        val today = now.atZone(MarketClock.NEW_YORK).toLocalDate()
        val weekly = generateSequence(today.plusDays(STEP_DAYS)) { it.plusDays(STEP_DAYS) }.takeWhile { it.isBefore(expiry) }.toList()
        val dayBefore = generateSequence(expiry.minusDays(1)) { it.minusDays(1) }.first { MarketClock.isTradingDay(it) }
        val dates = (weekly + dayBefore).filter { it.isAfter(today) }.distinct().sorted()
        return listOf(today to now) + dates.map { it to closeAt(it) } + (expiry to closeAt(expiry))
    }

    private fun closeAt(date: LocalDate): Instant = MarketClock.expiryAt(date).toInstant()
}
