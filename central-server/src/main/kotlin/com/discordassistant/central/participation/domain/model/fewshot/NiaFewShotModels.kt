package com.discordassistant.central.participation.domain.model.fewshot

import java.time.Instant

enum class NiaFewShotAction {
    IGNORE,
    WAIT,
    REACT,
    SPEAK,
    CANCEL,
}

enum class NiaFewShotVersionStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED,
}

enum class NiaFewShotScopeType {
    GLOBAL,
    GUILD,
    CHANNEL,
    PERSONA,
}

enum class NiaFewShotPrivacyClass {
    SYNTHETIC,
    ANONYMIZED,
    PRODUCTION_DERIVED,
}

enum class NiaFewShotEvalStatus {
    NOT_RUN,
    PASS,
    FAIL,
}

data class NiaFewShotScope(
    val type: NiaFewShotScopeType,
    val guildId: Long? = null,
    val channelId: Long? = null,
    val persona: String = DEFAULT_PERSONA,
) {
    init {
        require(persona.isStableSlug()) { "persona 는 안정 slug 여야 한다" }
        when (type) {
            NiaFewShotScopeType.GLOBAL -> {
                require(guildId == null) { "GLOBAL scope 는 guildId 를 가질 수 없다" }
                require(channelId == null) { "GLOBAL scope 는 channelId 를 가질 수 없다" }
            }
            NiaFewShotScopeType.PERSONA -> {
                require(guildId == null) { "PERSONA scope 는 guildId 를 가질 수 없다" }
                require(channelId == null) { "PERSONA scope 는 channelId 를 가질 수 없다" }
            }
            NiaFewShotScopeType.GUILD -> {
                require(guildId != null && guildId > 0) { "GUILD scope 는 양수 guildId 가 필요하다" }
                require(channelId == null) { "GUILD scope 는 channelId 를 가질 수 없다" }
            }
            NiaFewShotScopeType.CHANNEL -> {
                require(guildId != null && guildId > 0) { "CHANNEL scope 는 양수 guildId 가 필요하다" }
                require(channelId != null && channelId > 0) { "CHANNEL scope 는 양수 channelId 가 필요하다" }
            }
        }
    }

    val stableKey: String
        get() =
            when (type) {
                NiaFewShotScopeType.GLOBAL -> "global"
                NiaFewShotScopeType.PERSONA -> "persona:$persona"
                NiaFewShotScopeType.GUILD -> "guild:$guildId:$persona"
                NiaFewShotScopeType.CHANNEL -> "channel:$guildId:$channelId:$persona"
            }

    companion object {
        const val DEFAULT_PERSONA = "nia"

        fun global(): NiaFewShotScope = NiaFewShotScope(NiaFewShotScopeType.GLOBAL)
    }
}

data class NiaFewShotLookupScope(
    val guildId: Long?,
    val channelId: Long?,
    val persona: String = NiaFewShotScope.DEFAULT_PERSONA,
) {
    init {
        guildId?.let { require(it > 0) { "guildId 는 양수여야 한다" } }
        channelId?.let { require(it > 0) { "channelId 는 양수여야 한다" } }
        require(persona.isStableSlug()) { "persona 는 안정 slug 여야 한다" }
        require(channelId == null || guildId != null) { "channelId 조회에는 guildId 가 필요하다" }
    }

    fun candidates(): List<NiaFewShotScope> =
        buildList {
            if (guildId != null && channelId != null) {
                add(NiaFewShotScope(NiaFewShotScopeType.CHANNEL, guildId = guildId, channelId = channelId, persona = persona))
            }
            if (guildId != null) {
                add(NiaFewShotScope(NiaFewShotScopeType.GUILD, guildId = guildId, persona = persona))
            }
            add(NiaFewShotScope(NiaFewShotScopeType.PERSONA, persona = persona))
            add(NiaFewShotScope.global())
        }
}

data class NiaFewShotRawMessage(
    val ref: String,
    val authorRole: String,
    val offsetMs: Long,
    val text: String,
) {
    init {
        require(ref.isStableRef()) { "raw message ref 는 안정 ref 여야 한다" }
        require(authorRole.isStableSlug()) { "authorRole 은 안정 slug 여야 한다" }
        require(text.isNotBlank()) { "raw message text 는 비어 있을 수 없다" }
        require(text.length <= MAX_TEXT_CHARS) { "raw message text 가 너무 길다" }
    }

    companion object {
        const val MAX_TEXT_CHARS = 4_000
    }
}

data class NiaFewShotBadAlternative(
    val action: NiaFewShotAction,
    val whyBad: String,
) {
    init {
        require(whyBad.isNotBlank()) { "bad alternative reason 은 비어 있을 수 없다" }
        require(whyBad.length <= MAX_REASON_CHARS) { "bad alternative reason 이 너무 길다" }
    }

    companion object {
        const val MAX_REASON_CHARS = 1_000
    }
}

data class NiaFewShotExample(
    val id: Long? = null,
    val title: String,
    val rawMessages: List<NiaFewShotRawMessage>,
    val expectedAction: NiaFewShotAction,
    val expectedReplies: List<String> = emptyList(),
    val reason: String,
    val evidenceRefs: Set<String>,
    val badAlternative: NiaFewShotBadAlternative,
    val tags: Set<String> = emptySet(),
    val priority: Int = 0,
    val privacyClass: NiaFewShotPrivacyClass = NiaFewShotPrivacyClass.SYNTHETIC,
    val evalStatus: NiaFewShotEvalStatus = NiaFewShotEvalStatus.NOT_RUN,
) {
    init {
        require(title.isNotBlank()) { "few-shot title 은 비어 있을 수 없다" }
        require(title.length <= MAX_TITLE_CHARS) { "few-shot title 이 너무 길다" }
        require(rawMessages.isNotEmpty()) { "few-shot rawMessages 는 비어 있을 수 없다" }
        require(rawMessages.size <= MAX_RAW_MESSAGES) { "few-shot rawMessages 가 너무 많다" }
        require(reason.isNotBlank()) { "few-shot reason 은 비어 있을 수 없다" }
        require(reason.length <= MAX_REASON_CHARS) { "few-shot reason 이 너무 길다" }
        require(expectedReplies.size <= MAX_EXPECTED_REPLIES) { "few-shot expectedReplies 가 너무 많다" }
        expectedReplies.forEach { reply ->
            require(reply.isNotBlank()) { "few-shot expected reply 는 비어 있을 수 없다" }
            require(reply.length <= MAX_EXPECTED_REPLY_CHARS) { "few-shot expected reply 가 너무 길다" }
        }
        require(expectedAction == NiaFewShotAction.SPEAK || expectedReplies.isEmpty()) {
            "SPEAK 이 아닌 few-shot 은 expectedReplies 를 가질 수 없다"
        }
        require(badAlternative.action != expectedAction) { "badAlternative 는 expectedAction 과 달라야 한다" }
        require(evidenceRefs.isNotEmpty()) { "few-shot evidenceRefs 는 비어 있을 수 없다" }
        evidenceRefs.forEach { require(it.isStableRef()) { "few-shot evidence ref 는 안정 ref 여야 한다" } }
        val rawRefs = rawMessages.map { it.ref }.toSet()
        require(rawRefs.containsAll(evidenceRefs)) { "few-shot evidenceRefs 는 rawMessages ref 를 가리켜야 한다" }
        tags.forEach { require(it.isStableSlug()) { "few-shot tag 는 안정 slug 여야 한다" } }
    }

    companion object {
        const val MAX_TITLE_CHARS = 160
        const val MAX_REASON_CHARS = 1_000
        const val MAX_RAW_MESSAGES = 32
        const val MAX_EXPECTED_REPLIES = 4
        const val MAX_EXPECTED_REPLY_CHARS = 2_000
    }
}

data class NiaFewShotVersion(
    val id: Long?,
    val setId: Long?,
    val version: Int,
    val status: NiaFewShotVersionStatus,
    val examples: List<NiaFewShotExample>,
    val createdBy: Long?,
    val reviewedBy: Long?,
    val publishedAt: Instant?,
    val rollbackOfVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= 1) { "few-shot version 은 1 이상이어야 한다" }
        require(examples.isNotEmpty()) { "few-shot version 은 최소 하나의 example 이 필요하다" }
        if (status == NiaFewShotVersionStatus.ACTIVE) {
            require(publishedAt != null) { "ACTIVE few-shot version 은 publishedAt 이 필요하다" }
        }
    }
}

data class NiaFewShotSet(
    val id: Long?,
    val scope: NiaFewShotScope,
    val activeVersion: Int?,
    val versions: List<NiaFewShotVersion>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        versions.forEach { version ->
            require(version.setId == null || version.setId == id) { "few-shot version setId 가 set 과 일치해야 한다" }
        }
        activeVersion?.let { active ->
            require(versions.any { it.version == active && it.status == NiaFewShotVersionStatus.ACTIVE }) {
                "activeVersion 은 ACTIVE version 을 가리켜야 한다"
            }
        }
    }

    val active: NiaFewShotVersion?
        get() = activeVersion?.let { active -> versions.firstOrNull { it.version == active } }
}

private fun String.isStableSlug(): Boolean = matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))

private fun String.isStableRef(): Boolean = matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))
