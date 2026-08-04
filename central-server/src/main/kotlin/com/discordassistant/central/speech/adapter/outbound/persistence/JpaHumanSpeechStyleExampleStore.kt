package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleBubble
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 암호화된 Speech 말투 카드 저장소. 원문/응답/벡터 모두 encrypted column에만 보관한다. */
@Repository
class JpaHumanSpeechStyleExampleStore(
    private val rows: HumanSpeechStyleExampleRepository,
    private val clock: Clock = Clock.systemUTC(),
) : HumanSpeechStyleExampleStorePort {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Transactional(readOnly = true)
    override fun listEnabled(): List<HumanSpeechStyleExample> {
        if (!FieldCrypto.isConfigured()) return emptyList()
        return rows.findAllByEnabledTrueOrderByExampleIdAsc().map(::toDomain)
    }

    @Transactional
    override fun replaceAll(examples: List<HumanSpeechStyleExample>): Int {
        require(FieldCrypto.isConfigured()) { "human speech style encryption key is not configured" }
        require(examples.isNotEmpty()) { "human speech style import requires examples" }
        require(examples.size <= MAX_EXAMPLES) { "human speech style import has too many examples" }
        require(examples.map(HumanSpeechStyleExample::exampleId).toSet().size == examples.size) {
            "human speech style import contains duplicate example ids"
        }
        rows.deleteAllInBatch()
        rows.saveAll(examples.sortedBy(HumanSpeechStyleExample::exampleId).map(::toEntity))
        return examples.size
    }

    private fun toEntity(example: HumanSpeechStyleExample): HumanSpeechStyleExampleEntity =
        Instant.now(clock).let { now ->
            HumanSpeechStyleExampleEntity(
                exampleId = example.exampleId,
                responseMode = example.responseMode.name,
                quality = example.quality.name,
                sourceFingerprint = example.sourceFingerprint,
                consentRevision = example.consentRevision,
                combinedChars = example.combinedChars,
                enabled = true,
                payloadJson = mapper.writeValueAsString(example.toPayload()),
                embeddingJson = mapper.writeValueAsString(example.embedding.toList()),
                embeddingModel = example.embeddingModel,
                createdAt = now,
                updatedAt = now,
            )
        }

    private fun toDomain(entity: HumanSpeechStyleExampleEntity): HumanSpeechStyleExample {
        val payload = mapper.readValue<HumanSpeechStyleStoredPayload>(entity.payloadJson)
        val embedding = mapper.readValue<List<Float>>(entity.embeddingJson).toFloatArray()
        return HumanSpeechStyleExample(
            exampleId = entity.exampleId,
            responseMode = HumanSpeechResponseMode.valueOf(entity.responseMode),
            situation = payload.situation,
            styleSignals = payload.styleSignals,
            contextBubbles = payload.contextBubbles,
            responseBubbles = payload.responseBubbles,
            quality = HumanSpeechStyleQuality.valueOf(entity.quality),
            sourceFingerprint = entity.sourceFingerprint,
            consentRevision = entity.consentRevision,
            combinedChars = entity.combinedChars,
            embedding = embedding,
            embeddingModel = entity.embeddingModel,
        )
    }

    private fun HumanSpeechStyleExample.toPayload(): HumanSpeechStyleStoredPayload =
        HumanSpeechStyleStoredPayload(
            situation = situation,
            styleSignals = styleSignals,
            contextBubbles = contextBubbles,
            responseBubbles = responseBubbles,
        )

    private companion object {
        const val MAX_EXAMPLES: Int = 2_000
    }
}

private data class HumanSpeechStyleStoredPayload(
    val situation: String,
    val styleSignals: List<String>,
    val contextBubbles: List<HumanSpeechStyleBubble>,
    val responseBubbles: List<HumanSpeechStyleBubble>,
)

@Entity
@Table(name = "nia_human_speech_style_example")
class HumanSpeechStyleExampleEntity(
    @Id
    @Column(name = "example_id", length = 64)
    var exampleId: String = "",
    @Column(name = "response_mode", nullable = false, length = 32)
    var responseMode: String = "",
    @Column(name = "quality", nullable = false, length = 48)
    var quality: String = "",
    @Column(name = "source_fingerprint", nullable = false, length = 96)
    var sourceFingerprint: String = "",
    @Column(name = "consent_revision", nullable = false, length = 128)
    var consentRevision: String = "",
    @Column(name = "combined_chars", nullable = false)
    var combinedChars: Int = 0,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    var payloadJson: String = "",
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "embedding_json", nullable = false, columnDefinition = "TEXT")
    var embeddingJson: String = "",
    @Column(name = "embedding_model", nullable = false, length = 96)
    var embeddingModel: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "HumanSpeechStyleExampleEntity(exampleId=$exampleId, responseMode=$responseMode, quality=$quality, " +
            "combinedChars=$combinedChars, enabled=$enabled, embeddingModel=$embeddingModel)"
}

interface HumanSpeechStyleExampleRepository : JpaRepository<HumanSpeechStyleExampleEntity, String> {
    fun findAllByEnabledTrueOrderByExampleIdAsc(): List<HumanSpeechStyleExampleEntity>
}
