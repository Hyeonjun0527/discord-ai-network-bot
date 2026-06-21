package com.discordassistant.central.arch.nexafixture.participationsrc

import com.discordassistant.central.arch.nexafixture.downstreamadapter.FakeSpeechActionAdapter

/**
 * 금지 의존 #2 self-test fixture — participation 이 speech 문장 생성·actionruntime 전송 **구현**
 * (adapter 내부)에 직접 의존하는 위반.
 *
 * module-dag.md 금지 의존 #2: "participation은 speech 문장 생성·actionruntime 전송 구현에
 * 의존하지 않는다(포트만)." 이 가짜 participation 소스는 downstream adapter 내부 구현
 * (`..downstreamadapter..`)을 직접 import 하므로
 * `participationDoesNotDependOnDownstreamImplementationRule` 적용 시 AssertionError 로 실패한다.
 */
class FakeParticipationOrchestrator {
    fun reachIntoTransport(): FakeSpeechActionAdapter = FakeSpeechActionAdapter()
}
