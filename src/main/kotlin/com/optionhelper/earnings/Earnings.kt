package com.optionhelper.earnings

import com.optionhelper.marketdata.DailyBar
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class EarningsTiming {
    // 장 시작 전 발표: 그날 장에서 반응
    BEFORE_OPEN,

    // 장 마감 후 발표: 다음 거래일 장에서 반응
    AFTER_CLOSE,
}

data class EarningsEvent(val symbol: String, val date: LocalDate, val timing: EarningsTiming) {
    /** 만기(16:00 ET)까지 이 실적의 주가 반응이 들어오는가. */
    fun reactsBy(expiry: LocalDate): Boolean =
        when (timing) {
            EarningsTiming.BEFORE_OPEN -> !date.isAfter(expiry)
            EarningsTiming.AFTER_CLOSE -> date.isBefore(expiry)
        }
}

/** 실적 반응 = 발표 직전 종가 → 반응일 종가 변동. */
data class EarningsReaction(val event: EarningsEvent, val movePct: Double)

data class EarningsMoveStats(
    val count: Int,
    // 절댓값 평균·중앙값·최대(0.08 = 8%)
    val averageAbsMove: Double,
    val medianAbsMove: Double,
    val maxAbsMove: Double,
    val reactions: List<EarningsReaction>,
)

object Earnings {
    fun nextEvent(
        events: List<EarningsEvent>,
        today: LocalDate,
    ): EarningsEvent? = events.filter { !it.date.isBefore(today) }.minByOrNull { it.date }

    fun daysUntil(
        event: EarningsEvent,
        today: LocalDate,
    ): Long = ChronoUnit.DAYS.between(today, event.date)

    /** 과거 실적들의 반응. 일봉에 앞뒤 날짜가 없으면 그 실적은 뺀다. */
    fun reactions(
        events: List<EarningsEvent>,
        bars: List<DailyBar>,
    ): List<EarningsReaction> {
        val sorted = bars.sortedBy { it.date }
        return events.sortedBy { it.date }.mapNotNull { event ->
            val dayIndex = sorted.indexOfFirst { it.date == event.date }
            if (dayIndex < 0) return@mapNotNull null
            val (beforeIndex, reactionIndex) =
                when (event.timing) {
                    EarningsTiming.BEFORE_OPEN -> dayIndex - 1 to dayIndex
                    EarningsTiming.AFTER_CLOSE -> dayIndex to dayIndex + 1
                }
            val before = sorted.getOrNull(beforeIndex) ?: return@mapNotNull null
            val reaction = sorted.getOrNull(reactionIndex) ?: return@mapNotNull null
            EarningsReaction(event, reaction.close / before.close - 1)
        }
    }

    /** 최근 lastN번 실적 반응 통계. 반응이 하나도 없으면 null. */
    fun moveStats(
        reactions: List<EarningsReaction>,
        lastN: Int = 12,
    ): EarningsMoveStats? {
        val recent = reactions.sortedBy { it.event.date }.takeLast(lastN)
        if (recent.isEmpty()) return null
        val abs = recent.map { abs(it.movePct) }.sorted()
        val mid = abs.size / 2
        return EarningsMoveStats(
            count = recent.size,
            averageAbsMove = abs.average(),
            medianAbsMove = if (abs.size % 2 == 1) abs[mid] else (abs[mid - 1] + abs[mid]) / 2,
            maxAbsMove = abs.last(),
            reactions = recent,
        )
    }
}
