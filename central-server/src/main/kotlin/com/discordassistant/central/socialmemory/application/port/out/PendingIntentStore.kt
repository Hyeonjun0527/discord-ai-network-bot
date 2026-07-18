package com.discordassistant.central.socialmemory.application.port.out

import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import java.time.Instant

/** 열린 약속의 durable SSOT인 기존 PendingIntent 저장 경계다. */
interface PendingIntentStore {
    fun save(intent: PendingIntent): PendingIntent

    fun findActive(
        focusThreadKey: String,
        now: Instant,
    ): List<PendingIntent>

    fun complete(
        id: String,
        completedAt: Instant,
        completedByActionId: String,
    ): PendingIntent?

    fun invalidate(id: String): PendingIntent?

    fun invalidateBySource(sourceEventId: String): Int
}
