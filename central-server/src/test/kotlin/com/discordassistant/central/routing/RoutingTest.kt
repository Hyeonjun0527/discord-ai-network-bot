package com.discordassistant.central.routing
import com.discordassistant.central.domain.ModelBurden
import com.discordassistant.central.provider.domain.model.ProviderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun candidate(
    id: Long,
    burdens: Set<ModelBurden> = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD),
    state: ProviderState = ProviderState.ONLINE_IDLE,
    maxConcurrency: Int = 2,
    active: Int = 0,
    remainingDaily: Int = 50,
    allowedRoles: Set<Long>? = null,
    allowedChannels: Set<Long>? = null,
    maxPrompt: Int = 100_000,
    failureRate: Double = 0.0,
    cooldown: Boolean = false,
    recent: Int = 0,
    qualityTier: String = "standard",
    observedSuccessRate: Double = 0.86,
    observedTimeoutRate: Double = 0.0,
    observedLatencyMillis: Long = 0,
    observedOutputChars: Int = 0,
    observedSampleCount: Int = 0,
) = Candidate(
    id,
    state,
    burdens,
    maxConcurrency,
    active,
    remainingDaily,
    allowedRoles,
    allowedChannels,
    maxPrompt,
    failureRate,
    cooldown,
    recent,
    emptySet(),
    qualityTier,
    observedSuccessRate,
    observedTimeoutRate,
    observedLatencyMillis,
    observedOutputChars,
    observedSampleCount,
)

private val ctxLight = RequestContext(ModelBurden.LIGHT, setOf(1L), 200L, 50)

class ProviderFilterPipelineTest {
    private val pipe = ProviderFilterPipeline()

    @Test fun `모든 조건 통과`() {
        val out = pipe.filter(listOf(candidate(1)), ctxLight)
        assertEquals(1, out.eligible.size)
        assertEquals(FilterSignal.OK, out.signal)
    }

    @Test fun `단계별 탈락 사유`() {
        assertEquals(
            "MODEL_BURDEN_UNSUPPORTED",
            pipe
                .filter(
                    listOf(candidate(1, burdens = setOf(ModelBurden.STANDARD))),
                    RequestContext(ModelBurden.HEAVY, setOf(1), 200, 50),
                ).dropped[1],
        )
        assertEquals(1, pipe.filter(listOf(candidate(1, state = ProviderState.ONLINE_BUSY, active = 1)), ctxLight).eligible.size)
        assertEquals(
            "CONCURRENCY_FULL",
            pipe
                .filter(listOf(candidate(1, state = ProviderState.ONLINE_BUSY, maxConcurrency = 1, active = 1)), ctxLight)
                .dropped[1],
        )
        assertEquals("PROVIDER_OFFLINE", pipe.filter(listOf(candidate(1, state = ProviderState.OFFLINE)), ctxLight).dropped[1])
        assertEquals("ROLE_MISMATCH", pipe.filter(listOf(candidate(1, allowedRoles = setOf(999))), ctxLight).dropped[1])
        assertEquals("CHANNEL_MISMATCH", pipe.filter(listOf(candidate(1, allowedChannels = setOf(999))), ctxLight).dropped[1])
        assertEquals("QUOTA_INSUFFICIENT", pipe.filter(listOf(candidate(1, remainingDaily = 0)), ctxLight).dropped[1])
        assertEquals("CONCURRENCY_FULL", pipe.filter(listOf(candidate(1, maxConcurrency = 1, active = 1)), ctxLight).dropped[1])
        assertEquals("COOLDOWN", pipe.filter(listOf(candidate(1, cooldown = true)), ctxLight).dropped[1])
        assertEquals("CONTEXT_LIMIT_EXCEEDED", pipe.filter(listOf(candidate(1, maxPrompt = 10)), ctxLight).dropped[1])
        assertEquals("FAILURE_RATE_TOO_HIGH", pipe.filter(listOf(candidate(1, failureRate = 0.9)), ctxLight).dropped[1])
    }

    @Test fun `신호 — 권한부족 vs 없음`() {
        assertEquals(FilterSignal.PERMISSION_DENIED, pipe.filter(listOf(candidate(1, allowedRoles = setOf(999))), ctxLight).signal)
        assertEquals(FilterSignal.NONE_AVAILABLE, pipe.filter(listOf(candidate(1, maxConcurrency = 1, active = 1)), ctxLight).signal)
        assertEquals(FilterSignal.NONE_AVAILABLE, pipe.filter(emptyList(), ctxLight).signal)
    }

    @Test fun `RESTRICTED 요청 — 관리자만 통과(#139)`() {
        val restrictedCand = candidate(1, burdens = setOf(ModelBurden.RESTRICTED), observedSampleCount = 3)
        val nonAdmin = RequestContext(ModelBurden.RESTRICTED, setOf(1L), 200L, 50, requesterIsAdmin = false)
        val admin = RequestContext(ModelBurden.RESTRICTED, setOf(1L), 200L, 50, requesterIsAdmin = true)
        assertEquals("RESTRICTED_REQUEST", pipe.filter(listOf(restrictedCand), nonAdmin).dropped[1])
        assertEquals(FilterSignal.PERMISSION_DENIED, pipe.filter(listOf(restrictedCand), nonAdmin).signal)
        assertEquals(1, pipe.filter(listOf(restrictedCand), admin).eligible.size)
    }
}

class ProviderRouterTest {
    private val router = ProviderRouter()

    @Test fun `light 요청 — heavy provider 낭비 방지(light provider 선택)`() {
        val light = candidate(1, burdens = setOf(ModelBurden.LIGHT))
        val heavy = candidate(2, burdens = setOf(ModelBurden.LIGHT, ModelBurden.STANDARD, ModelBurden.HEAVY))
        val sel = router.select(listOf(heavy, light), ctxLight)
        assertEquals(1L, sel!!.providerId) // light provider 우선
    }

    @Test fun `부하 적은 쪽이 높은 점수`() {
        val busy = candidate(1, active = 2, maxConcurrency = 3)
        val idle = candidate(2, active = 0)
        assertTrue(router.score(idle, ctxLight) > router.score(busy, ctxLight))
    }

    @Test fun `관측 latency 가 SLO 확률에 반영된다`() {
        val slow = candidate(1, observedLatencyMillis = 40_000, observedSampleCount = 12)
        val fast = candidate(2, observedLatencyMillis = 2_000, observedSampleCount = 12)
        val sel = router.select(listOf(slow, fast), ctxLight)
        assertEquals(2L, sel!!.providerId)
    }

    @Test fun `동점 — 최근 처리량 적은 쪽으로 분산`() {
        val a = candidate(1, recent = 5)
        val b = candidate(2, recent = 0)
        val sel = router.select(listOf(a, b), ctxLight)
        assertEquals(2L, sel!!.providerId)
    }

    @Test fun `품질 티어는 공정성보다 약한 보조 신호다`() {
        val overusedSpecialized = candidate(1, recent = 4, qualityTier = "specialized")
        val idleStandard = candidate(2, recent = 0, qualityTier = "standard")

        val sel = router.select(listOf(overusedSpecialized, idleStandard), ctxLight)

        assertEquals(2L, sel!!.providerId)
    }

    @Test fun `품질이 높아도 보호 필터를 통과하지 못하면 선택되지 않는다`() {
        val unsafeSpecialized = candidate(1, cooldown = true, qualityTier = "specialized")
        val safeStandard = candidate(2, qualityTier = "standard")
        val filtered = ProviderFilterPipeline().filter(listOf(unsafeSpecialized, safeStandard), ctxLight)

        assertEquals("COOLDOWN", filtered.dropped[1])
        assertEquals(2L, router.select(filtered.eligible, ctxLight)!!.providerId)
    }

    @Test fun `빈 후보 → null`() {
        assertEquals(null, router.select(emptyList(), ctxLight))
    }
}
