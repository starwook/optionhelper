package com.optionhelper.pricing

import org.springframework.boot.context.properties.ConfigurationProperties

/** 블랙-숄즈에 넣는 무위험금리(연, 소수). 기본 4%는 임시값(기획서 미결 사항). */
@ConfigurationProperties(prefix = "optionhelper.pricing")
data class PricingProperties(
    val riskFreeRate: Double = 0.04,
)
