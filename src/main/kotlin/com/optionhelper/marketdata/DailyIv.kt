package com.optionhelper.marketdata

import java.time.LocalDate

/** 하루 한 개의 30일 내재변동성(소수, 0.45 = 45%). 데이터 소스가 계산해 주는 값(예: IBKR OPTION_IMPLIED_VOLATILITY 봉). */
data class DailyIv(
    val date: LocalDate,
    val iv: Double,
)
