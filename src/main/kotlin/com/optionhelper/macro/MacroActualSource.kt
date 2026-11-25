package com.optionhelper.macro

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset

/** 경제지표 발표 결과(월간 값)를 주는 곳. */
interface MacroActualSource {
    val name: String

    /** 작년 1월부터 지금까지의 월간 값. */
    fun observations(): List<MacroObservation>
}

/** BLS Public Data API v2. 키 없이 불러도 된다(2026-11-26 실측). 발표값은 API에 하루 늦게 반영된다(docs/외부API-명세.md 6절). */
@Component
@ConditionalOnProperty(prefix = "optionhelper.macro-actuals", name = ["source"], havingValue = "bls", matchIfMissing = true)
class BlsMacroActualSource(
    restClientBuilder: RestClient.Builder,
    private val clock: Clock,
) : MacroActualSource {
    private val client = restClientBuilder.baseUrl("https://api.bls.gov").build()

    override val name = "미국 노동통계국(BLS)"

    override fun observations(): List<MacroObservation> {
        val year = clock.instant().atZone(ZoneOffset.UTC).year
        val body =
            mapOf(
                "seriesid" to MacroSeries.entries.map { it.seriesId },
                "startyear" to (year - 1).toString(),
                "endyear" to year.toString(),
            )
        val json =
            client.post().uri("/publicAPI/v2/timeseries/data/").body(body).retrieve().body(JsonNode::class.java)
                ?: error("BLS 응답이 비었습니다")
        return MacroActuals.parseBls(json)
    }
}

/** 네트워크 없이 테스트·화면 확인용. 매달 CPI 지수가 0.3%씩 오르고 고용이 15만 명씩 느는 가짜 값. */
@Component
@ConditionalOnProperty(prefix = "optionhelper.macro-actuals", name = ["source"], havingValue = "fake")
class FakeMacroActualSource(private val clock: Clock) : MacroActualSource {
    override val name = "가짜 데이터"

    override fun observations(): List<MacroObservation> {
        val now = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC))
        return (0L..23L).flatMap { back ->
            val month = now.minusMonths(back)
            val growth = Math.pow(1.003, -back.toDouble())
            listOf(
                MacroObservation(MacroSeries.CPI_ALL, month, 330.0 * growth),
                MacroObservation(MacroSeries.CPI_CORE, month, 335.0 * growth),
                MacroObservation(MacroSeries.PPI_FINAL_DEMAND, month, 160.0 * growth),
                MacroObservation(MacroSeries.NONFARM_PAYROLLS, month, 159_000.0 - 150 * back),
                MacroObservation(MacroSeries.UNEMPLOYMENT_RATE, month, 4.1),
            )
        }
    }
}
