package com.discordassistant.central.platform.discord.admin

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmException
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudToolCall
import com.discordassistant.central.routing.application.CloudToolResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 계획층 검증: 비활성/실패 graceful·도구 미호출 폴백·SAFE 즉시/CONFIRM 게이트 분기·확인 버튼 권한 재검증·
 * 다른 사람 클릭/요청자 불일치 거부. CloudLlm 은 정해진 tool 응답을 돌려주는 fake.
 */
class AdminAssistantServiceTest {
    private class FakeCloudLlm(
        private val enabled: Boolean = true,
        private val response: CloudToolResponse = CloudToolResponse(text = "일반 답변"),
        private val throws: Boolean = false,
    ) : CloudLlm {
        var toolsAttached = false

        override fun isEnabled() = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = throw CloudLlmException("미사용")

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse {
            toolsAttached = toolsJson.isNotBlank()
            if (throws) throw CloudLlmException("upstream")
            return response
        }

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ) = throw CloudLlmException("미사용")

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = throw CloudLlmException("미사용")
    }

    private fun service(cloud: CloudLlm) = AdminAssistantService(cloud, PendingAdminActionStore(), AdminActionAuditLog())

    private fun toolResponse(
        name: String,
        args: String,
    ) = CloudToolResponse(toolCalls = listOf(CloudToolCall(name, args)))

    @Test
    fun `클라우드 비활성이면 관리 의도 없음으로 비켜선다`() {
        val decision = service(FakeCloudLlm(enabled = false)).plan(1L, 9L, "철수 차단")
        assertTrue(decision is AdminAssistantDecision.NotAdminAction)
    }

    @Test
    fun `도구를 호출하지 않으면 일반 질문으로 폴백한다`() {
        val decision = service(FakeCloudLlm(response = CloudToolResponse(text = "안녕"))).plan(1L, 9L, "오늘 날씨 어때")
        assertTrue(decision is AdminAssistantDecision.NotAdminAction)
    }

    @Test
    fun `GLM 호출 실패는 일반 질문으로 graceful 폴백한다`() {
        val decision = service(FakeCloudLlm(throws = true)).plan(1L, 9L, "채널 만들어줘")
        assertTrue(decision is AdminAssistantDecision.NotAdminAction)
    }

    @Test
    fun `SAFE 액션은 즉시 실행 가능으로 분기한다`() {
        val decision = service(FakeCloudLlm(response = toolResponse("create_text_channel", """{"name":"잡담"}"""))).plan(1L, 9L, "채널 파줘")
        assertTrue(decision is AdminAssistantDecision.ReadyToRun)
        assertEquals(AdminActionType.CREATE_TEXT_CHANNEL, (decision as AdminAssistantDecision.ReadyToRun).plan.type)
    }

    @Test
    fun `CONFIRM 액션은 확인 토큰을 발급한다`() {
        val decision = service(FakeCloudLlm(response = toolResponse("ban_member", """{"userId":"55"}"""))).plan(1L, 9L, "55 차단")
        assertTrue(decision is AdminAssistantDecision.NeedsConfirm)
        assertTrue((decision as AdminAssistantDecision.NeedsConfirm).token.isNotBlank())
    }

    @Test
    fun `tools 는 어드민 경로에서만 첨부된다(서비스 호출 시 항상 전달)`() {
        // 서비스는 ctx.isAdmin 일 때만 DiscordBot 이 호출한다. 호출되면 tools 가 비어있지 않게 전달됨을 확인.
        val cloud = FakeCloudLlm(response = CloudToolResponse(text = "x"))
        service(cloud).plan(1L, 9L, "안녕")
        assertTrue(cloud.toolsAttached)
    }

    @Test
    fun `확인 버튼은 비어드민이 누르면 거부한다`() {
        val svc = service(FakeCloudLlm(response = toolResponse("ban_member", """{"userId":"55"}""")))
        val decision = svc.plan(1L, 9L, "55 차단") as AdminAssistantDecision.NeedsConfirm
        val result = svc.confirmAndRun(decision.token, clickerUserId = 9L, clickerIsAdmin = false) { error("실행되면 안 됨") }
        assertTrue(result is AdminActionResult.Rejected)
        assertTrue((result as AdminActionResult.Rejected).message.contains("관리자"))
    }

    @Test
    fun `확인 버튼은 요청자가 아닌 사람이 누르면 거부한다`() {
        val svc = service(FakeCloudLlm(response = toolResponse("ban_member", """{"userId":"55"}""")))
        val decision = svc.plan(1L, requesterUserId = 9L, "55 차단") as AdminAssistantDecision.NeedsConfirm
        val result = svc.confirmAndRun(decision.token, clickerUserId = 10L, clickerIsAdmin = true) { error("실행되면 안 됨") }
        assertTrue(result is AdminActionResult.Rejected)
    }

    @Test
    fun `만료된 토큰은 거부한다`() {
        val svc = service(FakeCloudLlm(response = toolResponse("ban_member", """{"userId":"55"}""")))
        val decision = svc.plan(1L, 9L, "55 차단") as AdminAssistantDecision.NeedsConfirm
        // 첫 클릭으로 consume → 두 번째(또는 만료)는 거부.
        svc.confirmAndRun(decision.token, 9L, true) { FakeGateway() }
        val again = svc.confirmAndRun(decision.token, 9L, true) { error("실행되면 안 됨") }
        assertTrue(again is AdminActionResult.Rejected)
    }

    @Test
    fun `정상 확인은 게이트웨이로 실행한다`() {
        val svc = service(FakeCloudLlm(response = toolResponse("ban_member", """{"userId":"55"}""")))
        val decision = svc.plan(1L, requesterUserId = 9L, "55 차단") as AdminAssistantDecision.NeedsConfirm
        val gw = FakeGateway()
        val result = svc.confirmAndRun(decision.token, clickerUserId = 9L, clickerIsAdmin = true) { gw }
        assertTrue(result is AdminActionResult.Done)
        assertFalse(gw.banned.isEmpty())
    }

    /** confirmAndRun 통합 검증용 최소 게이트웨이(모든 권한 OK). */
    private class FakeGateway : AdminGuildGateway {
        val banned = mutableListOf<Long>()

        override fun ownerId() = 1L

        override fun selfUserId() = 2L

        override fun botHasPermission(permission: AdminPermission) = true

        override fun botCanInteractWithMember(userId: Long) = true

        override fun resolveMemberId(raw: String) = raw.toLongOrNull()

        override fun resolveChannelId(raw: String) = raw.toLongOrNull()

        override fun createTextChannel(
            name: String,
            categoryRaw: String?,
            topic: String?,
        ) = GatewayResult.ok("ok")

        override fun createVoiceChannel(
            name: String,
            categoryRaw: String?,
        ) = GatewayResult.ok("ok")

        override fun createCategory(name: String) = GatewayResult.ok("ok")

        override fun renameChannel(
            channelId: Long,
            newName: String,
        ) = GatewayResult.ok("ok")

        override fun setChannelTopic(
            channelId: Long,
            topic: String,
        ) = GatewayResult.ok("ok")

        override fun setSlowmode(
            channelId: Long,
            seconds: Int,
        ) = GatewayResult.ok("ok")

        override fun unbanMember(userId: Long) = GatewayResult.ok("ok")

        override fun removeTimeout(userId: Long) = GatewayResult.ok("ok")

        override fun banMember(
            userId: Long,
            reason: String?,
            deleteMessageDays: Int,
        ): GatewayResult {
            banned += userId
            return GatewayResult.ok("ok")
        }

        override fun kickMember(
            userId: Long,
            reason: String?,
        ) = GatewayResult.ok("ok")

        override fun timeoutMember(
            userId: Long,
            minutes: Long,
            reason: String?,
        ) = GatewayResult.ok("ok")

        override fun deleteChannel(channelId: Long) = GatewayResult.ok("ok")

        override fun setChannelPermission(
            channelId: Long,
            targetId: Long,
            allow: List<String>,
            deny: List<String>,
        ) = GatewayResult.ok("ok")
    }
}
