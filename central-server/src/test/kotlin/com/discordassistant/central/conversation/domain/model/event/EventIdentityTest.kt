package com.discordassistant.central.conversation.domain.model.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * NEXA-P03-T006 EventIdentity acceptance:
 * 동일 Gateway 이벤트 재수신 시 같은 ID, 다른 revision 은 충돌하지 않는다.
 */
class EventIdentityTest {
    @Test
    fun `같은 Gateway 이벤트 재수신은 같은 EventId 를 만든다`() {
        val first = EventIdentity(discordId = 100L, type = EventType.MESSAGE_CREATED).toEventId()
        val second = EventIdentity(discordId = 100L, type = EventType.MESSAGE_CREATED).toEventId()
        assertEquals(first, second)
    }

    @Test
    fun `같은 메시지의 다른 revision 은 충돌하지 않는다`() {
        val rev0 = EventIdentity(discordId = 100L, type = EventType.MESSAGE_UPDATED, revision = 0L).toEventId()
        val rev1 = EventIdentity(discordId = 100L, type = EventType.MESSAGE_UPDATED, revision = 1L).toEventId()
        assertNotEquals(rev0, rev1)
    }

    @Test
    fun `같은 discordId 라도 이벤트 종류가 다르면 충돌하지 않는다`() {
        val created = EventIdentity(discordId = 100L, type = EventType.MESSAGE_CREATED).toEventId()
        val deleted = EventIdentity(discordId = 100L, type = EventType.MESSAGE_DELETED).toEventId()
        assertNotEquals(created, deleted)
    }

    @Test
    fun `key 는 type discordId revision 을 결정론적으로 합친다`() {
        val key = EventIdentity(discordId = 42L, type = EventType.REACTION, revision = 1L).key()
        assertEquals("reaction:42:1", key)
    }

    @Test
    fun `음수 revision 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            EventIdentity(discordId = 1L, type = EventType.MESSAGE_CREATED, revision = -1L)
        }
    }
}
