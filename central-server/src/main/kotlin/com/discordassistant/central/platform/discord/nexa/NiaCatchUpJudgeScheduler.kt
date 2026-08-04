package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.port.out.RawContextStorePort
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.participation.application.catchup.NiaCatchUpCadence
import com.discordassistant.central.participation.application.catchup.NiaCatchUpClaim
import com.discordassistant.central.participation.application.catchup.NiaCatchUpJudgeResult
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** CATCH_UP 채널의 누적 원문을 같은 Final Judge에 주기적으로 전달하는 scheduler다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.participation.catch-up.enabled"], havingValue = "true")
class NiaCatchUpJudgeScheduler(
    private val cadence: NiaCatchUpCadence,
    private val rawContextStore: RawContextStorePort,
    private val participationEmitBridge: NexaParticipationEmitBridge,
) {
    private val log = LoggerFactory.getLogger(NiaCatchUpJudgeScheduler::class.java)

    @Scheduled(fixedDelayString = "\${central.nexa.participation.catch-up.poll-interval-millis:5000}")
    fun tick() {
        val claims =
            try {
                cadence.claimDue()
            } catch (e: Exception) {
                log.warn("NIA CATCH_UP due claim 실패 — 다음 tick에서 재시도: {}", e.message)
                return
            }
        claims.forEach(::process)
    }

    private fun process(claim: NiaCatchUpClaim) {
        try {
            val target = claim.target
            if (target == null) {
                log.warn("NIA CATCH_UP 대상 메타데이터 누락(state={}) — ACTIVE로 복귀", claim.stateId)
                cadence.complete(claim, NiaCatchUpJudgeResult.NON_IGNORE)
                return
            }
            val entry =
                rawContextStore
                    .readRecent(target.toRawContextScope())
                    .entries
                    .firstOrNull { it.messageId == target.messageId && it.sourceType == RawContextSourceType.HUMAN }
            val text = (entry?.content as? RawContextContent.Available)?.text
            if (text == null) {
                // 삭제·보존 만료된 원문은 다시 Judge에 보내지 않는다. 다음 새 메시지부터 ACTIVE로 시작한다.
                cadence.complete(claim, NiaCatchUpJudgeResult.NON_IGNORE)
                return
            }
            val outcome = participationEmitBridge.onMessageTurn(target.toParticipationSignal(text)).outcome
            val result =
                if (outcome == ParticipationEmitOutcome.Inactive) {
                    NiaCatchUpJudgeResult.NON_IGNORE
                } else {
                    outcome.toNiaCatchUpJudgeResult()
                }
            cadence.complete(claim, result)
        } catch (e: Exception) {
            log.warn("NIA CATCH_UP Judge 처리 실패(state={}) — 재시도: {}", claim.stateId, e.message)
            runCatching { cadence.complete(claim, NiaCatchUpJudgeResult.UNPROCESSED) }
        }
    }
}

private fun NiaCatchUpMessage.toRawContextScope(): RawContextScope =
    RawContextScope(
        guildId = scope.guildId,
        channelId = scope.channelId,
        threadId = scope.threadId,
    )

private fun NiaCatchUpMessage.toParticipationSignal(rawText: String): ParticipationMessageSignal =
    ParticipationMessageSignal(
        guildId = scope.guildId,
        channelId = scope.channelId,
        messageId = messageId,
        userId = userId,
        threadId = scope.threadId,
        replyToMessageId = replyToMessageId,
        sourceType = ParticipationMessageSourceType.HUMAN,
        mentioned = mentioned,
        recentTurns = emptyList(),
        triggerText = rawText.take(500),
        rawText = rawText,
        speakerLabel = "user_${userId % 100000}",
        replyToNia = replyToNia,
        replyToHuman = replyToMessageId != null && !replyToNia,
        tsMs = occurredAt.toEpochMilli(),
        sceneSeq = messageId,
        contextVersion = 0,
        seed = messageId,
        turnGeneration = messageId,
        rawContextPreCaptured = true,
        // 장면 projection이 이후 버전을 부여해도, 같은 CATCH_UP target의 전송 예약 identity는 항상 같다.
        decisionIdOverride = catchUpDecisionId(),
    )

private fun NiaCatchUpMessage.catchUpDecisionId(): String {
    val channel =
        ScopedPseudonymizer.pseudonymize(
            purpose = ScopedPseudonymizer.Purpose.MEMORY,
            guildId = scope.guildId,
            snowflake = scope.channelId,
        )
    val message =
        ScopedPseudonymizer.pseudonymize(
            purpose = ScopedPseudonymizer.Purpose.MEMORY,
            guildId = scope.guildId,
            snowflake = messageId,
        )
    return "catch-up:$channel:$message"
}
