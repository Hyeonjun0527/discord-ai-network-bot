package com.discordassistant.central.arch.nexafixture.speech

import net.dv8tion.jda.api.JDA

// T022 self-test fixture — speech 금지 의존(module-dag.md #3, speech-context.md).
// speech.domain 은 routing 의 `CloudLlm` 포트만 호출해야 하고, JDA·provider-agent glm·Z.AI SDK
// 타입에 직접 의존하면 안 된다. 아래 가짜 speech 도메인은 둘 다 위반한다.
// `speechHasNoForbiddenBackendDependencyRule` 빌더를 이 패키지에 적용하면 AssertionError 로 실패한다.

/** provider-agent glm / Z.AI SDK 타입을 흉내 낸 식별자(이름 기반 탐지 대상). */
class GlmZaiClient {
    fun model(): String = "glm-5.1"
}

/** JDA + glm 타입에 직접 의존하는 위반 speech 도메인. */
class FakeSpeechPlanner(
    private val client: GlmZaiClient,
) {
    fun jda(): Class<JDA> = JDA::class.java
}
