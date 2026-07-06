package com.discordassistant.central.participation.application.judge

import com.discordassistant.central.participation.domain.model.action.SocialActionKind

/**
 * 원문 장면을 읽는 단일 participation judge.
 *
 * 기존 ONNX/feature 정책 계약은 원문을 담지 않는다. 이 포트는 니아 자발 발화 전용으로, 암호화 raw-context store 에서
 * 꺼낸 quoted scene 을 LLM judge 에만 넘겨 "말할지/기다릴지/반응만 할지/침묵할지"를 하나로 고르게 한다.
 */
fun interface RawParticipationJudgePort {
    fun decide(request: RawParticipationJudgeRequest): RawParticipationJudgeDecision?

    object Noop : RawParticipationJudgePort {
        override fun decide(request: RawParticipationJudgeRequest): RawParticipationJudgeDecision? = null
    }
}

data class RawParticipationJudgeRequest(
    val guildPseudonym: String,
    val channelId: String,
    val triggerMessageId: Long,
    val triggerText: String,
    val mentioned: Boolean,
    val replyToNia: Boolean,
    val replyToOtherUser: Boolean,
    val quotedSceneData: String,
    val omittedOldestCount: Int,
    val seed: Long,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(triggerMessageId >= 0) { "triggerMessageId 는 음수일 수 없다: $triggerMessageId" }
        require(quotedSceneData.isNotBlank()) { "quotedSceneData 는 비어 있을 수 없다" }
        require(omittedOldestCount >= 0) { "omittedOldestCount 는 음수일 수 없다: $omittedOldestCount" }
    }
}

data class RawParticipationJudgeDecision(
    val action: SocialActionKind,
    val confidence: Double,
    val reasonCode: String,
    val modelVersion: String,
) {
    init {
        require(action != SocialActionKind.CANCEL_PENDING) { "raw judge 는 CANCEL_PENDING 을 직접 고르지 않는다" }
        require(confidence in 0.0..1.0) { "confidence 는 [0,1] 범위여야 한다: $confidence" }
        require(reasonCode.isNotBlank()) { "reasonCode 는 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
    }
}
