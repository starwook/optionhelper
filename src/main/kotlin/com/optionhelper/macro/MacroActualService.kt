package com.optionhelper.macro

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 경제지표 발표 결과를 DB에 기록하고 준다. 화면 요청은 DB만 읽고, 갱신은 MacroActualRefresher가 한다.
 * 평소엔 6시간마다, 발표 후 2시간 동안 결과가 아직 없으면 10분마다 받는다.
 */
@Service
class MacroActualService(
    private val source: MacroActualSource,
    private val repository: MacroObservationRepository,
    private val calendar: MacroCalendarFile,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastAttempt: Instant? = null

    val sourceName: String get() = source.name

    @Transactional(readOnly = true)
    fun observations(): List<MacroObservation> = repository.findAll().map { it.toObservation() }

    /** 마지막으로 받은 시각. 한 번도 못 받았으면 null. */
    @Transactional(readOnly = true)
    fun lastFetchedAt(): Instant? = repository.findAll().maxOfOrNull { it.fetchedAt }

    /** 10분마다 불린다. 결과를 기다리는 발표가 있거나 마지막 시도 후 6시간이 지났으면 받는다. 실제로 받았으면 true. */
    @Transactional
    fun refreshIfDue(): Boolean {
        val now = clock.instant()
        val waiting = MacroActuals.awaiting(calendar.events, observations(), now)
        val stale = lastAttempt?.let { Duration.between(it, now) >= REFRESH_INTERVAL } ?: true
        if (waiting.isEmpty() && !stale) return false
        refresh()
        return true
    }

    /** 소스에서 받아 새 값은 넣고, 바뀐 값(수정 발표)은 덮어쓴다. 실패하면 저장된 값을 그대로 둔다. */
    @Transactional
    fun refresh() {
        val now = clock.instant()
        lastAttempt = now
        val fetched =
            runCatching { source.observations() }
                .onFailure { log.warn("경제지표 발표값 갱신 실패, 저장된 값 사용: {}", it.message) }
                .getOrNull() ?: return
        val saved = repository.findAll().associateBy { it.series to it.refMonth }
        val firstLoad = saved.isEmpty()
        fetched.forEach { obs ->
            val existing = saved[obs.series to obs.month.toString()]
            if (existing == null) {
                repository.save(MacroObservationEntity(obs.series, obs.month.toString(), obs.value, now))
                if (!firstLoad) logFirstSeen(obs, now)
            } else {
                existing.value = obs.value
                existing.fetchedAt = now
            }
        }
    }

    /** 발표 후 API에 반영되기까지 얼마나 걸리는지 실측하려고 남긴다(docs/외부API-명세.md 6-1). */
    private fun logFirstSeen(
        obs: MacroObservation,
        now: Instant,
    ) {
        val release =
            calendar.events
                .filter { it.type == obs.series.eventType && MacroActuals.referenceMonth(it) == obs.month }
                .maxOfOrNull { it.releaseAt().toInstant() }
        val delay = release?.let { "발표 후 ${Duration.between(it, now).toMinutes()}분" } ?: "발표 시각 모름"
        log.info("경제지표 새 값 처음 받음: {} {} = {} ({})", obs.series, obs.month, obs.value, delay)
    }

    companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofHours(6)
    }
}

/** 앱 시작 20초 뒤부터 10분마다 확인한다. 실제 호출은 MacroActualService.refreshIfDue가 정한다(평소 하루 4회). */
@Component
class MacroActualRefresher(
    private val service: MacroActualService,
    @Value("\${optionhelper.macro-actuals.background-refresh:true}") private val enabled: Boolean,
) {
    @Scheduled(initialDelay = 20_000, fixedDelay = 10 * 60 * 1000L)
    fun refresh() {
        if (enabled) service.refreshIfDue()
    }
}
