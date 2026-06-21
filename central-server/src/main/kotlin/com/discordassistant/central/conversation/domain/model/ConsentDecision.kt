package com.discordassistant.central.conversation.domain.model

/**
 * 동의 정책 합성 결과(NEXA conversation 도메인 소유의 순수 타입).
 *
 * 근거: consent-model.md(길드 관리자 동의), user-opt-out.md(개인 거부 우선), channel-scope.md(관찰/발화 2축).
 * 동의 전엔 관찰조차 시작하지 않는다 — [observationAllowed] 가 false 면 conversation 수집 경계는 아무것도 수집하지
 * 않는다. [speechAllowed] 는 별개 축(관찰은 허용되나 발화는 금지될 수 있다).
 *
 * conversation.domain 순수성 규칙(NexaArchitectureTest.nexaDomainsArePure)을 지키기 위해 Spring/JPA/JDA/adapter
 * 타입을 일절 참조하지 않는다.
 */
data class ConsentDecision(
    /** 관찰(메시지 수집) 허용 여부 — 길드 활성화 AND 개인 옵트아웃 아님 AND 채널 관찰 허용의 합성. */
    val observationAllowed: Boolean,
    /** 발화(자율 참여) 허용 여부 — 관찰과 별개 축. 관찰이 막히면 발화도 막힌다. */
    val speechAllowed: Boolean,
) {
    init {
        // 불변식: 관찰이 금지되면 발화도 금지된다(관찰 없이 발화할 수 없다, channel-scope.md 2축).
        require(observationAllowed || !speechAllowed) {
            "speechAllowed 는 observationAllowed 없이 true 일 수 없다(관찰 전제)"
        }
    }

    companion object {
        /** 관찰·발화 모두 금지(동의 없음 또는 거부). conversation 수집 경계의 안전 기본값(fail-closed). */
        val DENIED: ConsentDecision = ConsentDecision(observationAllowed = false, speechAllowed = false)

        /** 관찰만 허용, 발화는 금지(관찰 동의는 있으나 발화 비활성 채널/정책). */
        val OBSERVE_ONLY: ConsentDecision = ConsentDecision(observationAllowed = true, speechAllowed = false)

        /** 관찰·발화 모두 허용. */
        val OBSERVE_AND_SPEAK: ConsentDecision = ConsentDecision(observationAllowed = true, speechAllowed = true)
    }
}
