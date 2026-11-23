package com.optionhelper.option

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate

/**
 * 장중 주기적으로 남기는 체인 스냅샷. IV 히스토리·퍼센타일과 복기 모드(그때 화면 재현)에 쓴다.
 * 다시 받을 수 없는 데이터라 DB에 저장한다.
 */
@Entity
@Table(name = "option_snapshot", indexes = [Index(name = "idx_snapshot_symbol_time", columnList = "symbol,capturedAt")])
class OptionSnapshot(
    @Column(nullable = false, length = 16)
    val symbol: String,
    @Column(nullable = false)
    val expiry: LocalDate,
    @Column(nullable = false)
    val capturedAt: Instant,
    @Column(nullable = false)
    val spot: Double,
    val atmStrike: Double?,
    val straddle: Double?,
    // 가장 가까운 만기의 ATM IV(직접 역산값 우선)
    val atmIv: Double?,
    // 화면용 체인 행(JSON). 복기 모드에서 그대로 다시 그린다.
    @Lob
    @Column(nullable = false)
    val chainJson: String,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

interface OptionSnapshotRepository : JpaRepository<OptionSnapshot, Long> {
    @Query("select s.atmIv from OptionSnapshot s where s.symbol = :symbol and s.capturedAt >= :since and s.atmIv is not null")
    fun findAtmIvs(
        symbol: String,
        since: Instant,
    ): List<Double>

    /** 체인 JSON 없이 시각·ATM IV만(1년치를 읽어도 가볍게). */
    @Query(
        "select new com.optionhelper.option.IvPoint(s.capturedAt, s.atmIv) from OptionSnapshot s " +
            "where s.symbol = :symbol and s.capturedAt >= :since and s.atmIv is not null order by s.capturedAt",
    )
    fun findIvPoints(
        symbol: String,
        since: Instant,
    ): List<IvPoint>

    fun findBySymbolAndCapturedAtGreaterThanEqualOrderByCapturedAt(
        symbol: String,
        since: Instant,
    ): List<OptionSnapshot>
}
