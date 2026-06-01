package com.discordassistant.central.network

import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
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

    fun spaceStatus(
        guildId: Long,
        spaceId: Long,
    ): KnowledgeSpaceStatusSummary {
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.startsWith("deleted") }
        val indexed = sourceList.count { it.status == "indexed" }
        val blocked = sourceList.count { it.status.startsWith("blocked") || it.riskLevel in BLOCKING_RISK_LEVELS }
        val pending = sourceList.count { it.status == "pending" }
        val rejected = sourceList.count { it.status.startsWith("rejected") }
        val readiness =
            when {
                indexed > 0 && blocked == 0 && pending == 0 -> "ready"
                indexed > 0 -> "partial"
                pending > 0 -> "indexing_needed"
                blocked > 0 -> "needs_review"
                rejected > 0 -> "rejected"
                else -> "empty"
            }
        return KnowledgeSpaceStatusSummary(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            channelAiId = space.channelAiId,
            displayName = space.displayName,
            status = space.status,
            readiness = readiness,
            sourceCount = sourceList.size,
            indexedSourceCount = indexed,
            pendingSourceCount = pending,
            blockedSourceCount = blocked,
            rejectedSourceCount = rejected,
            chunkCount = space.chunkCount,
            riskLevels = sourceList.groupingBy { it.riskLevel }.eachCount(),
            sourceStatuses = sourceList.groupingBy { it.status }.eachCount(),
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
        val normalizedType = sourceType.trim().lowercase().ifBlank { "text" }
        val normalizedUri = sourceUri?.trim()?.ifBlank { null }
        val validation = validateSource(normalizedType, normalizedUri, contentPreview)
        val source =
            sources.save(
                KnowledgeSourceEntity(
                    knowledgeSpaceId = space.id,
                    guildId = guildId,
                    sourceType = normalizedType,
                    sourceUri = normalizedUri,
                    title = title.trim().ifBlank { "untitled" },
                    status = validation.initialStatus,
                    contentHash = stableHash(normalizedUri.orEmpty() + "\n" + contentPreview.orEmpty()),
                    riskLevel = validation.riskLevel,
                    addedBy = addedBy,
                    addedAt = now,
                ),
            )
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).size
        space.status = if (validation.initialStatus == "pending") "pending_index" else "needs_review"
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
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        require(source.status == "pending") { "only pending source can be indexed: ${source.status}" }
        require(source.riskLevel == "normal" || source.riskLevel == "review") { "unsafe source cannot be indexed: ${source.riskLevel}" }
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
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        source.status = "rejected:${reason.trim().take(80)}"
        return sources.save(source)
    }

    @Transactional
    fun removeSource(
        guildId: Long,
        spaceId: Long,
        sourceId: Long,
        reason: String,
    ): KnowledgeSourceEntity {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        source.status = "deleted:${reason.trim().take(80)}"
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { it.status.startsWith("deleted").not() }
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        return saved
    }

    private fun validateSource(
        sourceType: String,
        sourceUri: String?,
        contentPreview: String?,
    ): SourceValidation {
        if (sourceType !in ALLOWED_SOURCE_TYPES) {
            return SourceValidation("review", "blocked_type")
        }
        val text = listOf(sourceType, sourceUri.orEmpty(), contentPreview.orEmpty()).joinToString(" ")
        if (contentPreview.orEmpty().length > MAX_CONTENT_PREVIEW_CHARS) {
            return SourceValidation("review", "blocked_too_large")
        }
        if (SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }) {
            return SourceValidation("sensitive", "blocked_sensitive")
        }
        if (sourceUri != null) {
            val uriRisk = validateUri(sourceUri)
            if (uriRisk != null) return uriRisk
        }
        return SourceValidation("normal", "pending")
    }

    private fun validateUri(sourceUri: String): SourceValidation? {
        val uri = runCatching { URI(sourceUri) }.getOrNull() ?: return SourceValidation("review", "blocked_bad_uri")
        if (uri.scheme != "https") return SourceValidation("review", "blocked_non_https")
        val host = uri.host?.lowercase() ?: return SourceValidation("review", "blocked_bad_uri")
        return when {
            host == "localhost" || host.endsWith(".localhost") -> SourceValidation("ssrf", "blocked_ssrf")
            host in PRIVATE_HOSTS -> SourceValidation("ssrf", "blocked_ssrf")
            PRIVATE_IPV4_PREFIXES.any { host.startsWith(it) } -> SourceValidation("ssrf", "blocked_ssrf")
            host.endsWith(".local") || host.endsWith(".internal") -> SourceValidation("ssrf", "blocked_ssrf")
            else -> null
        }
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private data class SourceValidation(
        val riskLevel: String,
        val initialStatus: String,
    )

    private companion object {
        const val MAX_CONTENT_PREVIEW_CHARS = 8_000
        val ALLOWED_SOURCE_TYPES = setOf("file", "link", "text", "faq", "constitution", "preset")
        val PRIVATE_HOSTS = setOf("127.0.0.1", "0.0.0.0", "169.254.169.254", "::1")
        val BLOCKING_RISK_LEVELS = setOf("sensitive", "ssrf")
        val PRIVATE_IPV4_PREFIXES = listOf("10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.", "172.2", "172.30.", "172.31.")
        val SENSITIVE_PATTERNS =
            listOf(
                Regex("(?i)\\b(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
                Regex("(?i)\\b(api[_-]?key|secret|token|bot[_-]?token|private[_-]?key)\\s*[:=]\\s*\\S+"),
                Regex("(?i)-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----"),
                Regex("(?i)discord[_-]?bot[_-]?token"),
                Regex("(?i)sk-[A-Za-z0-9_-]{20,}"),
            )
    }
}

data class KnowledgeSpaceStatusSummary(
    val guildId: Long,
    val knowledgeSpaceId: Long,
    val channelId: Long?,
    val channelAiId: Long?,
    val displayName: String,
    val status: String,
    val readiness: String,
    val sourceCount: Int,
    val indexedSourceCount: Int,
    val pendingSourceCount: Int,
    val blockedSourceCount: Int,
    val rejectedSourceCount: Int,
    val chunkCount: Int,
    val riskLevels: Map<String, Int>,
    val sourceStatuses: Map<String, Int>,
)
