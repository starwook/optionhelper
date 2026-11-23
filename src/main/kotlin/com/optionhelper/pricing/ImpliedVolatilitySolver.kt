package com.optionhelper.pricing

/**
 * 시장 가격으로 IV를 역산한다. 블랙-숄즈 가격은 sigma에 대해 단조 증가하므로 이분법으로 안정적으로 찾는다.
 */
object ImpliedVolatilitySolver {
    private const val MIN_SIGMA = 0.001
    private const val MAX_SIGMA = 5.0
    private const val PRICE_TOLERANCE = 1e-6
    private const val MAX_ITERATIONS = 200

    /** 가격이 내재가치보다 낮거나 IV 500%로도 못 미치는 등 풀 수 없으면 null. */
    fun solve(
        type: OptionType,
        marketPrice: Double,
        s: Double,
        k: Double,
        t: Double,
        r: Double,
    ): Double? {
        if (t <= 0.0 || marketPrice <= 0.0) return null
        var low = MIN_SIGMA
        var high = MAX_SIGMA
        if (marketPrice < BlackScholes.price(type, s, k, t, r, low)) return null
        if (marketPrice > BlackScholes.price(type, s, k, t, r, high)) return null

        repeat(MAX_ITERATIONS) {
            val mid = (low + high) / 2
            val diff = BlackScholes.price(type, s, k, t, r, mid) - marketPrice
            if (kotlin.math.abs(diff) < PRICE_TOLERANCE) return mid
            if (diff > 0) high = mid else low = mid
        }
        return (low + high) / 2
    }
}
