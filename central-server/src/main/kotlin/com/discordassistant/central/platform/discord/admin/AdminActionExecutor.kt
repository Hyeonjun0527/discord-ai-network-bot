package com.discordassistant.central.platform.discord.admin

/**
 * AI 관리 비서의 **실행 + 안전장치**(JDA 는 [AdminGuildGateway] 뒤로 숨겨 단위 테스트 가능). 한 [AdminActionPlan] 을
 * 받아 ① 필수 인자 검증 ② 봇 권한 체크 ③ 역할 계층(canInteract) ④ 대상 보호(소유자/봇/요청자 본인) ⑤ 실행 위임을
 * 수행하고, 모든 결과를 [AdminActionResult] 로 돌려준다. 안전 위반은 예외가 아니라 거부 결과(graceful)다.
 *
 * @param requesterUserId 이 작업을 요청한 어드민의 ID(자기 자신 ban/kick 방지 — 대상 보호).
 */
class AdminActionExecutor(
    private val gateway: AdminGuildGateway,
    private val requesterUserId: Long,
) {
    /** 한 액션을 안전장치를 통과시킨 뒤 실행한다(확인 게이트는 호출자가 이미 통과시켰다고 가정). */
    fun execute(plan: AdminActionPlan): AdminActionResult =
        when (plan.type) {
            AdminActionType.CREATE_TEXT_CHANNEL -> createTextChannel(plan)
            AdminActionType.CREATE_VOICE_CHANNEL -> createVoiceChannel(plan)
            AdminActionType.CREATE_CATEGORY -> createCategory(plan)
            AdminActionType.RENAME_CHANNEL -> renameChannel(plan)
            AdminActionType.SET_CHANNEL_TOPIC -> setChannelTopic(plan)
            AdminActionType.SET_SLOWMODE -> setSlowmode(plan)
            AdminActionType.UNBAN_MEMBER -> unbanMember(plan)
            AdminActionType.REMOVE_TIMEOUT -> removeTimeout(plan)
            AdminActionType.BAN_MEMBER -> banMember(plan)
            AdminActionType.KICK_MEMBER -> kickMember(plan)
            AdminActionType.TIMEOUT_MEMBER -> timeoutMember(plan)
            AdminActionType.DELETE_CHANNEL -> deleteChannel(plan)
            AdminActionType.SET_CHANNEL_PERMISSION -> setChannelPermission(plan)
        }

    // ── SAFE 액션 ────────────────────────────────────────────────────
    private fun createTextChannel(plan: AdminActionPlan): AdminActionResult {
        val name = plan.arg("name") ?: return reject("채널 이름이 필요해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.createTextChannel(name, plan.arg("categoryId"), plan.arg("topic")))
    }

    private fun createVoiceChannel(plan: AdminActionPlan): AdminActionResult {
        val name = plan.arg("name") ?: return reject("음성 채널 이름이 필요해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.createVoiceChannel(name, plan.arg("categoryId")))
    }

    private fun createCategory(plan: AdminActionPlan): AdminActionResult {
        val name = plan.arg("name") ?: return reject("카테고리 이름이 필요해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.createCategory(name))
    }

    private fun renameChannel(plan: AdminActionPlan): AdminActionResult {
        val channelId = resolveChannel(plan, "channelId") ?: return reject("바꿀 채널을 찾지 못했어요.")
        val newName = plan.arg("newName") ?: return reject("새 채널 이름이 필요해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.renameChannel(channelId, newName))
    }

    private fun setChannelTopic(plan: AdminActionPlan): AdminActionResult {
        val channelId = resolveChannel(plan, "channelId") ?: return reject("주제를 바꿀 채널을 찾지 못했어요.")
        val topic = plan.arg("topic") ?: return reject("설정할 주제가 필요해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.setChannelTopic(channelId, topic))
    }

    private fun setSlowmode(plan: AdminActionPlan): AdminActionResult {
        val channelId = resolveChannel(plan, "channelId") ?: return reject("슬로우모드를 바꿀 채널을 찾지 못했어요.")
        val seconds = plan.intArg("seconds") ?: return reject("슬로우모드 초(숫자)가 필요해요.")
        if (seconds !in 0..MAX_SLOWMODE_SECONDS) return reject("슬로우모드는 0~$MAX_SLOWMODE_SECONDS 초 사이여야 해요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.setSlowmode(channelId, seconds))
    }

    private fun unbanMember(plan: AdminActionPlan): AdminActionResult {
        val userId = plan.arg("userId")?.toLongOrNull() ?: return reject("차단 해제할 사용자 ID 가 필요해요.")
        requireBotPermission(AdminPermission.BAN_MEMBERS)?.let { return it }
        return from(gateway.unbanMember(userId))
    }

    private fun removeTimeout(plan: AdminActionPlan): AdminActionResult {
        val userId = resolveMember(plan, "userId") ?: return reject("타임아웃을 해제할 사용자를 찾지 못했어요.")
        requireBotPermission(AdminPermission.MODERATE_MEMBERS)?.let { return it }
        return from(gateway.removeTimeout(userId))
    }

    // ── CONFIRM 액션 ─────────────────────────────────────────────────
    private fun banMember(plan: AdminActionPlan): AdminActionResult {
        val userId = resolveMember(plan, "userId") ?: return reject("차단할 사용자를 찾지 못했어요.")
        protectTarget(userId)?.let { return it }
        requireBotPermission(AdminPermission.BAN_MEMBERS)?.let { return it }
        requireHierarchy(userId)?.let { return it }
        val days = (plan.intArg("deleteMessageDays") ?: 0).coerceIn(0, MAX_DELETE_MESSAGE_DAYS)
        return from(gateway.banMember(userId, plan.arg("reason"), days))
    }

    private fun kickMember(plan: AdminActionPlan): AdminActionResult {
        val userId = resolveMember(plan, "userId") ?: return reject("추방할 사용자를 찾지 못했어요.")
        protectTarget(userId)?.let { return it }
        requireBotPermission(AdminPermission.KICK_MEMBERS)?.let { return it }
        requireHierarchy(userId)?.let { return it }
        return from(gateway.kickMember(userId, plan.arg("reason")))
    }

    private fun timeoutMember(plan: AdminActionPlan): AdminActionResult {
        val userId = resolveMember(plan, "userId") ?: return reject("타임아웃할 사용자를 찾지 못했어요.")
        protectTarget(userId)?.let { return it }
        val minutes = plan.intArg("minutes")?.toLong() ?: return reject("타임아웃 분(숫자)이 필요해요.")
        if (minutes !in 1..MAX_TIMEOUT_MINUTES) return reject("타임아웃은 1~$MAX_TIMEOUT_MINUTES 분 사이여야 해요.")
        requireBotPermission(AdminPermission.MODERATE_MEMBERS)?.let { return it }
        requireHierarchy(userId)?.let { return it }
        return from(gateway.timeoutMember(userId, minutes, plan.arg("reason")))
    }

    private fun deleteChannel(plan: AdminActionPlan): AdminActionResult {
        val channelId = resolveChannel(plan, "channelId") ?: return reject("삭제할 채널을 찾지 못했어요.")
        requireBotPermission(AdminPermission.MANAGE_CHANNEL)?.let { return it }
        return from(gateway.deleteChannel(channelId))
    }

    private fun setChannelPermission(plan: AdminActionPlan): AdminActionResult {
        val channelId = resolveChannel(plan, "channelId") ?: return reject("권한을 바꿀 채널을 찾지 못했어요.")
        val targetId =
            plan.arg("targetId")?.let { gateway.resolveMemberId(it) ?: it.filter(Char::isDigit).toLongOrNull() }
                ?: return reject("권한 대상(역할/사용자)을 찾지 못했어요.")
        requireBotPermission(AdminPermission.MANAGE_PERMISSIONS)?.let { return it }
        val allow = csv(plan.arg("allow"))
        val deny = csv(plan.arg("deny"))
        if (allow.isEmpty() && deny.isEmpty()) return reject("허용 또는 거부할 권한을 적어주세요.")
        return from(gateway.setChannelPermission(channelId, targetId, allow, deny))
    }

    // ── 안전장치 헬퍼 ────────────────────────────────────────────────

    /** 봇 권한 부족이면 거부 결과(graceful 안내), 충분하면 null. */
    private fun requireBotPermission(permission: AdminPermission): AdminActionResult? =
        if (gateway.botHasPermission(permission)) {
            null
        } else {
            reject("봇에게 ${permissionLabel(permission)} 권한이 없어요. 서버 설정에서 봇 역할에 권한을 부여해 주세요.")
        }

    /** 역할 계층 — 봇 역할이 대상보다 낮으면 거부. */
    private fun requireHierarchy(userId: Long): AdminActionResult? =
        if (gateway.botCanInteractWithMember(userId)) {
            null
        } else {
            reject("봇 역할이 대상보다 낮아 실행할 수 없어요. 봇 역할을 더 위로 올려주세요.")
        }

    /** 대상 보호 — 서버 소유자·봇 자신·요청 어드민 본인을 ban/kick/timeout 대상으로 하면 거부. */
    private fun protectTarget(userId: Long): AdminActionResult? =
        when (userId) {
            gateway.ownerId() -> reject("서버 소유자는 대상으로 할 수 없어요.")
            gateway.selfUserId() -> reject("봇 자신은 대상으로 할 수 없어요.")
            requesterUserId -> reject("자기 자신은 대상으로 할 수 없어요.")
            else -> null
        }

    private fun resolveMember(
        plan: AdminActionPlan,
        key: String,
    ): Long? = plan.arg(key)?.let { gateway.resolveMemberId(it) }

    private fun resolveChannel(
        plan: AdminActionPlan,
        key: String,
    ): Long? = plan.arg(key)?.let { gateway.resolveChannelId(it) }

    private fun from(result: GatewayResult): AdminActionResult =
        if (result.ok) AdminActionResult.Done(result.message) else AdminActionResult.Rejected(result.message)

    private fun reject(message: String) = AdminActionResult.Rejected(message)

    private fun csv(raw: String?): List<String> = raw?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        const val MAX_SLOWMODE_SECONDS = 21600
        const val MAX_TIMEOUT_MINUTES = 40320L // Discord 최대 28일
        const val MAX_DELETE_MESSAGE_DAYS = 7

        fun permissionLabel(permission: AdminPermission): String =
            when (permission) {
                AdminPermission.MANAGE_CHANNEL -> "채널 관리"
                AdminPermission.BAN_MEMBERS -> "멤버 차단"
                AdminPermission.KICK_MEMBERS -> "멤버 추방"
                AdminPermission.MODERATE_MEMBERS -> "멤버 관리(타임아웃)"
                AdminPermission.MANAGE_PERMISSIONS -> "권한 관리"
            }
    }
}

/** 한 액션 실행/거부 결과(사람에게 보일 한국어 메시지 포함). */
sealed interface AdminActionResult {
    val message: String

    /** 실행 완료. */
    data class Done(
        override val message: String,
    ) : AdminActionResult

    /** 안전장치/검증/실패로 실행하지 않음(또는 게이트웨이 실패). */
    data class Rejected(
        override val message: String,
    ) : AdminActionResult
}
