package com.optionhelper.volatility

import com.optionhelper.watchlist.SemiconductorWatchlist
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/iv")
class IvHistoryController(private val ivHistoryService: IvHistoryService) {
    @GetMapping("/history")
    fun history(
        @RequestParam(defaultValue = "NVDA") symbol: String,
        @RequestParam(defaultValue = "365") days: Long,
    ) = ivHistoryService.history(symbol, days.coerceIn(30, 3650))

    /** 관심 종목 전체의 지금 IV 위치 비교. */
    @GetMapping("/summary")
    fun summary() = ivHistoryService.summary(SemiconductorWatchlist.SYMBOLS)
}
