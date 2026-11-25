package com.optionhelper.dashboard

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.earnings.EarningsMoveStats
import com.optionhelper.marketdata.SessionClose
import com.optionhelper.pricing.MarketSession
import com.optionhelper.pricing.OptionType
import com.optionhelper.signal.StrategySignal
import com.optionhelper.strategy.Breakeven
import com.optionhelper.strategy.OptionLeg
import com.optionhelper.strategy.PayoffPoint
import com.optionhelper.strategy.Strategy
import com.optionhelper.strategy.StrategyCalculator
import com.optionhelper.volatility.ExpectedMove
import com.optionhelper.volatility.RealizedVsExpected
import java.time.Instant
import java.time.LocalDate

/** 실시간 모의투자 화면이 쓰는 종목·만기 하나의 값. 금액은 주당(계약 1개 = × 100), 비율·%는 소수. */
data class DashboardResponse(
    val symbol: String,
    val feed: FeedView,
    val earningsSource: String,
    val asOf: Instant,
    val asOfEt: String,
    val asOfKst: String,
    val marketOpen: Boolean,
    // 소스 오류로 마지막 값을 다시 보여 주는 중이면 true
    val stale: Boolean,
    val error: String?,
    val expirations: List<LocalDate>,
    val expiry: LocalDate,
    val expiryEt: String,
    val expiryKst: String,
    val hoursToExpiry: Double,
    val spot: Double,
    // 현재가 체결 시각(장 밖이면 프리장·장후 체결)
    val spotAsOfKst: String,
    val previousClose: Double?,
    // 지금 거래 시간대와 정규장이 끝난 가장 최근 거래일 마감가(맨 위 현재가 줄, 실시간 값이 오기 전)
    val session: MarketSession,
    val lastClose: SessionClose?,
    val atm: AtmView?,
    val straddle: Double?,
    val expectedMove: ExpectedMove?,
    val realizedVsExpected: RealizedVsExpected?,
    val volatility: VolatilityView,
    val chain: List<ChainRow>,
    val earnings: EarningsView,
    val macro: MacroView,
    val signals: List<StrategySignal>,
    val warnings: List<String>,
)

data class FeedView(val name: String, val kind: String, val label: String, val note: String)

data class LegView(
    val bid: Double,
    val ask: Double,
    val mid: Double,
    // 데이터 소스가 준 IV
    val ivApi: Double?,
    // 중간가로 직접 역산한 IV
    val ivCalc: Double?,
    val delta: Double?,
)

data class AtmView(val strike: Double, val call: LegView, val put: LegView)

data class ChainRow(val strike: Double, val call: LegView?, val put: LegView?, val atm: Boolean)

data class VolatilityView(
    val atmIvApi: Double?,
    val atmIvCalc: Double?,
    val hv20: Double?,
    val ivHvRatio: Double?,
    val ivPercentile: Double?,
)

/** 전략 하나의 화면용 값(빨리감기 연습·복기 화면이 쓴다). */
data class StrategyView(
    val kind: String,
    val label: String,
    val plainName: String,
    // 어떤 전망일 때 쓰는 전략인지(BIG_MOVE, UP, DOWN, NOT_DOWN, NOT_UP)와 그 화면 문구
    val view: String,
    val viewLabel: String,
    val legs: String,
    val netPremium: Double,
    val feePerShare: Double,
    val breakevens: List<Breakeven>,
    val breakevensWithFee: List<Breakeven>,
    val maxProfit: Double?,
    val maxLoss: Double?,
    val payoff: List<PayoffPoint>,
) {
    companion object {
        /** 전략 계산 결과를 화면용으로. 손익 곡선은 현재가 ± width(소수) 범위. */
        fun of(
            strategy: Strategy,
            spot: Double,
            width: Double,
        ): StrategyView {
            val result = StrategyCalculator.evaluate(strategy, spot)
            return StrategyView(
                kind = strategy.kind.name,
                label = strategy.kind.label,
                plainName = strategy.kind.plainName,
                view = strategy.kind.view.name,
                viewLabel = strategy.kind.view.label,
                legs = strategy.legs.joinToString(" + ") { "${if (it.quantity > 0) "매수" else "매도"} ${legText(it)}" },
                netPremium = result.netPremium,
                feePerShare = result.feePerShare,
                breakevens = result.breakevens,
                breakevensWithFee = result.breakevensWithFee,
                maxProfit = result.maxProfit,
                maxLoss = result.maxLoss,
                payoff = StrategyCalculator.payoffCurve(strategy, spot * (1 - width), spot * (1 + width)),
            )
        }

        private fun legText(leg: OptionLeg) = "${num(leg.strike)}${if (leg.type == OptionType.CALL) "C" else "P"} @${num(leg.premium)}"

        private fun num(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}

data class EarningsView(
    val next: EarningsEvent?,
    val daysUntil: Long?,
    // 선택한 만기 안에 실적 반응이 들어오는가
    val beforeExpiry: Boolean,
    val stats: EarningsMoveStats?,
)

data class MacroView(
    // 오늘 이후 가까운 순
    val upcoming: List<MacroEventView>,
    // 일정 파일이 채워진 마지막 날짜
    val coversUntil: LocalDate?,
)

data class MacroEventView(
    val type: String,
    val label: String,
    val date: LocalDate,
    val timeEt: String,
    val timeKst: String,
    val daysUntil: Long,
    // 선택한 만기 안에 발표되는가
    val beforeExpiry: Boolean,
)
