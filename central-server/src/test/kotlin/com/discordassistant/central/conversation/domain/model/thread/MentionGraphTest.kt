package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** NEXA-P05-T003: 직접 addressee 후보(DIRECT)와 단순 알림(ROLE/EVERYONE) mention 을 구분한다. */
class MentionGraphTest {
    private val burst = BurstId("burst:A")

    @Test
    fun `DIRECT mention 만 addressee 후보로 모은다 (acceptance)`() {
        val graph =
            MentionGraph(
                listOf(
                    MentionEdge(burst, MentionKind.DIRECT, AuthorId(7L)),
                    MentionEdge(burst, MentionKind.ROLE, null),
                    MentionEdge(burst, MentionKind.EVERYONE, null),
                ),
            )
        assertEquals(listOf(AuthorId(7L)), graph.directAddresseeCandidates(burst))
    }

    @Test
    fun `ROLE 과 EVERYONE 은 단순 알림으로 분리된다 (acceptance)`() {
        val graph =
            MentionGraph(
                listOf(
                    MentionEdge(burst, MentionKind.DIRECT, AuthorId(7L)),
                    MentionEdge(burst, MentionKind.ROLE, null),
                    MentionEdge(burst, MentionKind.EVERYONE, null),
                ),
            )
        val notify = graph.notificationOnlyMentions(burst)
        assertEquals(2, notify.size)
        assertEquals(setOf(MentionKind.ROLE, MentionKind.EVERYONE), notify.map { it.kind }.toSet())
    }

    @Test
    fun `DIRECT 후보는 중복 제거되고 순서를 보존한다`() {
        val graph =
            MentionGraph(
                listOf(
                    MentionEdge(burst, MentionKind.DIRECT, AuthorId(7L)),
                    MentionEdge(burst, MentionKind.DIRECT, AuthorId(7L)),
                    MentionEdge(burst, MentionKind.DIRECT, AuthorId(9L)),
                ),
            )
        assertEquals(listOf(AuthorId(7L), AuthorId(9L)), graph.directAddresseeCandidates(burst))
    }

    @Test
    fun `DIRECT 는 member 가 필수이고 ROLE EVERYONE 은 member 가 없어야 한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            MentionEdge(burst, MentionKind.DIRECT, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MentionEdge(burst, MentionKind.EVERYONE, AuthorId(1L))
        }
    }
}
