package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.conversation.application.ingest.ConsentSnapshot
import com.discordassistant.central.conversation.application.ingest.IngestDiscordEventService
import com.discordassistant.central.conversation.application.ingest.IngestEnvelope
import com.discordassistant.central.conversation.application.ingest.IngestOutcome
import com.discordassistant.central.conversation.application.ingest.MapperVersion
import com.discordassistant.central.conversation.application.ingest.ShardId
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventIdentity
import com.discordassistant.central.conversation.domain.model.event.EventType
import com.discordassistant.central.conversation.domain.model.event.GenericObservedEvent
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P15-T004 Discord inbound → conversation ingestion 브리지 단위 테스트.
 *
 * 핵심 acceptance: **flag OFF(legacy)면 ingestion 미호출(기존 동작 100% 보존)**, flag ON 이면 위임·멱등.
 */
class NexaInboundBridgeTest {
    @Test
    fun `acceptance — flag OFF 면 ingestion 을 호출하지 않는다(기존 동작 보존)`() {
        val store = CountingStore()
        val bridge = NexaInboundBridge(flagService(ShadowMode.OFF), ingestWith(store))

        val result = bridge.forward(envelope())

        assertThat(result).isEqualTo(ForwardResult.Skipped)
        assertThat(store.appendCalls).isZero() // ingestion 전혀 안 탐
    }

    @Test
    fun `flag ON(LIVE)이면 ingestion 으로 위임한다`() {
        val store = CountingStore()
        val bridge = NexaInboundBridge(flagService(ShadowMode.LIVE), ingestWith(store))

        val result = bridge.forward(envelope())

        assertThat(result).isEqualTo(ForwardResult.Ingested(IngestOutcome.APPENDED))
        assertThat(store.appendCalls).isEqualTo(1)
    }

    @Test
    fun `flag ON 이라도 ingestion 예외는 흡수한다(사용자 응답 보호)`() {
        val throwing = IngestDiscordEventService(AllowConsent, ThrowingStore)
        val bridge = NexaInboundBridge(flagService(ShadowMode.SHADOW_PREDICT), throwing)
        assertThat(bridge.forward(envelope())).isEqualTo(ForwardResult.Failed)
    }

    private fun ingestWith(store: EventStorePort) = IngestDiscordEventService(AllowConsent, store)

    private fun flagService(mode: ShadowMode) = NexaParticipationFlagService(FakeModeStore(mode), FakeFlagPort(), "OFF")

    private fun envelope(): IngestEnvelope =
        IngestEnvelope(
            event =
                GenericObservedEvent(
                    eventId = EventIdentity(discordId = 1L, type = EventType.MESSAGE_CREATED).toEventId(),
                    guildId = GuildId(7L),
                    channelId = ChannelId(100L),
                    occurredAt = Instant.EPOCH,
                    receivedAt = Instant.EPOCH,
                    sourceSequence = 1L,
                    privacyClass = PrivacyClass.MEDIUM,
                ),
            receivedAt = Instant.EPOCH,
            shardId = ShardId.NO_SHARD,
            sessionId = null,
            gatewaySequence = null,
            mapperVersion = MapperVersion("test-1"),
            consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = false),
        )

    private object AllowConsent : ConsentPolicyPort {
        override fun observationDecision(
            guildId: Long,
            userId: Long,
            channelId: Long,
        ) = com.discordassistant.central.conversation.domain.model.ConsentDecision.OBSERVE_AND_SPEAK
    }

    private abstract class StubStore : EventStorePort {
        override fun exists(eventId: com.discordassistant.central.conversation.domain.model.event.EventId): Boolean = false

        override fun streamByChannel(channelId: ChannelId) =
            emptyList<com.discordassistant.central.conversation.application.port.out.StoredEventRecord>()

        override fun streamByRange(
            from: Instant,
            to: Instant,
        ) = emptyList<com.discordassistant.central.conversation.application.port.out.StoredEventRecord>()

        override fun markRedacted(eventId: com.discordassistant.central.conversation.domain.model.event.EventId): Boolean = false
    }

    private class CountingStore : StubStore() {
        var appendCalls = 0

        override fun append(
            event: com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent,
        ): com.discordassistant.central.conversation.application.port.out.AppendResult {
            appendCalls++
            return com.discordassistant.central.conversation.application.port.out.AppendResult.APPENDED
        }
    }

    private object ThrowingStore : StubStore() {
        override fun append(
            event: com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent,
        ): com.discordassistant.central.conversation.application.port.out.AppendResult = error("boom")
    }

    private class FakeModeStore(
        private val mode: ShadowMode,
    ) : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = mode

        override fun applyTransition(audit: ShadowModeAudit) = Unit

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = emptyList()
    }

    private class FakeFlagPort : NexaParticipationFlagPort {
        override fun channelOverride(
            guildPseudonym: String,
            channelId: Long,
        ): ParticipationLane? = null

        override fun excludedChannelIds(guildPseudonym: String): Set<Long> = emptySet()

        override fun clearGuild(guildPseudonym: String) = Unit

        override fun setChannelOverride(
            guildPseudonym: String,
            channelId: Long,
            lane: ParticipationLane?,
        ) = Unit

        override fun setChannelExcluded(
            guildPseudonym: String,
            channelId: Long,
            excluded: Boolean,
        ) = Unit
    }
}
