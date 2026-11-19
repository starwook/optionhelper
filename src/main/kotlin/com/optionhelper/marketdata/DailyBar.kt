package com.optionhelper.marketdata

import java.time.LocalDate

/** 일봉 한 개. 데이터 소스가 무엇이든 이 모양으로 넘긴다. */
data class DailyBar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)
