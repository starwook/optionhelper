package com.optionhelper.paper

import com.optionhelper.dashboard.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class NewDrillResponse(val game: String)

/** 모의투자 가격 API. 계좌는 브라우저에 있고 여기서는 값만 계산한다. */
@RestController
@RequestMapping("/api/paper")
class PaperController(
    private val paperService: PaperService,
    private val drillService: DrillService,
) {
    @PostMapping("/marks")
    fun marks(
        @RequestBody request: MarksRequest,
    ) = paperService.marks(request.also { limited(it.legs) })

    @PostMapping("/daily-marks")
    fun dailyMarks(
        @RequestBody request: DailyMarksRequest,
    ) = paperService.dailyMarks(request.also { limited(it.legs) })

    @GetMapping("/drill/new")
    fun newDrill() = NewDrillResponse(drillService.newGame())

    @GetMapping("/drill/day")
    fun drillDay(
        @RequestParam game: String,
        @RequestParam day: Int,
        @RequestParam(required = false) expiry: Int?,
    ) = drillService.day(game, day, expiry)

    @PostMapping("/drill/marks")
    fun drillMarks(
        @RequestBody request: DrillMarksRequest,
    ) = drillService.marks(request.also { limited(it.legs) })

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "요청이 올바르지 않습니다"))

    /** 공개 API라 한 번에 계산하는 다리 수를 묶어 둔다. */
    private fun limited(legs: List<*>) = require(legs.size <= MAX_LEGS) { "한 번에 ${MAX_LEGS}개 다리까지만 계산합니다" }

    private companion object {
        const val MAX_LEGS = 200
    }
}
