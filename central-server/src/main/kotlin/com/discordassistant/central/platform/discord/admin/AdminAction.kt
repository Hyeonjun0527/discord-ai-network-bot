package com.discordassistant.central.platform.discord.admin

/**
 * AI 관리 비서가 실행할 수 있는 Discord 관리 액션의 위험도. SAFE 는 즉시 실행 후 보고,
 * CONFIRM 은 확인 버튼([실행]/[취소]) 게이트를 거쳐 실행한다(메모리 인덱스에 저장된 사용자 확정 설계).
 */
enum class AdminActionRisk {
    /** 되돌리기 쉬운 안전 액션 — 즉시 실행 후 결과 보고(채널 생성·이름변경·슬로우모드·차단해제 등). */
    SAFE,

    /** 파괴적/사람 대상 액션 — 확인 버튼 후 실행(차단·추방·타임아웃·채널 삭제·권한 변경). */
    CONFIRM,
}

/**
 * AI 관리 비서 액션 카탈로그(SSOT). 각 항목의 [toolName] 은 GLM 에 보내는 OpenAI function 이름과 1:1 대응하고,
 * [risk] 는 확인 게이트 필요 여부를 결정한다. 새 액션을 추가하면 여기 + [AdminToolCatalog] 스키마만 늘리면 된다.
 */
enum class AdminActionType(
    val toolName: String,
    val risk: AdminActionRisk,
) {
    // ── SAFE(즉시 실행) ──────────────────────────────────────────────
    CREATE_TEXT_CHANNEL("create_text_channel", AdminActionRisk.SAFE),
    CREATE_VOICE_CHANNEL("create_voice_channel", AdminActionRisk.SAFE),
    CREATE_CATEGORY("create_category", AdminActionRisk.SAFE),
    RENAME_CHANNEL("rename_channel", AdminActionRisk.SAFE),
    SET_CHANNEL_TOPIC("set_channel_topic", AdminActionRisk.SAFE),
    SET_SLOWMODE("set_slowmode", AdminActionRisk.SAFE),
    UNBAN_MEMBER("unban_member", AdminActionRisk.SAFE),
    REMOVE_TIMEOUT("remove_timeout", AdminActionRisk.SAFE),

    // ── CONFIRM(확인 후 실행) ────────────────────────────────────────
    BAN_MEMBER("ban_member", AdminActionRisk.CONFIRM),
    KICK_MEMBER("kick_member", AdminActionRisk.CONFIRM),
    TIMEOUT_MEMBER("timeout_member", AdminActionRisk.CONFIRM),
    DELETE_CHANNEL("delete_channel", AdminActionRisk.CONFIRM),
    SET_CHANNEL_PERMISSION("set_channel_permission", AdminActionRisk.CONFIRM),
    ;

    companion object {
        /** GLM 이 돌려준 함수 이름(tool_calls)을 액션 종류로 매핑. 모르는 이름이면 null(상위가 무시·일반 처리). */
        fun fromToolName(name: String): AdminActionType? = entries.firstOrNull { it.toolName == name.trim() }
    }
}

/**
 * GLM tool_call 을 파싱한 **실행 의도**(JDA 미참조 순수 값). [args] 는 GLM 이 만든 인자 맵(문자열 값만 — 멘션·이름·ID
 * 모두 description 으로 ID 우선 유도하나, 이름으로 와도 실행 시 guild 에서 해석을 시도한다).
 */
data class AdminActionPlan(
    val type: AdminActionType,
    val args: Map<String, String>,
) {
    val risk: AdminActionRisk get() = type.risk

    fun arg(key: String): String? = args[key]?.trim()?.takeIf { it.isNotBlank() }

    fun intArg(key: String): Int? = arg(key)?.toIntOrNull()
}
