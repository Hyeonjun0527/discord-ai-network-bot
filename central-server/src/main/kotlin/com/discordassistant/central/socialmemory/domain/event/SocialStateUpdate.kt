package com.discordassistant.central.socialmemory.domain.event

import com.discordassistant.central.participation.domain.model.state.ChannelScope
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import java.time.Instant

/**
 * 사회 상태 갱신 이벤트(NEXA-P06-T016, 순수 도메인 sealed 계약·불변).
 *
 * socialmemory projection 을 한 단계 전진시키는 **세 가지 관찰된 사실**을 닫힌 집합으로 정의한다 — 장면 갱신 수신
 * ([SceneUpdateObserved]), NEXA 자신의 행동([NexaActionObserved]), 사람의 결과 반응([HumanOutcomeObserved]).
 * conversation 의 SceneUpdated, participation 의 NEXA action, socialmemory 의 human outcome 이 이 한 계약으로
 * 상태를 갱신한다(deliverable T016).
 *
 * **acceptance(T016) — 각 update 가 idempotency key 와 projection version 을 가진다**:
 * 모든 변형이 [idempotencyKey] 와 [projectionVersion] 을 가진다. 같은 키·같은 version 의 update 는 한 번만 적용돼야
 * 한다(at-least-once 전달에서 중복 side effect 방지, ADR 0010 — 한 이벤트 = 관계 projection 1회). [sourceEventIds]
 * 로 모든 update 가 원천 이벤트로 환원·설명 가능하다(observable-state-policy 체크리스트 #7).
 *
 * 윤리(observable-state-policy): 원문/심리 라벨을 담지 않는다 — 닫힌 코드·식별자·카운트·시각만이다(불변식 1·2).
 * 순수성: Spring/JPA/JDA·ainetwork 엔티티 미참조. 표준 java.time 만 쓴다.
 */
sealed interface SocialStateUpdate {
    /**
     * 멱등성 키 — 같은 사실의 중복 전달을 식별한다. 소비자는 (idempotencyKey, projectionVersion) 으로 dedup 한다
     * (한 사실 = projection 1회 갱신).
     */
    val idempotencyKey: String

    /**
     * projection 무효화 버전 — 이 update 를 만든 projection 규칙 버전. 같은 키라도 version 이 다르면 재투영 대상이다
     * (재투영의 목적). conversation 의 contextVersion 과 같은 역할.
     */
    val projectionVersion: Long

    /** 이 update 를 뒷받침하는 원천 이벤트 ID 목록(provenance). 비어 있을 수 없다 — 관찰 근거가 있어야 한다. */
    val sourceEventIds: List<String>

    /** update 가 관찰된 시각(Clock 주입 — 도메인은 시각을 갖지 않는다). */
    val observedAt: Instant
}

/**
 * 장면 갱신이 socialmemory 로 관찰됨(NEXA-P06-T016). conversation 의 SceneUpdated 를 socialmemory 가 수신해
 * 채널 문화·참여 상태를 갱신하는 update 다. 원문 비포함 — 채널 스코프·장면 순번·contextVersion 메타만.
 */
data class SceneUpdateObserved(
    /** 갱신이 일어난 guild-scoped 채널(가명). */
    val scope: ChannelScope,
    /** SceneUpdated 멱등키의 순번 부분(채널 내 단조 증가 장면 순번). */
    val sceneSeq: Long,
    /** 장면이 알린 새 contextVersion(소비자가 직전 판단 재사용 여부를 비교). */
    val sceneContextVersion: Long,
    override val projectionVersion: Long,
    override val sourceEventIds: List<String>,
    override val observedAt: Instant,
) : SocialStateUpdate {
    init {
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
        require(sceneContextVersion >= 0) { "sceneContextVersion 은 음수일 수 없다" }
        require(projectionVersion >= 0) { "projectionVersion 은 음수일 수 없다" }
        require(sourceEventIds.isNotEmpty()) { "update 는 적어도 하나의 source event ID 를 가져야 한다(provenance)" }
        require(sourceEventIds.all { it.isNotBlank() }) { "source event ID 는 비어 있을 수 없다" }
    }

    override val idempotencyKey: String
        get() = "scene:${scope.guildPseudonym}:${scope.channelPseudonym}:$sceneSeq"
}

/**
 * NEXA 자신이 한 관찰 가능한 행동(NEXA-P06-T016). NEXA 가 특정 사용자에게 burst 1회를 향했음을 socialmemory 로
 * 전달해 관계 통계(reciprocity·familiarity)를 갱신한다. 사람을 프로파일링하지 않는다 — NEXA 자기 행동의 집계다.
 */
data class NexaActionObserved(
    /** 행동 대상 사용자의 guild-scoped 가명 관계 키. */
    val key: MemberKey,
    /** NEXA 행동 종류(닫힌 코드 — 자유 텍스트 아님). */
    val action: NexaActionKind,
    override val projectionVersion: Long,
    override val sourceEventIds: List<String>,
    override val observedAt: Instant,
) : SocialStateUpdate {
    init {
        require(projectionVersion >= 0) { "projectionVersion 은 음수일 수 없다" }
        require(sourceEventIds.isNotEmpty()) { "update 는 적어도 하나의 source event ID 를 가져야 한다(provenance)" }
        require(sourceEventIds.all { it.isNotBlank() }) { "source event ID 는 비어 있을 수 없다" }
    }

    override val idempotencyKey: String
        get() = "nexa-action:${key.guildPseudonym}:${key.memberPseudonym}:${action.wireName}:${sourceEventIds.first()}"
}

/**
 * 사람의 관찰된 결과 반응(NEXA-P06-T016). NEXA 행동 직후 사람이 보인 [InteractionOutcome](이어감·무응답·reaction
 * 등)을 socialmemory 로 전달해 결과 통계를 갱신한다. "기분/감정" 추론이 아니라 닫힌 행동 코드다(불변식 1).
 */
data class HumanOutcomeObserved(
    /** 결과가 관찰된 사용자의 guild-scoped 가명 관계 키. */
    val key: MemberKey,
    /** 관찰된 결과 코드(닫힌 집합). */
    val outcome: InteractionOutcome,
    override val projectionVersion: Long,
    override val sourceEventIds: List<String>,
    override val observedAt: Instant,
) : SocialStateUpdate {
    init {
        require(projectionVersion >= 0) { "projectionVersion 은 음수일 수 없다" }
        require(sourceEventIds.isNotEmpty()) { "update 는 적어도 하나의 source event ID 를 가져야 한다(provenance)" }
        require(sourceEventIds.all { it.isNotBlank() }) { "source event ID 는 비어 있을 수 없다" }
    }

    override val idempotencyKey: String
        get() = "human-outcome:${key.guildPseudonym}:${key.memberPseudonym}:${outcome.wireName}:${sourceEventIds.first()}"
}

/** NEXA 행동 종류(NEXA-P06-T016, 순수 enum). 닫힌 코드 — NEXA 가 한 관찰 가능한 행동만. */
enum class NexaActionKind(
    val wireName: String,
) {
    /** NEXA 가 이 사용자에게 말을 건(향한) burst. */
    ADDRESSED_MEMBER("addressed_member"),

    /** NEXA 가 사용자 호출에 응답한 burst. */
    RESPONDED_TO_MEMBER("responded_to_member"),
}
