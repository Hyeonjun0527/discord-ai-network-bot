package com.discordassistant.central.socialmemory.adapter.outbound.persistence

import com.discordassistant.central.socialmemory.application.port.out.PendingIntentStore
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.intent.IntentActivation
import com.discordassistant.central.socialmemory.domain.model.intent.IntentUrgency
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.intent.SocialAct
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** V56의 nexa_pending_intent와 source-event 테이블을 사용해 열린 약속을 재시작 뒤에도 보존한다. */
@Repository
class JpaPendingIntentStore(
    private val intents: PendingIntentEntityRepository,
    private val sources: PendingIntentSourceEventRepository,
) : PendingIntentStore {
    @Transactional
    override fun save(intent: PendingIntent): PendingIntent {
        val entity = intents.findByIdForUpdate(intent.id) ?: PendingIntentEntity(id = intent.id)
        if (entity.status != MemoryStatus.ACTIVE.name && intent.status == MemoryStatus.ACTIVE) {
            return entity.toDomain(sourceIds(intent.id))
        }
        entity.copyFrom(intent)
        intents.save(entity)
        intent.source.sourceEventIds.forEach { sourceId ->
            if (!sources.existsByMemoryIdAndMemoryKindAndSourceEventId(intent.id, MEMORY_KIND, sourceId)) {
                sources.save(PendingIntentSourceEventEntity(memoryId = intent.id, memoryKind = MEMORY_KIND, sourceEventId = sourceId))
            }
        }
        return entity.toDomain(sourceIds(intent.id))
    }

    @Transactional(readOnly = true)
    override fun findActive(
        focusThreadKey: String,
        now: Instant,
    ): List<PendingIntent> =
        intents
            .findByFocusThreadKeyAndStatus(focusThreadKey, MemoryStatus.ACTIVE.name)
            .map { it.toDomain(sourceIds(it.id)) }
            .filter { it.isActiveAt(now) }

    @Transactional
    override fun complete(
        id: String,
        completedAt: Instant,
        completedByActionId: String,
    ): PendingIntent? {
        val entity = intents.findByIdForUpdate(id) ?: return null
        if (entity.status != MemoryStatus.ACTIVE.name) return entity.toDomain(sourceIds(id))
        entity.status = MemoryStatus.COMPLETED.name
        entity.completedAt = completedAt
        entity.completedByActionId = completedByActionId
        return entity.toDomain(sourceIds(id))
    }

    @Transactional
    override fun invalidate(id: String): PendingIntent? {
        val entity = intents.findByIdForUpdate(id) ?: return null
        if (entity.status != MemoryStatus.ACTIVE.name) return entity.toDomain(sourceIds(id))
        entity.status = MemoryStatus.INVALIDATED.name
        entity.completedAt = null
        entity.completedByActionId = null
        return entity.toDomain(sourceIds(id))
    }

    @Transactional
    override fun invalidateBySource(sourceEventId: String): Int {
        val affected = sources.findByMemoryKindAndSourceEventId(MEMORY_KIND, sourceEventId)
        var changed = 0
        affected.forEach { source ->
            sources.delete(source)
            if (sources.countByMemoryIdAndMemoryKind(source.memoryId, MEMORY_KIND) == 0L) {
                intents.findById(source.memoryId).orElse(null)?.let { entity ->
                    if (entity.status != MemoryStatus.INVALIDATED.name) {
                        entity.status = MemoryStatus.INVALIDATED.name
                        changed++
                    }
                }
            }
        }
        return changed
    }

    private fun sourceIds(memoryId: String): Set<String> =
        sources.findByMemoryIdAndMemoryKind(memoryId, MEMORY_KIND).mapTo(linkedSetOf(), PendingIntentSourceEventEntity::sourceEventId)

    private companion object {
        const val MEMORY_KIND: String = "PENDING_INTENT"
    }
}

@Entity
@Table(name = "nexa_pending_intent")
class PendingIntentEntity(
    @Id var id: String = "",
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "visibility_kind") var visibilityKind: String = "GUILD",
    @Column(name = "channel_pseudonym") var channelPseudonym: String? = null,
    @Column(name = "thread_pseudonym") var threadPseudonym: String? = null,
    @Column(name = "subject_pseudonym") var subjectPseudonym: String? = null,
    @Column(name = "topic") var topic: String = "",
    @Column(name = "target_pseudonym") var targetPseudonym: String? = null,
    @Column(name = "social_act") var socialAct: String = "REPLY",
    @Column(name = "activation") var activation: String = "IMMEDIATE",
    @Column(name = "urgency") var urgency: String = "NORMAL",
    @Column(name = "extraction_version") var extractionVersion: Long = 0,
    @Column(name = "consent_granted") var consentGranted: Boolean = false,
    @Column(name = "source_created_at") var sourceCreatedAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at") var expiresAt: Instant? = null,
    @Column(name = "confidence") var confidence: Double = 1.0,
    @Column(name = "status") var status: String = "ACTIVE",
    @Column(name = "focus_thread_key") var focusThreadKey: String? = null,
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "completed_by_action_id") var completedByActionId: String? = null,
) {
    fun copyFrom(intent: PendingIntent) {
        guildPseudonym = intent.visibility.guildPseudonym
        visibilityKind = intent.visibility.kind()
        channelPseudonym = (intent.visibility as? VisibilityScope.Channel)?.channelPseudonym
            ?: (intent.visibility as? VisibilityScope.Thread)?.channelPseudonym
        threadPseudonym = (intent.visibility as? VisibilityScope.Thread)?.threadPseudonym
        subjectPseudonym = (intent.visibility as? VisibilityScope.Private)?.subjectPseudonym
        topic = intent.topic
        targetPseudonym = intent.targetPseudonym
        socialAct = intent.socialAct.name
        activation = intent.activation.name
        urgency = intent.urgency.name
        extractionVersion = intent.source.extractionVersion
        consentGranted = intent.source.consentGranted
        sourceCreatedAt = intent.source.createdAt
        expiresAt = intent.expiresAt
        confidence = intent.confidence
        status = intent.status.name
        focusThreadKey = intent.focusThreadKey
        completedAt = intent.completedAt
        completedByActionId = intent.completedByActionId
    }

    fun toDomain(sourceIds: Set<String>): PendingIntent =
        PendingIntent(
            id = id,
            visibility = visibility(),
            topic = topic,
            targetPseudonym = targetPseudonym,
            socialAct = SocialAct.valueOf(socialAct),
            activation = IntentActivation.valueOf(activation),
            urgency = IntentUrgency.valueOf(urgency),
            source = MemorySource(sourceIds, extractionVersion, consentGranted, sourceCreatedAt),
            expiresAt = expiresAt,
            confidence = confidence,
            status = MemoryStatus.valueOf(status),
            focusThreadKey = focusThreadKey,
            completedAt = completedAt,
            completedByActionId = completedByActionId,
        )

    private fun visibility(): VisibilityScope =
        when (visibilityKind) {
            "CHANNEL" -> VisibilityScope.Channel(guildPseudonym, requireNotNull(channelPseudonym))
            "THREAD" -> VisibilityScope.Thread(guildPseudonym, requireNotNull(channelPseudonym), requireNotNull(threadPseudonym))
            "PRIVATE" -> VisibilityScope.Private(guildPseudonym, requireNotNull(subjectPseudonym))
            else -> VisibilityScope.Guild(guildPseudonym)
        }

    private fun VisibilityScope.kind(): String =
        when (this) {
            is VisibilityScope.Guild -> "GUILD"
            is VisibilityScope.Channel -> "CHANNEL"
            is VisibilityScope.Thread -> "THREAD"
            is VisibilityScope.Private -> "PRIVATE"
        }
}

interface PendingIntentEntityRepository : JpaRepository<PendingIntentEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PendingIntentEntity i where i.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: String,
    ): PendingIntentEntity?

    fun findByFocusThreadKeyAndStatus(
        focusThreadKey: String,
        status: String,
    ): List<PendingIntentEntity>
}

@Entity
@Table(name = "nexa_memory_source_event")
class PendingIntentSourceEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "memory_id") var memoryId: String = "",
    @Column(name = "memory_kind") var memoryKind: String = "PENDING_INTENT",
    @Column(name = "source_event_id") var sourceEventId: String = "",
)

interface PendingIntentSourceEventRepository : JpaRepository<PendingIntentSourceEventEntity, Long> {
    fun existsByMemoryIdAndMemoryKindAndSourceEventId(
        memoryId: String,
        memoryKind: String,
        sourceEventId: String,
    ): Boolean

    fun findByMemoryIdAndMemoryKind(
        memoryId: String,
        memoryKind: String,
    ): List<PendingIntentSourceEventEntity>

    fun findByMemoryKindAndSourceEventId(
        memoryKind: String,
        sourceEventId: String,
    ): List<PendingIntentSourceEventEntity>

    fun countByMemoryIdAndMemoryKind(
        memoryId: String,
        memoryKind: String,
    ): Long
}
