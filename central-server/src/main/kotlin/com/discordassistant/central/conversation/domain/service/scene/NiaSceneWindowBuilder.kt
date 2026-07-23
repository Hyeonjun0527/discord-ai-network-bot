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
        val indexed = chronological.withIndex().toList()
        val selected = selectNewestWithinBudget(indexed)
        val refByMessageId = selected.associate { indexedEntry -> indexedEntry.value.messageId to messageRef(indexedEntry.index) }
        // 원본 pseudonym 자체를 외부 payload에 싣지 않으면서도 A/B 화자를 잃지 않는다. 전체 snapshot에서 먼저
        // 번호를 부여하므로 char budget이 바뀌어도 같은 snapshot의 화자 라벨은 흔들리지 않는다.
        val speakerLabels = speakerLabels(chronological)
        val messages =
            selected.map { indexedEntry ->
                val entry = indexedEntry.value
                NiaSceneMessage(
                    ref = messageRef(indexedEntry.index),
                    authorRole = entry.authorRole(),
                    speakerLabel = speakerLabels.getValue(entry.speakerKey()),
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

    private fun selectNewestWithinBudget(entries: List<IndexedValue<RawContextEntry>>): List<IndexedValue<RawContextEntry>> {
        val selectedNewestFirst = mutableListOf<IndexedValue<RawContextEntry>>()
        var used = 0
        for (indexedEntry in entries.asReversed()) {
            val next = indexedEntry.value.contentLength
            if (used + next > maxRawChars) break
            selectedNewestFirst += indexedEntry
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

    private fun speakerLabels(entries: List<RawContextEntry>): Map<SpeakerKey, String> {
        val nextIndexByRole = mutableMapOf<NiaSceneAuthorRole, Int>()
        return buildMap {
            entries.forEach { entry ->
                val key = entry.speakerKey()
                getOrPut(key) {
                    when (key.role) {
                        NiaSceneAuthorRole.NIA -> "nia"
                        NiaSceneAuthorRole.SYSTEM -> "system"
                        NiaSceneAuthorRole.MEMBER,
                        NiaSceneAuthorRole.BOT,
                        -> {
                            val index = nextIndexByRole.getOrDefault(key.role, 0) + 1
                            nextIndexByRole[key.role] = index
                            "${key.role.wireName}_$index"
                        }
                    }
                }
            }
        }
    }

    private fun RawContextEntry.speakerKey(): SpeakerKey = SpeakerKey(authorRole(), authorPseudonym)

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

    private data class SpeakerKey(
        val role: NiaSceneAuthorRole,
        val pseudonym: String,
    )
}
