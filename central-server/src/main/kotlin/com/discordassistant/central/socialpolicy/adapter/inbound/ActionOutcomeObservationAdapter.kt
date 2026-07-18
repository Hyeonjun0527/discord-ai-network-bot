package com.discordassistant.central.socialpolicy.adapter.inbound

import com.discordassistant.central.actionruntime.application.port.out.ActionOutcomeObservationPort
import com.discordassistant.central.actionruntime.domain.model.ScheduledSocialAction
import com.discordassistant.central.socialmemory.application.port.out.PendingIntentStore
import com.discordassistant.central.socialpolicy.application.port.out.InteractionOutcomePort
import com.discordassistant.central.socialpolicy.domain.model.InteractionEvidenceRef
import com.discordassistant.central.socialpolicy.domain.model.UnresolvedInteraction
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** 실제 실행이 확인된 행동만 미종결 interaction으로 연다. */
@Component
class ActionOutcomeObservationAdapter(
    private val outcomes: InteractionOutcomePort,
    private val pendingIntents: PendingIntentStore,
) : ActionOutcomeObservationPort {
    @Transactional
    override fun recordExecuted(
        action: ScheduledSocialAction,
        discordMessageId: String?,
        executedAt: Instant,
    ) {
        outcomes.open(
            UnresolvedInteraction(
                actionId = action.identity.value,
                focusThreadKey = action.target.threadId,
                actionKind = action.type.wireName,
                intentSummary = null,
                sourceEvidenceRef = InteractionEvidenceRef.scheduledAction(action.identity.value),
                sentMessageRef = discordMessageId?.let(InteractionEvidenceRef::discordMessage),
                openedAt = executedAt,
                expiresAt = executedAt.plus(OUTCOME_WINDOW),
            ),
        )
        action.fulfillsPendingIntentId?.let { intentId ->
            pendingIntents.complete(
                id = intentId,
                completedAt = executedAt,
                completedByActionId = action.identity.value,
            )
        }
    }

    private companion object {
        val OUTCOME_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
