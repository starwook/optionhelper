package com.optionhelper.macro

import com.optionhelper.claude.ClaudeClient
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@ConfigurationProperties(prefix = "optionhelper.macro-flash")
data class MacroFlashProperties(
    val enabled: Boolean = true,
    // 발표 몇 분 뒤에 묻는가. 못 찾으면 다음 시각에 다시(발표당 최대 3회). 연 56회 발표 × 보통 1~2회 × 0.02달러 ≈ 연 1~2달러
    val attemptMinutes: List<Long> = listOf(5, 15, 40),
    val maxSearches: Long = 2,
) {
    // 마지막 시도 뒤 이만큼 지나면 기다리기를 멈춘다
    val untilMinutes: Long get() = attemptMinutes.last() + 15
}

/** 속보 기록. 발표마다 한 줄, 확인을 통과한 숫자만 JSON으로 둔다. */
@Entity
@Table(name = "macro_flash_result", uniqueConstraints = [UniqueConstraint(columnNames = ["type", "event_date"])])
class MacroFlashEntity(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val type: MacroEventType,
    @Column(nullable = false)
    val eventDate: LocalDate,
    @Column(nullable = false, length = 2000)
    val valuesJson: String,
    @Column(nullable = false, length = 64)
    val model: String,
    @Column(nullable = false)
    val fetchedAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

interface MacroFlashRepository : JpaRepository<MacroFlashEntity, Long>

/** 속보 결과 + 출처. */
data class FlashResult(val view: MacroActualView, val sourceUrl: String?, val expectedSourceUrl: String?)

/** 발표 직후 공식 값이 없으면 Claude로 속보를 뽑아 기록한다. Claude 키가 없으면 아무것도 안 한다. */
@Service
class MacroFlashService(
    private val claude: ClaudeClient,
    private val repository: MacroFlashRepository,
    private val calendar: MacroCalendarFile,
    private val actuals: MacroActualService,
    private val properties: MacroFlashProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val active: Boolean get() = properties.enabled && claude.enabled
    val model: String get() = claude.model

    fun flashes(): Map<MacroEvent, FlashResult> =
        repository.findAll().mapNotNull { entity ->
            val event = MacroEvent(entity.type, entity.eventDate)
            val values = MacroFlash.read(entity.valuesJson)
            MacroFlash.view(event, values)?.let {
                event to FlashResult(it, values.path("source_url").textValue(), values.path("expected_source_url").textValue())
            }
        }.toMap()

    /** 속보를 기다리는 중인가(화면의 "결과 확인 중…"과 빠른 새로고침용). */
    fun waiting(
        event: MacroEvent,
        now: Instant,
    ): Boolean = active && event.inFlashWindow(now, 0, properties.untilMinutes)

    fun refreshIfDue() {
        if (!active) return
        val now = clock.instant()
        val observations = actuals.observations()
        val done = repository.findAll().map { MacroEvent(it.type, it.eventDate) }.toSet()
        calendar.events
            .filter { it.inFlashWindow(now, properties.attemptMinutes.first(), properties.untilMinutes) }
            .filter { it !in done && MacroActuals.of(it, observations) == null }
            .filter { event ->
                // 지금까지 n번 불렀으면 n번째 시도 시각이 지났을 때만 다시
                val tried = claude.callCount(purpose(event)).toInt()
                tried < properties.attemptMinutes.size &&
                    !now.isBefore(event.releaseAt().toInstant().plusSeconds(properties.attemptMinutes[tried] * 60))
            }
            .forEach { fetch(it, now) }
    }

    private fun purpose(event: MacroEvent) = "macro-flash:${event.type}:${event.date}"

    private fun fetch(
        event: MacroEvent,
        now: Instant,
    ) {
        val answer =
            runCatching { claude.askWithWebSearch(purpose(event), MacroFlash.prompt(event), properties.maxSearches) }
                .onFailure { log.warn("속보 호출 실패({} {}): {}", event.type, event.date, it.message) }
                .getOrNull() ?: return
        val (values, reason) = MacroFlash.parse(event, answer.text)
        if (values == null) {
            log.info("속보 아직 없음({} {}): {}", event.type, event.date, reason)
            return
        }
        val minutes = Duration.between(event.releaseAt().toInstant(), now).toMinutes()
        log.info("속보 받음({} {}): {} (발표 후 {}분)", event.type, event.date, values, minutes)
        repository.save(MacroFlashEntity(event.type, event.date, values.toString(), claude.model, now))
    }
}

/** 1분마다 확인한다. 실제 호출은 발표 5·15·40분 뒤에만(MacroFlashService.refreshIfDue). */
@Component
class MacroFlashRefresher(private val service: MacroFlashService) {
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    fun refresh() = service.refreshIfDue()
}
