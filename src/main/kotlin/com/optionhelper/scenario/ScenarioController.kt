package com.optionhelper.scenario

import com.optionhelper.dashboard.ErrorResponse
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.pricing.OptionType
import com.optionhelper.pricing.PricingProperties
import com.optionhelper.volatility.IvHistoryService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

data class ScenarioView(
    val symbol: String,
    val type: OptionType,
    val strike: Double,
    val expiry: LocalDate,
    val price: Double,
    val spot: Double,
    // 주가를 어디서 가져왔는지(직접 입력 또는 데이터 소스 이름)
    val spotSource: String,
    val scenario: OptionScenario,
    val notes: List<String>,
)

class ScenarioUnsolvableException(message: String) : RuntimeException(message)

@Service
class ScenarioService(
    private val source: MarketDataSource,
    private val ivHistory: IvHistoryService,
    private val pricing: PricingProperties,
    private val clock: Clock,
) {
    fun scenario(
        symbol: String,
        type: OptionType,
        strike: Double,
        expiry: LocalDate,
        price: Double,
        spot: Double?,
    ): ScenarioView {
        val sym = symbol.uppercase()
        val usedSpot = spot ?: source.stockQuote(sym).price
        val history = runCatching { ivHistory.history(sym) }.getOrNull()
        val notes = mutableListOf("주가는 그대로라고 가정합니다. 실제로는 주가 변화(델타)가 더 크게 움직일 수 있습니다.")

        val shifts = mutableListOf<IvShift>()
        val current = history?.current
        val range = history?.range
        if (current != null && range != null) {
            // 종목 IV(30일)가 1년 중앙값·최저로 돌아갈 때, 이 옵션 IV도 같은 폭만큼 움직인다고 본다(행사가별 스큐는 유지)
            shifts += IvShift("1년 중앙값 ${pct(range.median)}로", range.median - current)
            shifts += IvShift("1년 최저 ${pct(range.min)}로", range.min - current)
            notes += "중앙값·최저 열은 종목 IV(${history.basisLabel})가 지금 ${pct(current)}에서 그 값으로 바뀐 만큼 이 옵션 IV도 같은 폭으로 바뀐다고 계산합니다."
        } else {
            notes += "종목 IV 기록이 없어 ±10%p 열만 보여 줍니다."
        }
        shifts += IvShift("−10%p", -0.10)
        shifts += IvShift("+10%p", 0.10)

        val scenario =
            OptionScenarios.build(type, usedSpot, strike, expiry, price, clock.instant(), pricing.riskFreeRate, shifts)
                ?: throw ScenarioUnsolvableException("이 가격으로는 IV를 계산할 수 없습니다(만기가 지났거나 가격이 내재가치보다 낮음).")
        return ScenarioView(sym, type, strike, expiry, price, usedSpot, if (spot != null) "직접 입력" else source.feed.name, scenario, notes)
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)
}

@RestController
class ScenarioController(private val scenarioService: ScenarioService) {
    @GetMapping("/api/scenario")
    fun scenario(
        @RequestParam symbol: String,
        @RequestParam type: OptionType,
        @RequestParam strike: Double,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) expiry: LocalDate,
        @RequestParam price: Double,
        @RequestParam(required = false) spot: Double?,
    ) = scenarioService.scenario(symbol, type, strike, expiry, price, spot)

    @ExceptionHandler(ScenarioUnsolvableException::class)
    fun unsolvable(e: ScenarioUnsolvableException) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorResponse(e.message ?: ""))
}
