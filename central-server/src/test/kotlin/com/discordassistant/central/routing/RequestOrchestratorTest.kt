package com.discordassistant.central.routing

import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.domain.RequestState
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** sendFrame(InferRequest) 를 받으면 즉시 결과/에러를 세션에 되먹여 future 를 완료시킨다. */
private class EchoConnection(val behavior: String) : AgentConnection {
    lateinit var session: ProviderSession
    override val remoteId = "echo"
    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) {
            if (behavior == "ok") {
                session.handleFrame(InferResult(frame.requestId, "답변-${frame.requestId.take(4)}"))
            } else {
                session.handleFrame(InferError(frame.requestId, "OLLAMA_ERROR", "boom"))
            }
        }
    }
    override fun close(reason: String) {}
}

class RequestOrchestratorTest {

    private val fakePolicy = object : RoutingPolicy {
        var channelAllowed = true
        var max = ModelBurden.HEAVY
        override fun isChannelAllowed(guildId: Long, channelId: Long) = channelAllowed
        override fun maxAllowedBurden(guildId: Long, memberRoleIds: Collection<Long>) = max
    }
    private val fakeProfiles = object : ProviderProfileProvider {
        var supported = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY)
        override fun profile(providerId: Long) = ProviderProfile(supportedBurdens = supported)
    }
    private val recorder = object : UsageRecorder {
        var count = 0
        override fun recordSuccess(guildId: Long, userId: Long, providerId: Long, requestId: String) { count++ }
    }

    private fun newRegistry() = ConnectionRegistry()

    private fun register(reg: ConnectionRegistry, providerId: Long, behavior: String): ProviderSession {
        val conn = EchoConnection(behavior)
        val s = ProviderSession(conn, providerId, guildId = 100)
        conn.session = s
        reg.register(s)
        return s
    }

    private fun orchestrator(reg: ConnectionRegistry) =
        RequestOrchestrator(reg, fakePolicy, RequestWeigher(), ProviderFilterPipeline(), ProviderRouter(), recorder, fakeProfiles)

    private val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "안녕", roleIds = setOf(1))

    @Test
    fun `성공 — 사용량 기록`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val r = orchestrator(reg).handle(input)
        assertEquals(RequestState.COMPLETED, r.state)
        assertNotNull(r.text)
        assertEquals(1L, r.providerId)
        assertEquals(1, recorder.count)
    }

    @Test
    fun `차단 사용자 → REJECTED`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val blocking = object : BlocklistChecker {
            override fun isBlocked(guildId: Long, userId: Long): Boolean = userId == 5L
        }
        val orch = RequestOrchestrator(
            reg, fakePolicy, RequestWeigher(), ProviderFilterPipeline(), ProviderRouter(), recorder, fakeProfiles, blocking,
        )
        assertEquals(RequestState.REJECTED, orch.handle(input).state) // input.userId = 5
    }

    @Test
    fun `쿼터 초과 → REJECTED`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val noBlock = object : BlocklistChecker {
            override fun isBlocked(guildId: Long, userId: Long): Boolean = false
        }
        val quota = object : QuotaChecker {
            override fun exceededQuota(guildId: Long, userId: Long, roleIds: Set<Long>): Boolean = true
        }
        val orch = RequestOrchestrator(
            reg, fakePolicy, RequestWeigher(), ProviderFilterPipeline(), ProviderRouter(), recorder, fakeProfiles, noBlock, quota,
        )
        assertEquals(RequestState.REJECTED, orch.handle(input).state)
    }

    @Test
    fun `실패 → 다른 provider 로 fallback`() {
        val reg = newRegistry()
        register(reg, 1, "err") // 먼저 선택되어 실패
        register(reg, 2, "ok")
        val r = orchestrator(reg).handle(input)
        assertEquals(RequestState.COMPLETED, r.state)
        assertEquals(2L, r.providerId) // fallback 으로 처리
    }

    @Test
    fun `채널 불가 → REJECTED`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        fakePolicy.channelAllowed = false
        val r = orchestrator(reg).handle(input)
        assertEquals(RequestState.REJECTED, r.state)
        fakePolicy.channelAllowed = true
    }

    @Test
    fun `프로바이더 없음 → FAILED`() {
        val r = orchestrator(newRegistry()).handle(input)
        assertEquals(RequestState.FAILED, r.state)
    }

    @Test
    fun `부담 수준 미지원 → 권한 REJECTED`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        fakeProfiles.supported = setOf(ModelBurden.STANDARD) // LIGHT 미지원
        val r = orchestrator(reg).handle(input)
        assertEquals(RequestState.REJECTED, r.state)
        fakeProfiles.supported = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY)
    }
}
