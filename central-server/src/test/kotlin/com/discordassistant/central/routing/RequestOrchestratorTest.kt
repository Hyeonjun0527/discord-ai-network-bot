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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** sendFrame(InferRequest) 를 받으면 즉시 결과/에러를 세션에 되먹여 future 를 완료시킨다. */
private class EchoConnection(
    val behavior: String,
) : AgentConnection {
    lateinit var session: ProviderSession
    var lastInfer: InferRequest? = null
    override val remoteId = "echo"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) {
            lastInfer = frame
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
    private val fakePolicy =
        object : RoutingPolicy {
            var channelAllowed = true
            var max = ModelBurden.HEAVY

            override fun isChannelAllowed(
                guildId: Long,
                channelId: Long,
            ) = channelAllowed

            override fun maxAllowedBurden(
                guildId: Long,
                memberRoleIds: Collection<Long>,
            ) = max
        }
    private val fakeProfiles =
        object : ProviderProfileProvider {
            var supported = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY)

            override fun profile(providerId: Long) = ProviderProfile(supportedBurdens = supported)
        }
    private val recorder =
        object : UsageRecorder {
            var count = 0

            override fun recordSuccess(
                guildId: Long,
                userId: Long,
                providerId: Long,
                requestId: String,
            ) {
                count++
            }
        }

    private fun newRegistry() = ConnectionRegistry()

    private fun register(
        reg: ConnectionRegistry,
        providerId: Long,
        behavior: String,
        models: List<String> = listOf("llama3.1:8b"),
    ): ProviderSession {
        val conn = EchoConnection(behavior)
        val s = ProviderSession(conn, providerId, guildId = 100)
        conn.session = s
        s.capability = s.capability.copy(models = models)
        reg.register(s)
        return s
    }

    private fun orchestrator(
        reg: ConnectionRegistry,
        providerSafety: ProviderSafetyChecker = ALLOW_ALL_PROVIDER_SAFETY,
    ) = RequestOrchestrator(
        reg,
        fakePolicy,
        RequestWeigher(),
        ProviderFilterPipeline(),
        ProviderRouter(),
        recorder,
        fakeProfiles,
        providerSafety = providerSafety,
    )

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
        val blocking =
            object : BlocklistChecker {
                override fun isBlocked(
                    guildId: Long,
                    userId: Long,
                ): Boolean = userId == 5L
            }
        val orch =
            RequestOrchestrator(
                reg,
                fakePolicy,
                RequestWeigher(),
                ProviderFilterPipeline(),
                ProviderRouter(),
                recorder,
                fakeProfiles,
                blocking,
            )
        assertEquals(RequestState.REJECTED, orch.handle(input).state) // input.userId = 5
    }

    @Test
    fun `쿼터 초과 → REJECTED`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val noBlock =
            object : BlocklistChecker {
                override fun isBlocked(
                    guildId: Long,
                    userId: Long,
                ): Boolean = false
            }
        val quota =
            object : QuotaChecker {
                override fun exceededQuota(
                    guildId: Long,
                    userId: Long,
                    roleIds: Set<Long>,
                ): Boolean = true
            }
        val orch =
            RequestOrchestrator(
                reg,
                fakePolicy,
                RequestWeigher(),
                ProviderFilterPipeline(),
                ProviderRouter(),
                recorder,
                fakeProfiles,
                noBlock,
                quota,
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
    fun `프로바이더 없음 → 다음 행동 안내와 함께 FAILED`() {
        val r = orchestrator(newRegistry()).handle(input)
        assertEquals(RequestState.FAILED, r.state)
        assertTrue(r.failReason!!.contains("/프로바이더참여"))
        assertTrue(r.failReason!!.contains("/내상태"))
        assertTrue(r.failReason!!.contains("Provider가 연결되면 다시 질문해주세요"))
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

    @Test
    fun `deep 응답 모드는 짧은 질문도 light only provider 에 바로 보내지 않는다`() {
        try {
            fakeProfiles.supported = setOf(ModelBurden.LIGHT)

            val normalReg = newRegistry()
            register(normalReg, 1, "ok")
            val normal = orchestrator(normalReg).handle(input.copy(userId = 61, responseMode = "balanced"))

            val deepReg = newRegistry()
            val lightOnly = register(deepReg, 2, "ok")
            val deep = orchestrator(deepReg).handle(input.copy(userId = 62, responseMode = "deep"))

            assertEquals(RequestState.COMPLETED, normal.state)
            assertEquals(RequestState.REJECTED, deep.state)
            assertEquals(null, deep.providerId)
            assertEquals(null, (lightOnly.connection as EchoConnection).lastInfer)
        } finally {
            fakeProfiles.supported = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY)
        }
    }

    @Test
    fun `Provider 보호 상태면 단일 질문 라우팅에서도 제외하고 안전한 provider 로 보낸다`() {
        val reg = newRegistry()
        val protected = register(reg, 1, "ok")
        val safe = register(reg, 2, "ok")
        val safety =
            object : ProviderSafetyChecker {
                override fun isRoutingProtected(
                    guildId: Long,
                    providerUserId: Long,
                ): Boolean = providerUserId == 1L
            }

        val r = orchestrator(reg, safety).handle(input)

        assertEquals(RequestState.COMPLETED, r.state)
        assertEquals(2L, r.providerId)
        assertEquals(null, (protected.connection as EchoConnection).lastInfer)
        assertNotNull((safe.connection as EchoConnection).lastInfer)
    }

    @Test
    fun `모든 Provider가 보호 상태면 다음 행동 안내와 함께 실패한다`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        register(reg, 2, "ok")
        val safety =
            object : ProviderSafetyChecker {
                override fun isRoutingProtected(
                    guildId: Long,
                    providerUserId: Long,
                ): Boolean = true
            }

        val r = orchestrator(reg, safety).handle(input)

        assertEquals(RequestState.FAILED, r.state)
        assertTrue(r.failReason!!.contains("참여 PC를 보호"))
        assertTrue(r.failReason!!.contains("/내상태"))
    }

    @Test
    fun `선호 모델이 있으면 해당 모델 제공 provider 로 라우팅하고 요청에 모델을 전달`() {
        val reg = newRegistry()
        register(reg, 1, "ok", models = listOf("llama3.1:8b"))
        val selected = register(reg, 2, "ok", models = listOf("qwen-coder"))

        val r = orchestrator(reg).handle(input.copy(preferredModel = "qwen-coder", responseMode = "fast"))

        assertEquals(RequestState.COMPLETED, r.state)
        assertEquals(2L, r.providerId)
        val sent = (selected.connection as EchoConnection).lastInfer!!
        assertEquals("qwen-coder", sent.model)
        assertEquals(512, sent.options["num_predict"])
    }
}
