package com.optionhelper.marketdata

import java.time.LocalDate

/** 일봉 한 개. 데이터 소스가 무엇이든 이 모양으로 넘긴다. volume은 소스가 주는 거래량(없으면 0). */
data class DailyBar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)
