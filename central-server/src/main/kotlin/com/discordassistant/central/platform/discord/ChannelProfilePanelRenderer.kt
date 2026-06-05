package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.channelai.application.DEFAULT_CHANNEL_AI_CONSTITUTION
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal

/**
 * 채널 AI 프로필 설정 패널·모달 + 질문 입력 모달의 순수 빌더(god class 분해 — verbatim 이동).
 * 본문(텍스트/모달 조립·기본값·문구)은 DiscordBot.Listener 에서 1바이트 불변으로 이동했다.
 * `channelBulkModal` 만 설정 마법사의 [effectiveAllowedChannelIds] 에 의존하므로,
 * pendingSettings 정적 맵 소유는 Listener 에 남기고 그 값만 함수로 주입받는다.
 */
class ChannelProfilePanelRenderer(
    private val channelProfiles: ChannelAiProfileService,
    private val effectiveAllowedChannelIds: (CommandContext) -> List<Long>,
) {
    fun askModal() =
        Modal
            .create("ask-long-modal", "질문 입력")
            .addActionRow(
                TextInput
                    .create("prompt", "질문", TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .setMaxLength(4000)
                    .build(),
            ).build()

    /** 채널 AI 프로필 설정 패널. 긴 옵션 입력 대신 버튼→모달→저장 흐름으로 관리한다. */
    fun channelProfilePanelText(ctx: CommandContext): String {
        val current = channelProfiles.get(ctx.guildId, ctx.channelId)
        val summary =
            if (current == null) {
                "아직 이 채널 전용 AI 프로필이 없습니다."
            } else {
                "현재 이름: **${current.displayName}**\n" +
                    "역할: `${current.purpose}` · 말투: `${current.tone}` · 길이: `${current.answerLength}`\n" +
                    "아이콘: ${if (current.avatarUrl.isNullOrBlank()) "기본 봇 아이콘" else "설정됨"}\n" +
                    "행동 버전: v${current.version}"
            }
        return "❂ **채널 AI 프로필 설정**\n\n" +
            "$summary\n\n" +
            "아래 버튼으로 설정하세요. 긴 명령어 옵션을 직접 외울 필요가 없습니다."
    }

    fun channelProfileRows(): List<ActionRow> =
        listOf(
            ActionRow.of(
                Button.primary(CHANNEL_PROFILE_EDIT, "프로필 편집"),
                Button.secondary(CHANNEL_PROFILE_AVATAR, "아이콘 URL"),
            ),
            ActionRow.of(
                Button.secondary(CHANNEL_PROFILE_ROLLBACK, "이전 버전으로 롤백"),
                Button.danger(CHANNEL_PROFILE_RESET, "기본값으로 초기화"),
            ),
        )

    fun channelProfileModal(ctx: CommandContext): Modal {
        val current = channelProfiles.get(ctx.guildId, ctx.channelId)
        return Modal
            .create(CHANNEL_PROFILE_SAVE_MODAL, "채널 AI 프로필 저장")
            .addActionRow(
                TextInput
                    .create("name", "이름", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMaxLength(80)
                    .setValue(current?.displayName ?: "니아")
                    .build(),
            ).addActionRow(
                TextInput
                    .create("purpose", "역할", TextInputStyle.SHORT)
                    .setRequired(false)
                    .setMaxLength(200)
                    .setValue(current?.purpose ?: "general_assistant")
                    .build(),
            ).addActionRow(
                TextInput
                    .create("tone", "말투", TextInputStyle.SHORT)
                    .setRequired(false)
                    .setMaxLength(80)
                    .setValue(current?.tone ?: "friendly")
                    .build(),
            ).addActionRow(
                TextInput
                    .create("answer-length", "답변 길이", TextInputStyle.SHORT)
                    .setRequired(false)
                    .setMaxLength(40)
                    .setValue(current?.answerLength ?: "balanced")
                    .build(),
            ).addActionRow(
                TextInput
                    .create("constitution", "AI 헌법/규칙", TextInputStyle.PARAGRAPH)
                    .setRequired(false)
                    .setMaxLength(2000)
                    .setValue(current?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION)
                    .build(),
            ).build()
    }

    fun channelProfileAvatarModal(ctx: CommandContext): Modal {
        val current = channelProfiles.get(ctx.guildId, ctx.channelId)
        val avatarInput =
            TextInput
                .create("avatar-url", "이미지 URL", TextInputStyle.SHORT)
                .setRequired(false)
                .setMaxLength(1000)
                .setPlaceholder("비우고 저장하면 아이콘 URL을 제거합니다.")
                .apply {
                    current
                        ?.avatarUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { setValue(it) }
                }.build()
        return Modal
            .create(CHANNEL_PROFILE_AVATAR_MODAL, "채널 AI 아이콘 URL 저장")
            .addActionRow(avatarInput)
            .build()
    }

    fun channelBulkModal(ctx: CommandContext): Modal {
        val current = effectiveAllowedChannelIds(ctx)
        val currentText = if (current.isEmpty()) "" else current.joinToString(" ") { "<#$it>" }
        return Modal
            .create(SETTINGS_CHANNEL_BULK_MODAL, "LLM 사용 허용 채널 일괄 설정")
            .addActionRow(
                TextInput
                    .create("channels", "채널 멘션/ID 목록", TextInputStyle.PARAGRAPH)
                    .setRequired(false)
                    .setMaxLength(2000)
                    .setPlaceholder("예: #질문 #개발 123456789012345678 / 비우거나 '전체' 입력 = 모든 채널 허용")
                    .setValue(currentText.takeIf { it.isNotBlank() })
                    .build(),
            ).build()
    }

    companion object {
        private const val CHANNEL_PROFILE_EDIT = "channel-profile:edit"
        private const val CHANNEL_PROFILE_AVATAR = "channel-profile:avatar"
        private const val CHANNEL_PROFILE_RESET = "channel-profile:reset"
        private const val CHANNEL_PROFILE_ROLLBACK = "channel-profile:rollback"
        private const val CHANNEL_PROFILE_SAVE_MODAL = "channel-profile:save-modal"
        private const val CHANNEL_PROFILE_AVATAR_MODAL = "channel-profile:avatar-modal"
        private const val SETTINGS_CHANNEL_BULK_MODAL = "settings:channel-bulk-modal"
    }
}
