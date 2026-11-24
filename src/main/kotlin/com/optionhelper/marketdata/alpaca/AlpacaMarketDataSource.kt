package com.optionhelper.marketdata.alpaca

import com.fasterxml.jackson.databind.JsonNode
import com.optionhelper.marketdata.DailyBar
import com.optionhelper.marketdata.DailyIv
import com.optionhelper.marketdata.DataFeed
import com.optionhelper.marketdata.FeedKind
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.marketdata.OccSymbol
import com.optionhelper.marketdata.StockQuote
import com.optionhelper.option.OptionChain
import com.optionhelper.pricing.AtmIvBacksolver
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.PricingProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriBuilder
import java.net.URI
import java.time.Clock
import java.time.LocalDate

/**
 * Alpaca Market Data API(무료 Basic 플랜 기준). 옵션 호가는 indicative(실시간 가공값), 주식은 IEX 실시간.
 */
@Component
@ConditionalOnProperty(prefix = "optionhelper.market-data", name = ["source"], havingValue = "alpaca")
class AlpacaMarketDataSource(
    private val properties: AlpacaProperties,
    private val clock: Clock,
    private val pricing: PricingProperties,
    restClientBuilder: RestClient.Builder,
) : MarketDataSource {
    init {
        require(properties.keyId.isNotBlank() && properties.secretKey.isNotBlank()) {
            "Alpaca 키가 없습니다. application-local.yml의 optionhelper.market-data.alpaca.key-id / secret-key를 채우세요."
        }
    }

    private val client =
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .defaultHeader("APCA-API-KEY-ID", properties.keyId)
            .defaultHeader("APCA-API-SECRET-KEY", properties.secretKey)
            .build()

    override val feed =
        if (properties.optionFeed == "opra") {
            DataFeed("Alpaca OPRA", FeedKind.REALTIME, "OPRA 실시간 호가")
        } else {
            DataFeed("Alpaca 무료(indicative)", FeedKind.INDICATIVE, "옵션 호가는 실시간이지만 실제 OPRA 호가를 가공한 참고값이고, 체결가는 15분 지연입니다.")
        }

    override val historicalIvLabel = "Alpaca 과거 옵션 종가로 역산한 약 30일 월물 ATM IV (마지막 체결가 기준이라 근사값)"

    override fun stockQuote(symbol: String): StockQuote =
        AlpacaParser.stockSnapshot(
            symbol,
            get {
                it.path("/v2/stocks/$symbol/snapshot").queryParam("feed", properties.stockFeed).build()
            },
            clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate(),
        )

    override fun expirations(
        symbol: String,
        spot: Double,
    ): List<LocalDate> {
        val today = today()
        // ATM 근처만 받아 만기 목록을 뽑는다(전체 체인을 받으면 페이지가 너무 많다).
        val quotes =
            optionPages(symbol) {
                it.queryParam("strike_price_gte", spot * 0.97)
                    .queryParam("strike_price_lte", spot * 1.03)
                    .queryParam("expiration_date_gte", today)
                    .queryParam("expiration_date_lte", today.plusDays(EXPIRATION_LOOKAHEAD_DAYS))
            }
        return quotes.map { it.first.expiry }.distinct().sorted()
    }

    override fun chain(
        symbol: String,
        expiry: LocalDate,
        minStrike: Double,
        maxStrike: Double,
    ): OptionChain {
        val quotes =
            optionPages(symbol) {
                it.queryParam("expiration_date", expiry)
                    .queryParam("strike_price_gte", minStrike)
                    .queryParam("strike_price_lte", maxStrike)
            }
        return OptionChain(symbol, expiry, quotes.map { it.second })
    }

    override fun dailyBars(
        symbol: String,
        from: LocalDate,
    ): List<DailyBar> =
        try {
            barPages(symbol, from, "sip")
        } catch (e: RestClientResponseException) {
            // 무료 플랜에서 SIP 과거 데이터가 막혀 있으면 IEX로 다시 받는다
            barPages(symbol, from, "iex")
        }

    /**
     * 과거 IV를 주지 않아서, 과거 옵션 일봉(만기된 계약 포함)으로 날마다 약 30일 ATM IV를 역산한다.
     * 행사가와 맞추려고 기초자산 종가는 분할 미조정(raw)으로 받는다. docs/외부API-명세.md 1-4.
     */
    override fun dailyImpliedVolatility(
        symbol: String,
        from: LocalDate,
    ): List<DailyIv> {
        val closes =
            try {
                barPages(symbol, maxOf(from, OPTION_HISTORY_START), "sip", "raw")
            } catch (e: RestClientResponseException) {
                barPages(symbol, maxOf(from, OPTION_HISTORY_START), "iex", "raw")
            }.associate { it.date to it.close }
        val prices =
            AtmIvBacksolver.plan(closes).flatMap { group ->
                group.contracts.chunked(OPTION_SYMBOLS_PER_REQUEST).flatMap { chunk ->
                    val symbols = chunk.map { OccSymbol(symbol, it.expiry, it.type, it.strike).format() }
                    optionBarPages(symbols, group.dates.first(), group.dates.last()).entries
                }
            }.associate { (key, close) ->
                val (date, occ) = key
                (date to AtmIvBacksolver.Contract(occ.expiry, occ.type, occ.strike)) to close
            }
        return AtmIvBacksolver.solve(closes, prices, pricing.riskFreeRate).map { (date, iv) -> DailyIv(date, iv) }
    }

    private fun optionBarPages(
        symbols: List<String>,
        start: LocalDate,
        end: LocalDate,
    ): Map<Pair<LocalDate, OccSymbol>, Double> {
        val closes = mutableMapOf<Pair<LocalDate, OccSymbol>, Double>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val page =
                AlpacaParser.optionBars(
                    get {
                        it.path("/v1beta1/options/bars")
                            .queryParam("symbols", symbols.joinToString(","))
                            .queryParam("timeframe", "1Day")
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("limit", 10000)
                            .apply { token?.let { t -> queryParam("page_token", t) } }
                            .build()
                    },
                )
            closes += page.closes
            token = page.nextPageToken ?: return closes
        }
        return closes
    }

    private fun barPages(
        symbol: String,
        from: LocalDate,
        feed: String,
        adjustment: String = "all",
    ): List<DailyBar> {
        val bars = mutableListOf<DailyBar>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val page =
                AlpacaParser.bars(
                    symbol,
                    get {
                        it.path("/v2/stocks/bars")
                            .queryParam("symbols", symbol)
                            .queryParam("timeframe", "1Day")
                            .queryParam("start", from)
                            .queryParam("end", today().minusDays(1))
                            .queryParam("limit", 10000)
                            .queryParam("adjustment", adjustment)
                            .queryParam("feed", feed)
                            .apply { token?.let { t -> queryParam("page_token", t) } }
                            .build()
                    },
                )
            bars += page.bars
            token = page.nextPageToken ?: return bars
        }
        return bars
    }

    private fun optionPages(
        symbol: String,
        filter: (UriBuilder) -> UriBuilder,
    ) = buildList {
        var token: String? = null
        for (i in 0 until MAX_PAGES) {
            val page =
                AlpacaParser.optionSnapshots(
                    get {
                        filter(it.path("/v1beta1/options/snapshots/$symbol"))
                            .queryParam("feed", properties.optionFeed)
                            .queryParam("limit", 1000)
                            .apply { token?.let { t -> queryParam("page_token", t) } }
                            .build()
                    },
                )
            addAll(page.quotes)
            token = page.nextPageToken ?: break
        }
    }

    private fun get(uri: (UriBuilder) -> URI): JsonNode =
        client.get().uri(uri).retrieve().body(JsonNode::class.java) ?: error("Alpaca 응답이 비었습니다")

    private fun today() = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()

    companion object {
        private const val MAX_PAGES = 10
        private const val EXPIRATION_LOOKAHEAD_DAYS = 70L

        // 옵션 과거 데이터 시작(docs/외부API-명세.md 공통 표)
        private val OPTION_HISTORY_START = LocalDate.of(2024, 2, 1)

        // 한 요청 symbols 최대 개수는 문서에 없어서 보수적으로 나눈다
        private const val OPTION_SYMBOLS_PER_REQUEST = 50
    }
}
