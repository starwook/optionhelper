package com.optionhelper.marketdata.alpaca

import com.fasterxml.jackson.databind.JsonNode
import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.OccSymbol
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.option.OptionQuote
import com.optionhelper.pricing.MarketClock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Alpaca 응답 JSON → 도메인. 필드명은 docs/데이터소스-조사.md(공식 문서 확인)를 따른다.
 * - 옵션 스냅샷: snapshots{심볼: {latestQuote{bp, ap}, impliedVolatility, greeks{delta}}}, next_page_token
 * - 주식 스냅샷: latestTrade{p, t}, dailyBar{t, o}, prevDailyBar{c}
 * - 일봉: bars{심볼: [{t, o, h, l, c}]}, next_page_token
 */
object AlpacaParser {
    data class OptionPage(val quotes: List<Pair<OccSymbol, OptionQuote>>, val nextPageToken: String?)

    data class BarPage(val bars: List<DailyBar>, val nextPageToken: String?)

    fun optionSnapshots(root: JsonNode): OptionPage {
        val quotes =
            root.path("snapshots").fields().asSequence().mapNotNull { (symbol, snap) ->
                val occ = OccSymbol.parse(symbol) ?: return@mapNotNull null
                val quote = snap.path("latestQuote")
                occ to
                    OptionQuote(
                        type = occ.type,
                        strike = occ.strike,
                        bid = quote.path("bp").asDouble(0.0),
                        ask = quote.path("ap").asDouble(0.0),
                        impliedVolatility = snap.path("impliedVolatility").doubleOrNull(),
                        delta = snap.path("greeks").path("delta").doubleOrNull(),
                    )
            }.toList()
        return OptionPage(quotes, root.path("next_page_token").textOrNull())
    }

    /** 개장 전엔 dailyBar가 직전 거래일 봉이라, 봉 날짜(ET)가 [today]가 아니면 시가를 비운다. */
    fun stockSnapshot(
        symbol: String,
        root: JsonNode,
        today: LocalDate,
    ): StockQuote {
        val trade = root.path("latestTrade")
        val dailyBar = root.path("dailyBar")
        val dailyBarDate =
            dailyBar.path(
                "t",
            ).textOrNull()?.let { OffsetDateTime.parse(it).atZoneSameInstant(MarketClock.NEW_YORK).toLocalDate() }
        return StockQuote(
            symbol = symbol,
            price = trade.path("p").asDouble(),
            open = if (dailyBarDate == today) dailyBar.path("o").doubleOrNull() else null,
            previousClose = root.path("prevDailyBar").path("c").doubleOrNull(),
            asOf = trade.path("t").textOrNull()?.let { OffsetDateTime.parse(it).toInstant() } ?: Instant.now(),
        )
    }

    fun bars(
        symbol: String,
        root: JsonNode,
    ): BarPage {
        val bars =
            root.path("bars").path(symbol).map { bar ->
                DailyBar(
                    date = OffsetDateTime.parse(bar.path("t").asText()).toLocalDate(),
                    open = bar.path("o").asDouble(),
                    high = bar.path("h").asDouble(),
                    low = bar.path("l").asDouble(),
                    close = bar.path("c").asDouble(),
                )
            }
        return BarPage(bars, root.path("next_page_token").textOrNull())
    }

    private fun JsonNode.doubleOrNull(): Double? = if (isNumber) asDouble() else null

    private fun JsonNode.textOrNull(): String? = if (isTextual && asText().isNotBlank()) asText() else null
}
