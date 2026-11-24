package com.optionhelper.watchlist

import com.optionhelper.option.OptionListing
import com.optionhelper.option.OptionListing.Companion.FRIDAY
import com.optionhelper.option.OptionListing.Companion.MON_WED_FRI
import com.optionhelper.option.StrikeStep

class UnknownSymbolException(symbol: String) : IllegalArgumentException("다루지 않는 종목입니다: $symbol")

/**
 * 사이트가 다루는 종목(고정). 첫 종목이 화면 기본값이다. Hyperliquid xyz에서 거래되는 반도체 종목만 둔다.
 * 옵션 상장 규칙은 2026-11-25 실측(docs/데이터소스-조사.md 11-2). SOXL은 3배 레버리지 반도체 ETF다.
 */
enum class WatchedSymbol(val listing: OptionListing) {
    NVDA(OptionListing(MON_WED_FRI, StrikeStep(1.0, stepAbove = 2.5), StrikeStep(5.0))),
    AMD(OptionListing(MON_WED_FRI, StrikeStep(2.5), StrikeStep(10.0))),
    AVGO(OptionListing(MON_WED_FRI, StrikeStep(2.5), StrikeStep(10.0))),
    TSM(OptionListing(FRIDAY, StrikeStep(2.5), StrikeStep(10.0))),
    MU(OptionListing(MON_WED_FRI, StrikeStep(2.5), StrikeStep(10.0))),
    INTC(OptionListing(FRIDAY, StrikeStep(1.0), StrikeStep(2.5))),
    QCOM(OptionListing(FRIDAY, StrikeStep(1.0, stepAbove = 2.5), StrikeStep(5.0))),
    ARM(OptionListing(FRIDAY, StrikeStep(1.0, stepAbove = 2.5), StrikeStep(5.0))),
    SNDK(OptionListing(FRIDAY, StrikeStep(0.625), StrikeStep(1.25))),

    // SK하이닉스 미국 ADR(NASDAQ)
    SKHY(OptionListing(FRIDAY, StrikeStep(1.0), StrikeStep(5.0))),
    SOXL(OptionListing(FRIDAY, StrikeStep(1.0), StrikeStep(1.0))),
    ;

    companion object {
        val DEFAULT = NVDA
        val SYMBOLS: List<String> = entries.map { it.name }

        /** 실적발표가 있는 개별 종목(레버리지 ETF SOXL 제외). */
        val STOCKS: List<String> = (entries - SOXL).map { it.name }

        fun of(symbol: String): WatchedSymbol =
            entries.firstOrNull { it.name == symbol.trim().uppercase() } ?: throw UnknownSymbolException(symbol)
    }
}
