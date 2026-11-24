package com.optionhelper.stockchart

import com.optionhelper.dashboard.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class StockChartController(private val stockChartService: StockChartService) {
    @GetMapping("/api/stock-chart")
    fun chart(
        @RequestParam(defaultValue = "NVDA") symbol: String,
        @RequestParam(defaultValue = "DAY") range: ChartRange,
    ): StockChartResponse = stockChartService.chart(symbol, range)

    @ExceptionHandler(StockChartUnavailableException::class)
    fun unavailable(e: StockChartUnavailableException) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse(e.message ?: "주가 차트를 가져오지 못했습니다"))
}
