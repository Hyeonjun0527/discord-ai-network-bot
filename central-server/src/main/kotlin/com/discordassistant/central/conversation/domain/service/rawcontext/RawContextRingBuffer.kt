package com.discordassistant.central.conversation.domain.service.rawcontext

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextAppendResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot

class RawContextRingBuffer(
    private val scope: RawContextScope,
    private val retention: RawContextRetentionPolicy,
) {
    private val entries: MutableList<RawContextEntry> = mutableListOf()

    fun append(entry: RawContextEntry): RawContextAppendResult {
        require(entry.scope == scope) { "entry scope ${entry.scope.stableKey} does not match buffer scope ${scope.stableKey}" }
        retention.ensureFits(entry)

        entries.removeAll { it.messageId == entry.messageId }
        entries.add(entry)
        entries.sortWith(compareBy<RawContextEntry> { it.occurredAt }.thenBy { it.messageId })

        val evicted = trimOldest()
        return RawContextAppendResult(snapshot(), evicted)
    }

    fun remove(messageId: Long): RawContextRedactionResult {
        require(messageId > 0) { "messageId 는 양수여야 한다: $messageId" }
        val removed = entries.removeIf { it.messageId == messageId }
        return RawContextRedactionResult(snapshot(), removed)
    }

    fun snapshot(): RawContextSnapshot = RawContextSnapshot(scope, entries.toList())

    private fun trimOldest(): List<Long> {
        val evicted = mutableListOf<Long>()
        while (snapshot().retainedRawChars > retention.maxRawChars || entries.size > retention.maxEntries) {
            val removed = entries.removeFirstOrNull() ?: break
            evicted += removed.messageId
        }
        return evicted
    }
}
