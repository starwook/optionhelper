package com.optionhelper.volatility

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

/** 가짜 소스의 일별 IV → DB 저장 → IV Rank·HV 계산 → JSON, 그리고 보유 옵션 시나리오. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IvHistoryApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var repository: DailyIvRepository

    private fun json(url: String) =
        objectMapper.readTree(
            mockMvc.get(url).andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

    @Test
    fun `1년 IV 기록과 지금 위치`() {
        val h = json("/api/iv/history?symbol=amd")
        assertThat(h["symbol"].asText()).isEqualTo("AMD")
        assertThat(h["basis"].asText()).isEqualTo("THIRTY_DAY")
        assertThat(h["points"].size()).isGreaterThan(200)
        assertThat(h["rank"].asDouble()).isBetween(0.0, 1.0)
        assertThat(h["percentile"].asDouble()).isBetween(0.0, 1.0)
        assertThat(h["hv20"].asDouble()).isPositive()
        assertThat(h["points"].last()["hv20"].isNull).isFalse()

        // 두 번째 조회는 소스를 다시 부르지 않고 저장된 값을 쓴다(중복 저장 없음)
        val stored = repository.count()
        json("/api/iv/history?symbol=AMD")
        assertThat(repository.count()).isEqualTo(stored)
    }

    @Test
    fun `관심 종목 IV 비교`() {
        val rows = json("/api/iv/summary")
        assertThat(rows.size()).isEqualTo(10)
        assertThat(rows.all { it["error"].isNull }).isTrue()
    }

    @Test
    fun `보유 옵션 시나리오 - 직접 넣은 주가, 중앙값·최저·±10%p 열`() {
        val expiry = LocalDate.now().plusDays(40)
        val s = json("/api/scenario?symbol=NVDA&type=CALL&strike=185&expiry=$expiry&price=6.35&spot=168.27")
        assertThat(s["spotSource"].asText()).isEqualTo("직접 입력")
        assertThat(s["scenario"]["levels"].map { it["label"].asText() }).hasSize(5).contains("지금", "−10%p", "+10%p")
        assertThat(s["scenario"]["rows"].last()["prices"][0].asDouble()).isEqualTo(0.0)
    }

    @Test
    fun `풀 수 없는 가격이면 422`() {
        mockMvc.get("/api/scenario?symbol=NVDA&type=CALL&strike=100&expiry=${LocalDate.now().plusDays(30)}&price=1&spot=168")
            .andExpect { status { isUnprocessableEntity() } }
    }
}
