package com.optionhelper.volatility

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * 데이터 소스에서 받은 일별 30일 IV. IBKR 과거 데이터 요청은 횟수 제한이 있어 한 번 받은 날짜는 DB에 두고 다시 받지 않는다.
 * 소스별로 따로 쌓아(source) 가짜 데이터와 실제 데이터가 섞이지 않게 한다.
 */
@Entity
@Table(name = "daily_iv", uniqueConstraints = [UniqueConstraint(name = "uk_daily_iv", columnNames = ["source", "symbol", "tradeDate"])])
class DailyIvRecord(
    @Column(nullable = false, length = 32)
    val source: String,
    @Column(nullable = false, length = 16)
    val symbol: String,
    @Column(nullable = false)
    val tradeDate: LocalDate,
    @Column(nullable = false)
    val iv: Double,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

interface DailyIvRepository : JpaRepository<DailyIvRecord, Long> {
    fun findTopBySourceAndSymbolOrderByTradeDateDesc(
        source: String,
        symbol: String,
    ): DailyIvRecord?

    fun findBySourceAndSymbolAndTradeDateGreaterThanEqualOrderByTradeDate(
        source: String,
        symbol: String,
        from: LocalDate,
    ): List<DailyIvRecord>
}
