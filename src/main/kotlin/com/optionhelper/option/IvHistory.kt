package com.optionhelper.option

import org.springframework.stereotype.Service
import java.time.Instant

data class IvPoint(val time: Instant, val iv: Double)

/** 쌓인 스냅샷에서 ATM IV 기록을 읽는다. */
interface IvHistory {
    /** since 이후 ATM IV 값들(퍼센타일 계산용). */
    fun atmIvs(
        symbol: String,
        since: Instant,
    ): List<Double>

    /** since 이후 시간순 ATM IV(추이 차트용). */
    fun points(
        symbol: String,
        since: Instant,
    ): List<IvPoint>
}

@Service
class SnapshotIvHistory(private val repository: OptionSnapshotRepository) : IvHistory {
    override fun atmIvs(
        symbol: String,
        since: Instant,
    ) = repository.findAtmIvs(symbol, since)

    override fun points(
        symbol: String,
        since: Instant,
    ) = repository.findBySymbolAndCapturedAtGreaterThanEqualOrderByCapturedAt(symbol, since)
        .mapNotNull { s -> s.atmIv?.let { IvPoint(s.capturedAt, it) } }
}
