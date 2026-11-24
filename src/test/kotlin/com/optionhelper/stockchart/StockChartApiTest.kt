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
    fun `5분봉 - 정규장 봉과 전일 종가`() {
        val d = chart("symbol=nvda&interval=MIN_5")
        assertThat(d["symbol"].asText()).isEqualTo("NVDA")
        assertThat(d["intraday"].asBoolean()).isTrue()
        assertThat(d["previousClose"].isNumber).isTrue()
        val c = d["candles"][0]
        assertThat(c["high"].asDouble()).isGreaterThanOrEqualTo(c["low"].asDouble())
    }

    @Test
    fun `월봉 - 일봉을 달마다 묶는다`() {
        val d = chart("symbol=NVDA&interval=MONTH")
        assertThat(d["intraday"].asBoolean()).isFalse()
        assertThat(d["previousClose"].isNull).isTrue()
        // 가짜 일봉은 12년치라 월봉은 140개 넘게
        assertThat(d["candles"].size()).isGreaterThan(140)
    }
}
