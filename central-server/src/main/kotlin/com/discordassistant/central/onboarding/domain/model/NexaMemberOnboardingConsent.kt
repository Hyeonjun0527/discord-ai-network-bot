package com.discordassistant.central.onboarding.domain.model

/**
 * NEXA "AI 멤버 채널"(사람처럼 참여) 온보딩 동의 — **목적별 독립 선택**(NEXA-P15-T014, 순수 도메인 값 객체·불변).
 *
 * "채널 자동만들기" 버튼은 AI 질문채널(ASSISTANT)과 AI 멤버채널(NEXA MEMBER)을 둘 다 만들지만, 멤버채널의
 * 데이터 처리 동의는 **하나의 포괄 동의로 묶지 않는다**(P02 consent-model.md, 메모리 nexa_onboarding_and_policy_ui).
 * 운영자는 아래 4개 목적을 **각각 따로** 켠다 — 한 축을 켜도 다른 축이 자동으로 켜지지 않는다.
 *
 * | 축 | 의미 | 기본 |
 * | --- | --- | --- |
 * | [observeScope] | 관찰(메시지 수집) 범위 동의(P02 channel-scope observe) | false |
 * | [externalGlmAllowed] | 발화 생성에 외부 GLM(클라우드 LLM) 호출 허용(ADR 0006) | false |
 * | [liveSendAllowed] | shadow(미발화 관찰)가 아니라 실제 Discord 전송(live) 허용 | false |
 * | [learningOptIn] | 수집 데이터의 학습(모델 개선) 옵트인(P02 learning_opt_in) | false |
 *
 * **acceptance(T014) — 한 번의 포괄 동의로 모든 목적을 묶지 않는다**:
 *  - 기본값은 모두 false(봇 추가·버튼 클릭만으로는 어떤 목적도 켜지지 않는다 — fail-closed).
 *  - 한 축 true 가 다른 축을 암시하지 않는다([impliesAll] 같은 포괄 승격이 없다). 각 축은 저장도 따로(별도 컬럼).
 *  - 단, 안전 전제: 발화하려면(외부 GLM·live) 관찰이 선행돼야 한다(관찰 없이 발화 불가) — 이는 *추가 권한 부여*가
 *    아니라 *제약*이므로 포괄 동의가 아니다([validate] 가 모순 조합을 거부할 뿐, 한 동의가 다른 동의를 만들지 않는다).
 *
 * 순수성: Spring/JPA/JDA 미참조. onboarding.domain 규칙(NexaArchitectureTest 경계 밖이지만 도메인 순수 유지).
 */
data class NexaMemberOnboardingConsent(
    /** 관찰(메시지 수집) 범위 동의. false 면 NEXA 멤버 파이프라인이 아무것도 수집하지 않는다. */
    val observeScope: Boolean = false,
    /** 발화 생성 시 외부 GLM(클라우드 LLM) 호출 허용. 관찰과 독립 축. */
    val externalGlmAllowed: Boolean = false,
    /** 실제 Discord 전송(live) 허용. false = shadow(관찰·예측만, 전송 0). 관찰/GLM 과 독립 축. */
    val liveSendAllowed: Boolean = false,
    /** 수집 데이터 학습(모델 개선) 옵트인. 다른 어떤 축과도 독립. */
    val learningOptIn: Boolean = false,
) {
    init {
        // 안전 제약(권한 부여 아님): 관찰 없이 외부 GLM 발화/실제 전송을 켤 수 없다. 한 동의가 다른 동의를 만들지는 않는다.
        require(observeScope || (!externalGlmAllowed && !liveSendAllowed)) {
            "관찰(observeScope) 동의 없이 외부 GLM 또는 실제 전송을 켤 수 없다"
        }
    }

    /** 어떤 목적이든 하나라도 켜졌는가(버튼이 NEXA 멤버 처리를 시작할지 판단 — 모두 꺼졌으면 ASSISTANT 채널만 의미). */
    val anyOptedIn: Boolean
        get() = observeScope || externalGlmAllowed || liveSendAllowed || learningOptIn

    companion object {
        /** 아무 목적도 동의하지 않은 기본값(봇 추가·버튼 클릭 직후 — fail-closed). */
        val NONE: NexaMemberOnboardingConsent = NexaMemberOnboardingConsent()
    }
}
