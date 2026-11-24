package com.optionhelper.earnings

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 실적 일정을 DB 캐시에서 준다. 화면 요청은 캐시만 읽고, 갱신은 EarningsRefresher가 백그라운드로 일주일에 한 번 한다
 * (실적일은 분기에 한 번 정해지고 거의 안 바뀜, Alpha Vantage 무료 한도 보호).
 * 한 번도 받은 적 없는 종목(관심 종목 밖이거나 앱 첫 실행 직후)만 요청 때 바로 받는다.
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

    /** 화면용: 캐시가 있으면 오래됐어도 그대로 준다. */
    @Transactional
    fun events(symbol: String): List<EarningsEvent> {
        val cached = repository.findBySymbolOrderByEventDate(symbol)
        if (cached.isNotEmpty()) return cached.map { it.toEvent() }
        return fetch(symbol, cached)
    }

    /** 백그라운드용: 받은 지 일주일이 지났거나 없으면 새로 받는다. 실제로 소스를 불렀으면 true. */
    @Transactional
    fun refreshIfStale(symbol: String): Boolean {
        val cached = repository.findBySymbolOrderByEventDate(symbol)
        val fresh = cached.firstOrNull()?.let { Duration.between(it.fetchedAt, clock.instant()) < REFRESH_INTERVAL } ?: false
        if (fresh) return false
        fetch(symbol, cached)
        return true
    }

    /** 소스가 실패하거나 빈 값을 주면 저장된 값을 쓰고, 한 시간 동안 다시 부르지 않는다. */
    private fun fetch(
        symbol: String,
        cached: List<EarningsEventEntity>,
    ): List<EarningsEvent> {
        val now = clock.instant()
        val recentlyTried = lastAttempt[symbol]?.let { Duration.between(it, now) < RETRY_INTERVAL } ?: false
        if (recentlyTried) return cached.map { it.toEvent() }

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
        val REFRESH_INTERVAL: Duration = Duration.ofDays(7)
        private val RETRY_INTERVAL: Duration = Duration.ofHours(1)
    }
}
