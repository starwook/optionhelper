package com.optionhelper.paper

import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.PricingProperties
import com.optionhelper.watchlist.WatchedSymbol
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class LegRequest(
    val symbol: String,
    val type: OptionType,
    val strike: Double,
    val expiry: LocalDate,
)

/** 과거 평가를 받을 다리와 기간(그 다리를 들고 있던 날들). */
data class HistoryLegRequest(
    val symbol: String,
    val type: OptionType,
    val strike: Double,
    val expiry: LocalDate,
    val from: LocalDate,
    val to: LocalDate,
)

data class MarksRequest(val legs: List<LegRequest>)

// spots: 요청에 나온 종목별 지금 가격(청산 때 "진입 뒤 주가가 얼마나 움직였나"를 남기는 데 쓴다)
data class MarksResponse(val asOf: Instant, val marketOpen: Boolean, val marks: List<LegMark>, val spots: Map<String, Double>)

data class DailyMarksRequest(val from: LocalDate, val legs: List<HistoryLegRequest>)

data class TradingDay(val date: LocalDate, val closeAt: Instant)

data class DatedMark(val date: LocalDate, val mid: Double)

/** days: from부터 마지막으로 끝난 거래일까지. legs[i]는 요청 다리 i의 날짜별 장 마감 값. */
data class DailyMarksResponse(val days: List<TradingDay>, val legs: List<List<DatedMark>>)

/**
 * 실시간 모의투자의 가격 계산. 계좌(현금·포지션·거래 기록)는 방문자 브라우저에 있고, 서버는 다리 값만 계산한다.
 */
@Service
class PaperService(
    private val source: MarketDataSource,
    private val dailyBars: DailyBarService,
    private val pricing: PricingProperties,
    private val clock: Clock,
) {
    /** 지금 값. 만기(그날 장 마감)가 지났으면 만기일 장 마감 가격으로 정산한 값. */
    fun marks(request: MarksRequest): MarksResponse {
        val now = clock.instant()
        val marks =
            request.legs.map { leg ->
                WatchedSymbol.of(leg.symbol)
                if (!now.isBefore(MarketClock.expiryAt(leg.expiry).toInstant())) {
                    PaperPricing.settledMark(leg.type, leg.strike, settlementPrice(leg.symbol, leg.expiry))
                        .copy(settledAt = MarketClock.expiryAt(leg.expiry).toInstant())
                } else {
                    val q =
                        source.quote(leg.symbol, leg.type, leg.strike, leg.expiry)
                            ?: throw IllegalArgumentException("${leg.symbol} ${leg.strike} 옵션 값을 계산하지 못했습니다")
                    LegMark(q.bid, q.ask, q.mid, false, null)
                }
            }
        val spots = request.legs.map { it.symbol }.distinct().associateWith { source.stockQuote(it).price }
        return MarksResponse(now, MarketClock.isOpen(now), marks, spots)
    }

    /** 일별 수익률용: 지나간 거래일마다 장 마감 기준 다리 값. 과거 값은 일봉 종가로 계산한다. */
    fun dailyMarks(request: DailyMarksRequest): DailyMarksResponse {
        val now = clock.instant()
        val days =
            generateSequence(request.from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(lastFinishedSession(now)) }
                .filter { MarketClock.isTradingDay(it) }
                .map { TradingDay(it, MarketClock.expiryAt(it).toInstant()) }
                .toList()
        val legs =
            request.legs.map { leg ->
                WatchedSymbol.of(leg.symbol)
                val bars = dailyBars.bars(leg.symbol)
                days.map { it.date }
                    .filter { !it.isBefore(leg.from) && !it.isAfter(leg.to) && !it.isAfter(leg.expiry) }
                    .mapNotNull { date ->
                        val index = bars.indexOfLast { !it.date.isAfter(date) }
                        if (index < 0) return@mapNotNull null
                        DatedMark(date, PaperPricing.markAt(bars, index, leg.type, leg.strike, leg.expiry, pricing.riskFreeRate).mid)
                    }
            }
        return DailyMarksResponse(days, legs)
    }

    /** 만기일 정규장 마지막 5분봉 종가. 오래돼 분봉이 없으면 그날 일봉 종가. */
    private fun settlementPrice(
        symbol: String,
        expiry: LocalDate,
    ): Double {
        val close = MarketClock.expiryAt(expiry).toInstant()
        runCatching { source.intradayBars(symbol, expiry) }.getOrNull()
            ?.lastOrNull { it.time.isBefore(close) }
            ?.let { return it.close }
        return dailyBars.bars(symbol).lastOrNull { !it.date.isAfter(expiry) }?.close
            ?: throw IllegalArgumentException("$symbol $expiry 정산 가격이 없습니다")
    }

    /** 장 마감이 지난 마지막 거래일. 일봉이 어제까지라 오늘은 장이 끝났어도 빼고 지금 값으로 따로 본다. */
    private fun lastFinishedSession(now: Instant): LocalDate =
        MarketClock.previousTradingDay(now.atZone(MarketClock.NEW_YORK).toLocalDate())
}
