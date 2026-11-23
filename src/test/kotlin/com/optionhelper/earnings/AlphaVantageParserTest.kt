package com.optionhelper.earnings

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AlphaVantageParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `캘린더 CSV - reportDate 열을 이름으로 찾고 시점 열이 있으면 읽는다`() {
        val csv =
            "symbol,name,reportDate,fiscalDateEnding,estimate,currency,timeOfTheDay\n" +
                "NVDA,NVIDIA,2027-02-24,2027-01-31,1.2,USD,post-market\n"
        val event = AlphaVantageParser.calendarCsv("NVDA", csv).single()
        assertThat(event.date).isEqualTo(LocalDate.of(2027, 2, 24))
        assertThat(event.timing).isEqualTo(EarningsTiming.AFTER_CLOSE)
    }

    @Test
    fun `캘린더 CSV - 시점 열이 없으면 UNKNOWN`() {
        val csv = "symbol,name,reportDate,fiscalDateEnding,estimate,currency\nNVDA,NVIDIA,2027-02-24,2027-01-31,1.2,USD"
        assertThat(AlphaVantageParser.calendarCsv("NVDA", csv).single().timing).isEqualTo(EarningsTiming.UNKNOWN)
    }

    @Test
    fun `캘린더 CSV - 회사명에 쉼표가 있어 따옴표로 감싸도 날짜 열이 밀리지 않는다`() {
        val csv =
            "symbol,name,reportDate,fiscalDateEnding,estimate,currency,timeOfTheDay\r\n" +
                "BRK,\"Berkshire Hathaway, Inc.\",2027-02-27,2026-12-31,,USD,pre-market\r\n"
        val event = AlphaVantageParser.calendarCsv("BRK", csv).single()
        assertThat(event.date).isEqualTo(LocalDate.of(2027, 2, 27))
        assertThat(event.timing).isEqualTo(EarningsTiming.BEFORE_OPEN)
    }

    @Test
    fun `캘린더 CSV - 예정이 없으면 헤더만 오고 빈 목록`() {
        val csv = "symbol,name,reportDate,fiscalDateEnding,estimate,currency,timeOfTheDay\r\n"
        assertThat(AlphaVantageParser.calendarCsv("NVDA", csv)).isEmpty()
    }

    @Test
    fun `캘린더 CSV - 깨진 응답(한도 초과 등)은 빈 목록이 아니라 예외`() {
        val broken = "symbol,name,reportDate,fiscalDateEnding,estimate,currency,timeOfTheDay\r\nI,n,f,o,r,m,a,t,i,o,n\r\n"
        assertThatThrownBy { AlphaVantageParser.calendarCsv("NVDA", broken) }.hasMessageContaining("Information")
        assertThatThrownBy { AlphaVantageParser.calendarCsv("NVDA", """{"Information": "rate limit"}""") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `과거 실적 JSON`() {
        val json =
            """
            {"quarterlyEarnings": [{"reportedDate": "2026-08-26", "reportTime": "post-market"}, {"reportedDate": "2026-05-28"}]}
            """.trimIndent()
        val events = AlphaVantageParser.historyJson("NVDA", mapper.readTree(json))
        assertThat(events.map { it.timing }).containsExactly(EarningsTiming.AFTER_CLOSE, EarningsTiming.UNKNOWN)
    }

    @Test
    fun `한도 초과 메시지를 잡는다`() {
        assertThat(AlphaVantageParser.errorMessage(mapper.readTree("""{"Information": "rate limit"}"""))).isEqualTo("rate limit")
        assertThat(AlphaVantageParser.errorMessage(mapper.readTree("""{"quarterlyEarnings": []}"""))).isNull()
    }
}
