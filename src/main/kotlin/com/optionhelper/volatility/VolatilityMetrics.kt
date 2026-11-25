package com.optionhelper.volatility

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** 스트래들 가격이 말해 주는 예상 변동폭. pct는 소수(0.035 = 3.5%). */
data class ExpectedMove(
    val dollars: Double,
    val pct: Double,
    val rule85Dollars: Double,
    val rule85Pct: Double,
) {
    companion object {
        /** ATM 스트래들 × 0.85 ≈ 1표준편차 변동(블랙-숄즈에서 √(2/π) ≈ 0.8). */
        const val RULE_85 = 0.85

        fun of(
            straddlePrice: Double,
            spot: Double,
        ) = ExpectedMove(
            dollars = straddlePrice,
            pct = straddlePrice / spot,
            rule85Dollars = straddlePrice * RULE_85,
            rule85Pct = straddlePrice * RULE_85 / spot,
        )
    }
}

/** 전일 종가 대비 실제로 움직인 것과 하루 예상 변동폭([VolatilityMetrics.dailyMove]) 비교. ratio가 1이면 하루 예상만큼 이미 움직임. */
data class RealizedVsExpected(
    val realizedPct: Double,
    val expectedPct: Double,
    val ratio: Double,
) {
    companion object {
        fun of(
            previousClose: Double,
            current: Double,
            expectedPct: Double,
        ): RealizedVsExpected {
            val realized = current / previousClose - 1
            return RealizedVsExpected(realized, expectedPct, abs(realized) / expectedPct)
        }
    }
}

/** 한 기간(보통 1년)의 IV 범위. 지금 IV가 그 안에서 어디쯤인지(IV Rank) 판단하는 규칙을 갖는다. */
data class IvRange(
    val min: Double,
    val max: Double,
    val median: Double,
    val samples: Int,
) {
    /** (지금 − 최저) ÷ (최고 − 최저), 0~1. 1년 내내 같은 값이었으면 null. */
    fun rank(current: Double): Double? = if (max > min) ((current - min) / (max - min)).coerceIn(0.0, 1.0) else null

    companion object {
        fun of(values: List<Double>): IvRange? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
            return IvRange(sorted.first(), sorted.last(), median, sorted.size)
        }
    }
}

/**
 * 시장 변동성(연율 %)이 높은 편인지. 공식 기준은 없고 VIX에서 흔히 쓰는 관례 구간을 쓴다
 * (15 아래 평온, 15~20 보통, 20~30 불안, 30 이상 공포). 실현변동성은 보통 VIX보다 몇 포인트 낮게 나온다.
 */
enum class VolatilityLevel(val label: String) {
    CALM("낮음"),
    NORMAL("보통"),
    ELEVATED("높음"),
    HIGH("매우 높음"),
    ;

    companion object {
        fun of(percent: Double): VolatilityLevel =
            when {
                percent < 15 -> CALM
                percent < 20 -> NORMAL
                percent < 30 -> ELEVATED
                else -> HIGH
            }

        /** 차트에 그을 구간 경계(%). */
        val BOUNDARIES = listOf(15.0, 20.0, 30.0)
    }
}

object VolatilityMetrics {
    const val HV_WINDOW = 20
    private const val TRADING_DAYS_PER_YEAR = 252.0

    /** 연 변동성(IV·HV)을 하루(1거래일) 1표준편차 변동으로: σ ÷ √252. 예: IV 50% → 하루 ±3.15%. 만기와 상관없는 "오늘 예상" 기준. */
    fun dailyMove(annualVol: Double): Double = annualVol / sqrt(TRADING_DAYS_PER_YEAR)

    /** 종가 목록(오래된 순)의 최근 window일 로그수익률로 연환산 실현변동성. 데이터가 모자라면 null. */
    fun historicalVolatility(
        closes: List<Double>,
        window: Int = HV_WINDOW,
    ): Double? {
        if (closes.size < window + 1) return null
        val returns = closes.takeLast(window + 1).zipWithNext { a, b -> ln(b / a) }
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return sqrt(variance * TRADING_DAYS_PER_YEAR)
    }

    /** 날마다 그날까지 window일 실현변동성(연율). 앞쪽 window일은 데이터가 모자라 빠진다. */
    fun <T> historicalVolatilitySeries(
        items: List<T>,
        close: (T) -> Double,
        window: Int = HV_WINDOW,
    ): List<Pair<T, Double>> {
        val closes = items.map(close)
        return (window until items.size).mapNotNull { i ->
            historicalVolatility(closes.subList(0, i + 1), window)?.let { items[i] to it }
        }
    }

    /** 1보다 크면 옵션이 최근 실제 변동보다 비싸게 거래되는 중. */
    fun ivHvRatio(
        iv: Double,
        hv: Double,
    ): Double = iv / hv

    /** 과거 IV 기록 중 현재보다 낮았던 비율(0~1). 기록이 없으면 null. */
    fun ivPercentile(
        history: List<Double>,
        current: Double,
    ): Double? {
        if (history.isEmpty()) return null
        return history.count { it < current }.toDouble() / history.size
    }
}
