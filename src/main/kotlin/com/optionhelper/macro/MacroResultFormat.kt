package com.optionhelper.macro

import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/** 지표 값의 단위. 화면 글자와 "예상과 같은가" 판정을 정한다. */
enum class MetricUnit {
    // 전월비 같은 변화율(+0.3%)
    PCT_CHANGE,

    // 실업률 같은 수준(4.1%)
    PCT,

    // 고용 증감(+165천 명)
    THOUSANDS,

    // 금리 변화(0.25%p 인하 / 동결)
    BP,
    ;

    fun format(value: Double): String =
        when (this) {
            PCT_CHANGE -> "%+.1f%%".format(value)
            PCT -> "%.1f%%".format(value)
            THOUSANDS -> "${if (value >= 0) "+" else ""}${value.roundToLong()}천 명"
            BP ->
                when {
                    value == 0.0 -> "동결"
                    value > 0 -> "%.2f%%p 인상".format(value / 100)
                    else -> "%.2f%%p 인하".format(-value / 100)
                }
        }
}

/** 지표 하나의 결과. expected는 언론 보도 기준 예상치(없을 수 있음). */
data class MacroMetric(val label: String, val unit: MetricUnit, val actual: Double, val expected: Double?) {
    val actualText: String get() = unit.format(actual)
    val expectedText: String? get() = expected?.let { unit.format(it) }

    /** 화면에 보이는 자릿수 기준으로 같으면 INLINE(부합), 크면 ABOVE(상회), 작으면 BELOW(하회). */
    val verdict: String?
        get() {
            val e = expected ?: return null
            return when {
                unit.format(actual) == unit.format(e) || abs(actual - e) < 1e-9 -> "INLINE"
                actual > e -> "ABOVE"
                else -> "BELOW"
            }
        }
}

/** 화면용 발표 결과. note는 계산 방식·속보 여부·FOMC 목표 범위 같은 덧붙임. */
data class MacroActualView(val referenceMonth: YearMonth, val metrics: List<MacroMetric>, val note: String) {
    /** 달력 칸용 짧은 글. 공식 값과 속보가 같은 결과인지도 이것으로 본다. */
    val summary: String get() = metrics.joinToString(" · ") { it.actualText }

    /** 예상치가 없는 지표에 [other]의 같은 지표 예상치를 채운다(공식 값에 속보의 예상치를 붙일 때). */
    fun withExpectedFrom(other: MacroActualView?): MacroActualView {
        if (other == null) return this
        val expected = other.metrics.associate { it.label to it.expected }
        return copy(metrics = metrics.map { if (it.expected == null) it.copy(expected = expected[it.label]) else it })
    }
}

/**
 * 지표별 결과 묶음. 공식 값(BLS)과 속보(Claude)가 같은 함수를 써서 지표 이름이 같다.
 * 예상치는 속보에서만 온다.
 */
object MacroResultFormat {
    fun cpi(
        month: YearMonth,
        all: Double,
        core: Double?,
        note: String,
        allExpected: Double? = null,
        coreExpected: Double? = null,
    ) = MacroActualView(
        month,
        listOfNotNull(
            MacroMetric("CPI 전월비", MetricUnit.PCT_CHANGE, all, allExpected),
            core?.let { MacroMetric("근원 CPI 전월비", MetricUnit.PCT_CHANGE, it, coreExpected) },
        ),
        note,
    )

    fun ppi(
        month: YearMonth,
        ppi: Double,
        note: String,
        expected: Double? = null,
    ) = MacroActualView(month, listOf(MacroMetric("PPI 전월비", MetricUnit.PCT_CHANGE, ppi, expected)), note)

    fun nfp(
        month: YearMonth,
        jobsThousands: Double,
        unemploymentRate: Double?,
        note: String,
        jobsExpected: Double? = null,
        rateExpected: Double? = null,
    ) = MacroActualView(
        month,
        listOfNotNull(
            MacroMetric("비농업 고용", MetricUnit.THOUSANDS, jobsThousands, jobsExpected),
            unemploymentRate?.let { MacroMetric("실업률", MetricUnit.PCT, it, rateExpected) },
        ),
        note,
    )

    fun pce(
        month: YearMonth,
        all: Double,
        core: Double?,
        note: String,
        allExpected: Double? = null,
        coreExpected: Double? = null,
    ) = MacroActualView(
        month,
        listOfNotNull(
            MacroMetric("PCE 물가 전월비", MetricUnit.PCT_CHANGE, all, allExpected),
            core?.let { MacroMetric("근원 PCE 전월비", MetricUnit.PCT_CHANGE, it, coreExpected) },
        ),
        note,
    )

    /** FOMC는 월분이 아니라 그날 결정. [changeBp]는 인상 +, 인하 −, 동결 0. */
    fun fomc(
        month: YearMonth,
        changeBp: Int,
        lower: Double,
        upper: Double,
        note: String,
        expectedBp: Int? = null,
    ) = MacroActualView(
        month,
        listOf(MacroMetric("기준금리", MetricUnit.BP, changeBp.toDouble(), expectedBp?.toDouble())),
        "목표 범위 %.2f~%.2f%%.%s".format(lower, upper, note),
    )
}
