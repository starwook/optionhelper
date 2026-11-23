package com.optionhelper.macro

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** 직접 입력한 경제지표 일정(`macro-calendar.csv`). 1년에 한 번 갱신하므로 시작할 때 한 번만 읽는다. */
@Component
class MacroCalendarFile {
    val events: List<MacroEvent> =
        MacroCalendar.parseCsv(ClassPathResource(FILE).inputStream.bufferedReader().use { it.readText() })

    private companion object {
        const val FILE = "macro-calendar.csv"
    }
}
