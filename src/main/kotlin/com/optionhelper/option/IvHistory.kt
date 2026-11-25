package com.optionhelper.option

import com.optionhelper.marketdata.MarketDataSource
import org.springframework.stereotype.Service
import java.time.Instant

data class IvPoint(val time: Instant, val iv: Double)

/** 쌓인 스냅샷에서 ATM IV 기록을 읽는다(지금 데이터 소스가 남긴 것만). */
interface IvHistory {
    /** since 이후 ATM IV 값들(퍼센타일 계산용). */
    fun atmIvs(
        symbol: String,
        since: Instant,
    ): List<Double>
}

@Service
class SnapshotIvHistory(
    private val repository: OptionSnapshotRepository,
    private val source: MarketDataSource,
) : IvHistory {
    override fun atmIvs(
        symbol: String,
        since: Instant,
    ) = repository.findAtmIvs(source.feed.name, symbol, since)
}
