package com.optionhelper.paper

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.option.OptionQuote
import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.TheoreticalChain
import java.time.Instant
import java.time.LocalDate

/** 모의투자 다리 하나의 값. settled면 만기가 지나 내재가치로 확정된 값(bid = ask = mid). 금액은 주당. */
data class LegMark(
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val settled: Boolean,
    // settled일 때 정산에 쓴 기초자산 가격
    val settlementPrice: Double?,
    // 실시간 모의투자에서 settled일 때 정산 시각(만기일 장 마감)
    val settledAt: Instant? = null,
)

/**
 * 과거 어느 날 장 마감 시점의 이론가. 대시보드 체인과 같은 식([TheoreticalChain])에 그날까지의 종가로 정한 ATM IV를 넣는다.
 * scale은 빨리감기 연습에서 실제 가격을 가리려고 곱하는 배율이다(블랙-숄즈는 주가·행사가를 같이 늘리면 값도 같은 배율로 늘어서 IV는 그대로).
 */
object PaperPricing {
    /** bars[index] 날 장 마감 기준 다리 값. 그날이 만기 이후면 만기일 종가로 정산한다. */
    fun markAt(
        bars: List<DailyBar>,
        index: Int,
        type: OptionType,
        strike: Double,
        expiry: LocalDate,
        rate: Double,
        scale: Double = 1.0,
    ): LegMark {
        val day = bars[index].date
        if (!day.isBefore(expiry)) {
            val settle = bars.last { !it.date.isAfter(expiry) }.close * scale
            return settledMark(type, strike, settle)
        }
        val quote = quoteAt(bars, index, type, strike, expiry, rate, scale)
        return LegMark(quote.bid, quote.ask, quote.mid, false, null)
    }

    fun quoteAt(
        bars: List<DailyBar>,
        index: Int,
        type: OptionType,
        strike: Double,
        expiry: LocalDate,
        rate: Double,
        scale: Double = 1.0,
    ): OptionQuote {
        val years = MarketClock.yearsUntil(MarketClock.expiryAt(bars[index].date).toInstant(), expiry)
        return TheoreticalChain.quote(type, strike, bars[index].close * scale, years, rate, atmIvAt(bars, index))
    }

    /** bars[index]까지의 종가로 정한 ATM IV. */
    fun atmIvAt(
        bars: List<DailyBar>,
        index: Int,
    ): Double = TheoreticalChain.atmIv(bars.subList(maxOf(0, index - HV_BARS), index + 1).map { it.close })

    fun settledMark(
        type: OptionType,
        strike: Double,
        settlementPrice: Double,
    ): LegMark {
        val value = Math.round(BlackScholes.intrinsic(type, settlementPrice, strike) * 100) / 100.0
        return LegMark(value, value, value, true, settlementPrice)
    }

    // HV20에 필요한 21개 종가보다 넉넉하게
    private const val HV_BARS = 30
}
