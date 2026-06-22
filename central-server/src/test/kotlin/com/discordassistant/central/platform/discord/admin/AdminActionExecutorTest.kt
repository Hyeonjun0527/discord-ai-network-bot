package com.discordassistant.central.platform.discord.admin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 실행 + 안전장치 검증(JDA 없이 fake 게이트웨이로). 봇 권한 부족 graceful·역할 계층·대상 보호(소유자/자기자신/요청자)·
 * 필수 인자 검증·SAFE 실행을 다룬다.
 */
class AdminActionExecutorTest {
    /** 모든 호출을 기록하고 정해진 정책을 돌려주는 fake 게이트웨이. */
    private class FakeGateway(
        private val ownerId: Long = 1L,
        private val selfId: Long = 2L,
        private val hasPermission: Boolean = true,
        private val canInteract: Boolean = true,
        private val memberResolves: Boolean = true,
        private val channelResolves: Boolean = true,
    ) : AdminGuildGateway {
        val executed = mutableListOf<String>()

        override fun ownerId() = ownerId

        override fun selfUserId() = selfId

        override fun botHasPermission(permission: AdminPermission) = hasPermission

        override fun botCanInteractWithMember(userId: Long) = canInteract

        override fun resolveMemberId(raw: String): Long? = if (memberResolves) raw.filter(Char::isDigit).toLongOrNull() ?: 100L else null

        override fun resolveChannelId(raw: String): Long? = if (channelResolves) raw.filter(Char::isDigit).toLongOrNull() ?: 500L else null

        override fun createTextChannel(
            name: String,
            categoryRaw: String?,
            topic: String?,
        ) = done("createText:$name")

        override fun createVoiceChannel(
            name: String,
            categoryRaw: String?,
        ) = done("createVoice:$name")

        override fun createCategory(name: String) = done("createCategory:$name")

        override fun renameChannel(
            channelId: Long,
            newName: String,
        ) = done("rename:$channelId:$newName")

        override fun setChannelTopic(
            channelId: Long,
            topic: String,
        ) = done("topic:$channelId")

        override fun setSlowmode(
            channelId: Long,
            seconds: Int,
        ) = done("slowmode:$channelId:$seconds")

        override fun unbanMember(userId: Long) = done("unban:$userId")

        override fun removeTimeout(userId: Long) = done("untimeout:$userId")

        override fun banMember(
            userId: Long,
            reason: String?,
            deleteMessageDays: Int,
        ) = done("ban:$userId")

        override fun kickMember(
            userId: Long,
            reason: String?,
        ) = done("kick:$userId")

        override fun timeoutMember(
            userId: Long,
            minutes: Long,
            reason: String?,
        ) = done("timeout:$userId:$minutes")

        override fun deleteChannel(channelId: Long) = done("delete:$channelId")

        override fun setChannelPermission(
            channelId: Long,
            targetId: Long,
            allow: List<String>,
            deny: List<String>,
        ) = done("perm:$channelId:$targetId")

        private fun done(tag: String): GatewayResult {
            executed += tag
            return GatewayResult.ok("ok")
        }
    }

    private fun exec(
        gateway: FakeGateway,
        requesterId: Long = 9L,
    ) = AdminActionExecutor(gateway, requesterId)

    @Test
    fun `SAFE 채널 생성은 즉시 실행된다`() {
        val gw = FakeGateway()
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.CREATE_TEXT_CHANNEL, mapOf("name" to "잡담")))
        assertTrue(result is AdminActionResult.Done)
        assertEquals(listOf("createText:잡담"), gw.executed)
    }

    @Test
    fun `봇 권한이 없으면 graceful 거부하고 실행하지 않는다`() {
        val gw = FakeGateway(hasPermission = false)
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.CREATE_TEXT_CHANNEL, mapOf("name" to "x")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("권한"))
        assertTrue(gw.executed.isEmpty())
    }

    @Test
    fun `봇 역할이 대상보다 낮으면 ban 을 거부한다`() {
        val gw = FakeGateway(canInteract = false)
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.BAN_MEMBER, mapOf("userId" to "55")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("역할"))
        assertTrue(gw.executed.isEmpty())
    }

    @Test
    fun `서버 소유자는 ban 대상이 될 수 없다`() {
        val gw = FakeGateway(ownerId = 77L)
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.BAN_MEMBER, mapOf("userId" to "77")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("소유자"))
    }

    @Test
    fun `봇 자신은 kick 대상이 될 수 없다`() {
        val gw = FakeGateway(selfId = 88L)
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.KICK_MEMBER, mapOf("userId" to "88")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("봇 자신"))
    }

    @Test
    fun `요청 어드민 본인은 ban 대상이 될 수 없다`() {
        val gw = FakeGateway()
        val result = exec(gw, requesterId = 9L).execute(AdminActionPlan(AdminActionType.BAN_MEMBER, mapOf("userId" to "9")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("자기 자신"))
    }

    @Test
    fun `필수 인자가 없으면 graceful 거부한다`() {
        val gw = FakeGateway()
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.CREATE_TEXT_CHANNEL, emptyMap()))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue(gw.executed.isEmpty())
    }

    @Test
    fun `슬로우모드 범위를 벗어나면 거부한다`() {
        val gw = FakeGateway()
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.SET_SLOWMODE, mapOf("channelId" to "5", "seconds" to "99999")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue(gw.executed.isEmpty())
    }

    @Test
    fun `대상을 찾지 못하면 graceful 거부한다`() {
        val gw = FakeGateway(memberResolves = false)
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.TIMEOUT_MEMBER, mapOf("userId" to "철수", "minutes" to "10")))
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue(gw.executed.isEmpty())
    }

    @Test
    fun `정상 ban 은 모든 게이트 통과 후 실행된다`() {
        val gw = FakeGateway()
        val result = exec(gw).execute(AdminActionPlan(AdminActionType.BAN_MEMBER, mapOf("userId" to "123", "reason" to "스팸")))
        assertTrue(result is AdminActionResult.Done)
        assertEquals(listOf("ban:123"), gw.executed)
    }
}
