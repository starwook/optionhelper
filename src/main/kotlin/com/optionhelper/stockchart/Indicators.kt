package com.optionhelper.stockchart

import kotlin.math.sqrt

/**
 * 봉 차트 보조지표. 리스트는 봉과 같은 길이·순서이고, 앞쪽에 기간이 모자라 값을 못 내는 자리는 null.
 * ma5·ma20·ma60·ma120 = 종가 단순 이동평균, bbUpper·bbLower = 볼린저밴드(20, 2σ), rsi = RSI(14, Wilder),
 * stochK·stochD = 느린 스토캐스틱(14, 3, 3), macd·macdSignal·macdHist = MACD(12, 26, 9).
 */
data class IndicatorSet(
    val ma5: List<Double?>,
    val ma20: List<Double?>,
    val ma60: List<Double?>,
    val ma120: List<Double?>,
    val bbUpper: List<Double?>,
    val bbLower: List<Double?>,
    val rsi: List<Double?>,
    val stochK: List<Double?>,
    val stochD: List<Double?>,
    val macd: List<Double?>,
    val macdSignal: List<Double?>,
    val macdHist: List<Double?>,
)

/** 보조지표 계산. Spring 없이 테스트할 수 있게 순수 함수로 둔다. */
object Indicators {
    fun of(candles: List<Candle>): IndicatorSet {
        val closes = candles.map { it.close }
        val (upper, lower) = bollinger(closes, 20, 2.0)
        val (k, d) = stochastic(candles, 14, 3, 3)
        val (macd, signal, hist) = macd(closes, 12, 26, 9)
        return IndicatorSet(
            sma(
                closes,
                5,
            ),
            sma(closes, 20), sma(closes, 60), sma(closes, 120), upper, lower, rsi(closes, 14), k, d, macd, signal, hist,
        )
    }

    /** 단순 이동평균. 앞쪽 null은 건너뛰고, null이 아닌 값이 period개 모인 자리부터 낸다. */
    fun sma(
        values: List<Double?>,
        period: Int,
    ): List<Double?> =
        values.indices.map { i ->
            val window = values.subList(maxOf(0, i - period + 1), i + 1)
            if (window.size < period || window.any { it == null }) null else window.sumOf { it!! } / period
        }

    /** 지수 이동평균. 첫 값은 처음 period개의 단순 평균으로 시작한다. */
    fun ema(
        values: List<Double?>,
        period: Int,
    ): List<Double?> {
        val k = 2.0 / (period + 1)
        val seed = sma(values, period)
        var prev: Double? = null
        return values.indices.map { i ->
            val v = values[i]
            prev =
                when {
                    v == null -> null
                    prev == null -> seed[i]
                    else -> v * k + prev!! * (1 - k)
                }
            prev
        }
    }

    /** (상단, 하단) = 이동평균 ± width × 표준편차(모집단). */
    fun bollinger(
        closes: List<Double>,
        period: Int,
        width: Double,
    ): Pair<List<Double?>, List<Double?>> {
        val mid = sma(closes, period)
        val sd =
            closes.indices.map { i ->
                mid[i]?.let { m ->
                    sqrt(closes.subList(i - period + 1, i + 1).sumOf { (it - m) * (it - m) } / period)
                }
            }
        return mid.zip(sd) { m, s -> if (m == null || s == null) null else m + width * s } to
            mid.zip(sd) { m, s -> if (m == null || s == null) null else m - width * s }
    }

    /** RSI(Wilder 평활). 손실이 0이면 100. */
    fun rsi(
        closes: List<Double>,
        period: Int,
    ): List<Double?> {
        val result = MutableList<Double?>(closes.size) { null }
        if (closes.size <= period) return result
        val changes = closes.zipWithNext { a, b -> b - a }
        var gain = changes.take(period).sumOf { maxOf(it, 0.0) } / period
        var loss = changes.take(period).sumOf { maxOf(-it, 0.0) } / period
        result[period] = rsiOf(gain, loss)
        for (i in period until changes.size) {
            gain = (gain * (period - 1) + maxOf(changes[i], 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-changes[i], 0.0)) / period
            result[i + 1] = rsiOf(gain, loss)
        }
        return result
    }

    private fun rsiOf(
        gain: Double,
        loss: Double,
    ) = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)

    /** 느린 스토캐스틱 (%K, %D). 빠른 %K = 최근 period개 고저 범위 안의 종가 위치(범위가 0이면 50). */
    fun stochastic(
        candles: List<Candle>,
        period: Int,
        smoothK: Int,
        smoothD: Int,
    ): Pair<List<Double?>, List<Double?>> {
        val fast =
            candles.indices.map { i ->
                if (i < period - 1) return@map null
                val window = candles.subList(i - period + 1, i + 1)
                val high = window.maxOf { it.high }
                val low = window.minOf { it.low }
                if (high == low) 50.0 else (candles[i].close - low) / (high - low) * 100
            }
        val k = sma(fast, smoothK)
        return k to sma(k, smoothD)
    }

    /** (MACD선, 시그널선, 히스토그램). MACD선 = 빠른 EMA − 느린 EMA, 시그널 = MACD선의 EMA. */
    fun macd(
        closes: List<Double>,
        fast: Int,
        slow: Int,
        signal: Int,
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val line = ema(closes, fast).zip(ema(closes, slow)) { f, s -> if (f == null || s == null) null else f - s }
        val sig = ema(line, signal)
        return Triple(line, sig, line.zip(sig) { l, s -> if (l == null || s == null) null else l - s })
    }
}
