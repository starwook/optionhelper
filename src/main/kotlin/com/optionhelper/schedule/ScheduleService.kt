package com.optionhelper.schedule

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.earnings.EarningsService
import com.optionhelper.macro.MacroCalendar
import com.optionhelper.macro.MacroCalendarFile
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.pricing.MarketClock
import com.optionhelper.seasonality.Seasonality
import com.optionhelper.watchlist.SemiconductorWatchlist
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
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        val from = today.minusDays(PAST_DAYS)
        val warnings = mutableListOf<String>()

        val macro =
            macroCalendar.events.filter { !it.date.isBefore(from) }
                .sortedWith(compareBy({ it.date }, { it.type.releaseTimeEt }))
                .map {
                    val kst = it.releaseAt().withZoneSameInstant(MarketClock.SEOUL)
                    ScheduleMacroView(
                        type = it.type.name,
                        label = it.type.label,
                        date = it.date,
                        // 달력 칸은 미국 날짜라, 한국 시간으로 다음 날이면 표시한다(예: FOMC 익일 04:00)
                        timeKst = (if (kst.toLocalDate() != it.date) "익일 " else "") + kst.format(HOUR_MINUTE),
                    )
                }

        val earningsEvents =
            SemiconductorWatchlist.STOCKS.flatMap { symbol ->
                runCatching { earnings.events(symbol) }
                    .onFailure { log.warn("실적 일정 조회 실패({}): {}", symbol, it.message) }
                    .getOrElse {
                        warnings += "$symbol 실적 일정을 못 받았습니다."
                        emptyList()
                    }
                    .filter { !it.date.isBefore(from) }
            }.sortedWith(compareBy<EarningsEvent>({ it.date }, { it.symbol }))

        val nasdaqBars =
            runCatching { dailyBars.bars(NASDAQ_PROXY) }.getOrElse {
                log.warn("나스닥 일봉 조회 실패: {}", it.message)
                warnings += "나스닥(${NASDAQ_PROXY}) 일봉을 못 받았습니다."
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

    companion object {
        // 나스닥 지수 자체는 시세 소스에 없어 나스닥100 ETF로 대신한다
        const val NASDAQ_PROXY = "QQQ"
        const val NASDAQ_LABEL = "나스닥100(QQQ)"

        // 달력에서 이번 달 지난 날짜도 보이게 한 달 전부터
        private const val PAST_DAYS = 31L
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
