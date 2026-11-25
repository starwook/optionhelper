package com.optionhelper.macro

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

/** 발표 결과(실제값)를 받는 월간 시리즈. 지금은 BLS 것만(docs/외부API-명세.md 6절). */
enum class MacroSeries(val seriesId: String, val eventType: MacroEventType) {
    CPI_ALL("CUSR0000SA0", MacroEventType.CPI),
    CPI_CORE("CUSR0000SA0L1E", MacroEventType.CPI),
    PPI_FINAL_DEMAND("WPSFD4", MacroEventType.PPI),
    NONFARM_PAYROLLS("CES0000000001", MacroEventType.NFP),
    UNEMPLOYMENT_RATE("LNS14000000", MacroEventType.NFP),
    ;

    companion object {
        fun of(seriesId: String): MacroSeries? = entries.firstOrNull { it.seriesId == seriesId }
    }
}

data class MacroObservation(val series: MacroSeries, val month: YearMonth, val value: Double)

object MacroActuals {
    /**
     * 발표일이 속한 달의 전달 값을 발표 결과로 본다(CPI·PPI·고용보고서는 보통 전달분을 발표).
     * 정부 셧다운 등으로 어긋날 수 있어 화면에 "몇 월분"인지 같이 쓴다.
     */
    fun referenceMonth(event: MacroEvent): YearMonth = YearMonth.from(event.date).minusMonths(1)

    /** 그 발표의 결과. 값이 아직 없거나(발표 전·API 반영 전) 받는 시리즈가 없는 지표면 null. */
    fun of(
        event: MacroEvent,
        observations: List<MacroObservation>,
    ): MacroActualView? {
        val month = referenceMonth(event)
        val values = observations.associateBy({ it.series to it.month }, { it.value })

        fun value(series: MacroSeries) = values[series to month]

        fun change(series: MacroSeries): Double? {
            val now = value(series) ?: return null
            val before = values[series to month.minusMonths(1)] ?: return null
            return now - before
        }

        fun percentChange(series: MacroSeries): Double? {
            val diff = change(series) ?: return null
            return diff / values.getValue(series to month.minusMonths(1)) * 100
        }

        return when (event.type) {
            MacroEventType.CPI -> {
                val all = percentChange(MacroSeries.CPI_ALL) ?: return null
                MacroResultFormat.cpi(month, all, percentChange(MacroSeries.CPI_CORE), FROM_INDEX)
            }
            MacroEventType.PPI -> MacroResultFormat.ppi(month, percentChange(MacroSeries.PPI_FINAL_DEMAND) ?: return null, FROM_INDEX)
            // CES 값 단위는 천 명
            MacroEventType.NFP ->
                MacroResultFormat.nfp(
                    month,
                    change(MacroSeries.NONFARM_PAYROLLS) ?: return null,
                    value(MacroSeries.UNEMPLOYMENT_RATE),
                    " 공식 값은 BLS 발표값.",
                )
            MacroEventType.FOMC, MacroEventType.PCE -> null
        }
    }

    private const val FROM_INDEX = " 공식 값은 BLS 계절조정 지수로 계산."

    /** 결과를 받는 지표 중, 발표 시각이 지났는데(최근 [window] 안) 아직 결과가 없는 발표. 있으면 자주 확인한다. */
    fun awaiting(
        events: List<MacroEvent>,
        observations: List<MacroObservation>,
        now: Instant,
        window: Duration = AWAIT_WINDOW,
    ): List<MacroEvent> =
        events.filter { event ->
            val release = event.releaseAt().toInstant()
            event.type in TRACKED && !release.isAfter(now) && Duration.between(release, now) < window && of(event, observations) == null
        }

    private val TRACKED = MacroSeries.entries.map { it.eventType }.toSet()

    // 발표 후 2시간만 10분 간격(12회). 키 없는 BLS 한도(하루 25회로 알려짐, 미확인) 안에 들게 그 뒤는 평소대로 6시간마다
    private val AWAIT_WINDOW: Duration = Duration.ofHours(2)

    /** BLS v2 `timeseries/data` 응답. 월간 값(`M01`~`M12`)만 읽고 연평균(`M13`)은 버린다. */
    fun parseBls(json: JsonNode): List<MacroObservation> {
        val status = json["status"]?.asText()
        require(status == "REQUEST_SUCCEEDED") { "BLS 응답 실패: $status ${json["message"]}" }
        return json["Results"]["series"].flatMap { series ->
            val id = MacroSeries.of(series["seriesID"].asText()) ?: return@flatMap emptyList()
            series["data"].mapNotNull { row ->
                val month = row["period"].asText().removePrefix("M").toIntOrNull()?.takeIf { it in 1..12 } ?: return@mapNotNull null
                val value = row["value"].asText().toDoubleOrNull() ?: return@mapNotNull null
                MacroObservation(id, YearMonth.of(row["year"].asText().toInt(), month), value)
            }
        }
    }
}
