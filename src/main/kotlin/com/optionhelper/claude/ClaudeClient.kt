package com.optionhelper.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** 앱 전체에서 쓰는 Claude API 설정. 키는 application-local.yml, 나머지는 application.yml. */
@ConfigurationProperties(prefix = "optionhelper.claude")
data class ClaudeProperties(
    val apiKey: String = "",
    val model: String = "claude-haiku-5-5",
    // 앱 전체 월 호출 상한. Haiku + 웹 검색 2회 ≈ 1회 0.02달러라 10회면 한 달 최대 약 0.2달러(연 약 2.4달러)
    val monthlyCallLimit: Int = 10,
)

/** Claude 호출 기록. 월 상한 계산과 기능별 재시도 횟수 계산에 쓴다(앱을 다시 켜도 유지). */
@Entity
@Table(name = "claude_call", indexes = [Index(name = "idx_claude_call_purpose", columnList = "purpose")])
class ClaudeCallEntity(
    // 어떤 기능이 무엇 때문에 불렀나. 예: "macro-flash:CPI:2026-12-10"
    @Column(nullable = false, length = 100)
    val purpose: String,
    @Column(nullable = false, length = 64)
    val model: String,
    @Column(nullable = false)
    val calledAt: Instant,
    @Column(nullable = false)
    var webSearches: Long = 0,
    @Column(nullable = false)
    var inputTokens: Long = 0,
    @Column(nullable = false)
    var outputTokens: Long = 0,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)

interface ClaudeCallRepository : JpaRepository<ClaudeCallEntity, Long> {
    fun countByPurpose(purpose: String): Long

    fun countByCalledAtGreaterThanEqual(from: Instant): Long
}

data class ClaudeAnswer(val text: String, val webSearches: Long, val inputTokens: Long, val outputTokens: Long)

class ClaudeBudgetExceeded(message: String) : RuntimeException(message)

/** Claude API 공용 클라이언트. 키가 없으면 enabled=false이고, 이를 쓰는 기능은 조용히 꺼진다. 모든 호출은 기록되고 월 상한을 넘지 않는다. */
@Component
class ClaudeClient(
    private val properties: ClaudeProperties,
    private val calls: ClaudeCallRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val enabled: Boolean = properties.apiKey.isNotBlank()
    val model: String get() = properties.model

    private val client: AnthropicClient? =
        if (enabled) AnthropicOkHttpClient.builder().apiKey(properties.apiKey).build() else null

    /** 이 목적으로 지금까지 부른 횟수(실패 포함). */
    fun callCount(purpose: String): Long = calls.countByPurpose(purpose)

    /** 웹 검색을 켜고 한 번 묻는다. 월 상한을 넘으면 ClaudeBudgetExceeded. */
    fun askWithWebSearch(
        purpose: String,
        prompt: String,
        maxSearches: Long,
    ): ClaudeAnswer {
        val client = checkNotNull(client) { "Claude API 키가 없습니다(optionhelper.claude.api-key)" }
        val now = clock.instant()
        val monthStart = YearMonth.from(now.atZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val used = calls.countByCalledAtGreaterThanEqual(monthStart)
        if (used >= properties.monthlyCallLimit) throw ClaudeBudgetExceeded("이번 달 Claude 호출 상한(${properties.monthlyCallLimit}회)에 닿았습니다")

        // 호출 전에 먼저 기록해서, 실패해도 횟수에 들어가게 한다(실패 반복으로 상한을 넘지 않게)
        val record = calls.save(ClaudeCallEntity(purpose, properties.model, now))
        val builder =
            MessageCreateParams.builder()
                .model(properties.model)
                .maxTokens(4_000L)
                // 숫자 몇 개 뽑는 일이라 생각을 짧게
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(prompt)
        // Haiku는 동적 필터링 웹 검색(20260209)을 못 써서 기본형을 쓴다
        if (properties.model.startsWith("claude-haiku")) {
            builder.addTool(WebSearchTool20250305.builder().maxUses(maxSearches).build())
        } else {
            builder.addTool(WebSearchTool20260209.builder().maxUses(maxSearches).build())
        }
        val response = client.messages().create(builder.build())
        val text = response.content().flatMap { it.text().map { block -> listOf(block.text()) }.orElse(emptyList()) }.joinToString("")
        val usage = response.usage()
        record.webSearches = usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
        record.inputTokens = usage.inputTokens()
        record.outputTokens = usage.outputTokens()
        calls.save(record)
        log.info(
            "Claude 호출({}): 검색 {}회, 입력 {} / 출력 {} 토큰, 이번 달 {}/{}회",
            purpose,
            record.webSearches,
            record.inputTokens,
            record.outputTokens,
            used + 1,
            properties.monthlyCallLimit,
        )
        return ClaudeAnswer(text, record.webSearches, record.inputTokens, record.outputTokens)
    }
}
