package com.optionhelper.earnings

import com.optionhelper.pricing.MarketClock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** 키 없이 화면 확인용. 분기마다 한 번 장 마감 후 발표한다고 가정한 가짜 일정. */
@Component
@ConditionalOnProperty(prefix = "optionhelper.earnings", name = ["source"], havingValue = "fake", matchIfMissing = true)
class FakeEarningsSource(private val clock: Clock) : EarningsCalendarSource {
    override val name = "가짜 데이터"

    override fun events(symbol: String): List<EarningsEvent> {
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        val offset = abs(symbol.hashCode() % 60).toLong()
        return (-12..2).map { quarter ->
            val date = today.plusDays(offset).plusMonths(3L * quarter).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
            EarningsEvent(symbol, date, EarningsTiming.AFTER_CLOSE)
        }
    }
}
