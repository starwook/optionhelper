package com.optionhelper.strategy

import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.OptionType
import kotlin.math.abs

data class Breakeven(
    val price: Double,
    // 현재가 대비 필요한 변동(+0.035 = +3.5%)
    val movePct: Double,
)

data class PayoffPoint(val price: Double, val profit: Double)

/** 모든 금액은 주당. 계약 1개 기준 달러는 × 100. 최대 손익은 수수료 반영, 무한이면 null. */
data class StrategyResult(
    val strategy: Strategy,
    // +면 낸 돈(데빗), -면 받은 돈(크레딧)
    val netPremium: Double,
    val feePerShare: Double,
    val breakevens: List<Breakeven>,
    val breakevensWithFee: List<Breakeven>,
    val maxProfit: Double?,
    val maxLoss: Double?,
)

/**
 * 만기 손익 계산. 수수료는 진입·청산 왕복을 보수적으로 넣는다(만기에 휴지 조각이 돼 청산 수수료가 안 나가는 경우도 포함).
 */
object StrategyCalculator {
    fun evaluate(
        strategy: Strategy,
        spot: Double,
    ): StrategyResult {
        val contracts = strategy.legs.sumOf { abs(it.quantity) }
        val fee = TossFee.roundTripPerShare(contracts)
        val netPremium = strategy.legs.sumOf { it.premium * it.quantity }
        val points = evaluationPoints(strategy)
        val profits = points.map { payoff(strategy, it) }
        val rightSlope = strategy.legs.sumOf { if (it.type == OptionType.CALL) it.quantity else 0 }

        return StrategyResult(
            strategy = strategy,
            netPremium = netPremium,
            feePerShare = fee,
            breakevens = roots(strategy, points, 0.0).map { Breakeven(it, it / spot - 1) },
            breakevensWithFee = roots(strategy, points, fee).map { Breakeven(it, it / spot - 1) },
            maxProfit = if (rightSlope > 0) null else profits.max() - fee,
            maxLoss = if (rightSlope < 0) null else -profits.min() + fee,
        )
    }

    /** 만기 주가가 price일 때 주당 손익(수수료 제외). */
    fun payoff(
        strategy: Strategy,
        price: Double,
    ): Double = strategy.legs.sumOf { (BlackScholes.intrinsic(it.type, price, it.strike) - it.premium) * it.quantity }

    fun payoffCurve(
        strategy: Strategy,
        from: Double,
        to: Double,
        steps: Int = 60,
    ): List<PayoffPoint> =
        (0..steps).map { i ->
            val price = from + (to - from) * i / steps
            PayoffPoint(price, payoff(strategy, price))
        }

    /** 손익 곡선은 행사가에서만 꺾이는 직선 조각이라 꺾이는 점과 양 끝만 보면 된다. */
    private fun evaluationPoints(strategy: Strategy): List<Double> {
        val strikes = strategy.legs.map { it.strike }.distinct().sorted()
        return listOf(0.0) + strikes + listOf(strikes.last() * 3)
    }

    /** payoff == target이 되는 주가들. */
    private fun roots(
        strategy: Strategy,
        points: List<Double>,
        target: Double,
    ): List<Double> =
        points.zipWithNext().mapNotNull { (a, b) ->
            val fa = payoff(strategy, a) - target
            val fb = payoff(strategy, b) - target
            when {
                fa == 0.0 -> a
                fa * fb < 0 -> a + (b - a) * fa / (fa - fb)
                else -> null
            }
        }.distinct()
}
