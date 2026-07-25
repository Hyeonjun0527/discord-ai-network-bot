package com.discordassistant.central.niaprompt.application

import com.discordassistant.central.niaprompt.adapter.outbound.persistence.NiaPromptConfigurationEntity
import com.discordassistant.central.niaprompt.adapter.outbound.persistence.NiaPromptConfigurationRepository
import com.discordassistant.central.shared.NiaPromptDefaults
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.NiaPromptTemplate
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

data class NiaPromptConfigurationView(
    val activeVersion: Int,
    val activeDocuments: Map<NiaPromptKey, String>,
    val draftDocuments: Map<NiaPromptKey, String>,
    val source: String,
    val dirty: Boolean,
    val updatedAt: Instant?,
    val appliedAt: Instant?,
)

@Service
class NiaPromptConfigurationService(
    private val repository: NiaPromptConfigurationRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : NiaPromptSource {
    @Volatile
    private var activeCache: Map<NiaPromptKey, String>? = null

    override fun documents(): Map<NiaPromptKey, String> = activeCache ?: load().activeDocuments.also { activeCache = it }

    @Transactional(readOnly = true)
    fun load(): NiaPromptConfigurationView {
        val entity = repository.findById(NiaPromptConfigurationEntity.SINGLETON_ID).orElse(null)
        val active = decode(entity?.activeDocumentsJson) ?: NiaPromptDefaults.documents
        val draft = decode(entity?.draftDocumentsJson) ?: active
        return NiaPromptConfigurationView(
            activeVersion = entity?.activeVersion ?: 0,
            activeDocuments = active,
            draftDocuments = draft,
            source = if (entity?.activeDocumentsJson == null) "CODE_DEFAULT" else "MANAGED",
            dirty = active != draft,
            updatedAt = entity?.updatedAt,
            appliedAt = entity?.appliedAt,
        )
    }

    @Transactional
    fun saveDraft(
        documents: Map<String, String>,
        actorUserId: Long?,
    ): NiaPromptConfigurationView {
        val validated = validate(documents)
        val now = clock.instant()
        val entity = repository.findById(NiaPromptConfigurationEntity.SINGLETON_ID).orElseGet(::NiaPromptConfigurationEntity)
        entity.draftDocumentsJson = encode(validated)
        entity.updatedBy = actorUserId
        entity.updatedAt = now
        repository.save(entity)
        return load()
    }

    @Transactional
    fun applyDraft(actorUserId: Long?): NiaPromptConfigurationView {
        val entity = repository.findById(NiaPromptConfigurationEntity.SINGLETON_ID).orElseGet(::NiaPromptConfigurationEntity)
        val draft = decode(entity.draftDocumentsJson) ?: NiaPromptDefaults.documents
        val now = clock.instant()
        entity.activeDocumentsJson = encode(draft)
        entity.draftDocumentsJson = encode(draft)
        entity.activeVersion += 1
        entity.updatedBy = actorUserId
        entity.updatedAt = now
        entity.appliedAt = now
        repository.save(entity)
        activeCache = draft
        return load()
    }

    @Transactional
    fun resetDraft(actorUserId: Long?): NiaPromptConfigurationView =
        saveDraft(NiaPromptDefaults.documents.mapKeys { it.key.wireName }, actorUserId)

    private fun validate(input: Map<String, String>): Map<NiaPromptKey, String> {
        val activeKeys = NiaPromptKey.entries.map(NiaPromptKey::wireName).toSet()
        val unknown = input.keys - activeKeys - RETIRED_PROMPT_KEYS
        require(unknown.isEmpty()) { "알 수 없는 프롬프트 키: ${unknown.sorted().joinToString()}" }
        val missing = NiaPromptKey.entries.filter { it.wireName !in input }
        require(missing.isEmpty()) { "누락된 프롬프트: ${missing.joinToString { it.wireName }}" }
        return NiaPromptKey.entries.associateWith { key ->
            val content = input.getValue(key.wireName).trim()
            require(content.isNotBlank()) { "${key.title}은 비워 둘 수 없습니다" }
            require(content.length <= MAX_DOCUMENT_CHARS) { "${key.title}은 ${MAX_DOCUMENT_CHARS}자 이하여야 합니다" }
            val placeholders = NiaPromptTemplate.placeholders(content)
            val absent = key.requiredPlaceholders - placeholders
            require(absent.isEmpty()) { "${key.title}에 변수가 누락되었습니다: ${absent.sorted().joinToString()}" }
            content
        }
    }

    private fun encode(documents: Map<NiaPromptKey, String>): String =
        mapper.writeValueAsString(documents.entries.associate { it.key.wireName to it.value })

    private fun decode(json: String?): Map<NiaPromptKey, String>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val raw = mapper.readValue(json, object : TypeReference<Map<String, String>>() {})
            validate(raw)
        }.getOrNull()
    }

    companion object {
        private const val MAX_DOCUMENT_CHARS = 120_000
        private val RETIRED_PROMPT_KEYS = setOf("judge_repair_template", "action_evaluator_template")
    }
}
