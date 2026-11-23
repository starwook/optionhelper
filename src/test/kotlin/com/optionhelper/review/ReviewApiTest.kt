package com.optionhelper.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.dashboard.DashboardService
import com.optionhelper.option.OptionSnapshot
import com.optionhelper.option.OptionSnapshotRepository
import com.optionhelper.pricing.MarketClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.ZoneOffset

/** 가짜 데이터로 만든 체인을 과거 만기의 스냅샷으로 저장한 뒤, 복기 API가 그때 전략과 만기 결과를 돌려주는지 본다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var dashboardService: DashboardService

    @Autowired
    lateinit var repository: OptionSnapshotRepository

    @Test
    fun `과거 스냅샷 복기 - 전략과 만기 종가 기준 결과`() {
        val d = dashboardService.dashboard("NVDA", null)
        // 2주 전 거래일을 만기로, 그 전날 찍은 스냅샷인 척 저장
        var expiry = LocalDate.now(MarketClock.NEW_YORK).minusDays(14)
        while (!MarketClock.isTradingDay(expiry)) expiry = expiry.minusDays(1)
        val capturedAt = expiry.minusDays(1).atTime(15, 0).toInstant(ZoneOffset.UTC)
        val saved =
            repository.save(
                OptionSnapshot(
                    "NVDA",
                    expiry,
                    capturedAt,
                    d.spot,
                    d.atm?.strike,
                    d.straddle,
                    d.volatility.atmIvCalc,
                    objectMapper.writeValueAsString(d.chain),
                ),
            )

        val list = objectMapper.readTree(get("/api/review/snapshots?symbol=NVDA&date=${expiry.minusDays(1)}"))
        assertThat(list.map { it["id"].asLong() }).contains(saved.id)

        val review = objectMapper.readTree(get("/api/review/snapshots/${saved.id}"))
        assertThat(review["strategies"].size()).isGreaterThan(3)
        val outcome = review["outcome"]
        assertThat(outcome["settled"].asBoolean()).isTrue()
        val price = outcome["price"].asDouble()
        assertThat(outcome["movePct"].asDouble()).isCloseTo(price / d.spot - 1, within(1e-9))
        // 롱 스트래들 결과 = (|만기가 − 행사가| − 스트래들 − 수수료/주) × 100
        val straddle = outcome["results"].first { it["kind"].asText() == "LONG_STRADDLE" }["profit"].asDouble()
        val expected = (kotlin.math.abs(price - d.atm!!.strike) - d.straddle!! - 4 * 1.99 / 100) * 100
        assertThat(straddle).isCloseTo(expected, within(1e-6))
    }

    @Test
    fun `없는 스냅샷은 404`() {
        mockMvc.get("/api/review/snapshots/999999").andExpect { status { isNotFound() } }
    }

    private fun get(url: String) = mockMvc.get(url).andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8)
}
