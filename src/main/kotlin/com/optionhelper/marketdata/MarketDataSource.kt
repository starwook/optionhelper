package com.optionhelper.marketdata

import com.optionhelper.option.OptionChain
import java.time.Instant
import java.time.LocalDate

/**
 * 시세 데이터 소스. 나머지 코드(대시보드·전략·차트 등)는 이 인터페이스만 보므로 구현체를 바꿔도 그대로 동작한다.
 * 지금 구현은 공개 사이트용 [ModeledMarketDataSource](기초자산 가격 + 이론가 옵션) 하나다.
 * TODO(로그인): 실제 시세(IBKR/Alpaca)는 재배포 불가라 소유자 로그인 때만 쓰는 구현으로 추가한다(docs/외부API-명세.md 1·3절).
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

    /** 그날(뉴욕 날짜) 정규장 5분봉, 오래된 순. 장이 아직 안 열렸으면 빈 목록. 상단 주가 차트에 쓴다. */
    fun intradayBars(
        symbol: String,
        date: LocalDate,
    ): List<IntradayBar>

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
    val previousClose: Double?,
    // 현재가(latestTrade)의 체결 시각. 장 밖이면 프리장·장후 체결일 수 있다.
    val asOf: Instant,
    // 정규장이 끝난 가장 최근 거래일의 마감가. 장 밖에 "정규장 $X로 마감 · 애프터마켓 ±"로 보여 준다
    val lastClose: SessionClose? = null,
)

/** 한 거래일 정규장 마감가와 그 전 거래일 마감가(그날 등락 기준). */
data class SessionClose(val date: LocalDate, val close: Double, val previousClose: Double?)

enum class FeedKind(val label: String) {
    REALTIME("실시간"),
    APPROXIMATE("근사값"),
    INDICATIVE("실시간 가공 호가"),
    DELAYED("지연"),
    SIMULATED("가짜 데이터"),
}

/** 화면에 항상 보여 줄 데이터 출처와 성격. */
data class DataFeed(val name: String, val kind: FeedKind, val note: String)
