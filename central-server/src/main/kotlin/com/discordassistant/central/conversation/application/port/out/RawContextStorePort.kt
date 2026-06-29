package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextAppendResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRedactionResult
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextScope
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextUnavailableReason

interface RawContextStorePort {
    fun append(entry: RawContextEntry): RawContextAppendResult

    fun readRecent(scope: RawContextScope): RawContextSnapshot

    fun redact(
        scope: RawContextScope,
        messageId: Long,
        reason: RawContextUnavailableReason,
    ): RawContextRedactionResult
}
