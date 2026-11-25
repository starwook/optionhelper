package com.optionhelper.schedule

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.earnings.EarningsService
import com.optionhelper.macro.FlashResult
import com.optionhelper.macro.MacroActualService
import com.optionhelper.macro.MacroActuals
import com.optionhelper.macro.MacroCalendar
import com.optionhelper.macro.MacroCalendarFile
import com.optionhelper.macro.MacroEvent
import com.optionhelper.macro.MacroFlashService
import com.optionhelper.macro.MacroObservation
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.pricing.MarketClock
import com.optionhelper.seasonality.Seasonality
import com.optionhelper.watchlist.WatchedSymbol
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 경제지표·관심 종목 실적·나스닥 일봉을 모아 일정 화면을 만든다. 각 소스 실패는 경고로만 남기고 나머지는 보여 준다. */
@Service
class ScheduleService(
    private val macroCalendar: MacroCalendarFile,
    private val macroActuals: MacroActualService,
    private val macroFlash: MacroFlashService,
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
        val observations = macroActuals.observations()
        val flashes = macroFlash.flashes()
        val now = clock.instant()
        val macro =
            macroCalendar.events
                .map { it to it.releaseAt().withZoneSameInstant(MarketClock.SEOUL) }
                .filter { (_, kst) -> !kst.toLocalDate().isBefore(from) }
                .sortedBy { (_, kst) -> kst }
                .map { (event, kst) -> macroView(event, kst.toLocalDate(), kst.format(HOUR_MINUTE), observations, flashes[event], now) }

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
            macroActualSource = macroActuals.sourceName,
            macroActualFetchedOn = macroActuals.lastFetchedAt()?.atZone(MarketClock.SEOUL)?.toLocalDate(),
            flashModel = if (macroFlash.active) macroFlash.model else null,
            // 발표 5분 전부터 속보를 기다리는 동안(발표 후 창 안)
            fastPoll =
                macroCalendar.events.any {
                    macroFlash.waiting(it, now) || macroFlash.waiting(it, now.plusSeconds(FAST_POLL_LEAD_SECONDS))
                },
            earnings = earningsEvents,
            earningsSource = earnings.sourceName,
            nasdaq = NasdaqSeasonality(NASDAQ_PROXY, NASDAQ_LABEL, Seasonality.monthStats(nasdaqBars), today.monthValue),
            warnings = warnings,
        )
    }

    /** 공식 값이 있으면 공식 값(+속보의 예상치), 없으면 속보. 공식 값이 속보와 다르면 note에 속보 값을 남긴다. */
    private fun macroView(
        event: MacroEvent,
        date: java.time.LocalDate,
        timeKst: String,
        observations: List<MacroObservation>,
        flash: FlashResult?,
        now: Instant,
    ): ScheduleMacroView {
        // 발표 전에는 전달 값이 이미 있어도 그 발표의 결과가 아니므로 붙이지 않는다
        val official = if (event.releaseAt().toInstant().isBefore(now)) MacroActuals.of(event, observations) else null
        val (actual, status) =
            when {
                official != null -> {
                    val differs = flash != null && flash.view.summary != official.summary
                    val note = official.note + if (differs) " 속보는 ${flash!!.view.summary}였음." else ""
                    official.withExpectedFrom(flash?.view).copy(note = note) to "OFFICIAL"
                }
                flash != null -> flash.view to "FLASH"
                macroFlash.waiting(event, now) -> null to "WAITING"
                else -> null to null
            }
        val source = if (status == "FLASH") flash?.sourceUrl else null
        val expectedSource = if (actual?.metrics?.any { it.expected != null } == true) flash?.expectedSourceUrl else null
        return ScheduleMacroView(event.type.name, event.type.label, date, timeKst, actual, status, source, expectedSource)
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
        private const val FAST_POLL_LEAD_SECONDS = 5 * 60L
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
