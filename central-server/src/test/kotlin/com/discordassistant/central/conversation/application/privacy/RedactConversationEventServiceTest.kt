package com.discordassistant.central.conversation.application.privacy

import com.discordassistant.central.conversation.application.port.out.AppendResult
import com.discordassistant.central.conversation.application.port.out.EventStorePort
import com.discordassistant.central.conversation.application.port.out.StoredEventRecord
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField

/**
 * NEXA-P03-T022 event store redaction 유스케이스 + redaction-contract.md 회귀 테스트.
 *
 * acceptance:
 * - 삭제/옵트아웃 트리거 시 암호화 payload 가 무효화되고(markRedacted), provenance+처리 증거만 남는다.
 * - redacted 이벤트는 replay(streamByChannel)에서 content unavailable(redacted=true)로 일관되게 보인다.
 *
 * **redaction-contract.md 금지 필드 부재 증명(보안 핵심)**: [RedactionReceipt] 가 원문/snowflake 원문/키/비가역
 * hash 를 담는 필드를 **구조적으로 갖지 않음**을 reflection 으로 고정한다(회귀 시 테스트 실패).
 */
class RedactConversationEventServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC)

    /**
     * content_cipher 와 redaction 상태를 흉내내는 in-memory event store fake. markRedacted 가 cipher 를 무효화하고
     * 상태를 전이한다(JpaEventStore 의미와 동일). replay 표면(streamByChannel)이 redacted 상태를 일관되게 노출한다.
     */
    private class FakeEventStore : EventStorePort {
        private data class Row(
            val eventId: String,
            val channelId: Long,
            var contentCipher: String?,
            var redacted: Boolean,
        )

        private val rows = mutableMapOf<String, Row>()

        fun seed(
            eventId: String,
            channelId: Long,
            cipher: String?,
        ) {
            rows[eventId] = Row(eventId, channelId, cipher, redacted = false)
        }

        fun cipherOf(eventId: String): String? = rows[eventId]?.contentCipher

        override fun append(event: NormalizedDiscordEvent): AppendResult = AppendResult.APPENDED

        override fun exists(eventId: EventId): Boolean = rows.containsKey(eventId.value)

        override fun streamByChannel(channelId: ChannelId): List<StoredEventRecord> =
            rows.values
                .filter { it.channelId == channelId.value }
                .map {
                    StoredEventRecord(
                        eventId = EventId(it.eventId),
                        channelId = ChannelId(it.channelId),
                        occurredAt = Instant.EPOCH,
                        receivedAt = Instant.EPOCH,
                        sourceSequence = 0L,
                        redacted = it.redacted,
                    )
                }

        override fun streamByRange(
            from: Instant,
            to: Instant,
        ): List<StoredEventRecord> = emptyList()

        override fun markRedacted(eventId: EventId): Boolean {
            val row = rows[eventId.value] ?: return false
            if (row.redacted) return false
            row.redacted = true
            row.contentCipher = null // 암호화 payload 무효화(존재·순서는 보존).
            return true
        }
    }

    @Test
    fun `redaction 은 암호화 payload 를 무효화하고 원문 없는 처리 증거를 남긴다`() {
        val store = FakeEventStore().apply { seed("evt-1", channelId = 10L, cipher = "enc1:CIPHERTEXT") }
        val service = RedactConversationEventService(store, clock)

        val result = service.redact(EventId("evt-1"), RedactionTrigger.MESSAGE_DELETED)

        assertEquals(RedactionOutcome.REDACTED, result.outcome)
        assertNull(store.cipherOf("evt-1"), "암호화 payload 가 무효화된다")
        val receipt = result.receipt
        assertNotNull(receipt, "처음 redaction 이면 처리 증거가 있다")
        receipt!!
        assertEquals(EventId("evt-1"), receipt.eventId)
        assertEquals(RedactionTrigger.MESSAGE_DELETED, receipt.trigger)
        assertEquals(clock.instant(), receipt.processedAt)
    }

    @Test
    fun `redacted 이벤트는 replay 에서 content unavailable 로 일관되게 보인다`() {
        val store = FakeEventStore().apply { seed("evt-2", channelId = 20L, cipher = "enc1:X") }
        val service = RedactConversationEventService(store, clock)

        service.redact(EventId("evt-2"), RedactionTrigger.CONSENT_WITHDRAWAL)

        val record = store.streamByChannel(ChannelId(20L)).single()
        assertTrue(record.redacted, "replay 에서 redacted=true → content unavailable 로 일관 노출")
    }

    @Test
    fun `이미 redaction 됐거나 대상이 없으면 멱등 흡수한다`() {
        val store = FakeEventStore().apply { seed("evt-3", channelId = 30L, cipher = "enc1:Y") }
        val service = RedactConversationEventService(store, clock)

        assertEquals(RedactionOutcome.REDACTED, service.redact(EventId("evt-3"), RedactionTrigger.USER_DELETION_REQUEST).outcome)
        // 두 번째 호출 — 멱등(추가 side effect 없음, receipt 없음).
        val again = service.redact(EventId("evt-3"), RedactionTrigger.USER_DELETION_REQUEST)
        assertEquals(RedactionOutcome.ALREADY_REDACTED_OR_ABSENT, again.outcome)
        assertNull(again.receipt)
        // 대상이 아예 없으면 멱등.
        assertEquals(
            RedactionOutcome.ALREADY_REDACTED_OR_ABSENT,
            service.redact(EventId("ghost"), RedactionTrigger.GUILD_REMOVAL).outcome,
        )
    }

    // ── redaction-contract.md 금지 필드 부재 증명(보안 회귀) ──────────────────────

    @Test
    fun `RedactionReceipt 는 원문 식별자 키 hash 를 담는 어떤 필드도 갖지 않는다`() {
        // 금지 필드명 패턴 — 회귀로 누군가 원문/식별자/키/hash 필드를 추가하면 이 테스트가 실패한다.
        val forbiddenNamePatterns =
            listOf(
                "content",
                "text",
                "message",
                "body",
                "raw",
                "prompt",
                "response",
                "snowflake",
                "userid",
                "authorid",
                "guildid",
                "channelid",
                "token",
                "key",
                "secret",
                "cipher",
                "plaintext",
                "hash",
                "digest",
                "fingerprint",
                "pseudonym",
            )

        val properties = RedactionReceipt::class.declaredMemberProperties
        for (prop in properties) {
            val name = prop.name.lowercase()
            for (pattern in forbiddenNamePatterns) {
                assertFalse(
                    name.contains(pattern),
                    "RedactionReceipt 에 금지 필드 누출: '${prop.name}' (패턴 '$pattern')",
                )
            }
            // 필드 타입도 원문/바이트를 담는 컬렉션/문자열 묶음이 아님을 보수적으로 확인(허용: EventId, enum, Instant).
            val typeName = prop.javaField?.type?.name ?: ""
            assertFalse(
                typeName == "java.lang.String" && name != "eventid",
                "RedactionReceipt 에 자유 String 필드 누출 가능: '${prop.name}'",
            )
        }
        // 명시적으로 허용된 3개 필드만 존재(드리프트 가드).
        assertEquals(setOf("eventId", "trigger", "processedAt"), properties.map { it.name }.toSet())
    }

    @Test
    fun `RedactionReceipt 직렬화 표면에 원문이 새지 않는다`() {
        val receipt =
            RedactionReceipt(
                eventId = EventId("msg.deleted:12345:0"),
                trigger = RedactionTrigger.MESSAGE_DELETED,
                processedAt = clock.instant(),
            )
        val serialized = receipt.toString()
        // 트리거 코드·이벤트 키·시각만 — 원문 마커가 없다.
        assertFalse(serialized.contains("CIPHERTEXT"))
        assertTrue(serialized.contains("MESSAGE_DELETED"))
    }
}
