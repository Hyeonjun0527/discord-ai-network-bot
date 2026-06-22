package com.discordassistant.central.arch.nexafixture.speechemit

// NEXA-P17 self-test fixture — speech-emit seam 우회 금지(security-reviewer enforcement seam).
//
// 발화 생성·전송은 반드시 speech-emit seam(platform.discord.nexa.NexaSpeechEmitService)과 그 빈 배선을 통과해야
// 한다. 외부 GLM 호출 adapter(speech.adapter.outbound.routing.RoutingCloudSpeechGenerationAdapter)를 seam 밖에서
// 직접 참조하면 allowlist payload 격리·critic·동의·고위험 fallback 을 우회할 수 있으므로 구조적으로 금지된다.
//
// 아래 가짜 클래스들은 그 우회를 흉내 낸다:
//  - RoutingCloudSpeechGenerationAdapter(이름 동일한 가짜) = 금지 대상(직접 호출 시 enforcement 우회).
//  - BypassingCaller = seam 밖에서 그 adapter 를 직접 참조하는 위반 호출자.
//
// speechEmitDoesNotBypassGenerationAdapterRule 빌더를 이 fixture 패키지에 적용하면 AssertionError 로 실패한다
// (실제 production 규칙은 빈 매칭에서 vacuous pass, allowEmptyShould(true)).

/** seam 밖 직접 호출이 금지된 외부 GLM 생성 adapter 의 이름-동일 가짜(이름 기반 탐지 대상). */
class RoutingCloudSpeechGenerationAdapter {
    fun generate(): String = "direct-glm-call-bypassing-critic-and-consent"
}

/** seam 을 우회해 생성 adapter 를 직접 참조하는 위반 호출자(allowlist/critic/consent 우회). */
class BypassingCaller(
    private val adapter: RoutingCloudSpeechGenerationAdapter,
) {
    fun speakWithoutEnforcement(): String = adapter.generate()
}
