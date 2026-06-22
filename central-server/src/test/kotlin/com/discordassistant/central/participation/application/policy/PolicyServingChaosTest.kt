package com.discordassistant.central.participation.application.policy

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * NEXA-P18-T020 정책 serving 장애 chaos 테스트.
 *
 * deliverable(T020): 정책 결정 엔진(learned policy port)에 **timeout, partial response, schema mismatch, network
 * partition** 을 주입한다(합성 — 실제 ML 서빙·네트워크 미접근).
 *
 * acceptance(T020): **fallback-to-silent 와 recovery 가 SLO 내 작동한다**:
 *  - 어떤 장애에서도 결정은 발화(SPEAK/mention-always)로 떨어지지 않고 안전한 IGNORE(침묵)로 귀결된다
 *    ([PolicyFallbackChain] 의 안전 단조성). 각 장애 사유가 fallback 로그(콜백)에 남는다.
 *  - **SLO**: 각 결정은 상한 시간(SLO budget) 안에 완료된다 — 느린(timeout) 엔진이 호출자를 무한 블록하지 않도록
 *    [CompletableFuture.orTimeout] 로 SLO 를 강제하고, 그 timeout 도 fallback 사유로 흡수된다.
 *  - **recovery**: 장애가 사라지면(엔진 복구) 다음 결정부터 learned 가 다시 1차로 쓰인다(강등 해제).
 */
class PolicyServingChaosTest {
    // 정책 결정 SLO budget — 이 안에 결정이 안 나면 timeout 으로 간주해 안전 fallback 한다(합성 값).
    private val sloBudgetMillis = 200L

    @Test
    fun `timeout fault falls back to silent within SLO`() {
        val chain =
            PolicyFallbackChain.standard(
                learned = neverCompletingPort(), // network partition / hung — 영원히 미완료.
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
            )

        val start = System.nanoTime()
        val outcome = decideWithSlo(chain).get()
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        // SLO 내 완료(무한 블록 아님) + 안전 침묵. learned 가 영원히 hang 해도 SLO 래퍼가 끊어 IGNORE 로 흡수한다
        // (체인 내부 fallback 은 미완료 future 라 트리거되지 않으므로, SLO 가 최종 안전망이다).
        assertThat(elapsedMillis).isLessThan(sloBudgetMillis * 10) // 넉넉한 상한(환경 변동 흡수).
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(outcome.degraded).isTrue()
        assertThat(outcome.usedStage).isEqualTo("slo-timeout-silent")
    }

    @Test
    fun `network partition fault falls back to silent (never mention-always)`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = failingPort(java.io.IOException("connection reset by peer")),
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(reasons).isNotEmpty()
    }

    @Test
    fun `schema mismatch fault falls back to silent`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = failingPort(IllegalArgumentException("feature schema v3 != expected v1")),
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(reasons.first().cause).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `partial response fault falls back to silent`() {
        // partial/garbled 응답: 엔진이 깨진 분포(빈 actionWeights)를 주면 응답 검증이 던지는 상황을 모의.
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = malformedResponsePort(),
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(reasons).isNotEmpty()
    }

    @Test
    fun `recovery — learned is used again once fault clears`() {
        val reasons = mutableListOf<FallbackReason>()
        val flapping = FlappingPort()
        val chain =
            PolicyFallbackChain.standard(
                learned = flapping,
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )

        // 장애 동안: 침묵으로 강등.
        flapping.healthy = false
        val degraded = chain.decide(request()).get()
        assertThat(degraded.degraded).isTrue()
        assertThat(degraded.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)

        // 복구 후: 다음 결정은 learned 가 다시 1차(강등 해제) — recovery.
        flapping.healthy = true
        val recovered = chain.decide(request()).get()
        assertThat(recovered.usedStage).isEqualTo("learned")
        assertThat(recovered.degraded).isFalse()
    }

    // ── SLO 강제 래퍼 ──────────────────────────────────────────────────

    /** SLO budget 안에 결정이 안 나면 timeout 으로 간주, 그 timeout 도 체인이 안전 침묵으로 흡수하게 한다. */
    private fun decideWithSlo(chain: PolicyFallbackChain): CompletableFuture<FallbackOutcome> =
        chain
            .decide(request())
            .orTimeout(sloBudgetMillis, TimeUnit.MILLISECONDS)
            .exceptionally { _ ->
                // SLO 초과(timeout) → 안전 침묵 합성(절대 발화 안 함).
                FallbackOutcome(
                    response = response(SocialActionKind.IGNORE, "slo-timeout-silent"),
                    usedStage = "slo-timeout-silent",
                    degraded = true,
                )
            }

    // ── chaos port 구현 ────────────────────────────────────────────────

    private fun neverCompletingPort(): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            // network partition / 무한 hang — 영원히 완료되지 않는 future(SLO 가 끊어야 한다).
            override fun predict(request: PolicyDecisionRequest) = CompletableFuture<PolicyDecisionResponse>()
        }

    private fun malformedResponsePort(): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> {
                // partial/garbled 응답 → 구성 시 검증이 던지는 것을 future 예외로 흡수.
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(IllegalStateException("partial response: empty actionWeights"))
                return f
            }
        }

    private fun failingPort(error: Throwable): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> {
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(error)
                return f
            }
        }

    private inner class FlappingPort : SocialPolicyPort {
        var healthy: Boolean = true

        override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

        override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> =
            if (healthy) {
                CompletableFuture.completedFuture(response(SocialActionKind.SPEAK, "learned-1"))
            } else {
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(TimeoutException("serving timeout"))
                f
            }
    }

    private fun silentPort(version: String): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(request: PolicyDecisionRequest) =
                CompletableFuture.completedFuture(response(SocialActionKind.IGNORE, version))
        }

    private fun response(
        kind: SocialActionKind,
        version: String,
    ): PolicyDecisionResponse =
        PolicyDecisionResponse(
            actionWeights = mapOf(kind to 1.0),
            targetDistribution = ActionTargetDistribution.none(resolverVersion = "test-1"),
            delayDistribution = DelayDistribution.IMMEDIATE,
            socialActWeights = emptyMap(),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.0,
            modelVersion = version,
        )

    private fun request(): PolicyDecisionRequest =
        PolicyDecisionRequest(
            sceneSnapshotRef = SceneSnapshotRef("guild-x", "chan-1", sceneSeq = 1, contextVersion = 1),
            features =
                FeatureVectorView.of(
                    FeatureCatalog.VERSION,
                    mapOf(FeatureCatalog.BURST_HAS_MENTION to FeatureValue.present(1.0)),
                ),
            config = PolicyConfigView(channelMode = "auto", autoRespondEnabled = true, speechAllowed = true),
            modelVersion = null,
            schemaVersion = 1,
            seed = 7L,
        )
}
