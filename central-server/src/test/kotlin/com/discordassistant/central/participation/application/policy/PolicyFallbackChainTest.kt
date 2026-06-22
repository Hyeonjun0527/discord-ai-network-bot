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

/**
 * NEXA-P12-T021 PolicyFallbackChain acceptance 테스트. 오류 시 더 조용한 정책으로만 하강하고(절대 mention-always
 * 아님), 이유가 로그(콜백)에 남는지 검증한다.
 */
class PolicyFallbackChainTest {
    @Test
    fun `acceptance — learned 성공이면 그대로 쓰고 강등 아님`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = speakPort("learned-1"),
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.usedStage).isEqualTo("learned")
        assertThat(outcome.degraded).isFalse()
        assertThat(reasons).isEmpty() // 실패 없음 → 로그 없음.
    }

    @Test
    fun `acceptance — learned 실패면 safe baseline 으로 하강하고 이유가 로그에 남는다`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = failingPort(IllegalStateException("ml engine down")),
                safeBaseline = silentPort("safe-1"),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.usedStage).isEqualTo("safe-baseline")
        assertThat(outcome.degraded).isTrue()
        // 더 조용한 쪽으로 갔다 — IGNORE(mention-always 아님).
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        // 이유가 로그에 남는다(acceptance).
        assertThat(reasons).hasSize(1)
        assertThat(reasons[0].failedStage).isEqualTo("learned")
        assertThat(reasons[0].nextStage).isEqualTo("safe-baseline")
        assertThat(reasons[0].logMessage).contains("learned").contains("safe-baseline")
    }

    @Test
    fun `acceptance — learned·safe 둘 다 실패면 always-silent 까지 내려가고 발화하지 않는다 (mention-always 아님)`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = failingPort(RuntimeException("ml down")),
                safeBaseline = failingPort(RuntimeException("heuristic down")),
                alwaysSilent = silentPort("silent-1"),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        assertThat(outcome.usedStage).isEqualTo("always-silent")
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(reasons.map { it.failedStage }).containsExactly("learned", "safe-baseline")
    }

    @Test
    fun `acceptance — 마지막 always-silent 마저 실패해도 계약상 안전 IGNORE 로 떨어진다 (절대 발화 안 함)`() {
        val reasons = mutableListOf<FallbackReason>()
        val chain =
            PolicyFallbackChain.standard(
                learned = failingPort(RuntimeException("ml down")),
                safeBaseline = failingPort(RuntimeException("heuristic down")),
                alwaysSilent = failingPort(RuntimeException("silent port itself threw")),
                onFallback = { reasons.add(it) },
            )
        val outcome = chain.decide(request()).get()
        // 모든 port 실패 → 합성된 안전 IGNORE(절대 발화 없음).
        assertThat(outcome.response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(outcome.response.actionWeights).containsEntry(SocialActionKind.IGNORE, 1.0)
        assertThat(outcome.degraded).isTrue()
        // 마지막 단계 실패 사유는 nextStage 가 없다.
        assertThat(reasons.last().failedStage).isEqualTo("always-silent")
        assertThat(reasons.last().nextStage).isNull()
    }

    // ── 테스트용 port 구현 ──────────────────────────────────────────────

    private fun speakPort(version: String): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(request: PolicyDecisionRequest) =
                CompletableFuture.completedFuture(response(SocialActionKind.SPEAK, version))
        }

    private fun silentPort(version: String): SocialPolicyPort =
        object : SocialPolicyPort {
            override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(request: PolicyDecisionRequest) =
                CompletableFuture.completedFuture(response(SocialActionKind.IGNORE, version))
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
