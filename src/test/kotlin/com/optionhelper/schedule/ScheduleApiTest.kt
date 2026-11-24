package com.optionhelper.schedule

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `일정 JSON - 경제지표, 관심 종목 실적(ETF 제외), 나스닥 월별 수익률`() {
        val body = mockMvc.get("/api/schedule").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val s = objectMapper.readTree(body)

        assertThat(s["macro"].size()).isGreaterThan(0)
        assertThat(s["macro"][0]["timeKst"].asText()).matches("(익일 )?\\d\\d:\\d\\d")
        assertThat(s["macroCoversUntil"].asText()).isEqualTo("2027-12-22")
        val symbols = s["earnings"].map { it["symbol"].asText() }.toSet()
        // 테스트끼리 DB 캐시를 같이 써서 특정 종목은 시점에 따라 범위 밖일 수 있다. 여러 종목이 모이는지만 본다
        assertThat(symbols.size).isGreaterThan(3)
        assertThat(symbols).doesNotContain("SOXX")
        assertThat(s["nasdaq"]["symbol"].asText()).isEqualTo("QQQ")
        assertThat(s["nasdaq"]["months"].size()).isEqualTo(12)
    }
}
