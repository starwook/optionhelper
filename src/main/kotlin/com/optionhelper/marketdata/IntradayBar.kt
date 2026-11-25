package com.optionhelper.marketdata

import java.time.Instant

/** 분봉 한 개(봉 시작 시각, 시가·고가·저가·종가, 거래량 — 소스가 안 주면 0). */
data class IntradayBar(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)
