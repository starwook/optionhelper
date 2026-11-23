package com.optionhelper.signal

/** 전략 구간. 교과서식 참고 지표이며 투자 조언이 아니다. */
enum class StrategyZone(val label: String) {
    PREMIUM_SELLING("프리미엄 매도형(숏 풋, 크레딧 스프레드)이 유리한 구간"),
    PREMIUM_BUYING("프리미엄 매수형(롱 스트래들, 롱 스트랭글)이 유리한 구간"),
    EARNINGS_LONG_VOL("실적 앞 롱 변동성 구간"),
    EARNINGS_OVERPRICED("실적 프리미엄이 과한 구간(매도형)"),
}

data class StrategySignal(val zone: StrategyZone, val reason: String)

/** 판단에 쓰는 지표. 없는 값은 null이고, 그 값을 쓰는 규칙은 건너뛴다. 비율·%는 소수. */
data class SignalInput(
    val ivHvRatio: Double?,
    val expectedMovePct: Double?,
    val earningsBeforeExpiry: Boolean,
    val pastEarningsAvgMovePct: Double?,
)

/** 기획서 4장 신호 규칙표. 기준값은 기획서와 같이 바꾼다. */
object StrategySignals {
    const val RICH_IV_HV = 1.3
    const val CHEAP_IV_HV = 0.9
    const val EARNINGS_OVERPRICED_FACTOR = 1.2

    fun evaluate(input: SignalInput): List<StrategySignal> {
        val signals = mutableListOf<StrategySignal>()
        input.ivHvRatio?.let { ratio ->
            if (ratio >= RICH_IV_HV) {
                signals += StrategySignal(StrategyZone.PREMIUM_SELLING, "IV/HV ${fmt(ratio)} ≥ $RICH_IV_HV: 옵션이 최근 실제 변동보다 비쌈")
            } else if (ratio <= CHEAP_IV_HV) {
                signals += StrategySignal(StrategyZone.PREMIUM_BUYING, "IV/HV ${fmt(ratio)} ≤ $CHEAP_IV_HV: 옵션이 최근 실제 변동보다 쌈")
            }
        }
        val expected = input.expectedMovePct
        val pastAvg = input.pastEarningsAvgMovePct
        if (input.earningsBeforeExpiry && expected != null && pastAvg != null) {
            if (expected < pastAvg) {
                signals +=
                    StrategySignal(
                        StrategyZone.EARNINGS_LONG_VOL,
                        "예상 변동 ${pct(expected)} < 과거 실적 평균 ${pct(pastAvg)}",
                    )
            } else if (expected > pastAvg * EARNINGS_OVERPRICED_FACTOR) {
                signals +=
                    StrategySignal(
                        StrategyZone.EARNINGS_OVERPRICED,
                        "예상 변동 ${pct(expected)} > 과거 실적 평균 ${pct(pastAvg)} × $EARNINGS_OVERPRICED_FACTOR",
                    )
            }
        }
        return signals
    }

    private fun fmt(value: Double) = "%.2f".format(value)

    private fun pct(value: Double) = "%.1f%%".format(value * 100)
}
