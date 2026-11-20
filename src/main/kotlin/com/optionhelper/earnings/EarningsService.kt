package com.optionhelper.earnings

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 실적 일정을 DB 캐시에서 주고, 하루 지났으면 소스에서 새로 받는다.
 * 소스가 실패하거나 빈 값을 주면 저장된 값을 쓰고, 한 시간 동안 다시 부르지 않는다(무료 한도 보호).
 */
@Service
class EarningsService(
    private val source: EarningsCalendarSource,
    private val repository: EarningsEventRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastAttempt = ConcurrentHashMap<String, Instant>()

    val sourceName: String get() = source.name

    @Transactional
    fun events(symbol: String): List<EarningsEvent> {
        val now = clock.instant()
        val cached = repository.findBySymbolOrderByEventDate(symbol)
        val fresh = cached.firstOrNull()?.let { Duration.between(it.fetchedAt, now) < REFRESH_INTERVAL } ?: false
        val recentlyTried = lastAttempt[symbol]?.let { Duration.between(it, now) < RETRY_INTERVAL } ?: false
        if (fresh || recentlyTried) return cached.map { it.toEvent() }

        lastAttempt[symbol] = now
        return try {
            val events = source.events(symbol)
            if (events.isEmpty()) return cached.map { it.toEvent() }
            repository.deleteBySymbol(symbol)
            repository.flush()
            repository.saveAll(events.map { EarningsEventEntity(it.symbol, it.date, it.timing, now) })
            events
        } catch (e: Exception) {
            log.warn("실적 일정 갱신 실패({}), 저장된 값 사용: {}", symbol, e.message)
            cached.map { it.toEvent() }
        }
    }

    companion object {
        private val REFRESH_INTERVAL: Duration = Duration.ofHours(24)
        private val RETRY_INTERVAL: Duration = Duration.ofHours(1)
    }
}
