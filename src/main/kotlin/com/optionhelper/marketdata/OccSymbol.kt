package com.optionhelper.marketdata

import com.optionhelper.pricing.OptionType
import java.time.LocalDate

/** OCC 옵션 심볼(예: NVDA261120C00157500 = 루트 + yyMMdd + C/P + 행사가×1000 8자리). */
data class OccSymbol(val root: String, val expiry: LocalDate, val type: OptionType, val strike: Double) {
    companion object {
        private const val TAIL_LENGTH = 15

        fun parse(symbol: String): OccSymbol? {
            if (symbol.length <= TAIL_LENGTH) return null
            val root = symbol.dropLast(TAIL_LENGTH)
            val tail = symbol.takeLast(TAIL_LENGTH)
            val date = tail.substring(0, 6)
            val type =
                when (tail[6]) {
                    'C' -> OptionType.CALL
                    'P' -> OptionType.PUT
                    else -> return null
                }
            val strike = tail.substring(7).toLongOrNull() ?: return null
            val expiry =
                runCatching {
                    LocalDate.of(2000 + date.substring(0, 2).toInt(), date.substring(2, 4).toInt(), date.substring(4, 6).toInt())
                }.getOrNull() ?: return null
            return OccSymbol(root, expiry, type, strike / 1000.0)
        }
    }
}
