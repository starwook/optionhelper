package com.optionhelper.strategy

import com.optionhelper.pricing.OptionType

/** 옵션 한 다리. quantity가 +면 매수, -면 매도. premium은 주당 가격(중간가). */
data class OptionLeg(
    val type: OptionType,
    val strike: Double,
    val premium: Double,
    val quantity: Int,
)

enum class StrategyKind(val label: String) {
    LONG_STRADDLE("롱 스트래들"),
    LONG_STRANGLE("롱 스트랭글"),
    SHORT_PUT("숏 풋"),
    BULL_CALL_SPREAD("불 콜 스프레드"),
    BEAR_PUT_SPREAD("베어 풋 스프레드"),
    BULL_PUT_CREDIT_SPREAD("불 풋 크레딧 스프레드"),
    BEAR_CALL_CREDIT_SPREAD("베어 콜 크레딧 스프레드"),
}

data class Strategy(
    val kind: StrategyKind,
    val legs: List<OptionLeg>,
) {
    companion object {
        fun longStraddle(
            strike: Double,
            callPremium: Double,
            putPremium: Double,
        ) = Strategy(
            StrategyKind.LONG_STRADDLE,
            listOf(OptionLeg(OptionType.CALL, strike, callPremium, 1), OptionLeg(OptionType.PUT, strike, putPremium, 1)),
        )

        fun longStrangle(
            putStrike: Double,
            putPremium: Double,
            callStrike: Double,
            callPremium: Double,
        ) = Strategy(
            StrategyKind.LONG_STRANGLE,
            listOf(OptionLeg(OptionType.PUT, putStrike, putPremium, 1), OptionLeg(OptionType.CALL, callStrike, callPremium, 1)),
        )

        fun shortPut(
            strike: Double,
            premium: Double,
        ) = Strategy(StrategyKind.SHORT_PUT, listOf(OptionLeg(OptionType.PUT, strike, premium, -1)))

        /** 낮은 행사가 콜 매수 + 높은 행사가 콜 매도. */
        fun bullCallSpread(
            lowStrike: Double,
            lowPremium: Double,
            highStrike: Double,
            highPremium: Double,
        ) = Strategy(
            StrategyKind.BULL_CALL_SPREAD,
            listOf(OptionLeg(OptionType.CALL, lowStrike, lowPremium, 1), OptionLeg(OptionType.CALL, highStrike, highPremium, -1)),
        )

        /** 높은 행사가 풋 매수 + 낮은 행사가 풋 매도. */
        fun bearPutSpread(
            highStrike: Double,
            highPremium: Double,
            lowStrike: Double,
            lowPremium: Double,
        ) = Strategy(
            StrategyKind.BEAR_PUT_SPREAD,
            listOf(OptionLeg(OptionType.PUT, highStrike, highPremium, 1), OptionLeg(OptionType.PUT, lowStrike, lowPremium, -1)),
        )

        /** 높은 행사가 풋 매도 + 낮은 행사가 풋 매수(크레딧). */
        fun bullPutCreditSpread(
            highStrike: Double,
            highPremium: Double,
            lowStrike: Double,
            lowPremium: Double,
        ) = Strategy(
            StrategyKind.BULL_PUT_CREDIT_SPREAD,
            listOf(OptionLeg(OptionType.PUT, highStrike, highPremium, -1), OptionLeg(OptionType.PUT, lowStrike, lowPremium, 1)),
        )

        /** 낮은 행사가 콜 매도 + 높은 행사가 콜 매수(크레딧). */
        fun bearCallCreditSpread(
            lowStrike: Double,
            lowPremium: Double,
            highStrike: Double,
            highPremium: Double,
        ) = Strategy(
            StrategyKind.BEAR_CALL_CREDIT_SPREAD,
            listOf(OptionLeg(OptionType.CALL, lowStrike, lowPremium, -1), OptionLeg(OptionType.CALL, highStrike, highPremium, 1)),
        )
    }
}
