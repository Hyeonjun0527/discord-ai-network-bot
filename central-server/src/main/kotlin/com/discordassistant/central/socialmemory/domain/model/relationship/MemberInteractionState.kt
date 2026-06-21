package com.discordassistant.central.socialmemory.domain.model.relationship

import java.time.Duration
import java.time.Instant

/**
 * NEXA 와 특정 사용자 사이의 **관찰된 상호작용 통계**(NEXA-P06-T004, 순수 도메인 값 객체·불변).
 *
 * 두 당사자가 실제로 한 행동의 집계만 담는다 — 주고받은 burst 수([nexaToMemberBursts]/[memberToNexaBursts]),
 * 관찰된 reaction 수, 평균 응답 지연, 최근 상호작용 시각. **관계 감정을 단정하지 않는다**("이 사람은 나를
 * 싫어한다" 추론 금지) — 빈도·최근성·reciprocity 같은 관찰 신호만이다(observable-state-policy 허용 목록).
 *
 * **acceptance(T004) — 전역 사용자 프로필이 아니라 guild-scoped key 를 사용한다**: 키가 [MemberKey] 라서
 * guild 가명 + 사용자 가명으로만 식별한다(cross-guild 연결 불가). ainetwork 호감도 score/stage 를 복제 저장하지
 * 않는다(ADR 0010 불변식 3 — 읽기 브리지는 후속 T020).
 *
 * 순수성: Spring/JPA/JDA·ainetwork 엔티티 미참조. 표준 java.time 만 쓴다.
 */
data class MemberInteractionState(
    /** guild-scoped 가명 관계 키(전역 프로필 아님). */
    val key: MemberKey,
    /** NEXA 가 이 사용자에게 말을 건(향한) burst 수. reciprocity 분자/분모(T006) 의 원천 카운트. */
    val nexaToMemberBursts: Int = 0,
    /** 이 사용자가 NEXA 에게 향한(응답·호출) burst 수. */
    val memberToNexaBursts: Int = 0,
    /** 두 당사자 사이에서 관찰된 reaction 수(누가 누구에게든 붙은 것). */
    val observedReactions: Int = 0,
    /** 한쪽 발화 후 상대가 응답하기까지의 평균 지연. null 이면 표본 없음. */
    val averageResponseDelay: Duration? = null,
    /** 가장 최근 상호작용 시각. 최근성(familiarity·decay) 의 기준. null 이면 상호작용 없음. */
    val lastInteractionAt: Instant? = null,
) {
    init {
        require(nexaToMemberBursts >= 0) { "nexaToMemberBursts 는 음수일 수 없다" }
        require(memberToNexaBursts >= 0) { "memberToNexaBursts 는 음수일 수 없다" }
        require(observedReactions >= 0) { "observedReactions 는 음수일 수 없다" }
    }

    /** 양방향 burst 합(교환 총량). familiarity(T005) 의 원천. */
    val totalExchangedBursts: Int
        get() = nexaToMemberBursts + memberToNexaBursts

    /** 한 번이라도 상호작용했는가(관찰 사실). */
    val hasInteracted: Boolean
        get() = lastInteractionAt != null

    /** NEXA 가 [at] 에 이 사용자에게 burst 1회를 향했음을 반영한 새 상태. */
    fun recordNexaToMember(at: Instant): MemberInteractionState =
        copy(nexaToMemberBursts = nexaToMemberBursts + 1, lastInteractionAt = latest(at))

    /** 사용자가 [at] 에 NEXA 에게 burst 1회를 향했음을 반영한 새 상태. */
    fun recordMemberToNexa(at: Instant): MemberInteractionState =
        copy(memberToNexaBursts = memberToNexaBursts + 1, lastInteractionAt = latest(at))

    private fun latest(at: Instant): Instant {
        val current = lastInteractionAt ?: return at
        return if (at.isAfter(current)) at else current
    }

    companion object {
        /** 아직 상호작용 없는 초기 상태(주어진 관계 키). */
        fun empty(key: MemberKey): MemberInteractionState = MemberInteractionState(key = key)
    }
}
