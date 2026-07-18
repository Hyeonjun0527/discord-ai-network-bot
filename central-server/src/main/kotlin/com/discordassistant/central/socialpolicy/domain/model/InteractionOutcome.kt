package com.discordassistant.central.socialpolicy.domain.model

import java.security.MessageDigest
import java.time.Instant

/** 니아 행동과 그 뒤 사람 반응을 원문 없이 연결하는 학습 가능한 궤적 계약이다. */
data class UnresolvedInteraction(
    val actionId: String,
    val focusThreadKey: String,
    val actionKind: String,
    val intentSummary: String?,
    val sourceEvidenceRef: String,
    val sentMessageRef: String?,
    val openedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(actionKind.matches(Regex("[a-z0-9_.-]{1,40}"))) { "actionKind 형식이 잘못됐다" }
        require(sourceEvidenceRef.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "source evidence ref 형식이 잘못됐다" }
        require(sentMessageRef == null || sentMessageRef.matches(Regex("discord_message:[a-f0-9]{64}"))) {
            "sent message ref 형식이 잘못됐다"
        }
        require(expiresAt.isAfter(openedAt)) { "interaction 만료는 생성 뒤여야 한다" }
    }
}

/** Discord snowflake 원문을 결과 귀속용 비가역 참조로 바꾼다. */
object InteractionEvidenceRef {
    fun discordMessage(messageId: String): String {
        require(messageId.isNotBlank()) { "messageId 는 비어 있을 수 없다" }
        return "discord_message:${sha256(messageId)}"
    }

    fun scheduledAction(actionId: String): String {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        return "scheduled_action:${sha256(actionId)}"
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

enum class ObservedOutcomeCode {
    HUMAN_FOLLOW_UP,
    POSITIVE_FEEDBACK,
    NEGATIVE_FEEDBACK,
    REPETITION_COMPLAINT,
    PROMISE_COMPLAINT,
    REPAIR_ACCEPTED,
}

data class ObservedInteractionOutcome(
    val actionId: String,
    val code: ObservedOutcomeCode,
    val evidenceRef: String,
    val observedAt: Instant,
) {
    init {
        require(actionId.isNotBlank()) { "actionId 는 비어 있을 수 없다" }
        require(evidenceRef.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "outcome evidence ref 형식이 잘못됐다" }
    }
}
