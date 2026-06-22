package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeCandidate
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeDistribution
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId

/**
 * 직접 mention 대상 resolver(NEXA-P05-T006, 순수 함수). 본문 mention(`<@id>`)과 닉네임 문자열 호출("철수야")을
 * **별도 feature** 로 처리한다 — 전자는 강한 신호, 후자는 약한 신호다.
 *
 * **acceptance(T006) — 문자열 이름 일치만으로 확정 target 을 만들지 않는다**:
 * - 본문 [directMentions] 가 있으면 그 member 들에 [config].mentionConfidence 를 나눠 준다(확정에 가까움).
 * - [nicknameMatches] 만 있고 본문 mention 이 없으면, 닉네임 일치는 **약한 신호** 라 후보 확률을
 *   [config].nicknameConfidence(낮음)로만 주고 [AddresseeEvidence.NICKNAME_STRING] 를 남긴다 — 단독으로
 *   1.0 확정 target 을 만들지 않는다(none 확률이 항상 남는다).
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음. 원문 텍스트가 아니라 **이미 매칭된 member id** 만 입력으로 받는다
 * (문자열 매칭은 상류 어댑터/feature 의 책임 — 도메인은 원문을 보지 않는다).
 */
object MentionAddresseeResolver {
    const val VERSION: String = "mention-v1"

    /**
     * mention 신호로부터 addressee 분포를 만든다.
     *
     * @param directMentions 본문 직접 mention 된 member 들(강한 신호; 중복은 무시).
     * @param nicknameMatches 닉네임 문자열로 호명됐을 가능성이 있는 member 들(약한 신호; 중복은 무시).
     */
    fun resolve(
        directMentions: Set<AuthorId>,
        nicknameMatches: Set<AuthorId>,
        config: MentionResolverConfig = MentionResolverConfig.DEFAULT,
    ): AddresseeDistribution {
        // 본문 직접 mention 우선 — 강한 신호. 여러 명이면 확신도를 균등 분배.
        if (directMentions.isNotEmpty()) {
            val per = config.mentionConfidence / directMentions.size
            val candidates = directMentions.map { AddresseeCandidate(member = it, probability = per) }
            return AddresseeDistribution(
                candidates = candidates,
                noneProbability = 1.0 - config.mentionConfidence,
                resolverVersion = VERSION,
                evidence = setOf(AddresseeEvidence.DIRECT_MENTION),
            )
        }

        // 닉네임 문자열만 — 약한 신호. 단독으로 확정하지 않는다(none 확률 큼).
        if (nicknameMatches.isNotEmpty()) {
            val per = config.nicknameConfidence / nicknameMatches.size
            val candidates = nicknameMatches.map { AddresseeCandidate(member = it, probability = per) }
            return AddresseeDistribution(
                candidates = candidates,
                noneProbability = 1.0 - config.nicknameConfidence,
                resolverVersion = VERSION,
                evidence = setOf(AddresseeEvidence.NICKNAME_STRING),
            )
        }

        // mention 신호 없음 — none.
        return AddresseeDistribution.none(resolverVersion = VERSION)
    }
}

/** 직접 mention resolver 설정(주입). nickname 은 본문 mention 보다 약하게(낮은 확신도) 둔다. */
data class MentionResolverConfig(
    /** 본문 직접 mention 의 총 확신도(여러 명이면 균등 분배). */
    val mentionConfidence: Double = 0.85,
    /** 닉네임 문자열 호명의 약한 총 확신도 — 단독으로 확정 못 하도록 낮게(none 확률이 크게 남는다). */
    val nicknameConfidence: Double = 0.4,
) {
    init {
        require(mentionConfidence in 0.0..1.0) { "mentionConfidence 는 [0,1] 범위여야 한다" }
        require(nicknameConfidence in 0.0..1.0) { "nicknameConfidence 는 [0,1] 범위여야 한다" }
        require(nicknameConfidence < 1.0) { "nicknameConfidence 는 1.0 미만이어야 확정 target 을 막는다" }
    }

    companion object {
        val DEFAULT: MentionResolverConfig = MentionResolverConfig()
    }
}
