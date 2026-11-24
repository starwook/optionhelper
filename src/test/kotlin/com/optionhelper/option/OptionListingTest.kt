package com.optionhelper.option

import com.optionhelper.watchlist.WatchedSymbol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** 2026-11-25(수) 실측한 실제 상장(docs/데이터소스-조사.md 11-2)과 같은 만기·행사가가 나오는지. */
class OptionListingTest {
    private val day = LocalDate.of(2026, 11, 25)

    @Test
    fun `월수금 종목 - 6주 주간 만기, 휴장일은 앞당김, 뒤로 월물`() {
        val exp = WatchedSymbol.NVDA.listing.expirations(day).map { it.toString() }
        assertThat(exp).startsWith(
            "2026-11-25",
            "2026-11-27",
            "2026-11-30",
            "2026-12-02",
            "2026-12-04",
            "2026-12-07",
            "2026-12-09",
            "2026-12-11",
        )
        // 12-25(성탄절) 금요일 만기는 12-24로, 1-1은 12-31로
        assertThat(exp).contains("2026-12-18", "2026-12-24", "2026-12-31").doesNotContain("2026-12-25", "2027-01-01")
        assertThat(exp).containsSubsequence("2026-12-31", "2027-01-15", "2027-02-19", "2027-03-19")
    }

    @Test
    fun `금요일 종목 - 금요일만`() {
        val exp = WatchedSymbol.TSM.listing.expirations(day).map { it.toString() }
        assertThat(exp).startsWith("2026-11-27", "2026-12-04", "2026-12-11", "2026-12-18", "2026-12-24", "2026-12-31", "2027-01-15")
    }

    @Test
    fun `행사가 간격 - 150달러 경계와 월물`() {
        val nvda = WatchedSymbol.NVDA.listing
        assertThat(nvda.strikeStep(LocalDate.of(2026, 11, 27), day).strikes(147.0, 156.0))
            .containsExactly(147.0, 148.0, 149.0, 150.0, 152.5, 155.0)
        assertThat(nvda.strikeStep(LocalDate.of(2027, 1, 15), day).strikes(130.0, 150.0))
            .containsExactly(130.0, 135.0, 140.0, 145.0, 150.0)
        assertThat(WatchedSymbol.SNDK.listing.weeklyStep.strikes(71.0, 73.0)).containsExactly(71.25, 71.875, 72.5)
    }
}
