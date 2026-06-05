package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.guild.application.PolicyService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import org.springframework.stereotype.Component

/**
 * 여러 명령 핸들러가 공유하는 공용 가드/헬퍼(god class 분해 공용 컴포넌트).
 * CommandService 에서 그대로 이동 — 동작/문구 불변. 핸들러들이 주입해 사용한다.
 */
@Component
class SharedCommandGuards(
    private val policy: PolicyService,
) {
    // 응답 언어: 요청자 Discord 언어(ko/en/ja) 우선 → 없으면 길드 기본 언어. (유저 로케일 우선 정책)
    fun lang(ctx: CommandContext): String = ctx.userLang ?: policy.guildLanguage(ctx.guildId)

    fun adminOnly(ctx: CommandContext): Reply? =
        if (!ctx.isAdmin) Replies.reject(Messages.get(Messages.Key.ADMIN_DENIED, lang(ctx))) else null
}
