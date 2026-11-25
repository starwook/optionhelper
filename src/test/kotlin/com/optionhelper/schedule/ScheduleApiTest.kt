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
    fun `일정 JSON - 경제지표, 관심 종목 실적(레버리지 ETF 제외), 나스닥 월별 수익률`() {
        val body = mockMvc.get("/api/schedule").andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8)
        val s = objectMapper.readTree(body)

        assertThat(s["macro"].size()).isGreaterThan(0)
        assertThat(s["macro"][0]["timeKst"].asText()).matches("\\d\\d:\\d\\d")
        // 한국 시간 기준: 미국 2026-12-09 14:00 FOMC는 12/10 04:00 칸
        val fomc = s["macro"].first { it["type"].asText() == "FOMC" && it["date"].asText() == "2026-12-10" }
        assertThat(fomc["timeKst"].asText()).isEqualTo("04:00")
        // 장 마감 후 실적은 기준 시각(한국 아침)이 있다
        assertThat(
            s["earnings"].filter {
                it["timing"].asText() == "AFTER_CLOSE"
            }.map { it["timeKst"].asText() },
        ).allMatch { it == "06:00" || it == "05:00" }
        assertThat(s["macroCoversUntil"].asText()).isEqualTo("2027-12-22")
        // 발표 전 일정에는 결과가 붙지 않는다
        assertThat(fomc["actual"].isNull).isTrue()
        assertThat(s["macroActualSource"].asText()).isEqualTo("가짜 데이터")
        // 테스트에는 Claude 키가 없어 속보 기능이 꺼져 있다
        assertThat(s["flashModel"].isNull).isTrue()
        assertThat(s["fastPoll"].asBoolean()).isFalse()
        val symbols = s["earnings"].map { it["symbol"].asText() }.toSet()
        // 테스트끼리 DB 캐시를 같이 써서 특정 종목은 시점에 따라 범위 밖일 수 있다. 여러 종목이 모이는지만 본다
        assertThat(symbols.size).isGreaterThan(3)
        assertThat(symbols).doesNotContain("SOXL")
        assertThat(s["nasdaq"]["symbol"].asText()).isEqualTo("XYZ100")
        assertThat(s["nasdaq"]["months"].size()).isEqualTo(12)
    }
}
