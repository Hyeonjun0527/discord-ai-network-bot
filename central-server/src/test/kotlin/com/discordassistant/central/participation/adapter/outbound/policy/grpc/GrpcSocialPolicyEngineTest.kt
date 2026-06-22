package com.discordassistant.central.participation.adapter.outbound.policy.grpc

import com.discordassistant.central.participation.adapter.outbound.policy.baseline.AlwaysSilentPolicy
import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * NEXA-P12-T020 GrpcSocialPolicyEngine acceptance 테스트(옵션 경로 골격). 서비스 부재·deadline·circuit·schema
 * 위반에서 configured fallback(always-silent)으로 안전 하강해 live action 을 멈출 수 있는지 검증한다.
 */
class GrpcSocialPolicyEngineTest {
    @Test
    fun `acceptance — 서비스 부재면 원격 미호출, fallback(always-silent)으로 live action 정지`() {
        var called = false
        val transport =
            fakeTransport(available = false) {
                called = true
                CompletableFuture.completedFuture(speakResponse())
            }
        val engine = GrpcSocialPolicyEngine(transport, fallback = AlwaysSilentPolicy())
        val out = engine.predict(request()).get()
        assertThat(called).isFalse() // 부재 시 원격 호출하지 않는다.
        assertThat(out.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE) // live action 정지.
    }

    @Test
    fun `서비스 가용·정상 응답이면 원격 결과를 쓴다`() {
        val transport = fakeTransport(available = true) { CompletableFuture.completedFuture(speakResponse()) }
        val engine = GrpcSocialPolicyEngine(transport, fallback = AlwaysSilentPolicy())
        val out = engine.predict(request()).get()
        assertThat(out.mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
        assertThat(out.modelVersion).isEqualTo("grpc-remote-1")
    }

    @Test
    fun `deadline 초과·오류면 fallback 으로 하강한다 (예외를 던지지 않음)`() {
        val transport =
            fakeTransport(available = true) {
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(RuntimeException("deadline exceeded"))
                f
            }
        val engine = GrpcSocialPolicyEngine(transport, fallback = AlwaysSilentPolicy())
        val out = engine.predict(request()).get() // 예외 없이 완료.
        assertThat(out.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
    }

    @Test
    fun `schema 위반(빈 modelVersion) 응답은 fallback 으로 하강한다`() {
        val transport =
            fakeTransport(available = true) {
                // PolicyDecisionResponse.init 가 빈 modelVersion 을 거부하므로, 위반은 transport 가 예외로 낸다고
                // 가정(여기선 future 를 예외로 완료해 schema validation 실패 경로를 자극).
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(IllegalArgumentException("modelVersion blank"))
                f
            }
        val engine = GrpcSocialPolicyEngine(transport, fallback = AlwaysSilentPolicy())
        assertThat(engine.predict(request()).get().mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
    }

    @Test
    fun `circuit breaker — 연속 실패가 임계에 닿으면 회로를 열어 원격을 건너뛴다`() {
        var calls = 0
        val transport =
            fakeTransport(available = true) {
                calls++
                val f = CompletableFuture<PolicyDecisionResponse>()
                f.completeExceptionally(RuntimeException("down"))
                f
            }
        val clock = FakeClock(0L)
        val engine =
            GrpcSocialPolicyEngine(
                transport,
                fallback = AlwaysSilentPolicy(),
                failureThreshold = 2,
                openWindow = Duration.ofSeconds(5),
                clock = clock::now,
            )
        // 2회 실패 → 회로 open.
        engine.predict(request()).get()
        engine.predict(request()).get()
        val callsAfterOpen = calls
        // open 상태에서는 원격을 호출하지 않는다(call 수 불변).
        engine.predict(request()).get()
        assertThat(calls).isEqualTo(callsAfterOpen)
        // open window 경과 후 half-open: 다시 한 번 원격 시도.
        clock.advance(6_000L)
        engine.predict(request()).get()
        assertThat(calls).isGreaterThan(callsAfterOpen)
    }

    // ── helper ─────────────────────────────────────────────────────────

    private class FakeClock(
        private var t: Long,
    ) {
        fun now(): Long = t

        fun advance(ms: Long) {
            t += ms
        }
    }

    private fun fakeTransport(
        available: Boolean,
        onPredict: () -> CompletableFuture<PolicyDecisionResponse>,
    ): PolicyGrpcTransport =
        object : PolicyGrpcTransport {
            override fun isAvailable(): Boolean = available

            override fun capabilities(): PolicyEngineCapabilities = PolicyEngineCapabilities(setOf(1), emptySet())

            override fun predict(
                request: PolicyDecisionRequest,
                deadline: Duration,
            ) = onPredict()
        }

    private fun speakResponse(): PolicyDecisionResponse =
        PolicyDecisionResponse(
            actionWeights = mapOf(SocialActionKind.SPEAK to 1.0),
            targetDistribution = ActionTargetDistribution.none(resolverVersion = "grpc-test-1"),
            delayDistribution = DelayDistribution.IMMEDIATE,
            socialActWeights = emptyMap(),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.0,
            modelVersion = "grpc-remote-1",
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
