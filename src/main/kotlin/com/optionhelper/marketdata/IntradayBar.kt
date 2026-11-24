package com.optionhelper.marketdata

import java.time.Instant

/** 분봉 한 개(봉 시작 시각, 종가). 차트 선만 그리므로 종가만 둔다. */
data class IntradayBar(
    val time: Instant,
    val close: Double,
)
