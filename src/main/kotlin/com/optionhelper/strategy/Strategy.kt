package com.optionhelper.strategy

import com.optionhelper.pricing.OptionType

/** 옵션 한 다리. quantity가 +면 매수, -면 매도. premium은 주당 가격(중간가). */
data class OptionLeg(
    val type: OptionType,
    val strike: Double,
    val premium: Double,
    val quantity: Int,
)

/** 사용자가 주가를 어떻게 보는가. 전략 화면은 이 질문부터 고르게 한다. */
enum class MarketView(val label: String) {
    BIG_MOVE("크게 움직일 것 같다"),
    UP("오를 것 같다"),
    DOWN("내릴 것 같다"),
    NOT_DOWN("안 떨어질 것 같다"),
    NOT_UP("안 오를 것 같다"),
}

/**
 * 전략 종류. label은 교과서 이름, plainName은 화면에서 먼저 보여 줄 쉬운 이름, view는 어떤 전망일 때 쓰는 전략인지.
 */
enum class StrategyKind(val label: String, val plainName: String, val view: MarketView) {
    LONG_STRADDLE("롱 스트래들", "같은 가격 콜+풋 사기", MarketView.BIG_MOVE),
    LONG_STRANGLE("롱 스트랭글", "떨어진 가격 콜+풋 사기 (더 쌈)", MarketView.BIG_MOVE),
    SHORT_PUT("숏 풋", "풋 팔기", MarketView.NOT_DOWN),
    BULL_CALL_SPREAD("불 콜 스프레드", "콜 사고 더 높은 콜 팔기", MarketView.UP),
    BEAR_PUT_SPREAD("베어 풋 스프레드", "풋 사고 더 낮은 풋 팔기", MarketView.DOWN),
    BULL_PUT_CREDIT_SPREAD("불 풋 크레딧 스프레드", "풋 팔고 더 낮은 풋 사기", MarketView.NOT_DOWN),
    BEAR_CALL_CREDIT_SPREAD("베어 콜 크레딧 스프레드", "콜 팔고 더 높은 콜 사기", MarketView.NOT_UP),
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
