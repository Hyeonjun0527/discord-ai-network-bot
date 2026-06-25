package com.discordassistant.central.socialmemory.adapter.outbound.persistence.niamind

import com.discordassistant.central.socialmemory.application.niamind.NiaSocialStatePort
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 니아 사회 마음 상태(관계 4축·감정) JPA 어댑터 — [NiaSocialStatePort] 구현(V70 테이블).
 *
 * core 의 in-memory 상태를 NEXA 에 영속한다(ADR 0015). 원문 미저장(I10)·서버 격리(I7, scope 키)·가명(person)·
 * bounded 는 도메인이 보장한다. 본 어댑터는 도메인 [RelationshipState]/[EmotionState] ↔ 엔티티 변환만 한다.
 * upsert: (scope, person)·(scope) 유니크로 기존 행을 찾아 갱신, 없으면 생성.
 */
@Repository
class JpaNiaSocialState(
    private val relationships: NiaRelationshipStateRepository,
    private val emotions: NiaEmotionStateRepository,
) : NiaSocialStatePort {
    override fun loadRelationship(
        personId: String,
        scope: String,
    ): RelationshipState? =
        relationships.findByScopeAndPersonPseudonym(scope, personId)?.let {
            RelationshipState(
                personId = personId,
                familiarity = it.familiarity,
                affinity = it.affinity,
                trust = it.trust,
                comfort = it.comfort,
                lastUpdatedAt = it.lastUpdatedAt,
            )
        }

    override fun saveRelationship(
        scope: String,
        state: RelationshipState,
    ) {
        val entity =
            relationships.findByScopeAndPersonPseudonym(scope, state.personId)
                ?: NiaRelationshipStateEntity(scope = scope, personPseudonym = state.personId)
        entity.familiarity = state.familiarity
        entity.affinity = state.affinity
        entity.trust = state.trust
        entity.comfort = state.comfort
        entity.lastUpdatedAt = state.lastUpdatedAt
        relationships.save(entity)
    }

    override fun loadEmotion(scope: String): EmotionState? =
        emotions.findByScope(scope)?.let {
            EmotionState(contextScope = scope, reaction = it.reaction, mood = it.mood, lastUpdatedAt = it.lastUpdatedAt)
        }

    override fun saveEmotion(state: EmotionState) {
        val entity = emotions.findByScope(state.contextScope) ?: NiaEmotionStateEntity(scope = state.contextScope)
        entity.reaction = state.reaction
        entity.mood = state.mood
        entity.lastUpdatedAt = state.lastUpdatedAt
        emotions.save(entity)
    }
}

@Entity
@Table(name = "nia_relationship_state")
class NiaRelationshipStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "scope") var scope: String = "",
    @Column(name = "person_pseudonym") var personPseudonym: String = "",
    @Column(name = "familiarity") var familiarity: Double = 0.0,
    @Column(name = "affinity") var affinity: Double = 0.0,
    @Column(name = "trust") var trust: Double = 0.0,
    @Column(name = "comfort") var comfort: Double = 0.0,
    @Column(name = "last_updated_at") var lastUpdatedAt: Instant? = null,
)

@Entity
@Table(name = "nia_emotion_state")
class NiaEmotionStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "scope") var scope: String = "",
    @Column(name = "reaction") var reaction: Double = 0.0,
    @Column(name = "mood") var mood: Double = 0.0,
    @Column(name = "last_updated_at") var lastUpdatedAt: Instant? = null,
)

interface NiaRelationshipStateRepository : JpaRepository<NiaRelationshipStateEntity, Long> {
    fun findByScopeAndPersonPseudonym(
        scope: String,
        personPseudonym: String,
    ): NiaRelationshipStateEntity?
}

interface NiaEmotionStateRepository : JpaRepository<NiaEmotionStateEntity, Long> {
    fun findByScope(scope: String): NiaEmotionStateEntity?
}
