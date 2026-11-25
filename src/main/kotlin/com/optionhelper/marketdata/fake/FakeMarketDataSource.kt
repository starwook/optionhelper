package com.optionhelper.marketdata.fake

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.marketdata.SessionClose
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.marketdata.UnderlyingPriceSource
import com.optionhelper.pricing.MarketClock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/** 네트워크 없이 화면·테스트를 돌리기 위한 가짜 기초자산 가격. 화면에 "가짜 데이터"로 표시된다. */
@Component
@ConditionalOnProperty(prefix = "optionhelper.market-data", name = ["source"], havingValue = "fake")
class FakeMarketDataSource(private val clock: Clock) : UnderlyingPriceSource {
    override val name = "가짜 데이터"
    override val note = "네트워크 없이 화면 확인용으로 만든 가격입니다. 매매 판단에 쓰지 마세요."

    override fun stockQuote(symbol: String): StockQuote {
        val base = basePrice(symbol)
        // 시간에 따라 천천히 출렁이게
        val minutes = clock.instant().epochSecond / 60.0
        val price = base * (1 + 0.01 * sin(minutes / 30.0))
        val lastClose = SessionClose(MarketClock.lastClosedSessionDate(clock.instant()), round2(base * 0.99), round2(base * 0.98))
        return StockQuote(symbol, round2(price), round2(base * 0.99), clock.instant(), lastClose)
    }

    override fun dailyBars(
        symbol: String,
        from: LocalDate,
    ): List<DailyBar> {
        val random = Random(symbol.hashCode())
        val yesterday = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate().minusDays(1)
        var close = 1.0
        val bars =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(yesterday) }
                .filter { MarketClock.isTradingDay(it) }
                .map { date ->
                    val open = close
                    close *= exp(0.0008 + 0.025 * random.nextDouble(-1.7, 1.7))
                    DailyBar(date, open, maxOf(open, close) * 1.01, minOf(open, close) * 0.99, close)
                }.toList()
        // 마지막 종가가 가짜 현재가 근처가 되게 맞춘다(복기 결과가 현재가와 동떨어지지 않게)
        val scale = basePrice(symbol) / (bars.lastOrNull()?.close ?: 1.0)
        return bars.map { DailyBar(it.date, it.open * scale, it.high * scale, it.low * scale, it.close * scale) }
    }

    /** 현재가와 같은 식으로 출렁이는 5분봉. 장 시작부터 지금(또는 마감)까지. */
    override fun intradayBars(
        symbol: String,
        date: LocalDate,
    ): List<IntradayBar> {
        val open = ZonedDateTime.of(date, MarketClock.OPEN, MarketClock.NEW_YORK).toInstant()
        val close = minOf(ZonedDateTime.of(date, MarketClock.closeOf(date), MarketClock.NEW_YORK).toInstant(), clock.instant())
        return minuteBars(symbol, 5, open, close)
    }

    /** 현재가와 같은 식으로 출렁이는 N분봉. 24시간 이어진다(실제 선물처럼). */
    override fun minuteBars(
        symbol: String,
        minutes: Int,
        from: Instant,
        to: Instant,
    ): List<IntradayBar> {
        val step = minutes * 60L
        val price = { t: Instant -> round2(basePrice(symbol) * (1 + 0.01 * sin(t.epochSecond / 60.0 / 30.0))) }
        val first = Instant.ofEpochSecond((from.epochSecond + step - 1) / step * step)
        return generateSequence(first) { it.plusSeconds(step) }
            .takeWhile { it.isBefore(to) }
            .map {
                val open = price(it)
                val close = price(it.plusSeconds(step))
                IntradayBar(it, open, maxOf(open, close), minOf(open, close), close, 1000.0 + abs(close - open) * 5000)
            }.toList()
    }

    private fun basePrice(symbol: String) = 50.0 + abs(symbol.hashCode() % 250)

    private fun round2(value: Double) = round(value * 100) / 100
}
