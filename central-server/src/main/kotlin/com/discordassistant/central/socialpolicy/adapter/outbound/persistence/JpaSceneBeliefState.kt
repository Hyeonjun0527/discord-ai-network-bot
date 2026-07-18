package com.discordassistant.central.socialpolicy.adapter.outbound.persistence

import com.discordassistant.central.socialpolicy.application.port.out.SceneBeliefStatePort
import com.discordassistant.central.socialpolicy.application.port.out.SceneObservation
import com.discordassistant.central.socialpolicy.domain.model.CommonGroundBelief
import com.discordassistant.central.socialpolicy.domain.model.IntentHypothesisBelief
import com.discordassistant.central.socialpolicy.domain.model.RecentInteractionOutcomeBelief
import com.discordassistant.central.socialpolicy.domain.model.RecentNiaActionBelief
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefDelta
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefState
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 채널·스레드별 믿음 상태를 bounded JSON projection으로 저장한다. 원문 메시지는 저장하지 않는다. */
@Repository
class JpaSceneBeliefState(
    private val repository: SceneBeliefStateRepository,
    private val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules(),
) : SceneBeliefStatePort {
    @Transactional
    override fun observe(observation: SceneObservation): SceneBeliefState {
        val entity =
            repository.findForUpdate(observation.focusThreadKey)
                ?: SceneBeliefStateEntity(
                    guildPseudonym = observation.guildPseudonym,
                    channelId = observation.channelId,
                    focusThreadKey = observation.focusThreadKey,
                )
        if (entity.lastEvidenceRef == observation.evidenceRef) return entity.toDomain()

        if (observation.sceneSeq < entity.sceneSeq || observation.contextVersion < entity.contextVersion) {
            return entity.toDomain()
        }
        entity.sceneSeq = observation.sceneSeq
        entity.contextVersion = observation.contextVersion
        entity.stateVersion += 1
        entity.lastEvidenceRef = observation.evidenceRef
        entity.updatedAt = observation.observedAt
        return repository.save(entity).toDomain()
    }

    @Transactional(readOnly = true)
    override fun find(focusThreadKey: String): SceneBeliefState? = repository.findByFocusThreadKey(focusThreadKey)?.toDomain()

    @Transactional
    override fun applyDelta(
        focusThreadKey: String,
        expectedContextVersion: Long,
        delta: SceneBeliefDelta,
    ): SceneBeliefState? {
        val entity = repository.findForUpdate(focusThreadKey) ?: return null
        if (entity.contextVersion != expectedContextVersion) return null
        val updated = entity.toDomain().apply(delta)
        entity.commonGroundJson = mapper.writeValueAsString(updated.commonGround)
        entity.intentHypothesesJson = mapper.writeValueAsString(updated.intentHypotheses)
        entity.stateVersion += 1
        return repository.save(entity).toDomain()
    }

    @Transactional
    override fun recordAction(
        focusThreadKey: String,
        action: RecentNiaActionBelief,
    ): SceneBeliefState? {
        val entity = repository.findForUpdate(focusThreadKey) ?: return null
        val updated = entity.toDomain().record(action)
        entity.recentNiaActionsJson = mapper.writeValueAsString(updated.recentNiaActions)
        entity.stateVersion += 1
        return repository.save(entity).toDomain()
    }

    @Transactional
    override fun recordOutcome(
        focusThreadKey: String,
        outcome: RecentInteractionOutcomeBelief,
    ): SceneBeliefState? {
        val entity = repository.findForUpdate(focusThreadKey) ?: return null
        val updated = entity.toDomain().record(outcome)
        entity.recentOutcomesJson = mapper.writeValueAsString(updated.recentOutcomes)
        entity.stateVersion += 1
        return repository.save(entity).toDomain()
    }

    private fun SceneBeliefStateEntity.toDomain(): SceneBeliefState =
        SceneBeliefState(
            guildPseudonym = guildPseudonym,
            channelId = channelId,
            focusThreadKey = focusThreadKey,
            sceneSeq = sceneSeq,
            contextVersion = contextVersion,
            commonGround = mapper.readValue(commonGroundJson, COMMON_GROUND_TYPE),
            intentHypotheses = mapper.readValue(intentHypothesesJson, HYPOTHESES_TYPE),
            recentNiaActions = mapper.readValue(recentNiaActionsJson, ACTIONS_TYPE),
            recentOutcomes = mapper.readValue(recentOutcomesJson, OUTCOMES_TYPE),
            updatedAt = updatedAt,
        )

    private companion object {
        val COMMON_GROUND_TYPE = object : TypeReference<List<CommonGroundBelief>>() {}
        val HYPOTHESES_TYPE = object : TypeReference<List<IntentHypothesisBelief>>() {}
        val ACTIONS_TYPE = object : TypeReference<List<RecentNiaActionBelief>>() {}
        val OUTCOMES_TYPE = object : TypeReference<List<RecentInteractionOutcomeBelief>>() {}
    }
}

@Entity
@Table(name = "nexa_scene_belief_state")
class SceneBeliefStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: String = "",
    @Column(name = "focus_thread_key") var focusThreadKey: String = "",
    @Column(name = "scene_seq") var sceneSeq: Long = 0,
    @Column(name = "context_version") var contextVersion: Long = 0,
    @Column(name = "state_version") var stateVersion: Long = 0,
    @Column(name = "last_evidence_ref") var lastEvidenceRef: String? = null,
    @Column(name = "common_ground_json", columnDefinition = "TEXT") var commonGroundJson: String = "[]",
    @Column(name = "intent_hypotheses_json", columnDefinition = "TEXT") var intentHypothesesJson: String = "[]",
    @Column(name = "recent_nia_actions_json", columnDefinition = "TEXT") var recentNiaActionsJson: String = "[]",
    @Column(name = "recent_outcomes_json", columnDefinition = "TEXT") var recentOutcomesJson: String = "[]",
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
    @Version @Column(name = "lock_version") var lockVersion: Long = 0,
)

interface SceneBeliefStateRepository : JpaRepository<SceneBeliefStateEntity, Long> {
    fun findByFocusThreadKey(focusThreadKey: String): SceneBeliefStateEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SceneBeliefStateEntity s where s.focusThreadKey = :focusThreadKey")
    fun findForUpdate(
        @Param("focusThreadKey") focusThreadKey: String,
    ): SceneBeliefStateEntity?
}
