package com.discordassistant.central.socialmemory.application.niamind

import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState

/**
 * 니아 사회 상태(관계 4축·감정) 영속 포트 — core 가 in-memory dict 로 들고 있던 상태의 NEXA 저장 추상화.
 *
 * 관계는 **사람별**([personId])·**서버별**([scope]), 감정은 **맥락별**([scope], 사람별 아님 — SSOT §11).
 * 구현(JPA + Flyway)은 후속 단계(별도 PR·DB 마이그레이션). 본 포트는 오케스트레이션이 의존하는 계약만 정의한다.
 */
interface NiaSocialStatePort {
    /** [personId]↔니아 관계 4축 조회(없으면 null = 기준점). [scope]=guild(+channel) 격리 키(I7). */
    fun loadRelationship(
        personId: String,
        scope: String,
    ): RelationshipState?

    /** 관계 4축 저장(갱신). [scope]=guild(+channel) 격리 키(I7) — RelationshipState 는 personId 만 가진다. */
    fun saveRelationship(
        scope: String,
        state: RelationshipState,
    )

    /** [scope] 의 니아 감정(reaction/mood) 조회(없으면 null = 기준점). 맥락별, 사람별 아님. */
    fun loadEmotion(scope: String): EmotionState?

    /** 감정 상태 저장(갱신). */
    fun saveEmotion(state: EmotionState)
}
