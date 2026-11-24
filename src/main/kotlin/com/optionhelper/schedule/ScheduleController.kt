package com.optionhelper.schedule

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ScheduleController(private val scheduleService: ScheduleService) {
    @GetMapping("/schedule")
    fun schedule(): ScheduleResponse = scheduleService.schedule()
}
