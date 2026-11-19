package com.optionhelper.option

import com.optionhelper.pricing.OptionType
import java.time.LocalDate
import kotlin.math.abs

data class OptionQuote(
    val type: OptionType,
    val strike: Double,
    val bid: Double,
    val ask: Double,
    // 데이터 소스가 주는 IV·델타(없으면 null)
    val impliedVolatility: Double? = null,
    val delta: Double? = null,
) {
    val mid: Double get() = (bid + ask) / 2
}

/** 한 만기의 옵션 체인. 행사가 탐색(ATM, n칸 바깥)은 체인이 안다. */
data class OptionChain(
    val underlying: String,
    val expiry: LocalDate,
    val quotes: List<OptionQuote>,
) {
    /** 콜과 풋이 모두 있는 행사가만, 오름차순. */
    val strikes: List<Double> by lazy {
        quotes.groupBy { it.strike }
            .filterValues { list -> list.any { it.type == OptionType.CALL } && list.any { it.type == OptionType.PUT } }
            .keys
            .sorted()
    }

    fun atmStrike(spot: Double): Double? = strikes.minByOrNull { abs(it - spot) }

    /** ATM에서 위(+)·아래(-)로 n칸 떨어진 행사가. 범위를 벗어나면 null. */
    fun strikeAway(
        atm: Double,
        steps: Int,
    ): Double? {
        val index = strikes.indexOf(atm)
        if (index < 0) return null
        return strikes.getOrNull(index + steps)
    }

    fun quote(
        type: OptionType,
        strike: Double,
    ): OptionQuote? = quotes.firstOrNull { it.type == type && it.strike == strike }
}
