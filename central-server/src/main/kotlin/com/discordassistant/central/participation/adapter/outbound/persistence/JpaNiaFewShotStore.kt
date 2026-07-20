package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.NiaFewShotStorePort
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotEvalStatus
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScopeType
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotSet
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Repository
class JpaNiaFewShotStore(
    private val sets: NiaFewShotSetRepository,
    private val versions: NiaFewShotVersionRepository,
    private val examples: NiaFewShotExampleRepository,
    private val clock: Clock = Clock.systemUTC(),
) : NiaFewShotStorePort {
    private val mapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    override fun listSets(limit: Int): List<NiaFewShotSet> {
        val page = PageRequest.of(0, limit.coerceIn(1, 200))
        return sets.findRecent(page).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun findSet(setId: Long): NiaFewShotSet? = sets.findById(setId).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun findActive(lookup: NiaFewShotLookupScope): NiaFewShotSet? {
        val candidateKeys = lookup.candidates().map { it.stableKey }
        val rows = sets.findByScopeKeyIn(candidateKeys).associateBy { it.scopeKey }
        return candidateKeys
            .asSequence()
            .mapNotNull { key -> rows[key]?.toDomain() }
            .firstOrNull { set -> set.active != null }
    }

    @Transactional(readOnly = true)
    override fun findByScope(scope: NiaFewShotScope): NiaFewShotSet? = sets.findByScopeKey(scope.stableKey)?.toDomain()

    @Transactional(readOnly = true)
    override fun findVersion(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion? = versions.findBySetIdAndVersion(setId, version)?.toDomain()

    @Transactional
    override fun createDraft(
        scope: NiaFewShotScope,
        examples: List<NiaFewShotExample>,
        actorUserId: Long?,
    ): NiaFewShotVersion {
        val now = Instant.now(clock)
        val set = sets.findByScopeKey(scope.stableKey) ?: sets.save(NiaFewShotSetEntity.from(scope, now))
        val nextVersion = versions.maxVersionForSet(set.id) + 1
        val version =
            versions.save(
                NiaFewShotVersionEntity(
                    setId = set.id,
                    version = nextVersion,
                    status = NiaFewShotVersionStatus.DRAFT.name,
                    createdBy = actorUserId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        this.examples.saveAll(examples.map { it.toEntity(version.id, now, mapper) })
        return version.toDomain()
    }

    @Transactional
    override fun replaceDraftExamples(
        setId: Long,
        version: Int,
        examples: List<NiaFewShotExample>,
    ): NiaFewShotVersion {
        val row = requireVersion(setId, version)
        require(row.status == NiaFewShotVersionStatus.DRAFT.name) { "published_or_archived_version_is_immutable" }
        this.examples.deleteByVersionId(row.id)
        val now = Instant.now(clock)
        this.examples.saveAll(examples.map { it.toEntity(row.id, now, mapper) })
        row.updatedAt = now
        versions.save(row)
        return row.toDomain()
    }

    @Transactional
    override fun publish(
        setId: Long,
        version: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet {
        val set = requireSet(setId)
        val target = requireVersion(setId, version)
        require(target.status == NiaFewShotVersionStatus.DRAFT.name) { "only_draft_version_can_be_published" }
        val now = Instant.now(clock)
        archiveCurrentActive(set, now)
        target.status = NiaFewShotVersionStatus.ACTIVE.name
        target.reviewedBy = reviewerUserId
        target.publishedAt = now
        target.updatedAt = now
        versions.save(target)
        set.activeVersion = version
        set.updatedAt = now
        sets.save(set)
        return set.toDomain()
    }

    @Transactional
    override fun rollback(
        setId: Long,
        targetVersion: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet {
        val set = requireSet(setId)
        val target = requireVersion(setId, targetVersion)
        require(target.status != NiaFewShotVersionStatus.DRAFT.name) { "draft_version_cannot_be_rollback_target" }
        val now = Instant.now(clock)
        val previousActive = set.activeVersion
        archiveCurrentActive(set, now)
        target.status = NiaFewShotVersionStatus.ACTIVE.name
        target.reviewedBy = reviewerUserId
        target.rollbackOfVersion = previousActive
        target.publishedAt = target.publishedAt ?: now
        target.updatedAt = now
        versions.save(target)
        set.activeVersion = targetVersion
        set.updatedAt = now
        sets.save(set)
        return set.toDomain()
    }

    @Transactional
    override fun archive(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion {
        val set = requireSet(setId)
        require(set.activeVersion != version) { "active_version_cannot_be_archived" }
        val row = requireVersion(setId, version)
        row.status = NiaFewShotVersionStatus.ARCHIVED.name
        row.updatedAt = Instant.now(clock)
        return versions.save(row).toDomain()
    }

    private fun archiveCurrentActive(
        set: NiaFewShotSetEntity,
        now: Instant,
    ) {
        val active = set.activeVersion ?: return
        val row = versions.findBySetIdAndVersion(set.id, active) ?: return
        row.status = NiaFewShotVersionStatus.ARCHIVED.name
        row.updatedAt = now
        versions.save(row)
    }

    private fun requireSet(setId: Long): NiaFewShotSetEntity =
        sets.findById(setId).orElseThrow { IllegalArgumentException("fewshot_set_not_found") }

    private fun requireVersion(
        setId: Long,
        version: Int,
    ): NiaFewShotVersionEntity =
        versions.findBySetIdAndVersion(setId, version) ?: throw IllegalArgumentException("fewshot_version_not_found")

    private fun NiaFewShotSetEntity.toDomain(): NiaFewShotSet =
        NiaFewShotSet(
            id = id,
            scope = toScope(),
            activeVersion = activeVersion,
            versions = versions.findBySetIdOrderByVersionAsc(id).map { it.toDomain() },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun NiaFewShotSetEntity.toScope(): NiaFewShotScope =
        NiaFewShotScope(
            type = NiaFewShotScopeType.valueOf(scopeType),
            guildId = guildId,
            channelId = channelId,
            persona = persona,
        )

    private fun NiaFewShotVersionEntity.toDomain(): NiaFewShotVersion =
        NiaFewShotVersion(
            id = id,
            setId = setId,
            version = version,
            status = NiaFewShotVersionStatus.valueOf(status),
            examples = examples.findByVersionIdOrderByPriorityDescIdAsc(id).map { it.toDomain(mapper) },
            createdBy = createdBy,
            reviewedBy = reviewedBy,
            publishedAt = publishedAt,
            rollbackOfVersion = rollbackOfVersion,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

@Entity
@Table(name = "nexa_fewshot_set")
class NiaFewShotSetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "scope_type") var scopeType: String = "",
    @Column(name = "scope_key") var scopeKey: String = "",
    @Column(name = "guild_id") var guildId: Long? = null,
    @Column(name = "channel_id") var channelId: Long? = null,
    @Column(name = "persona") var persona: String = NiaFewShotScope.DEFAULT_PERSONA,
    @Column(name = "active_version") var activeVersion: Int? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String = "NiaFewShotSetEntity(id=$id, scopeType=$scopeType, scopeKey=$scopeKey, activeVersion=$activeVersion)"

    companion object {
        fun from(
            scope: NiaFewShotScope,
            now: Instant,
        ): NiaFewShotSetEntity =
            NiaFewShotSetEntity(
                scopeType = scope.type.name,
                scopeKey = scope.stableKey,
                guildId = scope.guildId,
                channelId = scope.channelId,
                persona = scope.persona,
                createdAt = now,
                updatedAt = now,
            )
    }
}

@Entity
@Table(name = "nexa_fewshot_version")
class NiaFewShotVersionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "set_id") var setId: Long = 0,
    @Column(name = "version") var version: Int = 1,
    @Column(name = "status") var status: String = NiaFewShotVersionStatus.DRAFT.name,
    @Column(name = "created_by") var createdBy: Long? = null,
    @Column(name = "reviewed_by") var reviewedBy: Long? = null,
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(name = "rollback_of_version") var rollbackOfVersion: Int? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String = "NiaFewShotVersionEntity(id=$id, setId=$setId, version=$version, status=$status)"
}

@Entity
@Table(name = "nexa_fewshot_example")
class NiaFewShotExampleEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "version_id") var versionId: Long = 0,
    @Column(name = "title") var title: String = "",
    @Column(name = "raw_messages_json") var rawMessagesJson: String = "",
    @Column(name = "expected_action") var expectedAction: String = "",
    @Column(name = "expected_replies_json") var expectedRepliesJson: String = "[]",
    @Column(name = "bad_replies_json") var badRepliesJson: String = "[]",
    @Column(name = "current_state") var currentState: String? = null,
    @Column(name = "expected_reaction_code") var expectedReactionCode: String? = null,
    @Column(name = "expected_reevaluate_after_ms") var expectedReevaluateAfterMs: Long? = null,
    @Column(name = "reason") var reason: String = "",
    @Column(name = "evidence_refs_json") var evidenceRefsJson: String = "",
    @Column(name = "bad_alternative_json") var badAlternativeJson: String = "",
    @Column(name = "tags_json") var tagsJson: String = "",
    @Column(name = "priority") var priority: Int = 0,
    @Column(name = "privacy_class") var privacyClass: String = NiaFewShotPrivacyClass.SYNTHETIC.name,
    @Column(name = "eval_status") var evalStatus: String = NiaFewShotEvalStatus.NOT_RUN.name,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NiaFewShotExampleEntity(id=$id, versionId=$versionId, expectedAction=$expectedAction, priority=$priority)"
}

interface NiaFewShotSetRepository : JpaRepository<NiaFewShotSetEntity, Long> {
    fun findByScopeKey(scopeKey: String): NiaFewShotSetEntity?

    fun findByScopeKeyIn(scopeKeys: Collection<String>): List<NiaFewShotSetEntity>

    @Query("SELECT s FROM NiaFewShotSetEntity s ORDER BY s.updatedAt DESC")
    fun findRecent(pageable: Pageable): List<NiaFewShotSetEntity>
}

interface NiaFewShotVersionRepository : JpaRepository<NiaFewShotVersionEntity, Long> {
    fun findBySetIdAndVersion(
        setId: Long,
        version: Int,
    ): NiaFewShotVersionEntity?

    fun findBySetIdOrderByVersionAsc(setId: Long): List<NiaFewShotVersionEntity>

    @Query("SELECT COALESCE(MAX(v.version), 0) FROM NiaFewShotVersionEntity v WHERE v.setId = :setId")
    fun maxVersionForSet(
        @Param("setId") setId: Long,
    ): Int
}

interface NiaFewShotExampleRepository : JpaRepository<NiaFewShotExampleEntity, Long> {
    fun findByVersionIdOrderByPriorityDescIdAsc(versionId: Long): List<NiaFewShotExampleEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM NiaFewShotExampleEntity e WHERE e.versionId = :versionId")
    fun deleteByVersionId(
        @Param("versionId") versionId: Long,
    ): Int
}

private fun NiaFewShotExample.toEntity(
    versionId: Long,
    createdAt: Instant,
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
): NiaFewShotExampleEntity =
    NiaFewShotExampleEntity(
        versionId = versionId,
        title = title.trim(),
        rawMessagesJson = mapper.writeValueAsString(rawMessages),
        expectedAction = expectedAction.name,
        expectedRepliesJson = mapper.writeValueAsString(expectedReplies),
        badRepliesJson = mapper.writeValueAsString(badReplies),
        currentState = currentState?.trim(),
        expectedReactionCode = expectedReactionCode,
        expectedReevaluateAfterMs = expectedReevaluateAfterMs,
        reason = reason.trim(),
        evidenceRefsJson = mapper.writeValueAsString(evidenceRefs.sorted()),
        badAlternativeJson = mapper.writeValueAsString(badAlternative),
        tagsJson = mapper.writeValueAsString(tags.sorted()),
        priority = priority,
        privacyClass = privacyClass.name,
        evalStatus = evalStatus.name,
        createdAt = createdAt,
    )

private fun NiaFewShotExampleEntity.toDomain(mapper: com.fasterxml.jackson.databind.ObjectMapper): NiaFewShotExample =
    NiaFewShotExample(
        id = id,
        title = title,
        rawMessages = mapper.readValue<List<NiaFewShotRawMessage>>(rawMessagesJson),
        expectedAction = NiaFewShotAction.valueOf(expectedAction),
        expectedReplies = mapper.readValue<List<String>>(expectedRepliesJson),
        badReplies = mapper.readValue<List<String>>(badRepliesJson),
        currentState = currentState,
        expectedReactionCode = expectedReactionCode,
        expectedReevaluateAfterMs = expectedReevaluateAfterMs,
        reason = reason,
        evidenceRefs = mapper.readValue<List<String>>(evidenceRefsJson).toSet(),
        badAlternative = mapper.readValue<NiaFewShotBadAlternative>(badAlternativeJson),
        tags = mapper.readValue<List<String>>(tagsJson).toSet(),
        priority = priority,
        privacyClass = NiaFewShotPrivacyClass.valueOf(privacyClass),
        evalStatus = NiaFewShotEvalStatus.valueOf(evalStatus),
    )
