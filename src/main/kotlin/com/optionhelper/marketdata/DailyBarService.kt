package com.optionhelper.marketdata

import com.optionhelper.pricing.MarketClock
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 계절성·실적 반응·HV 계산용 일봉. 하루에 한 번만 소스에서 받고 메모리에 둔다(다시 받을 수 있는 데이터라 DB에 저장하지 않는다).
 */
@Service
class DailyBarService(
    private val source: MarketDataSource,
    private val clock: Clock,
) {
    private data class Cached(val day: LocalDate, val bars: List<DailyBar>)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun bars(symbol: String): List<DailyBar> {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        cache[symbol]?.takeIf { it.day == today }?.let { return it.bars }
        val bars = source.dailyBars(symbol, today.minusYears(HISTORY_YEARS))
        cache[symbol] = Cached(today, bars)
        return bars
    }

    companion object {
        const val HISTORY_YEARS = 12L
    }
}
