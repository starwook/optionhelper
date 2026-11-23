package com.optionhelper.review

import com.optionhelper.dashboard.ErrorResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/review")
class ReviewController(private val reviewService: ReviewService) {
    @GetMapping("/snapshots")
    fun snapshots(
        @RequestParam(defaultValue = "NVDA") symbol: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = reviewService.snapshots(symbol, date)

    @GetMapping("/snapshots/{id}")
    fun review(
        @PathVariable id: Long,
    ) = reviewService.review(id)

    @ExceptionHandler(SnapshotNotFoundException::class)
    fun notFound(e: SnapshotNotFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "없음"))
}
