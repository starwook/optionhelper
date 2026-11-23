package com.optionhelper.option

import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OptionChainTest {
    private val strikes = listOf(150.0, 152.5, 155.0, 157.5, 160.0, 162.5)
    private val chain =
        OptionChain(
            "NVDA",
            LocalDate.of(2026, 11, 20),
            // 165는 풋이 없어서 행사가 목록에서 빠져야 한다
            strikes.flatMap { listOf(OptionQuote(OptionType.CALL, it, 1.0, 1.2), OptionQuote(OptionType.PUT, it, 1.0, 1.2)) } +
                OptionQuote(OptionType.CALL, 165.0, 0.1, 0.2),
        )

    @Test
    fun `현재가와 가장 가까운 행사가가 ATM`() {
        assertThat(chain.atmStrike(158.13)).isEqualTo(157.5)
        assertThat(chain.atmStrike(158.9)).isEqualTo(160.0)
    }

    @Test
    fun `n칸 바깥 행사가`() {
        assertThat(chain.strikeAway(157.5, 1)).isEqualTo(160.0)
        assertThat(chain.strikeAway(157.5, -2)).isEqualTo(152.5)
        assertThat(chain.strikeAway(157.5, 3)).isNull()
    }

    @Test
    fun `중간가`() {
        assertThat(chain.quote(OptionType.CALL, 157.5)!!.mid).isEqualTo(1.1)
    }
}
