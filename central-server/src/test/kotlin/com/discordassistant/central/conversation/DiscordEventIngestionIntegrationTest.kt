package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.adapter.outbound.persistence.JpaEventStore
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaConversationOutboxRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.NexaEventRepository
import com.discordassistant.central.conversation.adapter.outbound.persistence.OutboxStatus
import com.discordassistant.central.conversation.application.ingest.ConsentSnapshot
import com.discordassistant.central.conversation.application.ingest.IngestDiscordEventService
import com.discordassistant.central.conversation.application.ingest.IngestEnvelope
import com.discordassistant.central.conversation.application.ingest.IngestOutcome
import com.discordassistant.central.conversation.application.ingest.MapperVersion
import com.discordassistant.central.conversation.application.ingest.ShardId
import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.SessionBoundaryObserved
import com.discordassistant.central.platform.discord.nexa.ContentSnapshot
import com.discordassistant.central.platform.discord.nexa.EmojiSnapshot
import com.discordassistant.central.platform.discord.nexa.JdaMessageEventMapper
import com.discordassistant.central.platform.discord.nexa.JdaMessageRevisionMapper
import com.discordassistant.central.platform.discord.nexa.JdaSessionBoundaryMapper
import com.discordassistant.central.platform.discord.nexa.JdaSocialEventMapper
import com.discordassistant.central.platform.discord.nexa.MessageCreatedSnapshot
import com.discordassistant.central.platform.discord.nexa.MessageDeletedSnapshot
import com.discordassistant.central.platform.discord.nexa.MessageSourceType
import com.discordassistant.central.platform.discord.nexa.MessageUpdatedSnapshot
import com.discordassistant.central.platform.discord.nexa.ReactionChangeSnapshot
import com.discordassistant.central.platform.discord.nexa.ReactionSnapshot
import com.discordassistant.central.platform.discord.nexa.SessionBoundaryKindSnapshot
import com.discordassistant.central.platform.discord.nexa.SessionBoundarySnapshot
import com.discordassistant.central.platform.discord.nexa.TypingSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P03-T023 JDA fake 기반 ingestion 통합 테스트(H2 + Flyway V51).
 *
 * "JDA fake" = 매퍼 입력 **스냅샷**(EventSnapshots)이다 — 매퍼의 매핑 단계가 JDA-free 순수 함수이므로(불변식 1)
 * JDA mock 없이 메시지 조각/수정/삭제/리액션/타이핑/재연결을 정규화한 뒤 실제 [IngestDiscordEventService] +
 * [JpaEventStore] 로 end-to-end 저장한다(test-fixtures/nexa/ingestion/ingestion-scenarios.v1.yaml 시나리오).
 *
 * acceptance: 중복·역순·미동의 케이스가 **DB 상태(event/ outbox 행)와 outbox 결과**로 검증된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DiscordEventIngestionIntegrationTest
    @Autowired
    constructor(
        private val events: NexaEventRepository,
        private val outbox: NexaConversationOutboxRepository,
    ) {
        private val clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC)
        private val store = JpaEventStore(events, outbox, clock)

        private val messageMapper = JdaMessageEventMapper()
        private val revisionMapper = JdaMessageRevisionMapper()
        private val socialMapper = JdaSocialEventMapper()
        private val sessionMapper = JdaSessionBoundaryMapper()

        private val guildId = 1001L
        private val channelId = 2002L
        private val authorA = 3003L
        private val reactorB = 4004L
        private val occurredBase = Instant.parse("2026-06-21T10:00:00Z")
        private val received = Instant.parse("2026-06-21T10:00:01Z")

        /** 동의 합성 fake — 기본 허용, 옵트아웃 사용자/제외 채널/비활성 길드만 거부. */
        private fun policy(
            disabledGuild: Boolean = false,
            optedOutUser: Long? = null,
            excludedChannel: Long? = null,
        ): ConsentPolicyPort =
            ConsentPolicyPort { g, u, c ->
                when {
                    disabledGuild -> ConsentDecision.DENIED
                    u == optedOutUser -> ConsentDecision.DENIED
                    c == excludedChannel -> ConsentDecision.DENIED
                    else -> ConsentDecision.OBSERVE_AND_SPEAK
                }
            }

        private fun service(policy: ConsentPolicyPort) = IngestDiscordEventService(policy, store)

        private fun envelope(event: NormalizedDiscordEvent): IngestEnvelope =
            IngestEnvelope(
                event = event,
                receivedAt = received,
                shardId = ShardId.NO_SHARD,
                sessionId = "sess-1",
                gatewaySequence = event.sourceSequence,
                mapperVersion = MapperVersion("t023-it"),
                consentSnapshot = ConsentSnapshot(observationAllowed = true, speechAllowed = true),
            )

        // ── 각 이벤트 종류의 정규화 이벤트 생성(JDA-free 스냅샷 → 매퍼) ──────────────

        private fun fragment(
            messageId: Long,
            seq: Long,
            author: Long = authorA,
            channel: Long = channelId,
        ): NormalizedDiscordEvent =
            messageMapper.toEvent(
                MessageCreatedSnapshot(
                    guildId = guildId,
                    channelId = channel,
                    messageId = messageId,
                    authorId = author,
                    sourceType = MessageSourceType.HUMAN,
                    content = ContentSnapshot.Readable("조각-$messageId"),
                    occurredAt = occurredBase.plusSeconds(seq),
                    receivedAt = received,
                    sourceSequence = seq,
                    replyToMessageId = null,
                    mentionedUserIds = emptySet(),
                    attachments = emptyList(),
                    threadId = null,
                ),
            )

        private fun edit(
            messageId: Long,
            revision: Long,
            seq: Long,
        ): NormalizedDiscordEvent =
            revisionMapper.toUpdated(
                MessageUpdatedSnapshot(
                    guildId = guildId,
                    channelId = channelId,
                    messageId = messageId,
                    revision = revision,
                    content = ContentSnapshot.Readable("수정-$revision"),
                    occurredAt = occurredBase.plusSeconds(seq),
                    receivedAt = received,
                    sourceSequence = seq,
                ),
            )

        private fun delete(
            messageId: Long,
            seq: Long,
        ): NormalizedDiscordEvent =
            revisionMapper.toDeleted(
                MessageDeletedSnapshot(
                    guildId = guildId,
                    channelId = channelId,
                    messageId = messageId,
                    occurredAt = occurredBase.plusSeconds(seq),
                    receivedAt = received,
                    sourceSequence = seq,
                ),
            )

        private fun reaction(
            messageId: Long,
            seq: Long,
            change: ReactionChangeSnapshot = ReactionChangeSnapshot.ADDED,
        ): NormalizedDiscordEvent =
            socialMapper.toReaction(
                ReactionSnapshot(
                    guildId = guildId,
                    channelId = channelId,
                    messageId = messageId,
                    actorId = reactorB,
                    emoji = EmojiSnapshot.Unicode("👍"),
                    change = change,
                    occurredAt = occurredBase.plusSeconds(seq),
                    receivedAt = received,
                    sourceSequence = seq,
                    burst = false,
                ),
            )

        private fun typing(seq: Long): NormalizedDiscordEvent =
            socialMapper.toTyping(
                TypingSnapshot(
                    guildId = guildId,
                    channelId = channelId,
                    actorId = authorA,
                    startedAt = occurredBase.plusSeconds(seq),
                    expiresAt = occurredBase.plusSeconds(seq + 10),
                    receivedAt = received,
                    sourceSequence = seq,
                ),
            )

        private fun reconnect(seq: Long): SessionBoundaryObserved =
            sessionMapper.toEvent(
                SessionBoundarySnapshot(
                    guildId = guildId,
                    channelId = channelId,
                    boundary = SessionBoundaryKindSnapshot.NEW_SESSION,
                    sessionId = "sess-2",
                    lastGatewaySequence = 999L,
                    occurredAt = occurredBase.plusSeconds(seq),
                    receivedAt = received,
                    sourceSequence = seq,
                ),
            )

        // ── 1) 모든 이벤트 종류 end-to-end 저장 ──────────────────────────────────────

        @Test
        fun `메시지 조각 수정 삭제 리액션 타이핑 재연결을 end-to-end 저장한다`() {
            val svc = service(policy())
            val all =
                listOf(
                    fragment(messageId = 10L, seq = 1),
                    edit(messageId = 10L, revision = 1, seq = 2),
                    reaction(messageId = 10L, seq = 3, change = ReactionChangeSnapshot.ADDED),
                    reaction(messageId = 10L, seq = 4, change = ReactionChangeSnapshot.REMOVED),
                    typing(seq = 5),
                    delete(messageId = 10L, seq = 6),
                    reconnect(seq = 7),
                )

            all.forEach { assertEquals(IngestOutcome.APPENDED, svc.ingest(envelope(it))) }

            // 7개 종류가 모두 적재되고 outbox 도 7개(원자적 동반).
            assertEquals(7, events.count())
            assertEquals(7, outbox.count())
            assertEquals(7, outbox.findByStatusOrderByIdAsc(OutboxStatus.PENDING.name).size)
        }

        // ── 2) 중복 재수신 → DB/ outbox 중복 없음 ─────────────────────────────────────

        @Test
        fun `중복 재수신은 DB 와 outbox 행을 늘리지 않고 DUPLICATE 결과를 낸다`() {
            val svc = service(policy())
            val frag = fragment(messageId = 20L, seq = 1)

            assertEquals(IngestOutcome.APPENDED, svc.ingest(envelope(frag)))
            assertEquals(IngestOutcome.DUPLICATE, svc.ingest(envelope(frag)))
            assertEquals(IngestOutcome.DUPLICATE, svc.ingest(envelope(frag)))

            assertEquals(1, events.count(), "중복 적재 없음(eventId 유니크)")
            assertEquals(1, outbox.count(), "중복 outbox 없음(멱등)")
        }

        // ── 3) 역순 도착 → 저장 받되 streamByChannel 이 sourceSequence 순서로 복원 ──────

        @Test
        fun `역순 도착 이벤트는 streamByChannel 에서 sourceSequence 순서로 복원된다`() {
            val svc = service(policy())
            // 늦은 순번(seq=3)이 먼저, 이른 순번(seq=1)이 나중에 도착.
            svc.ingest(envelope(fragment(messageId = 33L, seq = 3)))
            svc.ingest(envelope(fragment(messageId = 31L, seq = 1)))
            svc.ingest(envelope(fragment(messageId = 32L, seq = 2)))

            val ordered = store.streamByChannel(ChannelId(channelId)).map { it.sourceSequence }
            assertEquals(listOf(1L, 2L, 3L), ordered, "역순 도착이어도 내부 순서가 복원된다")
        }

        // ── 4) 미동의 → 적재 안 함(REJECTED_CONSENT), DB/ outbox 0 ─────────────────────

        @Test
        fun `미동의 길드 이벤트는 적재되지 않고 DB outbox 가 비어 있다`() {
            val svc = service(policy(disabledGuild = true))

            assertEquals(IngestOutcome.REJECTED_CONSENT, svc.ingest(envelope(fragment(messageId = 40L, seq = 1))))

            assertEquals(0, events.count())
            assertEquals(0, outbox.count())
        }

        @Test
        fun `옵트아웃 사용자와 제외 채널 이벤트도 적재되지 않는다`() {
            val optOut = service(policy(optedOutUser = authorA))
            assertEquals(IngestOutcome.REJECTED_CONSENT, optOut.ingest(envelope(fragment(messageId = 41L, seq = 1))))

            val excluded = service(policy(excludedChannel = channelId))
            assertEquals(IngestOutcome.REJECTED_CONSENT, excluded.ingest(envelope(fragment(messageId = 42L, seq = 2))))

            assertEquals(0, events.count())
            assertEquals(0, outbox.count())

            // 대조: 같은 채널에 다른(동의된) 사용자는 적재된다(차단 로직이 무조건 막는 게 아님).
            val allowed = service(policy(optedOutUser = authorA))
            assertEquals(
                IngestOutcome.APPENDED,
                allowed.ingest(envelope(fragment(messageId = 43L, seq = 3, author = reactorB))),
            )
            assertEquals(1, events.count())
        }

        // ── 5) 재연결 메타가 시퀀스 갭을 표시한다 ────────────────────────────────────

        @Test
        fun `재연결 새 세션 메타가 저장되고 시퀀스 갭을 표시한다`() {
            val svc = service(policy())
            val boundary = reconnect(seq = 1)

            assertEquals(IngestOutcome.APPENDED, svc.ingest(envelope(boundary)))
            assertTrue(boundary.createsSequenceGap, "새 세션 = 시퀀스 재시작 갭")
            assertEquals(1, events.count())
            // 저장된 행이 redaction 되지 않은 채로 존재(순서 보존).
            assertFalse(store.streamByChannel(ChannelId(channelId)).single().redacted)
        }
    }
