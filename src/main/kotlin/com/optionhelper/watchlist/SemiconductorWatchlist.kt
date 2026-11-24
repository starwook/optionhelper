package com.optionhelper.watchlist

/** 기본 관심 종목(반도체). 첫 종목이 화면 기본값이다. SOXX는 비교 기준 ETF. */
object SemiconductorWatchlist {
    val SYMBOLS = listOf("NVDA", "AMD", "AVGO", "TSM", "MU", "INTC", "QCOM", "ARM", "SMCI", "SOXX")
    val DEFAULT: String = SYMBOLS.first()
    const val BENCHMARK = "SOXX"

    /** 실적발표가 있는 개별 종목(비교 기준 ETF 제외). */
    val STOCKS: List<String> = SYMBOLS - BENCHMARK
}
