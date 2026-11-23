package com.optionhelper.earnings

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * Alpha Vantage 응답 → 실적 이벤트.
 * 공식 문서에 열·키 이름이 나와 있지 않아(docs/데이터소스-조사.md) 이름으로 찾고, 없으면 시점은 UNKNOWN으로 둔다.
 * 키를 받으면 실제 응답으로 확인해야 한다.
 */
object AlphaVantageParser {
    /** EARNINGS_CALENDAR(CSV, 예정 실적). 첫 줄은 헤더. */
    fun calendarCsv(
        symbol: String,
        csv: String,
    ): List<EarningsEvent> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split(',').map { it.trim() }
        val dateIndex = header.indexOfFirst { it.equals("reportDate", ignoreCase = true) }
        if (dateIndex < 0) return emptyList()
        val timeIndex = header.indexOfFirst { it.contains("time", ignoreCase = true) }
        return lines.drop(1).mapNotNull { line ->
            val cols = line.split(',')
            val date = cols.getOrNull(dateIndex)?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            EarningsEvent(symbol, date, timing(cols.getOrNull(timeIndex)))
        }
    }

    /** EARNINGS(JSON, 과거 실적) quarterlyEarnings[].reportedDate, reportTime(있으면). */
    fun historyJson(
        symbol: String,
        root: JsonNode,
    ): List<EarningsEvent> =
        root.path("quarterlyEarnings").mapNotNull { item ->
            val date = runCatching { LocalDate.parse(item.path("reportedDate").asText()) }.getOrNull() ?: return@mapNotNull null
            EarningsEvent(symbol, date, timing(item.path("reportTime").asText(null)))
        }

    /** 한도 초과·키 오류는 HTTP 200에 메시지만 담겨 온다. */
    fun errorMessage(root: JsonNode): String? =
        listOf("Note", "Information", "Error Message").firstNotNullOfOrNull { root.path(it).asText(null) }

    private fun timing(raw: String?): EarningsTiming {
        val value = raw?.trim()?.lowercase() ?: return EarningsTiming.UNKNOWN
        return when {
            value.startsWith("pre") || value == "bmo" -> EarningsTiming.BEFORE_OPEN
            value.startsWith("post") || value == "amc" -> EarningsTiming.AFTER_CLOSE
            else -> EarningsTiming.UNKNOWN
        }
    }
}
