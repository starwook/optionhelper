package com.optionhelper.marketdata.alpaca

import org.springframework.boot.context.properties.ConfigurationProperties

/** 키는 application-local.yml에만 둔다. */
@ConfigurationProperties(prefix = "optionhelper.market-data.alpaca")
data class AlpacaProperties(
    val keyId: String = "",
    val secretKey: String = "",
    val baseUrl: String = "https://data.alpaca.markets",
    // 무료 플랜은 indicative(실시간 가공 호가), 유료는 opra
    val optionFeed: String = "indicative",
    // 무료 플랜 주식 실시간은 iex
    val stockFeed: String = "iex",
)
