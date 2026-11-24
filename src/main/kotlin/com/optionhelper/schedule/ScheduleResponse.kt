package com.optionhelper.schedule

import com.optionhelper.earnings.EarningsEvent
import com.optionhelper.seasonality.MonthStats
import java.time.LocalDate

/** 종목과 상관없는 시장 일정 화면: 경제지표 + 관심 종목 실적발표 달력 + 나스닥 월별 수익률. */
data class ScheduleResponse(
    val today: LocalDate,
    val macro: List<ScheduleMacroView>,
    // 경제지표 일정 파일이 채워진 마지막 날짜
    val macroCoversUntil: LocalDate?,
    val earnings: List<EarningsEvent>,
    val earningsSource: String,
    val nasdaq: NasdaqSeasonality,
    val warnings: List<String>,
)

data class ScheduleMacroView(val type: String, val label: String, val date: LocalDate, val timeKst: String)

data class NasdaqSeasonality(val symbol: String, val label: String, val months: List<MonthStats>, val currentMonth: Int)
