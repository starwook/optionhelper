package com.optionhelper.pricing

import com.optionhelper.option.OptionChain
import com.optionhelper.option.OptionQuote
import com.optionhelper.volatility.VolatilityMetrics
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 실제 호가 없이 기초자산 가격만으로 만드는 근사 옵션 체인(이론가). 공개 사이트에서 쓴다.
 * - ATM IV = 최근 20거래일 HV × [HV_TO_IV] (범위 [MIN_IV, MAX_IV]). 일봉이 모자라면 [FALLBACK_IV].
 * - 행사가별 IV = ATM IV × (1 − [SKEW]·z + [SMILE]·z²), z = ln(K/S) ÷ (ATM IV·√t). 아래 행사가일수록 IV가 높은 보통의 스큐를 흉내 낸다.
 * - 가격·델타 = 블랙-숄즈(배당 0, 내재가치 하한). 미국식 조기행사 프리미엄은 근사 오차보다 작아 넣지 않는다.
 * - 가상 호가 = 이론가 ± 간격/2, 간격 = max([MIN_SPREAD], 이론가 × [SPREAD_RATIO]).
 * 계수는 모두 임시값이다(기획서 미결 사항).
 */
object TheoreticalChain {
    const val HV_TO_IV = 1.0
    const val MIN_IV = 0.15
    const val MAX_IV = 1.5
    const val FALLBACK_IV = 0.5
    const val SKEW = 0.04
    const val SMILE = 0.01
    private const val Z_LIMIT = 4.0
    const val SPREAD_RATIO = 0.05
    const val MIN_SPREAD = 0.05

    /** 종가(오래된 순)로 ATM IV를 정한다. 일봉이 모자라면 [FALLBACK_IV]. */
    fun atmIv(closes: List<Double>): Double = atmIvOrNull(closes) ?: FALLBACK_IV

    /** 일봉이 모자라면 null(과거 IV 기록을 만들 때는 그날을 건너뛴다). */
    fun atmIvOrNull(closes: List<Double>): Double? =
        VolatilityMetrics.historicalVolatility(closes)?.let { (it * HV_TO_IV).coerceIn(MIN_IV, MAX_IV) }

    fun strikeIv(
        atmIv: Double,
        spot: Double,
        strike: Double,
        years: Double,
    ): Double {
        val z = (ln(strike / spot) / (atmIv * sqrt(years))).coerceIn(-Z_LIMIT, Z_LIMIT)
        return atmIv * (1 - SKEW * z + SMILE * z * z)
    }

    fun chain(
        symbol: String,
        expiry: LocalDate,
        strikes: List<Double>,
        spot: Double,
        years: Double,
        rate: Double,
        atmIv: Double,
    ): OptionChain {
        val quotes = strikes.flatMap { k -> OptionType.entries.map { type -> quote(type, k, spot, years, rate, atmIv) } }
        return OptionChain(symbol, expiry, quotes)
    }

    /** 행사가 하나의 이론가 호가. 체인에 없는 행사가(예: 모의투자 보유 옵션)도 같은 식으로 값을 낸다. */
    fun quote(
        type: OptionType,
        strike: Double,
        spot: Double,
        years: Double,
        rate: Double,
        atmIv: Double,
    ): OptionQuote {
        // 만기 시각이면 내재가치 그대로(호가 간격 없이)
        if (years <= 0.0) {
            val value = round2(BlackScholes.intrinsic(type, spot, strike))
            val itm = value > 0
            val delta =
                if (!itm) {
                    0.0
                } else if (type == OptionType.CALL) {
                    1.0
                } else {
                    -1.0
                }
            return OptionQuote(type, strike, value, value, null, delta)
        }
        val iv = strikeIv(atmIv, spot, strike, years)
        val theo = maxOf(BlackScholes.price(type, spot, strike, years, rate, iv), BlackScholes.intrinsic(type, spot, strike))
        val half = maxOf(MIN_SPREAD, theo * SPREAD_RATIO) / 2
        return OptionQuote(
            type = type,
            strike = strike,
            bid = round2(maxOf(0.0, theo - half)),
            ask = round2(theo + half),
            impliedVolatility = iv,
            delta = BlackScholes.greeks(type, spot, strike, years, rate, iv).delta,
        )
    }

    private fun round2(value: Double) = round(value * 100) / 100
}
