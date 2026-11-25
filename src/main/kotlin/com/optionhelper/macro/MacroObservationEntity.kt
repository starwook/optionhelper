package com.optionhelper.macro

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.YearMonth

/** 경제지표 발표 결과 기록. 시리즈·월마다 한 줄, 값이 수정 발표되면 덮어쓴다. */
@Entity
@Table(name = "macro_observation", uniqueConstraints = [UniqueConstraint(columnNames = ["series", "ref_month"])])
class MacroObservationEntity(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val series: MacroSeries,
    // "2026-10"
    @Column(nullable = false, length = 7)
    val refMonth: String,
    // H2에서 VALUE가 예약어라 이름을 바꾼다
    @Column(name = "obs_value", nullable = false)
    var value: Double,
    @Column(nullable = false)
    var fetchedAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
) {
    fun toObservation() = MacroObservation(series, YearMonth.parse(refMonth), value)
}

interface MacroObservationRepository : JpaRepository<MacroObservationEntity, Long>
