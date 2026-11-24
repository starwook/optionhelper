package com.optionhelper.marketdata.hyperliquid

import org.springframework.boot.context.properties.ConfigurationProperties

/** Hyperliquid 공개 API(키 없음). 종목 심볼은 "배포자:종목"(예: xyz:NVDA)으로 부른다. */
@ConfigurationProperties(prefix = "optionhelper.market-data.hyperliquid")
data class HyperliquidProperties(
    val infoUrl: String = "https://api.hyperliquid.xyz/info",
    val wsUrl: String = "wss://api.hyperliquid.xyz/ws",
    // 대상 종목이 실제로 거래되는 배포자. 2026-11-25 실측에서 xyz 말고는 상장폐지(docs/데이터소스-조사.md 11-2)
    val dex: String = "xyz",
) {
    fun coin(symbol: String) = "$dex:${symbol.uppercase()}"
}
