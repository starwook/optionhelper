package com.optionhelper.macro

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.YearMonth
import kotlin.math.abs

/**
 * 발표 직후 공식 API보다 먼저 보여 주는 속보. Claude가 웹 검색으로 그 발표의 실제값과 언론 보도 기준 예상치를 뽑는다.
 * 숫자를 잘못 읽을 수 있어서, 발표일·몇 월분·값 범위를 확인하고 하나라도 어긋나면 버린다. 공식 값이 들어오면 공식 값을 쓴다.
 */
object MacroFlash {
    private val mapper = ObjectMapper()

    private data class Field(val name: String, val meaning: String, val valid: (Double) -> Boolean)

    private val pct = { v: Double -> abs(v) <= 3 }

    // 지표마다 [실제값 필드, 예상치 필드] 쌍. 예상치 필드 이름은 "expected_" + 실제값 이름
    private fun fields(type: MacroEventType): List<Field> =
        when (type) {
            MacroEventType.CPI ->
                listOf(
                    Field("cpi_mom_pct", "seasonally adjusted all-items CPI month-over-month %", pct),
                    Field("core_cpi_mom_pct", "core CPI (less food and energy) month-over-month %", pct),
                )
            MacroEventType.PPI -> listOf(Field("ppi_mom_pct", "PPI final demand month-over-month % (seasonally adjusted)", pct))
            MacroEventType.NFP ->
                listOf(
                    Field("nfp_change_thousands", "change in total nonfarm payroll employment in thousands") { abs(it) <= 3_000 },
                    Field("unemployment_rate_pct", "unemployment rate %") { it in 0.0..30.0 },
                )
            MacroEventType.PCE ->
                listOf(
                    Field("pce_mom_pct", "PCE price index month-over-month %", pct),
                    Field("core_pce_mom_pct", "core PCE price index (excluding food and energy) month-over-month %", pct),
                )
            MacroEventType.FOMC ->
                listOf(
                    Field("change_bp", "change in the federal funds target range in basis points (0 if unchanged, negative for a cut)") {
                        it % 25 == 0.0 && abs(it) <= 100
                    },
                )
        }

    private fun subject(event: MacroEvent): String {
        val month = MacroActuals.referenceMonth(event)
        return when (event.type) {
            MacroEventType.CPI -> "the U.S. CPI report (BLS) for $month"
            MacroEventType.PPI -> "the U.S. PPI report (BLS) for $month"
            MacroEventType.NFP -> "the U.S. Employment Situation report (BLS) for $month"
            MacroEventType.PCE -> "the U.S. Personal Income and Outlays report (BEA) for $month"
            MacroEventType.FOMC -> "the FOMC monetary policy decision"
        }
    }

    fun prompt(event: MacroEvent): String {
        val list = fields(event.type)
        val actual = list.joinToString(", ") { "\"${it.name}\": ${it.meaning}" }
        val expected =
            list.joinToString(", ") {
                "\"expected_${it.name}\": consensus forecast for ${it.name} as reported by news before the release, or null"
            }
        val fomc =
            if (event.type == MacroEventType.FOMC) {
                ", \"target_lower_pct\": new lower bound %, \"target_upper_pct\": new upper bound %"
            } else {
                ""
            }
        return """
            Find the official figures from ${subject(event)}, released on ${event.date} (U.S. Eastern time).
            Use only that release. If it is not published yet or you cannot find it, answer {"found": false}.
            Do not guess and do not use an earlier release.
            Answer with this JSON only, no other text:
            {"found": true, "release_date": "YYYY-MM-DD", "reference_month": "YYYY-MM", $actual$fomc, $expected, "source_url": "URL of the official release page you read", "expected_source_url": "URL of the news page with the forecast, or null"}
            """.trimIndent()
    }

    /** 답에서 확인을 통과한 숫자만 남긴다. 못 찾았거나 확인에 실패하면 null과 이유. 반환값을 그대로 저장한다. */
    fun parse(
        event: MacroEvent,
        answer: String,
    ): Pair<JsonNode?, String> {
        val json =
            runCatching { mapper.readTree(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)) }.getOrNull()
                ?: return null to "JSON 아님"
        if (!json.path("found").asBoolean(false)) return null to "아직 못 찾음"
        if (json.path("release_date").asText() != event.date.toString()) return null to "발표일 다름(${json.path("release_date").asText()})"
        if (event.type != MacroEventType.FOMC && json.path("reference_month").asText() != MacroActuals.referenceMonth(event).toString()) {
            return null to "월분 다름(${json.path("reference_month").asText()})"
        }
        val clean = mapper.createObjectNode()
        fields(event.type).forEach { field ->
            json.number(field.name)?.takeIf(field.valid)?.let { clean.put(field.name, it) }
            json.number("expected_${field.name}")?.takeIf(field.valid)?.let { clean.put("expected_${field.name}", it) }
        }
        if (event.type == MacroEventType.FOMC) {
            val lower = json.number("target_lower_pct")?.takeIf { it in 0.0..20.0 }
            val upper = json.number("target_upper_pct")?.takeIf { lower != null && abs(it - lower - 0.25) < 1e-6 }
            if (lower == null || upper == null) return null to "목표 범위 이상"
            clean.put("target_lower_pct", lower).put("target_upper_pct", upper)
        }
        // 첫 지표(헤드라인)는 꼭 있어야 한다
        if (!clean.has(fields(event.type).first().name)) return null to "값 없음·범위 밖"
        json.path("source_url").asText().takeIf { it.startsWith("https://") }?.let { clean.put("source_url", it.take(500)) }
        json.path(
            "expected_source_url",
        ).asText().takeIf { it.startsWith("https://") }?.let { clean.put("expected_source_url", it.take(500)) }
        return clean to "ok"
    }

    /** 저장한 숫자로 화면용 결과를 만든다. */
    fun view(
        event: MacroEvent,
        values: JsonNode,
    ): MacroActualView? {
        val month = MacroActuals.referenceMonth(event)

        fun v(name: String) = values.number(name)

        fun e(name: String) = values.number("expected_$name")
        return when (event.type) {
            MacroEventType.CPI ->
                v("cpi_mom_pct")?.let {
                    MacroResultFormat.cpi(month, it, v("core_cpi_mom_pct"), NOTE, e("cpi_mom_pct"), e("core_cpi_mom_pct"))
                }
            MacroEventType.PPI -> v("ppi_mom_pct")?.let { MacroResultFormat.ppi(month, it, NOTE, e("ppi_mom_pct")) }
            MacroEventType.NFP ->
                v("nfp_change_thousands")?.let {
                    MacroResultFormat.nfp(
                        month,
                        it,
                        v("unemployment_rate_pct"),
                        NOTE,
                        e("nfp_change_thousands"),
                        e("unemployment_rate_pct"),
                    )
                }
            MacroEventType.PCE ->
                v("pce_mom_pct")?.let {
                    MacroResultFormat.pce(month, it, v("core_pce_mom_pct"), NOTE, e("pce_mom_pct"), e("core_pce_mom_pct"))
                }
            MacroEventType.FOMC -> {
                val change = v("change_bp") ?: return null
                val lower = v("target_lower_pct") ?: return null
                val upper = v("target_upper_pct") ?: return null
                MacroResultFormat.fomc(YearMonth.from(event.date), change.toInt(), lower, upper, NOTE, e("change_bp")?.toInt())
            }
        }
    }

    fun read(text: String): JsonNode = mapper.readTree(text)

    private fun JsonNode.number(name: String): Double? = path(name).takeIf { it.isNumber }?.asDouble()

    private const val NOTE = " 속보: AI가 웹 검색으로 추출, 공식 확인 전."
}

/** 이 발표가 속보를 기다릴 때인가: 발표 [fromMinutes]분 뒤부터 [untilMinutes]분 뒤까지. */
fun MacroEvent.inFlashWindow(
    now: Instant,
    fromMinutes: Long,
    untilMinutes: Long,
): Boolean {
    val release = releaseAt().toInstant()
    return !now.isBefore(release.plusSeconds(fromMinutes * 60)) && now.isBefore(release.plusSeconds(untilMinutes * 60))
}
