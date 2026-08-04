package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.port.out.RawContextStorePort
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.participation.application.SafeDecision
import com.discordassistant.central.participation.application.catchup.NiaCatchUpCadence
import com.discordassistant.central.participation.application.catchup.NiaCatchUpClaim
import com.discordassistant.central.participation.application.catchup.NiaCatchUpJudgeResult
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant

class NiaCatchUpJudgeSchedulerTest {
    private val scope = NiaCatchUpScope(guildId = 10, channelId = 20)
    private val now = Instant.parse("2026-08-02T00:00:00Z")

    @Test
    fun `emit이 끝난 CATCH_UP 장면은 cursor 완료로 기록하고 재시도하지 않는다`() {
        val cadence = mock(NiaCatchUpCadence::class.java)
        val rawContextStore = mock(RawContextStorePort::class.java)
        val bridge = mock(NexaParticipationEmitBridge::class.java)
        val claim = claim()
        `when`(cadence.claimDue()).thenReturn(listOf(claim))
        `when`(rawContextStore.readRecent(anyRawScope())).thenReturn(snapshot(claim))
        `when`(bridge.onMessageTurn(anySignal())).thenReturn(
            ParticipationTurnOutcome(
                outcome =
                    ParticipationEmitOutcome.Emitted(
                        NexaSpeechEmitResult.notSpeaking(
                            SafeDecision(
                                finalAction = SocialActionKind.SPEAK,
                                safetyChanged = false,
                                removedKinds = emptySet(),
                                consumedGenerationQuota = false,
                            ),
                        ),
                    ),
                ownsTurn = false,
            ),
        )

        NiaCatchUpJudgeScheduler(cadence, rawContextStore, bridge).tick()

        verify(cadence).complete(claim, NiaCatchUpJudgeResult.NON_IGNORE)
    }

    @Test
    fun `삭제되거나 보존 만료된 원문은 Judge를 다시 호출하지 않고 완료 처리한다`() {
        val cadence = mock(NiaCatchUpCadence::class.java)
        val rawContextStore = mock(RawContextStorePort::class.java)
        val bridge = mock(NexaParticipationEmitBridge::class.java)
        val claim = claim()
        `when`(cadence.claimDue()).thenReturn(listOf(claim))
        `when`(rawContextStore.readRecent(anyRawScope())).thenReturn(RawContextSnapshot(scope.toRawScope(), emptyList()))

        NiaCatchUpJudgeScheduler(cadence, rawContextStore, bridge).tick()

        verify(cadence).complete(claim, NiaCatchUpJudgeResult.NON_IGNORE)
        verifyNoInteractions(bridge)
    }

    @Test
    fun `target 없는 손상 claim은 Judge 없이 ACTIVE 복귀로 종료한다`() {
        val cadence = mock(NiaCatchUpCadence::class.java)
        val rawContextStore = mock(RawContextStorePort::class.java)
        val bridge = mock(NexaParticipationEmitBridge::class.java)
        val claim = claim(target = null)
        `when`(cadence.claimDue()).thenReturn(listOf(claim))

        NiaCatchUpJudgeScheduler(cadence, rawContextStore, bridge).tick()

        verify(cadence).complete(claim, NiaCatchUpJudgeResult.NON_IGNORE)
        verifyNoInteractions(rawContextStore, bridge)
    }

    @Test
    fun `같은 CATCH_UP target 재실행은 장면 버전과 무관하게 같은 decision identity를 사용한다`() {
        val cadence = mock(NiaCatchUpCadence::class.java)
        val rawContextStore = mock(RawContextStorePort::class.java)
        val bridge = mock(NexaParticipationEmitBridge::class.java)
        val firstClaim = claim()
        val replayedClaim = firstClaim.copy(leaseToken = "lease-2")
        `when`(cadence.claimDue()).thenReturn(listOf(firstClaim), listOf(replayedClaim))
        `when`(rawContextStore.readRecent(anyRawScope())).thenReturn(snapshot(firstClaim))
        `when`(bridge.onMessageTurn(anySignal())).thenReturn(
            ParticipationTurnOutcome(
                outcome = ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE),
                ownsTurn = false,
            ),
        )

        val scheduler = NiaCatchUpJudgeScheduler(cadence, rawContextStore, bridge)
        scheduler.tick()
        scheduler.tick()

        val signals = ArgumentCaptor.forClass(ParticipationMessageSignal::class.java)
        verify(bridge, times(2)).onMessageTurn(captureSignal(signals))
        assertThat(signals.allValues.map { it.decisionIdOverride })
            .containsOnly(checkNotNull(signals.allValues.first().decisionIdOverride))
        assertThat(signals.allValues.first().decisionIdOverride).startsWith("catch-up:")
    }

    private fun claim(target: NiaCatchUpMessage? = message()): NiaCatchUpClaim =
        NiaCatchUpClaim(
            stateId = 1,
            scope = scope,
            target = target,
            leaseOwner = "worker-a",
            leaseToken = "lease-1",
        )

    private fun message(): NiaCatchUpMessage =
        NiaCatchUpMessage(
            scope = scope,
            messageId = 100,
            userId = 200,
            replyToMessageId = null,
            occurredAt = now,
            mentioned = false,
            replyToNia = false,
        )

    private fun snapshot(claim: NiaCatchUpClaim): RawContextSnapshot {
        val target = checkNotNull(claim.target)
        val rawScope = target.scope.toRawScope()
        return RawContextSnapshot(
            scope = rawScope,
            entries =
                listOf(
                    RawContextEntry(
                        scope = rawScope,
                        messageId = target.messageId,
                        authorPseudonym = "synthetic-user",
                        occurredAt = target.occurredAt,
                        replyToMessageId = target.replyToMessageId,
                        sourceType = RawContextSourceType.HUMAN,
                        content = RawContextContent.Available("synthetic replay message"),
                    ),
                ),
        )
    }

    private fun NiaCatchUpScope.toRawScope(): RawContextScope =
        RawContextScope(guildId = guildId, channelId = channelId, threadId = threadId)

    private fun anyRawScope(): RawContextScope = any(RawContextScope::class.java) ?: scope.toRawScope()

    private fun anySignal(): ParticipationMessageSignal =
        any(ParticipationMessageSignal::class.java)
            ?: ParticipationMessageSignal(
                guildId = scope.guildId,
                channelId = scope.channelId,
                messageId = 100,
                userId = 200,
                mentioned = false,
                recentTurns = emptyList(),
                sceneSeq = 100,
                contextVersion = 0,
                seed = 100,
            )

    private fun captureSignal(captor: ArgumentCaptor<ParticipationMessageSignal>): ParticipationMessageSignal =
        captor.capture()
            ?: ParticipationMessageSignal(
                guildId = scope.guildId,
                channelId = scope.channelId,
                messageId = 100,
                userId = 200,
                mentioned = false,
                recentTurns = emptyList(),
                sceneSeq = 100,
                contextVersion = 0,
                seed = 100,
            )
}
