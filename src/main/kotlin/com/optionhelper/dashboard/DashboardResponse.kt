package com.optionhelper.dashboard

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.earnings.EarningsMoveStats
import com.optionhelper.option.IvPoint
import com.optionhelper.seasonality.MonthStats
import com.optionhelper.signal.StrategySignal
import com.optionhelper.strategy.Breakeven
import com.optionhelper.strategy.PayoffPoint
import com.optionhelper.volatility.ExpectedMove
import com.optionhelper.volatility.RealizedVsExpected
import java.time.Instant
import java.time.LocalDate

/** 화면 한 장에 필요한 모든 값. 금액은 주당(계약 1개 = × 100), 비율·%는 소수. */
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
    val open: Double?,
    val previousClose: Double?,
    val atm: AtmView?,
    val straddle: Double?,
    val expectedMove: ExpectedMove?,
    val realizedVsExpected: RealizedVsExpected?,
    val volatility: VolatilityView,
    val chain: List<ChainRow>,
    val strategies: List<StrategyView>,
    val earnings: EarningsView,
    val seasonality: List<MonthStats>,
    val currentMonth: Int,
    val signals: List<StrategySignal>,
    val ivHistory: List<IvPoint>,
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
    val ivSamples: Int,
    val nextExpiry: LocalDate?,
    val nextExpiryIv: Double?,
    val termStructureRatio: Double?,
    // 같은 거리(2칸) OTM 풋 IV − 콜 IV
    val skew: Double?,
)

data class StrategyView(
    val kind: String,
    val label: String,
    val legs: String,
    val netPremium: Double,
    val feePerShare: Double,
    val breakevens: List<Breakeven>,
    val breakevensWithFee: List<Breakeven>,
    val maxProfit: Double?,
    val maxLoss: Double?,
    val payoff: List<PayoffPoint>,
)

data class EarningsView(
    val next: EarningsEvent?,
    val daysUntil: Long?,
    // 선택한 만기 안에 실적 반응이 들어오는가
    val beforeExpiry: Boolean,
    val stats: EarningsMoveStats?,
)
