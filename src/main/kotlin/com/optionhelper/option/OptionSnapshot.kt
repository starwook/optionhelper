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
 * 다시 받을 수 없는 데이터라 DB에 저장한다. 데이터 소스별로 따로 읽어(source) 다른 소스(가짜, 예전 Alpaca 실제 시세)의 기록이 섞여 보이지 않게 한다.
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
    // 길이를 안 주면 MySQL에서 tinytext(255바이트)가 된다. 실제 체인은 수십 KB라 mediumtext로 둔다.
    @Lob
    @Column(nullable = false, length = MEDIUMTEXT_LENGTH)
    val chainJson: String,
    // 기록한 데이터 소스 이름(MarketDataSource.feed.name). 출처 구분 전에 쌓인 행은 null이라 어느 화면에도 안 나온다.
    @Column(length = 64)
    val source: String? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

// MySQL mediumtext 최대 길이(16MB)
private const val MEDIUMTEXT_LENGTH = 16_777_215

interface OptionSnapshotRepository : JpaRepository<OptionSnapshot, Long> {
    @Query(
        "select s.atmIv from OptionSnapshot s " +
            "where s.source = :source and s.symbol = :symbol and s.capturedAt >= :since and s.atmIv is not null",
    )
    fun findAtmIvs(
        source: String,
        symbol: String,
        since: Instant,
    ): List<Double>

    /** 체인 JSON 없이 시각·ATM IV만(1년치를 읽어도 가볍게). */
    @Query(
        "select new com.optionhelper.option.IvPoint(s.capturedAt, s.atmIv) from OptionSnapshot s " +
            "where s.source = :source and s.symbol = :symbol and s.capturedAt >= :since and s.atmIv is not null order by s.capturedAt",
    )
    fun findIvPoints(
        source: String,
        symbol: String,
        since: Instant,
    ): List<IvPoint>

    fun findBySourceAndSymbolAndCapturedAtGreaterThanEqualOrderByCapturedAt(
        source: String,
        symbol: String,
        since: Instant,
    ): List<OptionSnapshot>
}
