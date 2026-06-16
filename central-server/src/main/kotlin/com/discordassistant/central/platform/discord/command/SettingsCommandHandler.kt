package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 공개 `/설정` 명령 — 카미봇처럼 ephemeral 안내 + 웹 대시보드로 가는 **링크 버튼**만 제공한다.
 * 모든 유저가 쓰는 공개 명령(관리자 잠금 없음). 실제 설정은 대시보드에서 한다.
 *
 * 공개 베이스 URL 도출은 [com.discordassistant.central.onboarding.application.ProviderConnectOnboardingService]
 * 의 publicBase() 와 동일 규칙: `central.connect.public-base-url` 우선, 비면 `central.relay.public-url` 의
 * wss/ws→https/http 변환 + 끝 `/agent` 제거. 비어 있으면 빈 link 버튼을 만들 수 없으므로(JDA 거부)
 * 버튼 대신 관리자 문의 안내([SettingsLinks.Unavailable])로 폴백한다.
 *
 * JDA 컴포넌트(Button)는 DiscordBot 어댑터가 [SettingsLinks] 로부터 만든다 — 이 핸들러는 순수 로직이라 단위 테스트 가능.
 */
@Component
class SettingsCommandHandler(
    private val guards: SharedCommandGuards,
    @param:Value("\${central.connect.public-base-url:}") private val configuredBase: String = "",
    @param:Value("\${central.relay.public-url:}") private val relayPublicUrl: String = "",
) {
    /** 대시보드 링크 버튼 한 건(라벨 + 절대 URL). */
    data class LinkButton(
        val label: String,
        val url: String,
    )

    /** `/설정` 응답 모델. 베이스 URL 이 있으면 [WithLinks], 없으면 [Unavailable] 로 폴백. */
    sealed interface SettingsLinks {
        val title: String
        val description: String
        val language: String

        /** 링크 버튼이 1개 이상인 정상 응답(설정 홈 + 길드/채널이 있으면 이 채널 설정). */
        data class WithLinks(
            override val title: String,
            override val description: String,
            override val language: String,
            val buttons: List<LinkButton>,
        ) : SettingsLinks

        /** 베이스 URL 미설정 → 버튼 없이 관리자 문의 안내. */
        data class Unavailable(
            override val title: String,
            override val description: String,
            override val language: String,
        ) : SettingsLinks
    }

    /**
     * `/설정` 응답을 만든다. 공개 명령이라 누구나 부르지만, 대시보드는 **관리자 전용**(OAuth)이라
     * 비관리자에게 그 링크를 주면 막혀 헛걸음한다 → 비관리자는 버튼 없이 친절 안내 + 유저 자기-서비스 명령을
     * 제시한다([SettingsLinks.Unavailable]). 관리자는 기존처럼 설정 홈/이 채널 설정 링크 버튼을 받는다.
     * DM(길드/채널 sentinel)에서는 "설정 홈" 버튼만 노출하고, 베이스 URL 이 비면 안내 폴백을 반환한다.
     */
    fun settings(ctx: CommandContext): SettingsLinks {
        val language = guards.lang(ctx)
        val title = Messages.get(Messages.Key.SETTINGS_LINK_TITLE, language)
        val description = Messages.get(Messages.Key.SETTINGS_LINK_DESCRIPTION, language)
        if (!ctx.isAdmin) {
            // 비관리자: 관리자 전용 대시보드 대신 본인이 바로 쓸 수 있는 명령을 친절히 안내(버튼 없음).
            return SettingsLinks.Unavailable(
                title = Messages.get(Messages.Key.SETTINGS_LINK_USER_TITLE, language),
                description = Messages.get(Messages.Key.SETTINGS_LINK_USER_DESCRIPTION, language),
                language = language,
            )
        }
        val base = publicBase()
        if (base.isBlank()) {
            return SettingsLinks.Unavailable(
                title = title,
                description = Messages.get(Messages.Key.SETTINGS_LINK_NO_BASE_URL, language),
                language = language,
            )
        }
        val buttons =
            buildList {
                add(LinkButton(Messages.get(Messages.Key.SETTINGS_LINK_HOME_BUTTON, language), "$base/admin/dashboard/"))
                if (ctx.guildId != CommandService.DM_SCOPE) {
                    add(
                        LinkButton(
                            Messages.get(Messages.Key.SETTINGS_LINK_CHANNEL_BUTTON, language),
                            "$base/admin/dashboard/?guild=${ctx.guildId}&channel=${enc(ctx.channelId.toString())}",
                        ),
                    )
                }
            }
        return SettingsLinks.WithLinks(title = title, description = description, language = language, buttons = buttons)
    }

    /** 공개 베이스 URL(설정값 우선, 없으면 relay wss→https 변환 + 끝 `/agent` 제거). */
    private fun publicBase(): String {
        val raw =
            configuredBase.ifBlank {
                relayPublicUrl.replace("wss://", "https://").replace("ws://", "http://").let {
                    if (it.endsWith("/agent")) it.dropLast("/agent".length) else it
                }
            }
        return raw.trim().trimEnd('/')
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
