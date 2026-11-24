package com.optionhelper.marketdata

import java.time.Instant
import java.time.LocalDate

/**
 * 기초자산 가격만 주는 소스(공개 사이트용: Hyperliquid, 가짜). 옵션은 [ModeledMarketDataSource]가 이 가격으로 계산한다.
 * 설정 `optionhelper.market-data.source` 값으로 하나만 빈으로 뜬다.
 */
interface UnderlyingPriceSource {
    // 화면에 보일 출처 이름(짧게: DB source 열 32자 안에 " · 이론가"까지 들어가야 한다)과 설명
    val name: String
    val note: String

    fun stockQuote(symbol: String): StockQuote

    /** from부터 어제까지의 미국 거래일 일봉, 오래된 순. */
    fun dailyBars(
        symbol: String,
        from: LocalDate,
    ): List<DailyBar>

    /** 그날(뉴욕 날짜) 정규장 5분봉, 오래된 순. */
    fun intradayBars(
        symbol: String,
        date: LocalDate,
    ): List<IntradayBar>

    /** 시작 시각이 [from, to)인 N분봉(정규장 밖 포함), 오래된 순. minutes는 1·5·15·30. 소스가 주는 만큼만(최근 일부일 수 있다). */
    fun minuteBars(
        symbol: String,
        minutes: Int,
        from: Instant,
        to: Instant,
    ): List<IntradayBar>
}
