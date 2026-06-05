package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import org.springframework.stereotype.Component

/**
 * 채널 AI 정체성/지침 명령군(setChannelAiProfile, setChannelAiInstruction)과 채널 AI 권한 가드.
 * CommandService 에서 응집 단위로 분리 — 권한가드/문구/불변식 그대로 이동, 시그니처 유지·위임.
 *
 * 불변식 보존:
 * - [channelAiAdminOnly] 는 관리자 가드 + requireCanManageChannelAi 권한 검사 본문 그대로(즉시-active 우회 차단의 첫 관문).
 * - [setChannelAiInstruction] 은 항상 requireApproval=true 로 PENDING 제안을 만든다(즉시-active 우회 차단, #5).
 */
@Component
class ChannelAiIdentityCommandHandler(
    private val channelProfiles: ChannelAiProfileService,
    private val channelAiCustomization: ChannelAiCustomizationService,
    private val guards: SharedCommandGuards,
) {
    fun channelAiAdminOnly(
        ctx: CommandContext,
        action: String,
    ): Reply? {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            channelAiCustomization.requireCanManageChannelAi(
                guildId = ctx.guildId,
                channelId = ctx.channelId,
                actorUserId = ctx.userId,
                actorRoleIds = ctx.roleIds,
                actorIsGuildAdmin = ctx.isAdmin,
                action = action,
            )
            null
        }.getOrElse {
            Replies.reject(it.message ?: "AI 설정 변경 권한이 없습니다.")
        }
    }

    fun setChannelAiProfile(
        ctx: CommandContext,
        name: String?,
        avatarUrl: String?,
        reset: Boolean,
        rollback: Boolean = false,
        purpose: String? = null,
        tone: String? = null,
        answerLength: String? = null,
        constitution: String? = null,
    ): Reply {
        channelAiAdminOnly(ctx, "channel_ai_profile")?.let { return it }
        if (reset) {
            channelProfiles.clear(ctx.guildId, ctx.channelId)
            return Reply("✅ 이 채널의 AI 응답 프로필을 기본 봇 표시로 되돌렸습니다.")
        }
        if (rollback) {
            val profile =
                channelProfiles.rollback(ctx.guildId, ctx.channelId, actorId = ctx.userId)
                    ?: return Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다.")
            return Reply("↩️ 이 채널 AI 행동 설정을 v${profile.version}(으)로 롤백했습니다. 현재 이름: **${profile.displayName}**")
        }
        val displayName = name?.trim().orEmpty()
        if (displayName.isBlank()) {
            val current = channelProfiles.get(ctx.guildId, ctx.channelId)
            return if (current == null) {
                Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다. `name` 옵션으로 설정하세요.")
            } else {
                val constitutionText = current.constitution ?: "기본 안전 규칙"
                Reply(
                    "현재 이 채널 AI: **${current.displayName}**\n" +
                        "행동 버전: v${current.version}\n" +
                        "역할: `${current.purpose}` · 말투: `${current.tone}` · 길이: `${current.answerLength}`\n" +
                        "헌법: $constitutionText",
                )
            }
        }
        val profile =
            channelProfiles.set(
                ctx.guildId,
                ctx.channelId,
                displayName,
                avatarUrl,
                actorId = ctx.userId,
                purpose = purpose,
                tone = tone,
                answerLength = answerLength,
                constitution = constitution,
            )
        val avatarLine = if (profile.avatarUrl.isNullOrBlank()) "" else "아이콘 이미지도 함께 설정했습니다.\n"
        return Reply(
            "✅ 이 채널 AI를 **${profile.displayName}**(으)로 설정했습니다.\n" +
                avatarLine +
                "행동 버전: v${profile.version}\n" +
                "역할: `${profile.purpose}` · 말투: `${profile.tone}` · 길이: `${profile.answerLength}`\n" +
                "이후 `/질문` 답변은 이 채널에서 그 이름으로 보입니다. 봇에 `웹후크 관리` 권한이 필요해요.",
        )
    }

    /**
     * `/ai-instruction` — 이 채널 AI에 자연어 자유 지침을 추가/수정한다.
     * text 가 비어 있으면 현재 지침을 확인만 한다. text 가 있으면 활성 behavior 를 베이스로
     * customInstruction 만 교체한 **새 behavior 버전 제안**을 만든다(위험 지침은 승인 큐로 강제).
     */
    fun setChannelAiInstruction(
        ctx: CommandContext,
        text: String?,
    ): Reply {
        channelAiAdminOnly(ctx, "set_custom_instruction")?.let { return it }
        val instruction = text?.trim().orEmpty()
        if (instruction.isBlank()) {
            return runCatching {
                val current = channelAiCustomization.currentCustomInstruction(ctx.guildId, ctx.channelId)
                if (current.isNullOrBlank()) {
                    Reply("현재 이 채널 AI에는 자유 지침이 없어요. `text` 옵션에 자연어 지침을 적어 추가하세요.")
                } else {
                    Reply("현재 이 채널 AI 자유 지침:\n> ${current.replace("\n", "\n> ")}")
                }
            }.getOrElse {
                Replies.warn("자유 지침을 확인하지 못했어요. ${it.message ?: "이 채널에 채널 AI가 있는지 확인해 주세요."}")
            }
        }
        return runCatching {
            // 자유 지침은 위험어 substring 우회(변형 인젝션) 위험이 있어 즉시 적용하지 않고 항상 사람 검토를 거친다(#5).
            // 온보딩 경로와 동일하게 requireApproval=true 로 PENDING 제안을 만들고, 관리자 승인 후에만 active 가 된다.
            val result =
                channelAiCustomization.proposeCustomInstruction(
                    guildId = ctx.guildId,
                    channelId = ctx.channelId,
                    actorUserId = ctx.userId,
                    actorRoleIds = ctx.roleIds,
                    actorIsGuildAdmin = ctx.isAdmin,
                    customInstruction = instruction,
                    requireApproval = true,
                )
            Replies.ok(
                "📝 자유 지침을 검토 대기열에 올렸어요(v${result.version}). " +
                    "관리자 승인 후 `/ask` 답변에 적용됩니다. (제안 `${result.proposalId}`)",
            )
        }.getOrElse {
            Replies.warn("자유 지침을 적용하지 못했어요. ${it.message ?: "잠시 후 다시 시도해 주세요."}")
        }
    }
}
