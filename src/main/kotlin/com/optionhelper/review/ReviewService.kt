package com.optionhelper.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.optionhelper.dashboard.ChainRow
import com.optionhelper.dashboard.StrategyView
import com.optionhelper.marketdata.DailyBarService
import com.optionhelper.marketdata.MarketDataSource
import com.optionhelper.option.OptionChain
import com.optionhelper.option.OptionQuote
import com.optionhelper.option.OptionSnapshot
import com.optionhelper.option.OptionSnapshotRepository
import com.optionhelper.pricing.MarketClock
import com.optionhelper.pricing.OptionType
import com.optionhelper.strategy.StrategyCalculator
import com.optionhelper.strategy.StrategyMenu
import com.optionhelper.volatility.ExpectedMove
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SnapshotNotFoundException(id: Long) : RuntimeException("스냅샷 $id 이 없습니다")

data class SnapshotSummary(
    val id: Long,
    val capturedAtEt: String,
    val expiry: LocalDate,
    val spot: Double,
    val straddle: Double?,
    val atmIv: Double?,
)

/** 그때 그 전략을 샀다면 만기(또는 지금)에 얼마였나. 금액은 1계약($), 수수료 왕복 포함. */
data class StrategyOutcome(val kind: String, val label: String, val legs: String, val profit: Double)

data class Outcome(
    // true면 만기가 지나 결과가 확정, false면 지금 가격으로 본 중간 결과
    val settled: Boolean,
    val price: Double?,
    val movePct: Double?,
    // 실제 변동 ÷ 그때 예상 변동폭(1이면 예상만큼 움직임)
    val moveVsExpected: Double?,
    val results: List<StrategyOutcome>,
)

data class ReviewResponse(
    val id: Long,
    val symbol: String,
    val capturedAtEt: String,
    val capturedAtKst: String,
    val expiry: LocalDate,
    val spot: Double,
    val atmStrike: Double?,
    val straddle: Double?,
    val expectedMove: ExpectedMove?,
    val atmIv: Double?,
    val chain: List<ChainRow>,
    val strategies: List<StrategyView>,
    val outcome: Outcome,
)

/**
 * 복기 모드: 저장된 스냅샷으로 그때 화면(체인·전략)을 다시 만들고, 실제로 만기까지 어떻게 됐는지 붙인다.
 * 일봉은 분할·배당 조정값이라 그 사이 액면분할이 있었다면 결과가 틀어질 수 있다.
 */
@Service
class ReviewService(
    private val repository: OptionSnapshotRepository,
    private val dailyBars: DailyBarService,
    private val source: MarketDataSource,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun snapshots(
        symbol: String,
        date: LocalDate,
    ): List<SnapshotSummary> {
        val from = date.atStartOfDay(MarketClock.NEW_YORK).toInstant()
        val to = date.plusDays(1).atStartOfDay(MarketClock.NEW_YORK).toInstant()
        return repository.findBySourceAndSymbolAndCapturedAtGreaterThanEqualOrderByCapturedAt(source.feed.name, symbol.uppercase(), from)
            .filter { it.capturedAt.isBefore(to) }
            .map { SnapshotSummary(it.id!!, format(it, MarketClock.NEW_YORK), it.expiry, it.spot, it.straddle, it.atmIv) }
    }

    fun review(id: Long): ReviewResponse {
        val snapshot = repository.findById(id).filter { it.source == source.feed.name }.orElseThrow { SnapshotNotFoundException(id) }
        val rows: List<ChainRow> = objectMapper.readValue(snapshot.chainJson, jacksonTypeRef<List<ChainRow>>())
        val chain = OptionChain(snapshot.symbol, snapshot.expiry, rows.flatMap { toQuotes(it) })
        val atm = snapshot.atmStrike ?: chain.atmStrike(snapshot.spot)
        val strategies = atm?.let { StrategyMenu.build(chain, it) }.orEmpty()
        val expectedMove = snapshot.straddle?.takeIf { it > 0 }?.let { ExpectedMove.of(it, snapshot.spot) }
        val width = maxOf(MIN_PAYOFF_WIDTH, PAYOFF_WIDTH_MULTIPLE * (expectedMove?.pct ?: 0.0))

        val (settled, price) = outcomePrice(snapshot)
        val move = price?.let { it / snapshot.spot - 1 }
        val outcome =
            Outcome(
                settled = settled,
                price = price,
                movePct = move,
                moveVsExpected = if (move != null && expectedMove != null) kotlin.math.abs(move) / expectedMove.pct else null,
                results =
                    price?.let { p ->
                        strategies.map { s ->
                            val view = StrategyView.of(s, snapshot.spot, width)
                            val fee = StrategyCalculator.evaluate(s, snapshot.spot).feePerShare
                            StrategyOutcome(view.kind, view.label, view.legs, (StrategyCalculator.payoff(s, p) - fee) * 100)
                        }
                    }.orEmpty(),
            )

        return ReviewResponse(
            id = snapshot.id!!,
            symbol = snapshot.symbol,
            capturedAtEt = format(snapshot, MarketClock.NEW_YORK) + " ET",
            capturedAtKst = format(snapshot, MarketClock.SEOUL) + " KST",
            expiry = snapshot.expiry,
            spot = snapshot.spot,
            atmStrike = atm,
            straddle = snapshot.straddle,
            expectedMove = expectedMove,
            atmIv = snapshot.atmIv,
            chain = rows,
            strategies = strategies.map { StrategyView.of(it, snapshot.spot, width) },
            outcome = outcome,
        )
    }

    /** 만기가 지났으면 만기일 종가(확정), 아니면 지금 가격(중간 결과). */
    private fun outcomePrice(snapshot: OptionSnapshot): Pair<Boolean, Double?> {
        val now = clock.instant()
        val settled = now.isAfter(MarketClock.expiryAt(snapshot.expiry).toInstant())
        val today = now.atZone(MarketClock.NEW_YORK).toLocalDate()
        if (settled && snapshot.expiry.isBefore(today)) {
            val close =
                runCatching {
                    dailyBars.bars(
                        snapshot.symbol,
                    )
                }.getOrDefault(emptyList()).firstOrNull { it.date == snapshot.expiry }?.close
            if (close != null) return true to close
        }
        // 만기 당일 장 마감 후거나 일봉이 없으면 현재가(장 마감 후면 사실상 종가)
        return settled to runCatching { source.stockQuote(snapshot.symbol).price }.getOrNull()
    }

    private fun toQuotes(row: ChainRow): List<OptionQuote> =
        listOfNotNull(
            row.call?.let { OptionQuote(OptionType.CALL, row.strike, it.bid, it.ask, it.ivApi, it.delta) },
            row.put?.let { OptionQuote(OptionType.PUT, row.strike, it.bid, it.ask, it.ivApi, it.delta) },
        )

    private fun format(
        snapshot: OptionSnapshot,
        zone: java.time.ZoneId,
    ) = snapshot.capturedAt.atZone(zone).format(TIME_FORMAT)

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val MIN_PAYOFF_WIDTH = 0.10
        private const val PAYOFF_WIDTH_MULTIPLE = 2.5
    }
}
