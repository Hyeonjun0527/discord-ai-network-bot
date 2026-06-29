package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason
import com.discordassistant.central.global.crypto.FieldCrypto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaRawContextStore::class)
@TestPropertySource(properties = ["nexa.raw-context.max-raw-chars-per-scope=8"])
class JpaRawContextStoreTest
    @Autowired
    constructor(
        private val store: JpaRawContextStore,
        private val rows: NexaRawContextMessageRepository,
        private val jdbc: JdbcTemplate,
    ) {
        private val scope = RawContextScope(guildId = 1, channelId = 2, threadId = 3)
        private val t0 = Instant.parse("2026-06-29T00:00:00Z")

        @BeforeEach
        fun setUp() {
            FieldCrypto.configure("raw-context-test-key")
        }

        @AfterEach
        fun tearDown() {
            FieldCrypto.configure(null)
        }

        @Test
        fun `available raw context is stored encrypted and read back as plaintext`() {
            store.append(entry(messageId = 10, text = "답장안해"))

            val stored =
                jdbc.queryForObject(
                    "SELECT content_cipher FROM nexa_raw_context_message WHERE message_id = 10",
                    String::class.java,
                )
            assertThat(stored).startsWith("enc1:")
            assertThat(stored).doesNotContain("답장안해")

            val snapshot = store.readRecent(scope)
            assertThat((snapshot.entries.single().content as RawContextContent.Available).text)
                .isEqualTo("답장안해")

            val entityText = rows.findAll().single().toString()
            assertThat(entityText).doesNotContain("답장안해")
            assertThat(entityText).doesNotContain("enc1:")
        }

        @Test
        fun `available raw context fails closed when encryption key is missing`() {
            FieldCrypto.configure(null)

            assertThatThrownBy { store.append(entry(messageId = 10, text = "secret")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("raw context encryption key is not configured")

            assertThat(rows.findAll()).isEmpty()
        }

        @Test
        fun `scope retention evicts oldest raw rows by configured char budget`() {
            val first = store.append(entry(messageId = 10, text = "1234", occurredAt = t0))
            assertThat(first.evictedMessageIds).isEmpty()
            val second = store.append(entry(messageId = 11, text = "5678", occurredAt = t0.plusSeconds(1)))
            assertThat(second.evictedMessageIds).isEmpty()

            val third = store.append(entry(messageId = 12, text = "abcd", occurredAt = t0.plusSeconds(2)))

            assertThat(third.evictedMessageIds).containsExactly(10)
            assertThat(store.readRecent(scope).entries.map { it.messageId }).containsExactly(11, 12)
        }

        @Test
        fun `unavailable context keeps reason without plaintext column`() {
            store.append(
                entry(
                    messageId = 20,
                    content = RawContextContent.Unavailable(RawContextUnavailableReason.INTENT_MISSING),
                ),
            )

            val row = rows.findAll().single()
            assertThat(row.contentCipher).isNull()
            assertThat(row.contentUnavailableReason).isEqualTo("intent_missing")
            assertThat(row.contentLength).isZero()

            val content =
                store
                    .readRecent(scope)
                    .entries
                    .single()
                    .content
            assertThat(content).isEqualTo(RawContextContent.Unavailable(RawContextUnavailableReason.INTENT_MISSING))
        }

        @Test
        fun `redact removes message from context and is idempotent`() {
            store.append(entry(messageId = 10, text = "drop"))
            store.append(entry(messageId = 11, text = "keep", occurredAt = t0.plusSeconds(1)))

            val removed = store.redact(scope, messageId = 10, reason = RawContextUnavailableReason.REDACTED)
            val repeated = store.redact(scope, messageId = 10, reason = RawContextUnavailableReason.REDACTED)

            assertThat(removed.removed).isTrue()
            assertThat(removed.snapshot.entries.map { it.messageId }).containsExactly(11)
            assertThat(repeated.removed).isFalse()
            assertThat(rows.findAll().map { it.messageId }).containsExactly(11)
        }

        @Test
        fun `oversized raw entry exception does not include raw text`() {
            assertThatThrownBy { store.append(entry(messageId = 99, text = "123456789")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("messageId=99")
                .hasMessageContaining("contentLength=9")
                .hasMessageNotContaining("123456789")
        }

        private fun entry(
            messageId: Long,
            text: String = "hello",
            content: RawContextContent = RawContextContent.Available(text),
            occurredAt: Instant = t0,
        ): RawContextEntry =
            RawContextEntry(
                scope = scope,
                messageId = messageId,
                authorPseudonym = "user-a",
                occurredAt = occurredAt,
                replyToMessageId = null,
                sourceType = RawContextSourceType.HUMAN,
                content = content,
            )
    }
