package com.discordassistant.central.platform.discord.admin

/**
 * AI 관리 비서가 한 길드에서 쓰는 **JDA-free 연산 포트**(얇은 어댑터 뒤로 JDA 를 숨김 — 실행 로직 단위 테스트용).
 * 권한·역할 계층·대상 보호 같은 안전 판정은 [AdminActionExecutor] 가 이 포트의 단순 질의로 결정하고,
 * 실제 채널/멤버 변경도 이 포트의 명령으로 위임한다. JDA 구현은 [JdaAdminGuildGateway].
 *
 * 모든 메서드는 실패 시 예외를 던지지 않고 graceful 한 결과(null/false/[GatewayResult])를 돌려준다 —
 * 한 액션 실패가 봇을 죽이지 않게 한다(과제 안전장치 7).
 */
interface AdminGuildGateway {
    /** 서버 소유자 ID(대상 보호 — 소유자는 ban/kick 불가). 미상이면 null. */
    fun ownerId(): Long?

    /** 봇 자신(셀프)의 사용자 ID(대상 보호 — 봇 자신 ban/kick 불가). */
    fun selfUserId(): Long?

    /** 봇이 이 액션에 필요한 권한을 가졌는지(예: 채널 관리/멤버 추방/타임아웃). */
    fun botHasPermission(permission: AdminPermission): Boolean

    /** 봇 역할이 대상 멤버보다 높아 상호작용(ban/kick/timeout/권한변경) 가능한지. 대상 미존재면 false. */
    fun botCanInteractWithMember(userId: Long): Boolean

    /** 이름/멘션/ID 문자열을 멤버 ID 로 해석(멘션 숫자·ID·표시 이름 폴백). 못 찾으면 null. */
    fun resolveMemberId(raw: String): Long?

    /** 이름/멘션/ID 문자열을 채널 ID 로 해석(멘션 숫자·ID·채널 이름 폴백). 못 찾으면 null. */
    fun resolveChannelId(raw: String): Long?

    // ── 실행 명령(모두 graceful — 성공/실패 결과만 반환) ──────────────────────
    fun createTextChannel(
        name: String,
        categoryRaw: String?,
        topic: String?,
    ): GatewayResult

    fun createVoiceChannel(
        name: String,
        categoryRaw: String?,
    ): GatewayResult

    fun createCategory(name: String): GatewayResult

    fun renameChannel(
        channelId: Long,
        newName: String,
    ): GatewayResult

    fun setChannelTopic(
        channelId: Long,
        topic: String,
    ): GatewayResult

    fun setSlowmode(
        channelId: Long,
        seconds: Int,
    ): GatewayResult

    fun unbanMember(userId: Long): GatewayResult

    fun removeTimeout(userId: Long): GatewayResult

    fun banMember(
        userId: Long,
        reason: String?,
        deleteMessageDays: Int,
    ): GatewayResult

    fun kickMember(
        userId: Long,
        reason: String?,
    ): GatewayResult

    fun timeoutMember(
        userId: Long,
        minutes: Long,
        reason: String?,
    ): GatewayResult

    fun deleteChannel(channelId: Long): GatewayResult

    fun setChannelPermission(
        channelId: Long,
        targetId: Long,
        allow: List<String>,
        deny: List<String>,
    ): GatewayResult
}

/** 봇 권한 체크 종류(JDA Permission 과 매핑하되 도메인 측은 JDA 비참조). */
enum class AdminPermission {
    MANAGE_CHANNEL,
    BAN_MEMBERS,
    KICK_MEMBERS,
    MODERATE_MEMBERS,
    MANAGE_PERMISSIONS,
}

/** 게이트웨이 실행 결과(성공/실패 + 사람에게 보일 한국어 요약). */
data class GatewayResult(
    val ok: Boolean,
    val message: String,
) {
    companion object {
        fun ok(message: String) = GatewayResult(true, message)

        fun fail(message: String) = GatewayResult(false, message)
    }
}
