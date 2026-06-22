package com.discordassistant.central.participation.application.feature

/**
 * consent·safety eligibility mask 빌더(NEXA-P08-T016, application 레이어·순수 함수). 동의·채널 mute·권한·kill
 * switch 같은 하드 게이트를 [EligibilityMask] 로 결합한다 — feature 빌더(burst/thread/tempo/...)와 같은 `*Features`
 * 계열이지만, 결과는 feature 벡터가 아니라 후처리(PolicySafetyConstraint, T021)가 쓰는 **별도 mask 차원** 이다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
object EligibilityFeatures {
    /**
     * 동의·채널 mute·권한·kill switch 의 불리언 결합으로 [EligibilityMask] 를 만든다(휴리스틱 없음). 포함 관계를
     * 자동으로 보정한다 — 상위 행동은 하위 행동 허용 + 추가 게이트가 모두 통과해야 켜진다.
     *
     * @param consentGranted 동의가 있는가(없으면 관찰부터 전면 불가).
     * @param channelMuted 채널이 mute 됐는가(true 면 reaction/speak/send 불가, 관찰은 가능).
     * @param hasSendPermission Discord 전송 권한이 있는가(없으면 외부 전송 불가).
     * @param killSwitchEngaged 운영 kill switch 가 걸렸는가(true 면 speak/send 전면 차단).
     */
    fun mask(
        consentGranted: Boolean,
        channelMuted: Boolean,
        hasSendPermission: Boolean,
        killSwitchEngaged: Boolean,
    ): EligibilityMask {
        val canObserve = consentGranted
        val canReact = canObserve && !channelMuted && !killSwitchEngaged
        val canSpeak = canReact && !killSwitchEngaged
        val canSendExternal = canSpeak && hasSendPermission && !killSwitchEngaged
        return EligibilityMask(
            canObserve = canObserve,
            canReact = canReact,
            canSpeak = canSpeak,
            canSendExternal = canSendExternal,
        )
    }
}

/**
 * consent·safety eligibility mask(NEXA-P08-T016, application 레이어·순수 값 객체).
 *
 * 관찰/반응/발화/외부 전송 각각이 **허용되는가** 를 feature 확률과 **분리된 별도 mask** 로 만든다. 동의·채널 mute·
 * 권한·kill switch 같은 하드 제약이 각 행동 차원을 끄고, 후처리([PolicySafetyConstraint], T021)가 이 mask 로
 * 모델 분포에서 금지 행동을 제거한다.
 *
 * **acceptance(T016) — 모델 확률이 높아도 금지 행동은 후처리에서 제거된다**:
 * mask 는 feature 벡터에 섞이지 않는 **별도 차원** 이다(모델 입력 신호가 아니라 하드 게이트). 모델이 SPEAK 에
 * 0.9 를 줘도 [canSpeak] = false 면 후처리가 SPEAK 를 0 으로 만든다 — mask 가 확률을 이긴다. mask 는 휴리스틱이
 * 아니라 동의/권한/kill switch 의 불리언 결합이다(사회적 판단은 들어오지 않는다 — T021 경계와 일관).
 *
 * 행동 차원은 **포함 관계** 가 있다: 외부 전송하려면 발화 가능해야 하고, 발화하려면 반응 가능해야 하며, 반응하려면
 * 관찰 가능해야 한다([requireCoherent] 가 강제). 상위 행동이 켜졌는데 하위가 꺼진 모순을 막는다.
 *
 * 순수성 경계: application 레이어 — 표준 타입만. Spring/JPA/JDA 미참조.
 */
data class EligibilityMask(
    /** 관찰(장면 읽기·feature 계산)이 허용되는가. 동의 없는 채널이면 false(아무것도 관찰 안 함). */
    val canObserve: Boolean,
    /** reaction(이모지 등)이 허용되는가. 채널 mute·권한 부족이면 false. */
    val canReact: Boolean,
    /** 발화(SPEAK)가 허용되는가. feature gate·채널 mute·kill switch 면 false. */
    val canSpeak: Boolean,
    /** 외부 전송(actionruntime → Discord 발사)이 허용되는가. 권한·kill switch 면 false. */
    val canSendExternal: Boolean,
) {
    init {
        requireCoherent()
    }

    /**
     * 행동 차원의 포함 관계 강제: 외부 전송 ⊆ 발화 ⊆ 반응 ⊆ 관찰. 상위가 켜졌는데 하위가 꺼진 모순을 거부한다
     * (예: 관찰 불가인데 발화 가능 — 일관성 위반).
     */
    private fun requireCoherent() {
        require(!canReact || canObserve) { "반응이 허용되려면 관찰이 허용돼야 한다" }
        require(!canSpeak || canReact) { "발화가 허용되려면 반응이 허용돼야 한다" }
        require(!canSendExternal || canSpeak) { "외부 전송이 허용되려면 발화가 허용돼야 한다" }
    }

    companion object {
        /** 모든 행동 차원이 허용되는 mask(제약 없음). 후처리가 분포를 그대로 통과시킨다. */
        val ALLOW_ALL: EligibilityMask =
            EligibilityMask(canObserve = true, canReact = true, canSpeak = true, canSendExternal = true)

        /** 아무 행동도 허용되지 않는 mask(전면 차단 — kill switch·동의 철회 등). 후처리가 IGNORE 로 접는다. */
        val DENY_ALL: EligibilityMask =
            EligibilityMask(canObserve = false, canReact = false, canSpeak = false, canSendExternal = false)
    }
}
