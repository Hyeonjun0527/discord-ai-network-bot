package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.ConsentDecision

/**
 * conversation 수집 경계가 동의 상태를 **단일 포트로 조회**하는 아웃바운드 포트(헥사고날).
 *
 * 근거: consent-model.md(길드 관리자 동의), user-opt-out.md(개인 거부 우선), channel-scope.md(관찰/발화 2축,
 * 관찰 금지 채널). conversation 은 관찰만 하고 행동을 결정하지 않으므로(conversation-context.md), 동의 합성은
 * 이 단일 포트 뒤에 캡슐화하고 conversation 은 결과([ConsentDecision])만 소비한다.
 *
 * 구현 어댑터는 후속 task — 길드 활성화 / 개인 옵트아웃 / 채널 관찰·발화 허용을 각 도메인에서 조회해 합성한다.
 */
fun interface ConsentPolicyPort {
    /**
     * 주어진 (guild, user, channel) 맥락에서 관찰·발화 허용 여부를 단일 호출로 답한다.
     *
     * 관찰 허용 = 길드 활성화 AND [userId] 가 개인 옵트아웃 아님 AND [channelId] 관찰 허용 채널.
     * 어느 하나라도 거부면 [ConsentDecision.DENIED](관찰조차 시작하지 않음).
     */
    fun observationDecision(
        guildId: Long,
        userId: Long,
        channelId: Long,
    ): ConsentDecision
}
