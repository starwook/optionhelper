package com.optionhelper.strategy

import com.optionhelper.option.OptionChain
import com.optionhelper.pricing.OptionType

/**
 * 화면에서 비교하는 전략 목록. ATM 기준으로 행사가를 골라 중간가로 만든다. 체인에 필요한 행사가가 없으면 그 전략은 빠진다.
 * 대시보드와 복기 모드가 같은 목록을 쓴다.
 */
object StrategyMenu {
    fun build(
        chain: OptionChain,
        atm: Double,
    ): List<Strategy> {
        fun k(steps: Int) = chain.strikeAway(atm, steps)

        fun mid(
            type: OptionType,
            strike: Double?,
        ) = strike?.let { chain.quote(type, it)?.mid }

        fun two(
            k1: Double?,
            t1: OptionType,
            k2: Double?,
            t2: OptionType,
            make: (Double, Double, Double, Double) -> Strategy,
        ): Strategy? {
            val m1 = mid(t1, k1) ?: return null
            val m2 = mid(t2, k2) ?: return null
            return make(k1!!, m1, k2!!, m2)
        }

        return listOfNotNull(
            two(atm, OptionType.CALL, atm, OptionType.PUT) { k, c, _, p -> Strategy.longStraddle(k, c, p) },
            two(k(-1), OptionType.PUT, k(1), OptionType.CALL, Strategy::longStrangle),
            two(k(-2), OptionType.PUT, k(2), OptionType.CALL, Strategy::longStrangle),
            k(-1)?.let { s -> mid(OptionType.PUT, s)?.let { Strategy.shortPut(s, it) } },
            two(atm, OptionType.CALL, k(2), OptionType.CALL, Strategy::bullCallSpread),
            two(atm, OptionType.PUT, k(-2), OptionType.PUT, Strategy::bearPutSpread),
            two(k(-1), OptionType.PUT, k(-3), OptionType.PUT, Strategy::bullPutCreditSpread),
            two(k(1), OptionType.CALL, k(3), OptionType.CALL, Strategy::bearCallCreditSpread),
        )
    }
}
