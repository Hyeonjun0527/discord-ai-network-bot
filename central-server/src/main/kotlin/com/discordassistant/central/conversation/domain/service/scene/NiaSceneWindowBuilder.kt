package com.discordassistant.central.conversation.domain.service.scene

import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextContent
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextEntry
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextRetentionPolicy
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSnapshot
import com.discordassistant.central.conversation.domain.model.rawcontext.RawContextSourceType
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneAuthorRole
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneContent
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneMessage
import com.discordassistant.central.conversation.domain.model.scene.NiaSceneWindow
import java.security.MessageDigest
import java.util.HexFormat

class NiaSceneWindowBuilder(
    private val maxRawChars: Int = RawContextRetentionPolicy.DEFAULT_MAX_RAW_CHARS,
    private val niaAuthorPseudonyms: Set<String> = emptySet(),
) {
    init {
        require(maxRawChars > 0) { "maxRawChars 는 양수여야 한다: $maxRawChars" }
        require(niaAuthorPseudonyms.none { it.isBlank() }) { "niaAuthorPseudonyms 는 공백 값을 담을 수 없다" }
    }

    fun build(snapshot: RawContextSnapshot): NiaSceneWindow {
        val chronological = snapshot.entries.sortedWith(compareBy<RawContextEntry> { it.occurredAt }.thenBy { it.messageId })
        val selected = selectNewestWithinBudget(chronological)
        val refByMessageId = selected.mapIndexed { index, entry -> entry.messageId to messageRef(index) }.toMap()
        val messages =
            selected.mapIndexed { index, entry ->
                NiaSceneMessage(
                    ref = messageRef(index),
                    authorRole = entry.authorRole(),
                    createdAt = entry.occurredAt,
                    replyToRef = entry.replyToMessageId?.let(refByMessageId::get),
                    content = entry.content.toSceneContent(),
                )
            }
        return NiaSceneWindow(
            scopeFingerprint = fingerprint(snapshot.scope.stableKey),
            maxChars = maxRawChars,
            messages = messages,
            omittedOldestCount = chronological.size - selected.size,
        )
    }

    private fun selectNewestWithinBudget(entries: List<RawContextEntry>): List<RawContextEntry> {
        val selectedNewestFirst = mutableListOf<RawContextEntry>()
        var used = 0
        for (entry in entries.asReversed()) {
            val next = entry.contentLength
            if (used + next > maxRawChars) break
            selectedNewestFirst += entry
            used += next
        }
        return selectedNewestFirst.asReversed()
    }

    private fun RawContextEntry.authorRole(): NiaSceneAuthorRole =
        when (sourceType) {
            RawContextSourceType.HUMAN -> NiaSceneAuthorRole.MEMBER
            RawContextSourceType.BOT -> if (authorPseudonym in niaAuthorPseudonyms) NiaSceneAuthorRole.NIA else NiaSceneAuthorRole.BOT
            RawContextSourceType.WEBHOOK -> NiaSceneAuthorRole.BOT
            RawContextSourceType.SYSTEM -> NiaSceneAuthorRole.SYSTEM
        }

    private fun RawContextContent.toSceneContent(): NiaSceneContent =
        when (this) {
            is RawContextContent.Available -> NiaSceneContent.Available(text)
            is RawContextContent.Unavailable -> NiaSceneContent.Unavailable(reason.wireName)
        }

    private fun messageRef(index: Int): String = "msg_${index + 1}"

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("nia-scene-window:$value".toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
