package com.optionhelper.quote

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 현재가 SSE: 구독하면 다음 주기에 현재가가 이벤트로 온다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuoteStreamApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var stream: QuoteStream

    @Test
    fun `구독 후 현재가 이벤트`() {
        val result = mockMvc.get("/api/quote-stream?symbol=nvda").andExpect { request { asyncStarted() } }.andReturn()
        stream.push()
        val body = result.response.getContentAsString(Charsets.UTF_8)
        assertThat(body).startsWith("data:").contains("\"symbol\":\"NVDA\"").contains("\"price\":")
    }

    @Test
    fun `고정 목록 밖 종목은 400`() {
        mockMvc.get("/api/quote-stream?symbol=TSLA").andExpect { status { isBadRequest() } }
    }
}
