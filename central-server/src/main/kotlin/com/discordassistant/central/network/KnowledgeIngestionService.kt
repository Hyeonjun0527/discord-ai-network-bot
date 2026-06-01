package com.discordassistant.central.network

import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Service
class KnowledgeIngestionService(
    private val spaces: KnowledgeSpaceRepository,
    private val sources: KnowledgeSourceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @Transactional
    fun createSpace(
        guildId: Long,
        channelId: Long?,
        channelAiId: Long?,
        displayName: String,
        createdBy: Long?,
        embeddingModel: String?,
        indexName: String?,
    ): KnowledgeSpaceEntity {
        featureGate.requireRagEnabled()
        val now = Instant.now(clock)
        return spaces.save(
            KnowledgeSpaceEntity(
                guildId = guildId,
                channelId = channelId,
                channelAiId = channelAiId,
                displayName = displayName.trim().ifBlank { "채널 지식공간" },
                status = "draft",
                embeddingModel = embeddingModel?.trim()?.ifBlank { null },
                indexName = indexName?.trim()?.ifBlank { null },
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional
    fun addSource(
        guildId: Long,
        spaceId: Long,
        sourceType: String,
        title: String,
        sourceUri: String?,
        contentPreview: String?,
        addedBy: Long?,
    ): KnowledgeSourceEntity {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val now = Instant.now(clock)
        val source =
            sources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = space.id,
                    guildId = guildId,
                    sourceType = sourceType.trim().ifBlank { "text" },
                    sourceUri = sourceUri?.trim()?.ifBlank { null },
                    title = title.trim().ifBlank { "untitled" },
                    status = "pending",
                    contentHash = stableHash(sourceUri.orEmpty() + "\n" + contentPreview.orEmpty()),
                    riskLevel = inferRiskLevel(sourceType, sourceUri, contentPreview),
                    addedBy = addedBy,
                    addedAt = now,
                ),
            )
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).size
        space.status = "pending_index"
        space.updatedAt = now
        spaces.save(space)
        return source
    }

    @Transactional
    fun markSourceIndexed(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        chunkCount: Int,
    ): KnowledgeSourceEntity {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        val now = Instant.now(clock)
        source.status = "indexed"
        source.indexedAt = now
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).size
        space.chunkCount = chunkCount.coerceAtLeast(0)
        space.status = "ready"
        space.updatedAt = now
        spaces.save(space)
        return saved
    }

    @Transactional
    fun rejectSource(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
    ): KnowledgeSourceEntity {
        featureGate.requireRagEnabled()
        spaces.findByGuildIdAndId(guildId, spaceId)
            ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        source.status = "rejected:${reason.trim().take(80)}"
        return sources.save(source)
    }

    private fun inferRiskLevel(
        sourceType: String,
        sourceUri: String?,
        contentPreview: String?,
    ): String {
        val text = listOf(sourceType, sourceUri.orEmpty(), contentPreview.orEmpty()).joinToString(" ").lowercase()
        return when {
            "password" in text || "api_key" in text || "secret" in text || "token" in text -> "sensitive"
            sourceUri != null && !sourceUri.startsWith("https://") -> "review"
            else -> "normal"
        }
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
