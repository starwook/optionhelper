package com.optionhelper.volatility

import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.option.OptionSnapshotRepository
import com.optionhelper.pricing.MarketClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** IV 기록이 무엇을 잰 값인지. 화면에 그대로 표시한다. */
enum class IvBasis(val label: String) {
    // 실제 설명은 데이터 소스가 준다(MarketDataSource.historicalIvLabel)
    THIRTY_DAY("데이터 소스가 준 과거 IV"),
    NEAREST_ATM("앱이 쌓은 스냅샷의 가장 가까운 만기 ATM IV (30일 IV 아님, 만기 직전엔 출렁임)"),
}

data class IvHistoryPoint(val date: LocalDate, val iv: Double, val hv20: Double?)

data class IvHistoryView(
    val symbol: String,
    val source: String,
    val basis: IvBasis?,
    val basisLabel: String?,
    val points: List<IvHistoryPoint>,
    val current: Double?,
    val currentDate: LocalDate?,
    val range: IvRange?,
    val rank: Double?,
    val percentile: Double?,
    val hv20: Double?,
    val ivHvRatio: Double?,
    val warnings: List<String>,
)

data class IvSummaryRow(
    val symbol: String,
    val current: Double?,
    val rank: Double?,
    val percentile: Double?,
    val hv20: Double?,
    val ivHvRatio: Double?,
    val samples: Int,
    val error: String?,
)

/**
 * 종목별 일별 IV 기록(기본 1년)과, 지금 IV가 그 범위에서 어디쯤인지(IV Rank·퍼센타일)·실제 변동(HV20) 대비 얼마인지를 만든다.
 * 데이터 소스가 과거 IV를 주면(IBKR) 그걸 DB에 쌓아 쓰고, 안 주면 앱이 장중에 쌓은 스냅샷의 하루 마지막 값으로 대신한다.
 */
@Service
class IvHistoryService(
    private val source: MarketDataSource,
    private val repository: DailyIvRepository,
    private val snapshots: OptionSnapshotRepository,
    private val dailyBars: DailyBarService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 종목별로 소스에서 마지막으로 받아 온 날(하루 한 번만 부른다)
    private val fetchedOn = ConcurrentHashMap<String, LocalDate>()

    fun history(
        symbol: String,
        days: Long = DEFAULT_DAYS,
    ): IvHistoryView {
        val sym = symbol.uppercase()
        val today = clock.instant().atZone(MarketClock.NEW_YORK).toLocalDate()
        val from = today.minusDays(days)
        val warnings = mutableListOf<String>()
        runCatching { refresh(sym, today) }.onFailure {
            log.warn("{} 과거 IV를 받지 못했습니다: {}", sym, it.message)
            warnings += "데이터 소스에서 과거 IV를 받지 못해 저장된 값만 보여 줍니다: ${it.message}"
        }

        val stored = repository.findBySourceAndSymbolAndTradeDateGreaterThanEqualOrderByTradeDate(source.feed.name, sym, from)
        val (basis, ivs) =
            if (stored.isNotEmpty()) {
                IvBasis.THIRTY_DAY to stored.map { it.tradeDate to it.iv }
            } else {
                IvBasis.NEAREST_ATM to snapshotDailyIvs(sym, from)
            }

        val hvByDate = hvByDate(sym)
        val points = ivs.map { (date, iv) -> IvHistoryPoint(date, iv, hvByDate[date]) }
        val current = points.lastOrNull()
        val values = points.map { it.iv }
        val range = IvRange.of(values)
        val hv20 = hvByDate.maxByOrNull { it.key }?.value

        if (points.isEmpty()) {
            warnings += "IV 기록이 없습니다. 일봉이 20거래일보다 적으면(새로 상장된 종목) 아직 계산할 수 없습니다."
        } else if (points.size < MIN_SAMPLES) {
            warnings += "IV 기록이 ${points.size}일치뿐이라 IV Rank·퍼센타일은 참고만 하세요."
        }
        if (basis == IvBasis.NEAREST_ATM && points.isNotEmpty()) warnings += "30일 IV가 아니라 가장 가까운 만기 ATM IV라서, 만기 직전 날은 값이 튈 수 있습니다."

        return IvHistoryView(
            symbol = sym,
            source = source.feed.name,
            basis = basis.takeIf { points.isNotEmpty() },
            basisLabel = (if (basis == IvBasis.THIRTY_DAY) source.historicalIvLabel else basis.label).takeIf { points.isNotEmpty() },
            points = points,
            current = current?.iv,
            currentDate = current?.date,
            range = range,
            rank = current?.let { range?.rank(it.iv) },
            percentile = current?.let { VolatilityMetrics.ivPercentile(values, it.iv) },
            hv20 = hv20,
            ivHvRatio = if (current != null && hv20 != null && hv20 > 0) VolatilityMetrics.ivHvRatio(current.iv, hv20) else null,
            warnings = warnings,
        )
    }

    fun summary(symbols: List<String>): List<IvSummaryRow> =
        symbols.map { symbol ->
            runCatching { history(symbol) }.fold(
                { h -> IvSummaryRow(h.symbol, h.current, h.rank, h.percentile, h.hv20, h.ivHvRatio, h.points.size, null) },
                { e -> IvSummaryRow(symbol, null, null, null, null, null, 0, e.message ?: "불러오지 못했습니다") },
            )
        }

    /** 마지막으로 저장한 다음 날부터 어제까지를 소스에서 받아 저장한다. */
    private fun refresh(
        symbol: String,
        today: LocalDate,
    ) {
        if (fetchedOn[symbol] == today) return
        val sourceName = source.feed.name
        val last = repository.findTopBySourceAndSymbolOrderByTradeDateDesc(sourceName, symbol)?.tradeDate
        val from = last?.plusDays(1) ?: today.minusDays(BACKFILL_DAYS)
        if (from.isBefore(today)) {
            val fetched = source.dailyImpliedVolatility(symbol, from).filter { last == null || it.date.isAfter(last) }
            repository.saveAll(fetched.map { DailyIvRecord(sourceName, symbol, it.date, it.iv) })
        }
        fetchedOn[symbol] = today
    }

    /** 스냅샷을 뉴욕 날짜별로 묶어 그날 마지막 ATM IV를 쓴다. */
    private fun snapshotDailyIvs(
        symbol: String,
        from: LocalDate,
    ): List<Pair<LocalDate, Double>> =
        snapshots.findIvPoints(source.feed.name, symbol, from.atStartOfDay().toInstant(ZoneOffset.UTC))
            .groupBy { it.time.atZone(MarketClock.NEW_YORK).toLocalDate() }
            .map { (date, list) -> date to list.last().iv }
            .sortedBy { it.first }

    private fun hvByDate(symbol: String): Map<LocalDate, Double> {
        val bars = runCatching { dailyBars.bars(symbol) }.getOrDefault(emptyList())
        val closes = bars.map { it.close }
        return bars.indices.mapNotNull { i ->
            VolatilityMetrics.historicalVolatility(closes.subList(0, i + 1))?.let { bars[i].date to it }
        }.toMap()
    }

    companion object {
        const val DEFAULT_DAYS = 365L

        // 처음 받을 때는 1년보다 조금 더(1년 전 날짜의 퍼센타일도 계산되게)
        private const val BACKFILL_DAYS = 400L
        private const val MIN_SAMPLES = 60
    }
}
