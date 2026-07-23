package com.discordassistant.central.conversation.domain.service.scene

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneAuthorRole
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class NiaSceneWindowBuilderTest {
    private val scope = RawContextScope(guildId = 123L, channelId = 456L, threadId = 789L)
    private val t0 = Instant.parse("2026-06-29T00:00:00Z")

    @Test
    fun `scene window 는 원문을 시간순 ref role reply 로 조립하고 식별자를 노출하지 않는다`() {
        val window =
            NiaSceneWindowBuilder(maxRawChars = 100, niaAuthorPseudonyms = setOf("nia-pseudo"))
                .build(
                    RawContextSnapshot(
                        scope,
                        listOf(
                            entry(100L, "member-a", t0, RawContextSourceType.HUMAN, "야 니아?"),
                            entry(200L, "nia-pseudo", t0.plusSeconds(1), RawContextSourceType.BOT, "응", replyToMessageId = 100L),
                            entry(300L, "bot-pseudo", t0.plusSeconds(2), RawContextSourceType.BOT, "bot notice"),
                            entry(
                                400L,
                                "system-pseudo",
                                t0.plusSeconds(3),
                                RawContextSourceType.SYSTEM,
                                content = RawContextContent.Unavailable(RawContextUnavailableReason.REDACTED),
                            ),
                        ),
                    ),
                )

        assertThat(window.messages.map { it.ref }).containsExactly("msg_1", "msg_2", "msg_3", "msg_4")
        assertThat(window.messages.map { it.authorRole })
            .containsExactly(
                NiaSceneAuthorRole.MEMBER,
                NiaSceneAuthorRole.NIA,
                NiaSceneAuthorRole.BOT,
                NiaSceneAuthorRole.SYSTEM,
            )
        assertThat(window.messages.map { it.speakerLabel })
            .containsExactly("member_1", "nia", "bot_1", "system")
        assertThat(window.messages[1].replyToRef).isEqualTo("msg_1")
        assertThat((window.messages[0].content as NiaSceneContent.Available).text).isEqualTo("야 니아?")

        val surface = window.toString()
        assertThat(window.scopeFingerprint).hasSize(64)
        assertThat(surface)
            .doesNotContain("guildId=123", "channelId=456", "threadId=789", "messageId=100", "member-a", "nia-pseudo", "야 니아?")
    }

    @Test
    fun `같은 사람은 같은 local label을 쓰고 다른 사람은 분리한다`() {
        val snapshot =
            RawContextSnapshot(
                scope,
                listOf(
                    entry(1L, "private-a", t0, RawContextSourceType.HUMAN, "a1"),
                    entry(2L, "private-b", t0.plusSeconds(1), RawContextSourceType.HUMAN, "b1"),
                    entry(3L, "private-a", t0.plusSeconds(2), RawContextSourceType.HUMAN, "a2"),
                ),
            )

        val messages = NiaSceneWindowBuilder(maxRawChars = 100).build(snapshot).messages

        assertThat(messages.map { it.speakerLabel }).containsExactly("member_1", "member_2", "member_1")
        assertThat(messages.toString()).doesNotContain("private-a", "private-b")
    }

    @Test
    fun `char budget 을 넘겨도 같은 snapshot 의 message ref 는 다른 window 에서 바뀌지 않는다`() {
        val snapshot =
            RawContextSnapshot(
                scope,
                listOf(
                    entry(10L, "a", t0, RawContextSourceType.HUMAN, "old1"),
                    entry(11L, "b", t0.plusSeconds(1), RawContextSourceType.HUMAN, "new2"),
                    entry(12L, "c", t0.plusSeconds(2), RawContextSourceType.HUMAN, "new3"),
                ),
            )
        val window =
            NiaSceneWindowBuilder(maxRawChars = 8)
                .build(snapshot)
        val fullWindow = NiaSceneWindowBuilder(maxRawChars = 100).build(snapshot)

        assertThat(window.omittedOldestCount).isEqualTo(1)
        assertThat(window.messages.map { it.ref }).containsExactly("msg_2", "msg_3")
        assertThat(window.messages.map { (it.content as NiaSceneContent.Available).text }).containsExactly("new2", "new3")
        assertThat(window.messages.last().ref).isEqualTo(fullWindow.messages.last().ref)
    }

    @Test
    fun `reply 대상이 budget 밖으로 생략되면 replyToRef 는 null 이다`() {
        val window =
            NiaSceneWindowBuilder(maxRawChars = 4)
                .build(
                    RawContextSnapshot(
                        scope,
                        listOf(
                            entry(10L, "a", t0, RawContextSourceType.HUMAN, "old1"),
                            entry(11L, "b", t0.plusSeconds(1), RawContextSourceType.HUMAN, "new2", replyToMessageId = 10L),
                        ),
                    ),
                )

        assertThat(window.messages).hasSize(1)
        assertThat(window.messages.single().ref).isEqualTo("msg_2")
        assertThat(window.messages.single().replyToRef).isNull()
    }

    private fun entry(
        messageId: Long,
        authorPseudonym: String,
        occurredAt: Instant,
        sourceType: RawContextSourceType,
        text: String = "hello",
        replyToMessageId: Long? = null,
        content: RawContextContent = RawContextContent.Available(text),
    ): RawContextEntry =
        RawContextEntry(
            scope = scope,
            messageId = messageId,
            authorPseudonym = authorPseudonym,
            occurredAt = occurredAt,
            replyToMessageId = replyToMessageId,
            sourceType = sourceType,
            content = content,
        )
}
