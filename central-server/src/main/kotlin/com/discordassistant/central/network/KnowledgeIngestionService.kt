package com.discordassistant.central.network

import com.discordassistant.central.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.persistence.KnowledgeSourceEntity
import com.discordassistant.central.persistence.KnowledgeSourceRepository
import com.discordassistant.central.persistence.KnowledgeSpaceEntity
import com.discordassistant.central.persistence.KnowledgeSpaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
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
    private val audits: CustomizationAuditLogRepository? = null,
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
        val saved =
            spaces.save(
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
        audit(saved.guildId, saved.channelId, createdBy, "knowledge_space_create", "knowledge_space", saved.id, saved.displayName)
        return saved
    }

    fun listSources(
        guildId: Long,
        spaceId: Long,
    ): List<KnowledgeSourceSummary> {
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        return sources
            .findByKnowledgeSpaceId(space.id)
            .filter { !it.status.startsWith("deleted") }
            .sortedWith(compareByDescending<KnowledgeSourceEntity> { it.addedAt }.thenBy { it.id })
            .map { it.toSummary() }
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

    fun indexingPlan(
        guildId: Long,
        spaceId: Long,
        force: Boolean = false,
    ): KnowledgeIndexingPlan {
        featureGate.requireRagEnabled()
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val sourceList = sources.findByKnowledgeSpaceId(space.id).filter { !it.status.startsWith("deleted") }
        val indexable =
            sourceList
                .filter { it.riskLevel in INDEXABLE_RISK_LEVELS }
                .filter { force || it.status == "pending" || it.status == "indexed" }
                .sortedWith(compareBy<KnowledgeSourceEntity> { it.status }.thenBy { it.id })
        val blocked = sourceList.filter { it.riskLevel !in INDEXABLE_RISK_LEVELS || it.status.startsWith("blocked") }
        val collection = space.indexName?.trim()?.ifBlank { null } ?: defaultCollectionName(guildId, space.channelId, space.id)
        val embeddingModel = space.embeddingModel?.trim()?.ifBlank { null } ?: DEFAULT_EMBEDDING_MODEL
        val command =
            listOf(
                "scripts/rag.sh",
                "rebuild",
                "--guild",
                guildId.toString(),
                "--space",
                space.id.toString(),
                "--collection",
                collection,
                "--embedding-model",
                embeddingModel,
            ) + if (force) listOf("--force") else emptyList()
        return KnowledgeIndexingPlan(
            guildId = guildId,
            knowledgeSpaceId = space.id,
            channelId = space.channelId,
            collectionName = collection,
            embeddingModel = embeddingModel,
            runtime = "python3.12-qdrant-llamaindex-bm25-rrf-reranker",
            qdrantRequired = true,
            force = force,
            command = command.joinToString(" "),
            indexableSources = indexable.map { it.toIndexingSource() },
            blockedSources = blocked.map { it.toIndexingSource() },
            ready = indexable.isNotEmpty() && blocked.none { it.riskLevel in BLOCKING_RISK_LEVELS },
            warnings = indexingWarnings(indexable, blocked),
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
        audit(
            guildId = guildId,
            channelId = space.channelId,
            actorUserId = addedBy,
            action = "knowledge_source_add",
            targetType = "knowledge_source",
            targetId = source.id,
            summary = "${source.sourceType}:${source.riskLevel}",
        )
        return source
    }

    @Transactional
    fun approveSourceForIndexing(
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
            sources.findByKnowledgeSpaceIdAndId(space.id, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        require(source.riskLevel == "review") { "only review-risk source can be manually approved: ${source.riskLevel}" }
        require(source.status.startsWith("blocked") || source.status == "review") {
            "only blocked/review source can be manually approved: ${source.status}"
        }
        source.status = "pending"
        val saved = sources.save(source)
        space.status = "pending_index"
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_approve", "knowledge_source", saved.id, reason)
        return saved
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
        audit(guildId, space.channelId, null, "knowledge_source_indexed", "knowledge_source", saved.id, "chunks=${space.chunkCount}")
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
        val space =
            spaces.findByGuildIdAndId(guildId, spaceId)
                ?: throw IllegalArgumentException("knowledge space not found: guild=$guildId space=$spaceId")
        val source =
            sources.findByKnowledgeSpaceIdAndId(spaceId, sourceId)
                ?: throw IllegalArgumentException("knowledge source not found: space=$spaceId source=$sourceId")
        require(source.guildId == guildId) { "knowledge source belongs to another guild" }
        source.status = "rejected:${sanitizeReason(reason)}"
        val saved = sources.save(source)
        audit(guildId, space.channelId, null, "knowledge_source_reject", "knowledge_source", saved.id, reason)
        return saved
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
        source.status = "deleted:${sanitizeReason(reason)}"
        val saved = sources.save(source)
        space.sourceCount = sources.findByKnowledgeSpaceId(space.id).count { it.status.startsWith("deleted").not() }
        space.updatedAt = Instant.now(clock)
        spaces.save(space)
        audit(guildId, space.channelId, null, "knowledge_source_delete", "knowledge_source", saved.id, reason)
        return saved
    }

    private fun audit(
        guildId: Long,
        channelId: Long?,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits?.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId ?: 0,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = sanitizeReason(summary).take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun KnowledgeSourceEntity.toIndexingSource(): KnowledgeIndexingSource =
        KnowledgeIndexingSource(
            id = id,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status,
            riskLevel = riskLevel,
            contentHash = contentHash,
        )

    private fun indexingWarnings(
        indexable: List<KnowledgeSourceEntity>,
        blocked: List<KnowledgeSourceEntity>,
    ): List<String> =
        buildList {
            if (indexable.isEmpty()) add("indexable_source_empty")
            if (blocked.any { it.riskLevel == "sensitive" }) add("sensitive_source_blocked")
            if (blocked.any { it.riskLevel == "ssrf" }) add("ssrf_source_blocked")
            if (blocked.any { it.riskLevel == "review" }) add("manual_review_required")
        }

    private fun defaultCollectionName(
        guildId: Long,
        channelId: Long?,
        spaceId: Long,
    ): String =
        listOfNotNull(
            "discord_ai",
            "guild_$guildId",
            channelId?.let { "channel_$it" },
            "space_$spaceId",
        ).joinToString("__")

    private fun KnowledgeSourceEntity.toSummary(): KnowledgeSourceSummary =
        KnowledgeSourceSummary(
            id = id,
            knowledgeSpaceId = knowledgeSpaceId,
            guildId = guildId,
            sourceType = sourceType,
            title = title,
            sourceUri = sourceUri,
            status = status,
            contentHash = contentHash,
            riskLevel = riskLevel,
            addedBy = addedBy,
            addedAt = addedAt.toString(),
            indexedAt = indexedAt?.toString(),
        )

    private fun sanitizeReason(reason: String): String =
        reason
            .trim()
            .replace(REASON_SECRET_PATTERN, "[redacted]")
            .take(80)
            .ifBlank { "manual" }

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
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return SourceValidation("review", "blocked_bad_uri")
        return when {
            host == "localhost" || host.endsWith(".localhost") -> SourceValidation("ssrf", "blocked_ssrf")
            host.endsWith(".local") || host.endsWith(".internal") -> SourceValidation("ssrf", "blocked_ssrf")
            isPrivateAddressLiteral(host) -> SourceValidation("ssrf", "blocked_ssrf")
            else -> null
        }
    }

    private fun isPrivateAddressLiteral(host: String): Boolean {
        parseIpv4Octets(host)?.let { octets ->
            val first = octets[0]
            val second = octets[1]
            return first == 0 ||
                first == 10 ||
                first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }
        if (":" !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        val bytes = address.address
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                first == 0xfc ||
                first == 0xfd ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }

    private fun parseIpv4Octets(host: String): List<Int>? {
        if (!IPV4_LITERAL.matches(host)) return null
        val octets = host.split(".").map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.size == 4 && values.all { it in 0..255 } }
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
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large"
        val ALLOWED_SOURCE_TYPES = setOf("file", "link", "text", "faq", "constitution", "preset")
        val BLOCKING_RISK_LEVELS = setOf("sensitive", "ssrf")
        val INDEXABLE_RISK_LEVELS = setOf("normal", "review")
        val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        val REASON_SECRET_PATTERN = Regex("""(?i)(password|passwd|token|api[_-]?key|secret|authorization|bearer)\s*[:=]\s*[^\s,;]+""")
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

data class KnowledgeSourceSummary(
    val id: Long,
    val knowledgeSpaceId: Long,
    val guildId: Long,
    val sourceType: String,
    val title: String,
    val sourceUri: String?,
    val status: String,
    val contentHash: String?,
    val riskLevel: String,
    val addedBy: Long?,
    val addedAt: String,
    val indexedAt: String?,
)

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

data class KnowledgeIndexingPlan(
    val guildId: Long,
    val knowledgeSpaceId: Long,
    val channelId: Long?,
    val collectionName: String,
    val embeddingModel: String,
    val runtime: String,
    val qdrantRequired: Boolean,
    val force: Boolean,
    val command: String,
    val indexableSources: List<KnowledgeIndexingSource>,
    val blockedSources: List<KnowledgeIndexingSource>,
    val ready: Boolean,
    val warnings: List<String>,
)

data class KnowledgeIndexingSource(
    val id: Long,
    val sourceType: String,
    val title: String,
    val sourceUri: String?,
    val status: String,
    val riskLevel: String,
    val contentHash: String?,
)
