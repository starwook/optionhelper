package com.optionhelper.marketdata

import com.optionhelper.option.OptionChain
import java.time.Instant
import java.time.LocalDate

/**
 * 시세 데이터 소스. 구현체(가짜, Alpaca, 나중에 IBKR)를 바꿔 끼워도 나머지 코드는 그대로다.
 * 설정 `optionhelper.market-data.source` 값으로 하나만 빈으로 뜬다.
 */
interface MarketDataSource {
    val feed: DataFeed

    fun stockQuote(symbol: String): StockQuote

    /** 오늘 이후 만기 목록(0DTE 포함), 오름차순. */
    fun expirations(
        symbol: String,
        spot: Double,
    ): List<LocalDate>

    /** 한 만기의 체인. 행사가는 [minStrike, maxStrike] 범위만. */
    fun chain(
        symbol: String,
        expiry: LocalDate,
        minStrike: Double,
        maxStrike: Double,
    ): OptionChain

    /** from부터 어제까지의 일봉(분할·배당 조정), 오래된 순. */
    fun dailyBars(
        symbol: String,
        from: LocalDate,
    ): List<DailyBar>

    /** [dailyImpliedVolatility]가 무엇을 잰 값인지. IV 히스토리 화면에 기준으로 표시한다. */
    val historicalIvLabel: String get() = "데이터 소스의 30일 IV"

    /**
     * from부터 어제까지 기초자산의 일별 30일 IV, 오래된 순. IV 히스토리(IV Rank·퍼센타일)에 쓴다.
     * IBKR은 `OPTION_IMPLIED_VOLATILITY` 일봉으로 구현한다. 과거 IV를 주지 않는 소스는 빈 목록(그러면 앱이 쌓은 스냅샷으로 대신한다).
     */
    fun dailyImpliedVolatility(
        symbol: String,
        from: LocalDate,
    ): List<DailyIv> = emptyList()
}

data class StockQuote(
    val symbol: String,
    val price: Double,
    // 오늘 시가. 장 시작 전이면 null.
    val open: Double?,
    val previousClose: Double?,
    // 현재가(latestTrade)의 체결 시각. 장 밖이면 프리장·장후 체결일 수 있다.
    val asOf: Instant,
)

enum class FeedKind(val label: String) {
    REALTIME("실시간"),
    INDICATIVE("실시간 가공 호가"),
    DELAYED("지연"),
    SIMULATED("가짜 데이터"),
}

/** 화면에 항상 보여 줄 데이터 출처와 성격. */
data class DataFeed(val name: String, val kind: FeedKind, val note: String)
