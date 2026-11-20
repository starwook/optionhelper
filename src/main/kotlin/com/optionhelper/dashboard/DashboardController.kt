package com.optionhelper.dashboard

import com.optionhelper.watchlist.SemiconductorWatchlist
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class WatchlistResponse(val symbols: List<String>, val default: String)

data class ErrorResponse(val error: String)

@RestController
@RequestMapping("/api")
class DashboardController(private val dashboardService: DashboardService) {
    @GetMapping("/dashboard")
    fun dashboard(
        @RequestParam(defaultValue = "NVDA") symbol: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) expiry: LocalDate?,
    ): DashboardResponse = dashboardService.dashboard(symbol, expiry)

    @GetMapping("/watchlist")
    fun watchlist() = WatchlistResponse(SemiconductorWatchlist.SYMBOLS, SemiconductorWatchlist.DEFAULT)

    @ExceptionHandler(DashboardUnavailableException::class)
    fun unavailable(e: DashboardUnavailableException) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(e.message ?: "데이터를 가져오지 못했습니다"))
}
