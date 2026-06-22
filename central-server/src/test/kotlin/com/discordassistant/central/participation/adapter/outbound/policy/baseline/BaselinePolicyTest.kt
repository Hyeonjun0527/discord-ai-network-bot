package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionDistribution
import com.discordassistant.central.participation.domain.service.SeededPolicySampler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * baseline 정책(NEXA-P09-T001~T005) acceptance 단위 테스트. 각 baseline 이 계약을 통과하고, 의도한 분포·결정론·
 * seed 재현·calibration sanity 를 만족하는지 검증한다.
 */
class BaselinePolicyTest {
    @Test
    fun `T001 AlwaysSilent 는 항상 IGNORE 1점 분포를 낸다`() {
        val policy = AlwaysSilentPolicy()
        val response = policy.decide(request(mention = true, autoRespond = true, speechAllowed = true))
        assertThat(response.actionWeights).containsEntry(SocialActionKind.IGNORE, 1.0)
        assertThat(response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
        assertThat(response.modelVersion).isEqualTo(AlwaysSilentPolicy.MODEL_VERSION)
    }

    @Test
    fun `T001 AlwaysSilent 는 비동기 predict 도 같은 결과를 낸다(replay 경로 동일)`() {
        val policy = AlwaysSilentPolicy()
        val req = request(mention = false)
        val async = policy.predict(req).get()
        assertThat(async).isEqualTo(policy.decide(req))
    }

    @Test
    fun `T002 MentionAlwaysSpeak 는 멘션이면 SPEAK, 아니면 IGNORE`() {
        val policy = MentionAlwaysSpeakPolicy()
        assertThat(policy.decide(request(mention = true)).mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
        assertThat(policy.decide(request(mention = false)).mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
    }

    @Test
    fun `T002 MentionAlwaysSpeak 는 speechAllowed=false 면 멘션이어도 IGNORE(계약 안전)`() {
        val policy = MentionAlwaysSpeakPolicy()
        assertThat(policy.decide(request(mention = true, speechAllowed = false)).mostLikelyAction)
            .isEqualTo(SocialActionKind.IGNORE)
    }

    @Test
    fun `T003 FixedProbability 는 seed 재현이 결정론적이다`() {
        val policy = FixedProbabilityPolicy()
        val dist = policy.decide(request(mention = false)).toDomain()
        val a = SeededPolicySampler.sample(dist, seed = 12345L)
        val b = SeededPolicySampler.sample(dist, seed = 12345L)
        assertThat(a).isEqualTo(b) // 같은 seed·같은 분포 = 같은 결과
    }

    @Test
    fun `T003 FixedProbability calibration sanity — 경험 분포가 고정 확률에 수렴한다`() {
        val policy = FixedProbabilityPolicy(ignoreProbability = 0.5, reactProbability = 0.3, speakProbability = 0.2)
        val dist: ActionDistribution = policy.decide(request(mention = false)).toDomain()
        val counts = mutableMapOf<SocialActionKind, Int>()
        val n = 20_000
        for (seed in 0 until n) {
            val outcome = SeededPolicySampler.sample(dist, seed.toLong())
            counts.merge(outcome.action, 1, Int::plus)
        }
        // ±3% 허용오차로 고정 확률에 수렴(calibration sanity).
        assertThat((counts[SocialActionKind.IGNORE] ?: 0) / n.toDouble()).isCloseTo(0.5, within(0.03))
        assertThat((counts[SocialActionKind.REACT] ?: 0) / n.toDouble()).isCloseTo(0.3, within(0.03))
        assertThat((counts[SocialActionKind.SPEAK] ?: 0) / n.toDouble()).isCloseTo(0.2, within(0.03))
    }

    @Test
    fun `T003 FixedProbability 는 speechAllowed=false 면 SPEAK 확률을 IGNORE 로 흡수한다`() {
        val policy = FixedProbabilityPolicy(ignoreProbability = 0.6, reactProbability = 0.1, speakProbability = 0.3)
        val weights = policy.decide(request(mention = false, speechAllowed = false)).actionWeights
        assertThat(weights[SocialActionKind.SPEAK] ?: 0.0).isEqualTo(0.0)
        assertThat(weights.getValue(SocialActionKind.IGNORE)).isCloseTo(0.9, within(1e-9))
    }

    @Test
    fun `T004 Cooldown — 멘션이면 cooldown 무시 SPEAK`() {
        val policy = CooldownHeuristicPolicy(cooldownThreshold = 2.0)
        val req = request(mention = true, recentBursts = 5.0) // 이미 많이 말했어도 멘션이면 SPEAK
        assertThat(policy.decide(req).mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
    }

    @Test
    fun `T004 Cooldown — 멘션 아니고 최근 발화 많으면 IGNORE, 적으면 SPEAK`() {
        val policy = CooldownHeuristicPolicy(cooldownThreshold = 2.0)
        assertThat(policy.decide(request(mention = false, recentBursts = 3.0)).mostLikelyAction)
            .isEqualTo(SocialActionKind.IGNORE)
        assertThat(policy.decide(request(mention = false, recentBursts = 0.0)).mostLikelyAction)
            .isEqualTo(SocialActionKind.SPEAK)
    }

    @Test
    fun `T005 BurstAware — 직접 대상이면 발화 경향이 높다`() {
        val policy = BurstAwareHeuristicPolicy()
        val addressed = policy.decide(request(mention = true)).actionWeights[SocialActionKind.SPEAK] ?: 0.0
        val notAddressed = policy.decide(request(mention = false)).actionWeights[SocialActionKind.SPEAK] ?: 0.0
        assertThat(addressed).isGreaterThan(notAddressed)
        assertThat(addressed).isGreaterThan(0.5)
    }

    @Test
    fun `T005 BurstAware — modelVersion 에 weights 버전이 박혀 versioned config 를 추적한다`() {
        val v1 = BurstAwareHeuristicPolicy(BurstAwareWeights.V1)
        assertThat(v1.modelVersion()).isEqualTo("${BurstAwareHeuristicPolicy.MODEL_VERSION_PREFIX}-w1")
        val v2 = BurstAwareHeuristicPolicy(BurstAwareWeights.V1.copy(version = 2))
        assertThat(v2.modelVersion()).isEqualTo("${BurstAwareHeuristicPolicy.MODEL_VERSION_PREFIX}-w2")
        assertThat(v2.modelVersion()).isNotEqualTo(v1.modelVersion()) // 가중치 변경 시 분리
    }

    @Test
    fun `T005 BurstAware — speechAllowed=false 면 SPEAK 0`() {
        val policy = BurstAwareHeuristicPolicy()
        assertThat(policy.decide(request(mention = true, speechAllowed = false)).actionWeights[SocialActionKind.SPEAK] ?: 0.0)
            .isEqualTo(0.0)
    }

    private fun within(offset: Double) =
        org.assertj.core.api.Assertions
            .within(offset)

    private fun request(
        mention: Boolean,
        autoRespond: Boolean = false,
        speechAllowed: Boolean = true,
        recentBursts: Double? = null,
    ): PolicyDecisionRequest {
        val features = mutableMapOf<FeatureId, FeatureValue>()
        features[FeatureCatalog.BURST_HAS_MENTION] = FeatureValue.present(if (mention) 1.0 else 0.0)
        if (recentBursts != null) {
            features[FeatureCatalog.AGENT_RECENT_BURST_COUNT] = FeatureValue.present(recentBursts)
        }
        return PolicyDecisionRequest(
            sceneSnapshotRef = SceneSnapshotRef("guild-x", "chan-1", sceneSeq = 1, contextVersion = 1),
            features = FeatureVectorView.of(FeatureCatalog.VERSION, features),
            config = PolicyConfigView(channelMode = "auto", autoRespondEnabled = autoRespond, speechAllowed = speechAllowed),
            modelVersion = null,
            schemaVersion = 1,
            seed = 7L,
        )
    }
}
