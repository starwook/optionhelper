package com.optionhelper.pricing

/**
 * 미국 주식 거래 시간대(뉴욕 시간). 프리마켓 04:00–09:30, 정규장 09:30–16:00, 애프터마켓 16:00–20:00(조기 마감일은 13:00–17:00).
 * 그 밖(심야·주말·휴장일)은 [CLOSED]. Hyperliquid 선물은 이때도 움직이지만 미국 장은 닫혀 있다.
 */
enum class MarketSession {
    PRE_MARKET,
    REGULAR,
    AFTER_HOURS,
    CLOSED,
}
