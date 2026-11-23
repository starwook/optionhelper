package com.optionhelper.earnings

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate

/**
 * Alpha Vantage 응답 → 실적 이벤트. 필드 이름은 docs/외부API-명세.md 2장(실측).
 * 시점: EARNINGS는 reportTime, EARNINGS_CALENDAR는 timeOfTheDay(pre-market/post-market, 빈 값 가능).
 */
object AlphaVantageParser {
    /**
     * EARNINGS_CALENDAR(CSV, 예정 실적). 첫 줄은 헤더, 예정이 없으면 헤더만 온다.
     * 한도 초과 등 오류도 HTTP 200으로 오고 CSV 모양이 깨지므로, 읽을 수 없으면 조용히 비우지 않고 예외를 던진다.
     */
    fun calendarCsv(
        symbol: String,
        csv: String,
    ): List<EarningsEvent> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = splitCsvLine(lines.first()).map { it.trim() }
        val dateIndex = header.indexOfFirst { it.equals("reportDate", ignoreCase = true) }
        check(dateIndex >= 0) { "예정 실적 응답을 읽을 수 없습니다: ${csv.take(ERROR_SNIPPET)}" }
        val timeIndex = header.indexOfFirst { it.contains("time", ignoreCase = true) }
        val rows = lines.drop(1).map { splitCsvLine(it) }
        val events =
            rows.mapNotNull { cols ->
                val raw = cols.getOrNull(dateIndex)?.trim()
                val date = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                EarningsEvent(symbol, date, timing(cols.getOrNull(timeIndex)))
            }
        check(rows.isEmpty() || events.isNotEmpty()) {
            "예정 실적 응답을 읽을 수 없습니다: ${lines.drop(1).joinToString(" ").replace(",", "").take(ERROR_SNIPPET)}"
        }
        return events
    }

    /** 따옴표로 감싼 칸(회사명 안의 쉼표 등)을 한 칸으로 자른다. "" 는 따옴표 하나. */
    internal fun splitCsvLine(line: String): List<String> {
        val cols = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && line.getOrNull(i + 1) == '"' -> cell.append('"').also { i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> cols += cell.toString().also { cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        cols += cell.toString()
        return cols
    }

    private const val ERROR_SNIPPET = 120

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
