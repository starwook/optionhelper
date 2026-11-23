package com.optionhelper.scenario

import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.ImpliedVolatilitySolver
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** 개장 예상가 표의 한 행: 개장 때 주가가 move(소수, −0.03 = −3%)만큼 다르면. prices는 levels 순서. */
data class OpenEstimateRow(val move: Double, val spot: Double, val intrinsic: Double, val prices: List<Double>)

data class OpenEstimate(
    // 전일 마감 옵션 가격으로 역산한 IV
    val impliedVol: Double,
    // 계산 기준 시각: 다음 개장(장중이면 지금)
    val basisAt: Instant,
    val previousCloseAt: Instant,
    // 프리마켓 주가의 전일 종가 대비 변화(소수)
    val gap: Double,
    // 프리마켓 주가 기준
    val delta: Double,
    val gamma: Double,
    val levels: List<IvLevel>,
    val rows: List<OpenEstimateRow>,
)

/**
 * 개장 예상가: 옵션은 프리마켓에 거래되지 않으므로, 전일 마감 옵션 가격으로 IV를 역산하고
 * 프리마켓 주가·개장 시점 남은 시간으로 개장 때 옵션 적정가를 추정한다(주가 변화 × IV 변화 표).
 */
object OpenEstimates {
    private const val MIN_IV = 0.01

    /** 전일 가격으로 IV를 풀 수 없거나 개장 시점에 이미 만기가 지났으면 null. */
    fun build(
        type: OptionType,
        strike: Double,
        expiry: LocalDate,
        previousPrice: Double,
        previousClose: Double,
        premarket: Double,
        now: Instant,
        rate: Double,
        moves: List<Double>,
        shifts: List<IvShift>,
    ): OpenEstimate? {
        val basisAt = basisAt(now)
        val previousCloseAt = previousCloseBefore(basisAt)
        val tPrev = MarketClock.yearsUntil(previousCloseAt, expiry)
        val tOpen = MarketClock.yearsUntil(basisAt, expiry)
        if (tPrev <= 0.0 || tOpen <= 0.0) return null
        val iv = ImpliedVolatilitySolver.solve(type, previousPrice, previousClose, strike, tPrev, rate) ?: return null

        val levels = listOf(IvLevel("전일 IV 유지", iv)) + shifts.map { IvLevel(it.label, maxOf(MIN_IV, iv + it.shift)) }
        val rows =
            moves.map { move ->
                val spot = premarket * (1 + move)
                OpenEstimateRow(
                    move,
                    spot,
                    BlackScholes.intrinsic(type, spot, strike),
                    levels.map { BlackScholes.price(type, spot, strike, tOpen, rate, it.iv) },
                )
            }
        val greeks = BlackScholes.greeks(type, premarket, strike, tOpen, rate, iv)
        return OpenEstimate(iv, basisAt, previousCloseAt, premarket / previousClose - 1, greeks.delta, greeks.gamma, levels, rows)
    }

    /** 장중이면 지금, 아니면 다음 정규장 개장 시각. */
    fun basisAt(now: Instant): Instant {
        if (MarketClock.isOpen(now)) return now
        val et = now.atZone(MarketClock.NEW_YORK)
        val today = et.toLocalDate()
        val date =
            if (MarketClock.isTradingDay(today) && et.toLocalTime().isBefore(MarketClock.OPEN)) {
                today
            } else {
                generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { MarketClock.isTradingDay(it) }
            }
        return ZonedDateTime.of(date, MarketClock.OPEN, MarketClock.NEW_YORK).toInstant()
    }

    /** basis 날짜 이전 마지막 거래일의 장 마감 시각. */
    private fun previousCloseBefore(basis: Instant): Instant {
        val basisDate = basis.atZone(MarketClock.NEW_YORK).toLocalDate()
        val date = generateSequence(basisDate.minusDays(1)) { it.minusDays(1) }.first { MarketClock.isTradingDay(it) }
        return MarketClock.expiryAt(date).toInstant()
    }
}
