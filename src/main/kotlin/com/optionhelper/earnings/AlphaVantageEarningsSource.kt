package com.optionhelper.earnings

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@ConfigurationProperties(prefix = "optionhelper.earnings.alpha-vantage")
data class AlphaVantageProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://www.alphavantage.co",
)

/** Alpha Vantage 무료 플랜(하루 25회). 호출은 EarningsService가 하루 한 번으로 줄인다. */
@Component
@ConditionalOnProperty(prefix = "optionhelper.earnings", name = ["source"], havingValue = "alphavantage")
class AlphaVantageEarningsSource(
    private val properties: AlphaVantageProperties,
    restClientBuilder: RestClient.Builder,
) : EarningsCalendarSource {
    init {
        require(properties.apiKey.isNotBlank()) {
            "Alpha Vantage 키가 없습니다. application-local.yml의 optionhelper.earnings.alpha-vantage.api-key를 채우세요."
        }
    }

    private val client = restClientBuilder.baseUrl(properties.baseUrl).build()

    override val name = "Alpha Vantage"

    override fun events(symbol: String): List<EarningsEvent> {
        val history = query("function" to "EARNINGS", "symbol" to symbol).body(JsonNode::class.java) ?: error("Alpha Vantage 응답이 비었습니다")
        AlphaVantageParser.errorMessage(history)?.let { error("Alpha Vantage: $it") }

        // 바로 이어 부르면 연속 호출 제한에 걸려 깨진 CSV가 온다(2026-11-24 실측). 하루 한 번이라 잠깐 쉰다.
        Thread.sleep(CALL_GAP_MS)
        val calendar =
            query(
                "function" to "EARNINGS_CALENDAR",
                "symbol" to symbol,
                "horizon" to "12month",
            ).body(String::class.java).orEmpty()

        val upcoming = AlphaVantageParser.calendarCsv(symbol, calendar)
        return (AlphaVantageParser.historyJson(symbol, history) + upcoming).distinctBy { it.date }.sortedBy { it.date }
    }

    private fun query(vararg params: Pair<String, String>) =
        client.get().uri { builder ->
            builder.path("/query")
            params.forEach { (key, value) -> builder.queryParam(key, value) }
            builder.queryParam("apikey", properties.apiKey).build()
        }.retrieve()

    private companion object {
        const val CALL_GAP_MS = 1_500L
    }
}
