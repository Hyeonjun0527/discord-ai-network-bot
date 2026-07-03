package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextAppendResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextBulkRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextDiagnostics
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextTombstone
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason

interface RawContextStorePort {
    fun append(entry: RawContextEntry): RawContextAppendResult

    fun readRecent(scope: RawContextScope): RawContextSnapshot

    fun readTombstones(scope: RawContextScope): List<RawContextTombstone>

    fun diagnostics(scope: RawContextScope): RawContextDiagnostics

    fun redact(
        scope: RawContextScope,
        messageId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextRedactionResult

    fun redactScope(
        scope: RawContextScope,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult

    fun redactChannel(
        guildId: Long,
        channelId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult

    fun redactGuild(
        guildId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult

    fun redactAuthor(
        guildId: Long,
        authorPseudonym: String,
        reason: RawContextUnavailableReason,
    ): RawContextBulkRedactionResult
}
