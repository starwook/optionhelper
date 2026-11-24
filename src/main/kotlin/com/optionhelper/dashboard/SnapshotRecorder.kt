package com.optionhelper.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.optionhelper.option.OptionSnapshot
import com.optionhelper.option.OptionSnapshotRepository
import com.optionhelper.pricing.MarketClock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@ConfigurationProperties(prefix = "optionhelper.recorder")
data class RecorderProperties(
    val enabled: Boolean = true,
    // 기본은 첫 종목만. 데이터 소스 호출 한도(IBKR 동시 100줄 등)를 보고 늘린다.
    val symbols: List<String> = listOf("NVDA"),
)

/**
 * 장중 1분마다 가장 가까운 만기의 체인을 저장한다. IV 퍼센타일·추이 차트·복기 모드의 원본 데이터다.
 */
@Component
class SnapshotRecorder(
    private val properties: RecorderProperties,
    private val dashboardService: DashboardService,
    private val repository: OptionSnapshotRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${optionhelper.recorder.interval-ms:60000}", initialDelay = 10_000)
    fun record() {
        if (!properties.enabled || !MarketClock.isOpen(clock.instant())) return
        properties.symbols.forEach { symbol ->
            runCatching {
                val d = dashboardService.dashboard(symbol, null)
                if (d.stale) return@runCatching
                repository.save(
                    OptionSnapshot(
                        symbol = d.symbol,
                        expiry = d.expiry,
                        capturedAt = d.asOf,
                        spot = d.spot,
                        atmStrike = d.atm?.strike,
                        straddle = d.straddle,
                        atmIv = d.volatility.atmIvCalc ?: d.volatility.atmIvApi,
                        chainJson = objectMapper.writeValueAsString(d.chain),
                        source = d.feed.name,
                    ),
                )
            }.onFailure { log.warn("스냅샷 저장 실패({}): {}", symbol, it.message) }
        }
    }
}
