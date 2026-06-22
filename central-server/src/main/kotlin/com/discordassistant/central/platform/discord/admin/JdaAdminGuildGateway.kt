package com.discordassistant.central.platform.discord.admin

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.IPermissionHolder
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.concrete.Category
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [AdminGuildGateway] 의 JDA 구현(얇은 어댑터 — 실제 길드 변경 I/O 만, 로직/안전판정은 [AdminActionExecutor]).
 * 한 [Guild] 에 묶이며, 모든 호출은 runCatching 으로 감싸 한 액션 실패가 봇을 죽이지 않게 한다(과제 안전장치 7).
 * 변경은 `.complete()` 로 동기 실행해 결과(성공/실패)를 즉시 사람에게 보고할 수 있게 한다(버튼 핸들러가 deferReply 이후 호출).
 */
class JdaAdminGuildGateway(
    private val guild: Guild,
) : AdminGuildGateway {
    private val log = LoggerFactory.getLogger(JdaAdminGuildGateway::class.java)

    override fun ownerId(): Long = guild.ownerIdLong

    override fun selfUserId(): Long = guild.jda.selfUser.idLong

    override fun botHasPermission(permission: AdminPermission): Boolean = guild.selfMember.hasPermission(permission.toJda())

    override fun botCanInteractWithMember(userId: Long): Boolean {
        val member = guild.getMemberById(userId) ?: runCatching { guild.retrieveMemberById(userId).complete() }.getOrNull() ?: return false
        return guild.selfMember.canInteract(member)
    }

    override fun resolveMemberId(raw: String): Long? {
        idFrom(raw)?.let { return it }
        // 이름 폴백: 표시 이름(닉네임 우선) 정확 일치.
        return guild.getMembersByEffectiveName(raw.trim(), true).firstOrNull()?.idLong
    }

    override fun resolveChannelId(raw: String): Long? {
        idFrom(raw)?.let { id -> if (guild.getGuildChannelById(id) != null) return id }
        val name = raw.trim()
        return guild.getTextChannelsByName(name, true).firstOrNull()?.idLong
            ?: guild.getVoiceChannelsByName(name, true).firstOrNull()?.idLong
            ?: guild.getCategoriesByName(name, true).firstOrNull()?.idLong
    }

    override fun createTextChannel(
        name: String,
        categoryRaw: String?,
        topic: String?,
    ): GatewayResult =
        run("텍스트 채널 만들기") {
            val category = categoryRaw?.let { resolveCategory(it) }
            val created = (if (category != null) guild.createTextChannel(name, category) else guild.createTextChannel(name)).complete()
            if (!topic.isNullOrBlank()) runCatching { created.manager.setTopic(topic).complete() }
            GatewayResult.ok("✅ 텍스트 채널 ${created.asMention} 을 만들었어요.")
        }

    override fun createVoiceChannel(
        name: String,
        categoryRaw: String?,
    ): GatewayResult =
        run("음성 채널 만들기") {
            val category = categoryRaw?.let { resolveCategory(it) }
            val created = (if (category != null) guild.createVoiceChannel(name, category) else guild.createVoiceChannel(name)).complete()
            GatewayResult.ok("✅ 음성 채널 `${created.name}` 을 만들었어요.")
        }

    override fun createCategory(name: String): GatewayResult =
        run("카테고리 만들기") {
            val created = guild.createCategory(name).complete()
            GatewayResult.ok("✅ 카테고리 `${created.name}` 을 만들었어요.")
        }

    override fun renameChannel(
        channelId: Long,
        newName: String,
    ): GatewayResult =
        run("채널 이름 바꾸기") {
            val channel = guild.getGuildChannelById(channelId) ?: return@run notFoundChannel()
            channel.manager.setName(newName).complete()
            GatewayResult.ok("✅ 채널 이름을 `$newName` 으로 바꿨어요.")
        }

    override fun setChannelTopic(
        channelId: Long,
        topic: String,
    ): GatewayResult =
        run("채널 주제 설정") {
            val channel = guild.getTextChannelById(channelId) ?: return@run GatewayResult.fail("텍스트 채널이 아니거나 찾을 수 없어요.")
            channel.manager.setTopic(topic).complete()
            GatewayResult.ok("✅ ${channel.asMention} 의 주제를 설정했어요.")
        }

    override fun setSlowmode(
        channelId: Long,
        seconds: Int,
    ): GatewayResult =
        run("슬로우모드 설정") {
            val channel = guild.getTextChannelById(channelId) ?: return@run GatewayResult.fail("텍스트 채널이 아니거나 찾을 수 없어요.")
            channel.manager.setSlowmode(seconds).complete()
            GatewayResult.ok("✅ ${channel.asMention} 슬로우모드를 ${seconds}초로 설정했어요.")
        }

    override fun unbanMember(userId: Long): GatewayResult =
        run("차단 해제") {
            guild.unban(UserSnowflake.fromId(userId)).complete()
            GatewayResult.ok("✅ <@$userId> 차단을 해제했어요.")
        }

    override fun removeTimeout(userId: Long): GatewayResult =
        run("타임아웃 해제") {
            guild.removeTimeout(UserSnowflake.fromId(userId)).complete()
            GatewayResult.ok("✅ <@$userId> 타임아웃을 해제했어요.")
        }

    override fun banMember(
        userId: Long,
        reason: String?,
        deleteMessageDays: Int,
    ): GatewayResult =
        run("차단") {
            guild
                .ban(UserSnowflake.fromId(userId), deleteMessageDays, TimeUnit.DAYS)
                .reason(reason?.take(MAX_REASON))
                .complete()
            GatewayResult.ok("✅ <@$userId> 를 차단했어요.")
        }

    override fun kickMember(
        userId: Long,
        reason: String?,
    ): GatewayResult =
        run("추방") {
            guild.kick(UserSnowflake.fromId(userId)).reason(reason?.take(MAX_REASON)).complete()
            GatewayResult.ok("✅ <@$userId> 를 추방했어요.")
        }

    override fun timeoutMember(
        userId: Long,
        minutes: Long,
        reason: String?,
    ): GatewayResult =
        run("타임아웃") {
            guild
                .timeoutFor(UserSnowflake.fromId(userId), Duration.ofMinutes(minutes))
                .reason(reason?.take(MAX_REASON))
                .complete()
            GatewayResult.ok("✅ <@$userId> 를 ${minutes}분 타임아웃했어요.")
        }

    override fun deleteChannel(channelId: Long): GatewayResult =
        run("채널 삭제") {
            val channel = guild.getGuildChannelById(channelId) ?: return@run notFoundChannel()
            val name = channel.name
            channel.delete().complete()
            GatewayResult.ok("✅ 채널 `$name` 을 삭제했어요.")
        }

    override fun setChannelPermission(
        channelId: Long,
        targetId: Long,
        allow: List<String>,
        deny: List<String>,
    ): GatewayResult =
        run("채널 권한 설정") {
            val container =
                guild.getGuildChannelById(channelId) as? IPermissionContainer
                    ?: return@run GatewayResult.fail("이 채널은 권한 설정을 지원하지 않아요.")
            val holder: IPermissionHolder =
                guild.getRoleById(targetId) ?: guild.getMemberById(targetId)
                    ?: return@run GatewayResult.fail("권한 대상(역할/사용자)을 찾지 못했어요.")
            val allowPerms = parsePermissions(allow)
            val denyPerms = parsePermissions(deny)
            if (allowPerms.isEmpty() && denyPerms.isEmpty()) {
                return@run GatewayResult.fail("알 수 없는 권한 이름이에요. 예: VIEW_CHANNEL, MESSAGE_SEND")
            }
            container
                .upsertPermissionOverride(holder)
                .setAllowed(allowPerms)
                .setDenied(denyPerms)
                .complete()
            GatewayResult.ok("✅ 채널 권한을 설정했어요.")
        }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────
    private fun resolveCategory(raw: String): Category? =
        idFrom(raw)?.let { guild.getCategoryById(it) } ?: guild.getCategoriesByName(raw.trim(), true).firstOrNull()

    /** 멘션/순수 ID 문자열에서 숫자 ID 추출(이름은 null). 멘션은 숫자만 남겨 파싱한다. */
    private fun idFrom(raw: String): Long? {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it }
        // <@123>, <@!123>, <#456>, <@&789> 형태 → 숫자만.
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) return trimmed.filter(Char::isDigit).toLongOrNull()
        return null
    }

    private fun parsePermissions(names: List<String>): List<Permission> =
        names.mapNotNull { name ->
            runCatching { Permission.valueOf(name.trim().uppercase()) }.getOrNull()
        }

    private fun notFoundChannel() = GatewayResult.fail("대상 채널을 찾을 수 없어요.")

    /** JDA I/O 한 건을 실행하고 실패는 graceful 결과로 — 예외를 던지지 않는다(봇 보호). */
    private fun run(
        label: String,
        block: () -> GatewayResult,
    ): GatewayResult =
        runCatching(block).getOrElse { e ->
            // 사용자에겐 친화 메시지, 서버엔 길드·스택을 남긴다(예외 원칙). 토큰/원문은 남기지 않는다.
            log.warn("관리 액션 실패({}, guild={}): {}", label, guild.idLong, e.message)
            GatewayResult.fail("$label 에 실패했어요. 권한이나 대상을 확인해 주세요.")
        }

    private fun AdminPermission.toJda(): Permission =
        when (this) {
            AdminPermission.MANAGE_CHANNEL -> Permission.MANAGE_CHANNEL
            AdminPermission.BAN_MEMBERS -> Permission.BAN_MEMBERS
            AdminPermission.KICK_MEMBERS -> Permission.KICK_MEMBERS
            AdminPermission.MODERATE_MEMBERS -> Permission.MODERATE_MEMBERS
            AdminPermission.MANAGE_PERMISSIONS -> Permission.MANAGE_PERMISSIONS
        }

    companion object {
        private const val MAX_REASON = 400
    }
}
