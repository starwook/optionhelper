package com.optionhelper.strategy

/** 토스증권 미국 옵션 수수료. 진입과 청산 양쪽에 계약당 붙는다. */
object TossFee {
    const val PER_CONTRACT = 1.99
    const val CONTRACT_MULTIPLIER = 100

    /** 진입+청산 왕복 수수료($). 스트래들 1세트(2계약)면 4번 = $7.96. */
    fun roundTrip(contracts: Int): Double = PER_CONTRACT * contracts * 2

    /** 왕복 수수료를 주당 가격으로 환산. */
    fun roundTripPerShare(contracts: Int): Double = roundTrip(contracts) / CONTRACT_MULTIPLIER
}
