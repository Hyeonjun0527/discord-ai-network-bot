package com.discordassistant.central.conversation.application.port.out

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P03-T008 acceptance: [EventStorePort] 가 **append-only** 원칙을 깨는 update/delete 메서드를 노출하지 않는다.
 *
 * 포트 인터페이스의 선언 메서드를 반영(reflection)으로 훑어 "update/delete/remove/save/persist/modify" 같은
 * 변경 동사를 가진 메서드가 없는지 검증한다. redaction(상태 전이)만 허용되며(markRedacted), 이것은 행 삭제가
 * 아니라 상태 전이라 append-only 와 양립한다(deletion-propagation.md).
 */
class EventStorePortContractTest {
    private val mutatingVerbs = listOf("update", "delete", "remove", "save", "persist", "modify", "drop", "truncate")

    @Test
    fun `포트는 append-only를 깨는 update delete 메서드를 노출하지 않는다`() {
        val methodNames = EventStorePort::class.java.declaredMethods.map { it.name.lowercase() }

        for (name in methodNames) {
            for (verb in mutatingVerbs) {
                assertFalse(
                    name.contains(verb),
                    "append-only 위반: EventStorePort 가 변경 메서드를 노출함 -> '$name' (금지 동사 '$verb')",
                )
            }
        }
    }

    @Test
    fun `포트는 append exists streamByChannel streamByRange markRedacted 를 제공한다`() {
        // value class(EventId/ChannelId) 파라미터를 받는 메서드는 Kotlin 이 이름을 mangling(접미 해시)하므로
        // exact match 대신 prefix 로 존재를 확인한다.
        val methodNames = EventStorePort::class.java.declaredMethods.map { it.name }

        for (expected in listOf("append", "exists", "streamByChannel", "streamByRange", "markRedacted")) {
            assertTrue(
                methodNames.any { it == expected || it.startsWith("$expected-") },
                "EventStorePort 에 '$expected' 메서드가 있어야 한다 (declared: $methodNames)",
            )
        }
    }
}
