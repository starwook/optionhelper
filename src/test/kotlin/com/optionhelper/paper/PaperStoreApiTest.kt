package com.optionhelper.paper

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

/** 모의투자 계좌 저장 API: 저장한 값을 그대로 돌려주고, 정해진 키만 받는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperStoreApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private fun save(
        key: String,
        json: String,
    ) = mockMvc.put("/api/paper/store/$key") {
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    @Test
    fun `저장하고 다시 받고 지운다`() {
        save("paper.account", """{"cash":9500.5,"positions":[]}""").andExpect { status { isOk() } }
        save("paper.account", """{"cash":9000,"positions":[]}""").andExpect { status { isOk() } }
        mockMvc.get("/api/paper/store").andExpect {
            status { isOk() }
            jsonPath("$['paper.account'].cash") { value(9000) }
        }

        save("paper.account", "null").andExpect { status { isOk() } }
        mockMvc.get("/api/paper/store").andExpect { jsonPath("$['paper.account']") { doesNotExist() } }
    }

    @Test
    fun `정해진 키가 아니면 거절한다`() {
        save("other", """{"a":1}""").andExpect { status { isBadRequest() } }
    }
}
