package com.discordassistant.central.routing

import com.discordassistant.central.knowledge.application.WebAugmentation
import com.discordassistant.central.knowledge.application.WebSearchAugmenter
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferError
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.RequestOrchestrator
import com.discordassistant.central.routing.application.port.ALLOW_ALL_PROVIDER_SAFETY
import com.discordassistant.central.routing.application.port.BlocklistChecker
import com.discordassistant.central.routing.application.port.ProviderProfileProvider
import com.discordassistant.central.routing.application.port.ProviderSafetyChecker
import com.discordassistant.central.routing.application.port.QuotaChecker
import com.discordassistant.central.routing.application.port.RoutingPolicy
import com.discordassistant.central.routing.application.port.UsageRecorder
import com.discordassistant.central.routing.domain.model.AiRequestInput
import com.discordassistant.central.routing.domain.model.ProviderProfile
import com.discordassistant.central.routing.domain.service.ProviderFilterPipeline
import com.discordassistant.central.routing.domain.service.ProviderRouter
import com.discordassistant.central.routing.domain.service.ProviderRoutingStats
import com.discordassistant.central.routing.domain.service.RequestWeigher
import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
            val failures = mutableListOf<Long>()

            override fun recordSuccess(
                guildId: Long,
                userId: Long,
                providerId: Long,
                requestId: String,
            ) {
                count++
            }

            override fun recordProviderFailure(providerId: Long) {
                failures += providerId
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
        routingStats: ProviderRoutingStats = ProviderRoutingStats(),
    ) = RequestOrchestrator(
        reg,
        fakePolicy,
        RequestWeigher(),
        ProviderFilterPipeline(),
        ProviderRouter(),
        recorder,
        fakeProfiles,
        providerSafety = providerSafety,
        routingStats = routingStats,
    )

    private val input = AiRequestInput(guildId = 100, channelId = 200, userId = 5, prompt = "안녕", roleIds = setOf(1))

    @Test
    fun `성공 — 사용량 기록`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val r = orchestrator(reg).handle(input)
        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertNotNull(r.text)
        assertEquals(1L, r.providerId)
        assertEquals(1, recorder.count)
    }

    @Test
    fun `성공 — 라우팅 통계 기록`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val stats = ProviderRoutingStats()
        val r = orchestrator(reg, routingStats = stats).handle(input)
        val snapshot = stats.snapshot(1, ModelBurden.LIGHT)

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals(1, snapshot.sampleCount)
        assertTrue(snapshot.latencyMillis >= 1)
        assertTrue(snapshot.outputChars > 0)
    }

    private val fakeWebEnabled =
        object : WebSearchAugmenter {
            override fun isEnabled() = true

            override fun augment(prompt: String) = WebAugmentation("[웹] $prompt", listOf("https://e.com"))
        }

    @Test
    fun `웹검색 활성 + webSearch=true → 프롬프트가 증강되어 전송`() {
        val reg = newRegistry()
        val s = register(reg, 1, "ok")
        val orch =
            RequestOrchestrator(
                reg,
                fakePolicy,
                RequestWeigher(),
                ProviderFilterPipeline(),
                ProviderRouter(),
                recorder,
                fakeProfiles,
                webSearch = fakeWebEnabled,
            )
        orch.handle(input.copy(webSearch = true))
        assertEquals("[웹] 안녕", (s.connection as EchoConnection).lastInfer?.prompt)
    }

    @Test
    fun `webSearch=false → 원본 프롬프트 전송(기본)`() {
        val reg = newRegistry()
        val s = register(reg, 1, "ok")
        val orch =
            RequestOrchestrator(
                reg,
                fakePolicy,
                RequestWeigher(),
                ProviderFilterPipeline(),
                ProviderRouter(),
                recorder,
                fakeProfiles,
                webSearch = fakeWebEnabled,
            )
        orch.handle(input) // webSearch 기본 false
        assertEquals("안녕", (s.connection as EchoConnection).lastInfer?.prompt)
    }

    @Test
    fun `시간 민감 질의는 webSearch 안 줘도 자동 증강된다`() {
        val reg = newRegistry()
        val s = register(reg, 1, "ok")
        val orch =
            RequestOrchestrator(
                reg,
                fakePolicy,
                RequestWeigher(),
                ProviderFilterPipeline(),
                ProviderRouter(),
                recorder,
                fakeProfiles,
                webSearch = fakeWebEnabled,
            )
        // web 옵션을 안 줬어도(false) 최신/연도 질의면 자동 검색 증강된다.
        orch.handle(input.copy(prompt = "2026년 6월 최신 뉴스", webSearch = false))
        assertEquals("[웹] 2026년 6월 최신 뉴스", (s.connection as EchoConnection).lastInfer?.prompt)
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
        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals(2L, r.providerId) // fallback 으로 처리
    }

    @Test
    fun `모든 provider 실패 → fallback은 같은 provider를 반복하지 않고 bounded 종료`() {
        val reg = newRegistry()
        val first = register(reg, 1, "err")
        val second = register(reg, 2, "err")
        val failureStart = recorder.failures.size

        val r = orchestrator(reg).handle(input)
        val newFailures = recorder.failures.drop(failureStart)

        assertEquals(RequestState.FAILED, r.state)
        assertEquals(2, newFailures.size)
        assertEquals(setOf(1L, 2L), newFailures.toSet())
        assertNotNull((first.connection as EchoConnection).lastInfer)
        assertNotNull((second.connection as EchoConnection).lastInfer)
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
        // 풀이 비었을 때 무료 클라우드 폴백(관리자 키 연결 시 /질문 자동)도 안내한다(UX 폴리시).
        assertTrue(r.failReason!!.contains("무료 클라우드"))
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

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals(2L, r.providerId)
        val sent = (selected.connection as EchoConnection).lastInfer!!
        assertEquals("qwen-coder", sent.model)
        assertEquals(512, sent.options["num_predict"])
    }

    // ── ADR 0006: 무료질문 클라우드 직결(CloudLlm) ─────────────────────────────────

    /** 호출 여부와 인자를 기록하는 fake — 실제 z.ai 호출 없이 라우팅 분기를 검증한다. */
    private class FakeCloudLlm(
        private val enabled: Boolean,
    ) : CloudLlm {
        var generateCalls = 0
        var lastPrompt: String? = null
        var lastModel: String? = null

        override fun isEnabled() = enabled

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult {
            generateCalls++
            lastPrompt = prompt
            lastModel = model
            return CloudLlmResult("클라우드 답변")
        }
    }

    private fun orchestratorWithCloud(
        reg: ConnectionRegistry,
        cloud: CloudLlm,
        blocklist: BlocklistChecker =
            object : BlocklistChecker {
                override fun isBlocked(
                    guildId: Long,
                    userId: Long,
                ) = false
            },
        quota: QuotaChecker =
            object : QuotaChecker {
                override fun exceededQuota(
                    guildId: Long,
                    userId: Long,
                    roleIds: Set<Long>,
                ) = false
            },
    ) = RequestOrchestrator(
        reg,
        fakePolicy,
        RequestWeigher(),
        ProviderFilterPipeline(),
        ProviderRouter(),
        recorder,
        fakeProfiles,
        blocklist,
        quota,
        cloudLlm = cloud,
    )

    @Test
    fun `정상 + glm + 키 있음 → cloudLlm 직결(sendInfer 미사용, providerId null)`() {
        val reg = newRegistry()
        val session = register(reg, 1, "ok") // 풀에 provider 가 있어도 클라우드 직결이 우선
        val cloud = FakeCloudLlm(enabled = true)
        val r = orchestratorWithCloud(reg, cloud).handle(input.copy(preferredModel = "glm-5.1"))

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals("클라우드 답변", r.text)
        assertNull(r.providerId) // 풀 프로바이더가 아니라 central 직결
        assertEquals(1, cloud.generateCalls)
        assertEquals("glm-5.1", cloud.lastModel)
        // sendInfer 경로(에이전트)는 타지 않았다 — 세션에 추론 요청이 전달되지 않음.
        assertNull((session.connection as EchoConnection).lastInfer)
    }

    @Test
    fun `정상 + glm + 키 없음 → 기존 에이전트 경로(sendInfer) 폴백`() {
        val reg = newRegistry()
        val session = register(reg, 1, "ok", models = listOf("glm-5.1"))
        val cloud = FakeCloudLlm(enabled = false)
        val r = orchestratorWithCloud(reg, cloud).handle(input.copy(preferredModel = "glm-5.1", responseMode = "fast"))

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals(0, cloud.generateCalls) // 클라우드는 호출되지 않음
        assertEquals(1L, r.providerId) // 풀 프로바이더(에이전트)가 처리
        assertNotNull((session.connection as EchoConnection).lastInfer)
    }

    @Test
    fun `비-glm 모델 → 클라우드 미사용, 항상 sendInfer 경로(변경 없음)`() {
        val reg = newRegistry()
        val session = register(reg, 1, "ok", models = listOf("llama3.1:8b"))
        val cloud = FakeCloudLlm(enabled = true) // 키 있어도
        val r = orchestratorWithCloud(reg, cloud).handle(input.copy(preferredModel = "llama3.1:8b", responseMode = "fast"))

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        assertEquals(0, cloud.generateCalls)
        assertEquals(1L, r.providerId)
        assertNotNull((session.connection as EchoConnection).lastInfer)
    }

    @Test
    fun `차단 사용자 + glm + 키 있음 → 거부(클라우드 호출 안 일어남) — 정책 우회 0`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val cloud = FakeCloudLlm(enabled = true)
        val blocking =
            object : BlocklistChecker {
                override fun isBlocked(
                    guildId: Long,
                    userId: Long,
                ) = userId == 5L
            }
        val r = orchestratorWithCloud(reg, cloud, blocklist = blocking).handle(input.copy(preferredModel = "glm-5.1"))

        assertEquals(RequestState.REJECTED, r.state)
        assertEquals(0, cloud.generateCalls)
    }

    @Test
    fun `일일한도 초과 + glm + 키 있음 → 거부(클라우드 호출 안 일어남) — 정책 우회 0`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val cloud = FakeCloudLlm(enabled = true)
        val overQuota =
            object : QuotaChecker {
                override fun exceededQuota(
                    guildId: Long,
                    userId: Long,
                    roleIds: Set<Long>,
                ) = true
            }
        val r = orchestratorWithCloud(reg, cloud, quota = overQuota).handle(input.copy(preferredModel = "glm-5.1"))

        assertEquals(RequestState.REJECTED, r.state)
        assertEquals(0, cloud.generateCalls)
    }

    @Test
    fun `금지 채널 + glm + 키 있음 → 거부(클라우드 호출 안 일어남) — 정책 우회 0`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val cloud = FakeCloudLlm(enabled = true)
        fakePolicy.channelAllowed = false
        try {
            val r = orchestratorWithCloud(reg, cloud).handle(input.copy(preferredModel = "glm-5.1"))
            assertEquals(RequestState.REJECTED, r.state)
            assertEquals(0, cloud.generateCalls)
        } finally {
            fakePolicy.channelAllowed = true
        }
    }

    @Test
    fun `클라우드 직결 실패면 FAILED 로 일반화 메시지 반환`() {
        val reg = newRegistry()
        register(reg, 1, "ok")
        val failing =
            object : CloudLlm {
                override fun isEnabled() = true

                override fun generate(
                    prompt: String,
                    model: String,
                ): CloudLlmResult =
                    throw com.discordassistant.central.routing.application
                        .CloudLlmException("클라우드 AI 일시 오류")
            }
        val r = orchestratorWithCloud(reg, failing).handle(input.copy(preferredModel = "glm-5.1"))

        assertEquals(RequestState.FAILED, r.state)
        assertEquals("클라우드 AI 일시 오류", r.failReason)
    }

    @Test
    fun `weighChars 가 있으면 시스템프롬프트 길이가 아닌 사용자 입력 길이로 부담 수준을 판단한다`() {
        // prompt 는 가드레일·페르소나를 포함해 5000자(HEAVY 기준 충족)지만,
        // 실제 사용자 입력(weighChars=50)은 LIGHT — weighChars 우선이면 LIGHT provider 도 처리 가능.
        val reg = newRegistry()
        fakeProfiles.supported = setOf(ModelBurden.LIGHT) // LIGHT 전용 provider
        register(reg, 1, "ok")

        val bigSystemPrompt = "x".repeat(5000)
        val r = orchestrator(reg).handle(input.copy(prompt = bigSystemPrompt, weighChars = 50))

        assertEquals(RequestState.COMPLETED, r.state, r.failReason)
        fakeProfiles.supported = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY)
    }
}
