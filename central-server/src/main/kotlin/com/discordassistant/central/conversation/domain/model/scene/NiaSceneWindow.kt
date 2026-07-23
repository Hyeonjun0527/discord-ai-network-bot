package com.discordassistant.central.conversation.domain.model.scene

import java.time.Instant

data class NiaSceneWindow(
    val scopeFingerprint: String,
    val maxChars: Int,
    val messages: List<NiaSceneMessage>,
    val omittedOldestCount: Int,
) {
    val retainedRawChars: Int = messages.sumOf { it.content.rawCharLength }

    init {
        require(scopeFingerprint.isNotBlank()) { "scopeFingerprint 은 비어 있을 수 없다" }
        require(maxChars > 0) { "maxChars 는 양수여야 한다: $maxChars" }
        require(omittedOldestCount >= 0) { "omittedOldestCount 는 음수일 수 없다: $omittedOldestCount" }
        require(messages.map { it.ref }.toSet().size == messages.size) { "message ref 는 중복될 수 없다" }
        val refs = messages.map { it.ref }.toSet()
        messages.forEach { message ->
            message.replyToRef?.let { require(it in refs) { "replyToRef 는 window 안의 ref 만 가리킬 수 있다: $it" } }
        }
    }
}

data class NiaSceneMessage(
    val ref: String,
    val authorRole: NiaSceneAuthorRole,
    val speakerLabel: String,
    val createdAt: Instant,
    val replyToRef: String?,
    val content: NiaSceneContent,
) {
    init {
        require(ref.isNotBlank()) { "ref 는 비어 있을 수 없다" }
        require(speakerLabel.matches(Regex("[a-z]+_[0-9]+|nia|system"))) {
            "speakerLabel 은 window-local 가명이어야 한다: $speakerLabel"
        }
        replyToRef?.let {
            require(it.isNotBlank()) { "replyToRef 는 공백일 수 없다" }
            require(it != ref) { "replyToRef 는 자기 자신을 가리킬 수 없다: $ref" }
        }
    }
}

enum class NiaSceneAuthorRole(
    val wireName: String,
) {
    MEMBER("member"),
    NIA("nia"),
    BOT("bot"),
    SYSTEM("system"),
}

sealed interface NiaSceneContent {
    val rawCharLength: Int

    data class Available(
        val text: String,
    ) : NiaSceneContent {
        init {
            require(text.isNotEmpty()) { "scene text 는 비어 있을 수 없다" }
        }

        override val rawCharLength: Int get() = text.length

        override fun toString(): String = "Available(rawCharLength=$rawCharLength)"
    }

    data class Unavailable(
        val reason: String,
    ) : NiaSceneContent {
        init {
            require(reason.isNotBlank()) { "unavailable reason 은 비어 있을 수 없다" }
        }

        override val rawCharLength: Int get() = 0
    }
}
