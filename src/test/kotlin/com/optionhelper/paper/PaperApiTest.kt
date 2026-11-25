package com.optionhelper.paper

import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate

/** 가짜 데이터로 모의투자 API(실시간 평가·일별 평가·빨리감기)가 끝까지 도는지 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun post(
        url: String,
        body: Any,
    ) = mockMvc.post(url) {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(body)
    }

    private fun json(
        url: String,
        body: Any,
    ) = objectMapper.readTree(post(url, body).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `실시간 평가 - 만기 전은 호가, 지난 만기는 정산`() {
        val today = LocalDate.now(MarketClock.NEW_YORK)
        val future = today.plusDays(30)
        var past = today.minusDays(10)
        while (!MarketClock.isTradingDay(past)) past = past.minusDays(1)
        val body =
            json(
                "/api/paper/marks",
                MarksRequest(
                    listOf(
                        LegRequest("NVDA", OptionType.CALL, 151.3, future),
                        LegRequest("NVDA", OptionType.PUT, 1000.0, past),
                    ),
                ),
            )
        val marks = body["marks"]
        assertThat(marks[0]["settled"].asBoolean()).isFalse()
        assertThat(marks[0]["ask"].asDouble()).isGreaterThan(marks[0]["bid"].asDouble())
        assertThat(marks[1]["settled"].asBoolean()).isTrue()
        assertThat(marks[1]["mid"].asDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `일별 평가 - 들고 있던 날만 값이 나온다`() {
        val today = LocalDate.now(MarketClock.NEW_YORK)
        val from = today.minusDays(20)
        val to = today.minusDays(10)
        val body =
            json(
                "/api/paper/daily-marks",
                DailyMarksRequest(
                    from,
                    listOf(HistoryLegRequest("NVDA", OptionType.CALL, 150.0, today.plusDays(30), from, to)),
                ),
            )
        val days = body["days"].map { LocalDate.parse(it["date"].asText()) }
        assertThat(days).isNotEmpty().allMatch { MarketClock.isTradingDay(it) && it.isBefore(today) }
        val marks = body["legs"][0].map { LocalDate.parse(it["date"].asText()) }
        assertThat(marks).isNotEmpty().allMatch { !it.isBefore(from) && !it.isAfter(to) }
    }

    @Test
    fun `빨리감기 - 새 게임, 하루씩 넘기기, 끝나면 공개`() {
        val game =
            objectMapper.readTree(
                mockMvc.get("/api/paper/drill/new").andExpect {
                    status { isOk() }
                }.andReturn().response.contentAsString,
            )["game"].asText()
        val day0 = objectMapper.readTree(mockMvc.get("/api/paper/drill/day?game=$game&day=0").andReturn().response.contentAsString)
        assertThat(day0["spot"].asDouble()).isEqualTo(100.0)
        assertThat(day0["reveal"].isNull).isTrue()
        assertThat(day0["strategies"].size()).isGreaterThan(0)
        val expiry = day0["expiry"].asInt()

        val marks =
            json(
                "/api/paper/drill/marks",
                DrillMarksRequest(game, expiry, listOf(DrillLeg(OptionType.CALL, 100.0, expiry))),
            )
        assertThat(marks[0]["settled"].asBoolean()).isTrue()

        val end =
            objectMapper.readTree(
                mockMvc.get("/api/paper/drill/day?game=$game&day=${DrillService.TOTAL_DAYS}").andReturn().response.contentAsString,
            )
        assertThat(end["finished"].asBoolean()).isTrue()
        assertThat(end["reveal"]["symbol"].asText()).isNotBlank()
    }

    @Test
    fun `잘못된 게임 ID는 400`() {
        mockMvc.get("/api/paper/drill/day?game=xx&day=0").andExpect { status { isBadRequest() } }
    }
}
