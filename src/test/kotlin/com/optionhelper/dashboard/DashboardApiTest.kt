package com.optionhelper.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 가짜 데이터 소스 + H2로 전체 흐름(소스 → 계산 → JSON)을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `대시보드 JSON - 스트래들, 예상 변동폭, 체인, 전략, 계절성`() {
        val body = mockMvc.get("/api/dashboard?symbol=nvda").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val d = objectMapper.readTree(body)

        assertThat(d["symbol"].asText()).isEqualTo("NVDA")
        assertThat(d["feed"]["kind"].asText()).isEqualTo("APPROXIMATE")
        val atm = d["atm"]
        val straddle = atm["call"]["mid"].asDouble() + atm["put"]["mid"].asDouble()
        assertThat(d["straddle"].asDouble()).isCloseTo(straddle, within(1e-9))
        assertThat(d["expectedMove"]["pct"].asDouble()).isCloseTo(straddle / d["spot"].asDouble(), within(1e-9))
        assertThat(d["chain"].size()).isGreaterThan(5)
        assertThat(d["strategies"].map { it["kind"].asText() }).contains("LONG_STRADDLE", "LONG_STRANGLE", "SHORT_PUT")
        assertThat(d["seasonality"].size()).isEqualTo(12)
        assertThat(d["earnings"]["next"].isNull).isFalse()
        assertThat(d["macro"]["coversUntil"].asText()).isEqualTo("2027-12-22")
        // 전일 종가는 일봉 마지막 종가, 현재가 체결 시각도 같이 온다
        assertThat(d["previousClose"].isNumber).isTrue()
        assertThat(d["spotAsOfKst"].asText()).endsWith("KST")
    }

    @Test
    fun `관심 종목 목록`() {
        mockMvc.get("/api/watchlist").andExpect {
            status { isOk() }
            jsonPath("$.default") { value("NVDA") }
        }
    }

    @Test
    fun `고정 목록 밖 종목은 400`() {
        mockMvc.get("/api/dashboard?symbol=TSLA").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/stock-chart?symbol=TSLA").andExpect { status { isBadRequest() } }
    }
}
