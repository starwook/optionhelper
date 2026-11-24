package com.optionhelper.stockchart

/** 상단 주가 차트의 기간. DAY는 마지막 정규장의 5분봉, 나머지는 일봉 종가를 거래일 수만큼. */
enum class ChartRange(val label: String, val tradingDays: Int?) {
    DAY("1일", null),
    THREE_MONTHS("3개월", 63),
    YEAR("1년", 252),
}
