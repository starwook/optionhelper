package com.optionhelper.earnings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class EarningsServiceTest {
    @Autowired
    lateinit var earnings: EarningsService

    @Test
    fun `한 번 받은 뒤 일주일 안에는 다시 부르지 않는다`() {
        assertThat(earnings.refreshIfStale("ARM")).isTrue()
        assertThat(earnings.events("ARM")).isNotEmpty()
        assertThat(earnings.refreshIfStale("ARM")).isFalse()
    }
}
