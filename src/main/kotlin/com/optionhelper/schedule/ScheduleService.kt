package com.optionhelper.schedule

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.earnings.EarningsService
import com.optionhelper.macro.MacroCalendar
import com.optionhelper.macro.MacroCalendarFile
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.pricing.MarketClock
import com.optionhelper.seasonality.Seasonality
import com.optionhelper.watchlist.WatchedSymbol
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.format.DateTimeFormatter

/** 경제지표·관심 종목 실적·나스닥 일봉을 모아 일정 화면을 만든다. 각 소스 실패는 경고로만 남기고 나머지는 보여 준다. */
@Service
class ScheduleService(
    private val macroCalendar: MacroCalendarFile,
    private val earnings: EarningsService,
    private val dailyBars: DailyBarService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun schedule(): ScheduleResponse {
        val today = clock.instant().atZone(MarketClock.SEOUL).toLocalDate()
        val from = today.minusDays(PAST_DAYS)
        val warnings = mutableListOf<String>()

        // 달력은 한국 시간 기준: 발표 시각을 한국 시간으로 바꾼 날짜 칸에 넣는다(예: FOMC 미국 12/9 14:00 → 12/10 04:00)
        val macro =
            macroCalendar.events
                .map { it to it.releaseAt().withZoneSameInstant(MarketClock.SEOUL) }
                .filter { (_, kst) -> !kst.toLocalDate().isBefore(from) }
                .sortedBy { (_, kst) -> kst }
                .map { (event, kst) -> ScheduleMacroView(event.type.name, event.type.label, kst.toLocalDate(), kst.format(HOUR_MINUTE)) }

        val earningsEvents =
            WatchedSymbol.STOCKS.flatMap { symbol ->
                runCatching { earnings.events(symbol) }
                    .onFailure { log.warn("실적 일정 조회 실패({}): {}", symbol, it.message) }
                    .getOrElse {
                        warnings += "$symbol 실적 일정을 못 받았습니다."
                        emptyList()
                    }
                    .map { earningsView(it) }
                    .filter { !it.date.isBefore(from) }
            }.sortedWith(compareBy({ it.date }, { it.symbol }))

        val nasdaqBars =
            runCatching { dailyBars.bars(NASDAQ_PROXY) }.getOrElse {
                log.warn("나스닥 일봉 조회 실패: {}", it.message)
                warnings += "$NASDAQ_LABEL 일봉을 못 받았습니다."
                emptyList()
            }

        return ScheduleResponse(
            today = today,
            macro = macro,
            macroCoversUntil = MacroCalendar.coversUntil(macroCalendar.events),
            earnings = earningsEvents,
            earningsSource = earnings.sourceName,
            nasdaq = NasdaqSeasonality(NASDAQ_PROXY, NASDAQ_LABEL, Seasonality.monthStats(nasdaqBars), today.monthValue),
            warnings = warnings,
        )
    }

    /** 장 마감 후 발표는 한국 시간으로 다음 날 아침이므로 그 날짜 칸에 넣는다. 시점 미상이면 미국 날짜 그대로. */
    private fun earningsView(event: EarningsEvent): ScheduleEarningsView {
        val kst = event.referenceTime()?.withZoneSameInstant(MarketClock.SEOUL)
        return ScheduleEarningsView(event.symbol, kst?.toLocalDate() ?: event.date, event.timing.name, kst?.format(HOUR_MINUTE))
    }

    companion object {
        // 나스닥 지수 자체는 시세 소스에 없어 Hyperliquid 나스닥100 추종 선물(xyz:XYZ100)로 대신한다(docs/데이터소스-조사.md 11-3)
        const val NASDAQ_PROXY = "XYZ100"
        const val NASDAQ_LABEL = "나스닥100"

        // 달력에서 이번 달 지난 날짜도 보이게 한 달 전부터
        private const val PAST_DAYS = 31L
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
