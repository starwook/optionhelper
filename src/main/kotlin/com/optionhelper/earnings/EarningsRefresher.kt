package com.optionhelper.earnings

import com.optionhelper.watchlist.WatchedSymbol
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 관심 종목 실적 일정을 백그라운드로 미리 받아 둔다. 앱 시작 직후 한 번, 이후 6시간마다 확인하지만
 * 실제 호출은 받은 지 일주일 지난 종목만 한다(EarningsService.refreshIfStale).
 */
@Component
class EarningsRefresher(
    private val earnings: EarningsService,
    @Value("\${optionhelper.earnings.background-refresh:true}") private val enabled: Boolean,
) {
    @Scheduled(initialDelay = 15_000, fixedDelay = 6 * 60 * 60 * 1000L)
    fun refresh() {
        if (!enabled) return
        WatchedSymbol.STOCKS.forEach { symbol ->
            // 연달아 부르면 Alpha Vantage 연속 호출 제한에 걸리므로, 실제로 부른 다음엔 잠깐 쉰다
            if (earnings.refreshIfStale(symbol)) Thread.sleep(CALL_GAP_MS)
        }
    }

    private companion object {
        const val CALL_GAP_MS = 2_000L
    }
}
