package com.discordassistant.central.arch.nexafixture.downstream

/**
 * conversation 위반 fixture 가 의존할 하류(participation) 표식 클래스.
 * conversation self-test 규칙은 `..nexafixture.conversation..` 이 `..nexafixture.downstream..`
 * (participation/speech/actionruntime/socialmemory 역할)에 의존하는지를 검사한다.
 */
class FakeParticipationDecision
