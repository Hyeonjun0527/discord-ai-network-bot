package com.discordassistant.central.socialpolicy

import com.discordassistant.central.actionruntime.support.typingSpeakAction
import com.discordassistant.central.socialmemory.application.port.out.PendingIntentStore
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.intent.IntentActivation
import com.discordassistant.central.socialmemory.domain.model.intent.IntentUrgency
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.intent.SocialAct
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource
import com.discordassistant.central.socialpolicy.adapter.inbound.ActionOutcomeObservationAdapter
import com.discordassistant.central.socialpolicy.application.port.out.InteractionOutcomePort
import com.discordassistant.central.socialpolicy.domain.model.ObservedInteractionOutcome
import com.discordassistant.central.socialpolicy.domain.model.ObservedOutcomeCode
import com.discordassistant.central.socialpolicy.domain.model.UnresolvedInteraction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ActionOutcomeObservationAdapterTest {
    @Test
    fun `실제 실행 action ID가 있는 성공 관찰만 열린 약속을 완료한다`() {
        val now = Instant.parse("2026-07-17T00:00:00Z")
        val intent = pendingIntent(now)
        val pending = RecordingPendingIntentStore(intent)
        val outcomes = RecordingOutcomes()
        val adapter = ActionOutcomeObservationAdapter(outcomes, pending)
        val action = typingSpeakAction().copy(fulfillsPendingIntentId = intent.id)

        adapter.recordExecuted(action, "123456789", now.plusSeconds(5))

        assertThat(outcomes.opened).hasSize(1)
        assertThat(pending.current.status).isEqualTo(MemoryStatus.COMPLETED)
        assertThat(pending.current.completedAt).isEqualTo(now.plusSeconds(5))
        assertThat(pending.current.completedByActionId).isEqualTo(action.identity.value)
    }

    private fun pendingIntent(now: Instant): PendingIntent =
        PendingIntent(
            id = "promise-1",
            visibility = VisibilityScope.Channel("g", "c"),
            topic = "재미있는 이야기",
            targetPseudonym = "u",
            socialAct = SocialAct.TELL_STORY,
            activation = IntentActivation.IMMEDIATE,
            urgency = IntentUrgency.NORMAL,
            source = MemorySource(setOf("message:1"), 1, true, now),
            expiresAt = now.plusSeconds(3600),
            confidence = 0.9,
            focusThreadKey = "thread-1",
        )

    private class RecordingPendingIntentStore(
        initial: PendingIntent,
    ) : PendingIntentStore {
        var current: PendingIntent = initial

        override fun save(intent: PendingIntent): PendingIntent = intent.also { current = it }

        override fun findActive(
            focusThreadKey: String,
            now: Instant,
        ): List<PendingIntent> = listOf(current).filter { it.focusThreadKey == focusThreadKey && it.isActiveAt(now) }

        override fun complete(
            id: String,
            completedAt: Instant,
            completedByActionId: String,
        ): PendingIntent? {
            if (current.id != id || current.status != MemoryStatus.ACTIVE) return null
            return current.complete(completedAt, completedByActionId).also { current = it }
        }

        override fun invalidate(id: String): PendingIntent? = null

        override fun invalidateBySource(sourceEventId: String): Int = 0
    }

    private class RecordingOutcomes : InteractionOutcomePort {
        val opened = mutableListOf<UnresolvedInteraction>()

        override fun open(interaction: UnresolvedInteraction): Boolean = opened.add(interaction)

        override fun observeLatest(
            focusThreadKey: String,
            code: ObservedOutcomeCode,
            evidenceRef: String,
            replyToMessageRef: String?,
            observedAt: Instant,
            explicitActionId: String?,
        ): ObservedInteractionOutcome? = null

        override fun invalidateByEvidence(evidenceRef: String): Int = 0
    }
}
