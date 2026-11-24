package com.optionhelper.stockchart

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 가짜 데이터 소스로 상단 주가 차트 JSON을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockChartApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun chart(query: String) =
        objectMapper.readTree(mockMvc.get("/api/stock-chart?$query").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `1일 - 마지막 정규장 5분봉과 전일 종가`() {
        val d = chart("symbol=nvda&range=DAY")
        assertThat(d["symbol"].asText()).isEqualTo("NVDA")
        assertThat(d["sessionDate"].isNull).isFalse()
        assertThat(d["previousClose"].isNumber).isTrue()
        assertThat(d["points"].size()).isGreaterThan(0)
    }

    @Test
    fun `1년 - 일봉 종가 252개`() {
        val d = chart("symbol=NVDA&range=YEAR")
        assertThat(d["points"].size()).isEqualTo(252)
        assertThat(d["previousClose"].isNull).isTrue()
    }
}
