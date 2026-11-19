package com.optionhelper.pricing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class OptionType { CALL, PUT }

data class Greeks(
    val delta: Double,
    val gamma: Double,
    // 변동성 1%p 변화당 가격 변화
    val vega: Double,
    // 1년당 가격 변화. 화면에서 하루 단위로 쓸 때는 실거래일 수로 나눈다.
    val theta: Double,
)

/**
 * 유럽형 블랙-숄즈(배당 0). t는 년 단위, r·sigma는 소수(0.04 = 4%).
 */
object BlackScholes {
    fun price(
        type: OptionType,
        s: Double,
        k: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0 || sigma <= 0.0) return intrinsic(type, s, k)
        val (d1, d2) = d1d2(s, k, t, r, sigma)
        val discount = exp(-r * t)
        return when (type) {
            OptionType.CALL -> s * Normal.cdf(d1) - k * discount * Normal.cdf(d2)
            OptionType.PUT -> k * discount * Normal.cdf(-d2) - s * Normal.cdf(-d1)
        }
    }

    fun greeks(
        type: OptionType,
        s: Double,
        k: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Greeks {
        val (d1, d2) = d1d2(s, k, t, r, sigma)
        val sqrtT = sqrt(t)
        val discount = exp(-r * t)
        val pdf = Normal.pdf(d1)
        val gamma = pdf / (s * sigma * sqrtT)
        val vega = s * pdf * sqrtT / 100.0
        val commonTheta = -s * pdf * sigma / (2 * sqrtT)
        return when (type) {
            OptionType.CALL ->
                Greeks(Normal.cdf(d1), gamma, vega, commonTheta - r * k * discount * Normal.cdf(d2))
            OptionType.PUT ->
                Greeks(Normal.cdf(d1) - 1.0, gamma, vega, commonTheta + r * k * discount * Normal.cdf(-d2))
        }
    }

    fun intrinsic(
        type: OptionType,
        s: Double,
        k: Double,
    ): Double =
        when (type) {
            OptionType.CALL -> maxOf(s - k, 0.0)
            OptionType.PUT -> maxOf(k - s, 0.0)
        }

    private fun d1d2(
        s: Double,
        k: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Pair<Double, Double> {
        val sigmaSqrtT = sigma * sqrt(t)
        val d1 = (ln(s / k) + (r + sigma * sigma / 2) * t) / sigmaSqrtT
        return d1 to d1 - sigmaSqrtT
    }
}

internal object Normal {
    private const val INV_SQRT_2PI = 0.3989422804014327

    fun pdf(x: Double): Double = INV_SQRT_2PI * exp(-x * x / 2)

    /** Abramowitz-Stegun 26.2.17 근사(오차 7.5e-8). */
    fun cdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val upper = pdf(x) * poly
        return if (x >= 0) 1.0 - upper else upper
    }
}
