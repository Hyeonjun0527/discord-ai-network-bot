package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.application.catchup.NiaCatchUpJudgeResult
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import java.time.Instant

/** Discord ingress signal에서 CATCH_UP 상태에 필요한 비원문 메타데이터만 뽑는다. */
internal fun ParticipationMessageSignal.toNiaCatchUpMessage(): NiaCatchUpMessage =
    NiaCatchUpMessage(
        scope = NiaCatchUpScope(guildId = guildId, channelId = channelId, threadId = threadId),
        messageId = messageId,
        userId = userId,
        replyToMessageId = replyToMessageId,
        occurredAt = Instant.ofEpochMilli(tsMs.coerceAtLeast(0)),
        mentioned = mentioned,
        replyToNia = replyToNia,
    )

/**
 * 실패·장면 교체만 재시도하고, 발화 예약·규칙 처리·주의력 보류처럼 이번 장면의 처리가 끝난 결과는 cursor를 전진시킨다.
 * 그렇지 않으면 같은 CATCH_UP 장면을 다시 Judge해 중복 비용이나 중복 발화 후보가 생긴다.
 */
internal fun ParticipationEmitOutcome.toNiaCatchUpJudgeResult(): NiaCatchUpJudgeResult =
    when (this) {
        is ParticipationEmitOutcome.NotSpeaking ->
            if (action == SocialActionKind.IGNORE) NiaCatchUpJudgeResult.IGNORE else NiaCatchUpJudgeResult.NON_IGNORE
        is ParticipationEmitOutcome.ShadowPredicted ->
            if (action == SocialActionKind.IGNORE) NiaCatchUpJudgeResult.IGNORE else NiaCatchUpJudgeResult.NON_IGNORE
        is ParticipationEmitOutcome.RuleSilent -> NiaCatchUpJudgeResult.IGNORE
        ParticipationEmitOutcome.Inactive -> NiaCatchUpJudgeResult.NON_IGNORE
        is ParticipationEmitOutcome.Emitted,
        is ParticipationEmitOutcome.SchedulingRejected,
        is ParticipationEmitOutcome.RuleWait,
        is ParticipationEmitOutcome.AttentionDeferred,
        -> NiaCatchUpJudgeResult.NON_IGNORE
        is ParticipationEmitOutcome.Superseded,
        ParticipationEmitOutcome.Failed,
        -> NiaCatchUpJudgeResult.UNPROCESSED
    }
