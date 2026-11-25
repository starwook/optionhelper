package com.optionhelper.paper

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 모의투자 화면이 저장하는 값(계좌·빨리감기 진행 판·지난 기록)을 화면의 JSON 그대로 둔다.
 * 임시: 로그인이 없어 계좌는 사이트 전체에 하나다. 소유자 로그인이 생기면 사용자별로 나눈다(기획서 미결).
 */
@Entity
@Table(name = "paper_store")
class PaperStoreEntry(
    @Id
    @Column(name = "store_key", length = 32)
    val key: String,
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    var json: String,
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    companion object {
        /** 화면이 쓰는 저장 키. 이 밖의 키는 받지 않는다. */
        val KEYS = setOf("paper.account", "drill.current", "drill.history")

        /** 한 키당 최대 크기(공개 API라 묶어 둔다). */
        const val MAX_CHARS = 1_000_000
    }
}

interface PaperStoreRepository : JpaRepository<PaperStoreEntry, String>
