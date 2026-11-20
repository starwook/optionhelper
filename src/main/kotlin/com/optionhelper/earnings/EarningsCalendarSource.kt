package com.optionhelper.earnings

/** 실적발표일 소스. 설정 `optionhelper.earnings.source` 값으로 하나만 빈으로 뜬다. */
interface EarningsCalendarSource {
    val name: String

    /** 과거와 예정 실적발표일 전체. */
    fun events(symbol: String): List<EarningsEvent>
}
