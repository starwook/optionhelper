package com.optionhelper.paper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PaperStoreService(
    private val repository: PaperStoreRepository,
    private val objectMapper: ObjectMapper,
) {
    /** 저장된 값 전부(키 → JSON). 없는 키는 빠진다. */
    @Transactional(readOnly = true)
    fun all(): Map<String, JsonNode> = repository.findAll().associate { it.key to objectMapper.readTree(it.json) }

    /** 값을 저장한다. null이면 지운다. */
    @Transactional
    fun put(
        key: String,
        value: JsonNode?,
    ) {
        require(key in PaperStoreEntry.KEYS) { "저장할 수 없는 키입니다: $key" }
        if (value == null || value.isNull) {
            repository.deleteById(key)
            return
        }
        val json = objectMapper.writeValueAsString(value)
        require(json.length <= PaperStoreEntry.MAX_CHARS) { "저장할 값이 너무 큽니다" }
        val entry = repository.findById(key).orElse(null)
        if (entry == null) {
            repository.save(PaperStoreEntry(key, json))
        } else {
            entry.json = json
            entry.updatedAt = Instant.now()
        }
    }
}
