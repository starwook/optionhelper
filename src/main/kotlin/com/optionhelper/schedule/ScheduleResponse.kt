package com.optionhelper.schedule

import com.optionhelper.macro.MacroActualView
import com.optionhelper.seasonality.MonthStats
import java.time.LocalDate

/** 종목과 상관없는 시장 일정 화면: 경제지표 + 관심 종목 실적발표 달력 + 나스닥 월별 수익률. 날짜·시각은 모두 한국 시간. */
data class ScheduleResponse(
    val today: LocalDate,
    val macro: List<ScheduleMacroView>,
    // 경제지표 일정 파일이 채워진 마지막 날짜
    val macroCoversUntil: LocalDate?,
    // 경제지표 발표 결과 출처와 마지막으로 받은 날짜(한국). 출처 표기 의무(BLS 약관) 때문에 화면에 쓴다
    val macroActualSource: String,
    val macroActualFetchedOn: LocalDate?,
    // 속보를 뽑는 Claude 모델. 속보 기능이 꺼져 있으면 null
    val flashModel: String?,
    // 발표 직전·직후라 화면이 자주(30초) 새로 받아야 하는가
    val fastPoll: Boolean,
    val earnings: List<ScheduleEarningsView>,
    val earningsSource: String,
    val nasdaq: NasdaqSeasonality,
    val warnings: List<String>,
)

// date는 한국 날짜, timeKst는 "HH:mm". actual은 지난 발표의 결과(공식 값, 없으면 속보).
// status: OFFICIAL(공식) / FLASH(속보, 공식 확인 전) / WAITING(발표 직후 확인 중) / null
data class ScheduleMacroView(
    val type: String,
    val label: String,
    val date: LocalDate,
    val timeKst: String,
    val actual: MacroActualView?,
    val status: String?,
    // 속보의 공식 발표 페이지, 예상치를 실은 기사
    val sourceUrl: String?,
    val expectedSourceUrl: String?,
)

// date는 한국 날짜. timeKst는 장 시작 전 발표면 "이 시각 전", 장 마감 후면 "이 시각 후"의 기준 시각, 시점 미상이면 null
data class ScheduleEarningsView(val symbol: String, val date: LocalDate, val timing: String, val timeKst: String?)

data class NasdaqSeasonality(val symbol: String, val label: String, val months: List<MonthStats>, val currentMonth: Int)
