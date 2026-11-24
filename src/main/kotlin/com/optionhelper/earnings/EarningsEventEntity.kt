package com.optionhelper.earnings

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

/** 실적 일정 캐시. Alpha Vantage 무료 한도(하루 25회) 때문에 DB에 두고 일주일에 한 번 백그라운드로 갱신한다. */
@Entity
@Table(name = "earnings_event", indexes = [Index(name = "idx_earnings_symbol", columnList = "symbol")])
class EarningsEventEntity(
    @Column(nullable = false, length = 16)
    val symbol: String,
    @Column(nullable = false)
    val eventDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val timing: EarningsTiming,
    @Column(nullable = false)
    val fetchedAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun toEvent() = EarningsEvent(symbol, eventDate, timing)
}

interface EarningsEventRepository : JpaRepository<EarningsEventEntity, Long> {
    fun findBySymbolOrderByEventDate(symbol: String): List<EarningsEventEntity>

    fun deleteBySymbol(symbol: String)
}
