package com.discordassistant.central.arch.nexafixture.conversation

import com.discordassistant.central.arch.nexafixture.downstream.FakeParticipationDecision

/**
 * T022 self-test fixture — conversation 금지 의존(module-dag.md #1, conversation-context.md).
 *
 * conversation(관찰)은 하류 participation/speech/actionruntime/socialmemory 를 모른다.
 * 아래 가짜 conversation 도메인이 하류(participation)에 의존하므로
 * `conversationDoesNotKnowDownstreamRule` 적용 시 AssertionError 로 실패한다.
 */
class FakeSceneProjection {
    fun leak(): FakeParticipationDecision = FakeParticipationDecision()
}
