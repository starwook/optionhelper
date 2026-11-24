package com.optionhelper.marketdata.fake

import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.DailyIv
import com.optionhelper.marketdata.DataFeed
import com.optionhelper.marketdata.FeedKind
import com.optionhelper.marketdata.IntradayBar
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.option.OptionChain
import com.optionhelper.option.OptionQuote
import com.optionhelper.pricing.BlackScholes
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/**
 * 키 없이 화면을 확인하기 위한 가짜 시세. 블랙-숄즈로 그럴듯한 체인을 만든다. 화면에 "가짜 데이터"로 표시된다.
 */
@Component
@ConditionalOnProperty(prefix = "optionhelper.market-data", name = ["source"], havingValue = "fake", matchIfMissing = true)
class FakeMarketDataSource(private val clock: Clock) : MarketDataSource {
    override val feed = DataFeed("가짜 데이터", FeedKind.SIMULATED, "API 키 없이 화면 확인용으로 만든 값입니다. 매매 판단에 쓰지 마세요.")

    override fun stockQuote(symbol: String): StockQuote {
        val base = basePrice(symbol)
        // 시간에 따라 천천히 출렁이게
        val minutes = clock.instant().epochSecond / 60.0
        val price = base * (1 + 0.01 * sin(minutes / 30.0))
        return StockQuote(symbol, round2(price), round2(base * 0.99), clock.instant())
    }

    override fun expirations(
        symbol: String,
        spot: Double,
    ): List<LocalDate> {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        val days = (0L..60L).map { today.plusDays(it) }
        val weekdays = days.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        // 오늘(0DTE) + 매주 금요일
        return (weekdays.take(1) + weekdays.filter { it.dayOfWeek == DayOfWeek.FRIDAY }).distinct().sorted()
    }

    override fun chain(
        symbol: String,
        expiry: LocalDate,
        minStrike: Double,
        maxStrike: Double,
    ): OptionChain {
        val spot = stockQuote(symbol).price
        val step = strikeStep(spot)
        val t = maxOf(MarketClock.yearsUntil(clock.instant(), expiry), 1.0 / (365 * 24 * 4))
        val strikes = generateSequence(round(minStrike / step) * step) { it + step }.takeWhile { it <= maxStrike }
        val quotes =
            strikes.flatMap { k ->
                // 아래쪽 행사가일수록 IV가 높은 스큐
                val iv = 0.48 + 0.6 * maxOf(0.0, (spot - k) / spot) + 0.2 * abs(k - spot) / spot
                OptionType.entries.map { type ->
                    val mid = BlackScholes.price(type, spot, k, t, RATE, iv)
                    val half = maxOf(0.01, mid * 0.02)
                    OptionQuote(
                        type,
                        k,
                        round2(maxOf(0.0, mid - half)),
                        round2(mid + half),
                        iv,
                        BlackScholes.greeks(type, spot, k, t, RATE, iv).delta,
                    )
                }
            }.toList()
        return OptionChain(symbol, expiry, quotes)
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
        val base = basePrice(symbol)
        return generateSequence(open) { it.plusSeconds(300) }
            .takeWhile { it.isBefore(close) }
            .map { IntradayBar(it, round2(base * (1 + 0.01 * sin(it.epochSecond / 60.0 / 30.0)))) }
            .toList()
    }

    /** 날짜로 정해지는 출렁이는 IV(같은 날짜는 항상 같은 값이라 DB에 나눠 쌓여도 이어진다). */
    override fun dailyImpliedVolatility(
        symbol: String,
        from: LocalDate,
    ): List<DailyIv> {
        val yesterday = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate().minusDays(1)
        val phase = abs(symbol.hashCode() % 100).toDouble()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(yesterday) }
            .filter { MarketClock.isTradingDay(it) }
            .map { date ->
                val day = date.toEpochDay().toDouble()
                DailyIv(date, 0.5 + 0.12 * sin(day / 37.0 + phase) + 0.05 * sin(day / 9.0))
            }.toList()
    }

    private fun basePrice(symbol: String) = 50.0 + abs(symbol.hashCode() % 250)

    private fun strikeStep(spot: Double) = if (spot < 100) 1.0 else 2.5

    private fun round2(value: Double) = round(value * 100) / 100

    companion object {
        private const val RATE = 0.04
    }
}
