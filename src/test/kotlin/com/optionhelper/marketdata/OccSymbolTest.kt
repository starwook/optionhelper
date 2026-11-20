package com.optionhelper.marketdata

import com.optionhelper.pricing.OptionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OccSymbolTest {
    @Test
    fun `OCC 심볼을 나눈다`() {
        val occ = OccSymbol.parse("NVDA261120C00157500")!!
        assertThat(occ.root).isEqualTo("NVDA")
        assertThat(occ.expiry).isEqualTo(LocalDate.of(2026, 11, 20))
        assertThat(occ.type).isEqualTo(OptionType.CALL)
        assertThat(occ.strike).isEqualTo(157.5)
    }

    @Test
    fun `한 글자 루트와 풋`() {
        val occ = OccSymbol.parse("F270115P00012000")!!
        assertThat(occ.root).isEqualTo("F")
        assertThat(occ.type).isEqualTo(OptionType.PUT)
        assertThat(occ.strike).isEqualTo(12.0)
    }

    @Test
    fun `형식이 틀리면 null`() {
        assertThat(OccSymbol.parse("NVDA")).isNull()
        assertThat(OccSymbol.parse("NVDA261120X00157500")).isNull()
    }
}
