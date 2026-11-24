package com.optionhelper.stockchart

/**
 * 상단 주가 차트의 봉 단위. 분봉은 정규장(09:30 ET~마감)만, 봉은 장 시작 시각에 맞춰 자른다.
 * sourceMinutes = 데이터 소스에서 받을 분봉(60분봉은 30분봉 두 개를 합친다: 소스의 1시간봉은 정각 기준이라 09:30에 안 맞는다).
 * 소스는 최근 5000개까지만 주므로 분봉이 볼 수 있는 기간은 1분 약 3일, 5분 약 17일, 15분 약 52일, 30·60분 약 104일(24시간 기준).
 */
enum class ChartInterval(val label: String, val minutes: Int? = null, val sourceMinutes: Int? = null) {
    MIN_1("1분", 1, 1),
    MIN_5("5분", 5, 5),
    MIN_15("15분", 15, 15),
    MIN_30("30분", 30, 30),
    MIN_60("60분", 60, 30),
    DAY("일"),
    WEEK("주"),
    MONTH("월"),
    YEAR("년"),
    ;

    val intraday: Boolean get() = minutes != null
}
