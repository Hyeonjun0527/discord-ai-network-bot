package com.discordassistant.central.platform.discord

import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.global.i18n.Messages

/**
 * 표준 응답 팩토리(차수 13 #184). 아이콘/어조를 한 곳에서 통일해 명령 전반의 에러/성공 메시지를
 * 일관되게 만든다.
 */
object Replies {
    fun ok(
        message: String,
        ephemeral: Boolean = true,
    ): Reply = Reply("✅ $message", ephemeral)

    fun info(
        message: String,
        ephemeral: Boolean = true,
    ): Reply = Reply("ℹ️ $message", ephemeral)

    fun warn(message: String): Reply = Reply("⚠️ $message")

    fun reject(message: String): Reply = Reply("⛔ $message")

    fun cooldown(message: String): Reply = Reply("⏳ $message")

    fun adminDenied(language: String = I18n.DEFAULT): Reply = reject(Messages.get(Messages.Key.ADMIN_DENIED, language))
}
