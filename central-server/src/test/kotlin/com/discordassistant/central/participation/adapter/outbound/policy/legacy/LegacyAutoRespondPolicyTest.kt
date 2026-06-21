package com.discordassistant.central.participation.adapter.outbound.policy.legacy

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.FeatureId
import com.discordassistant.central.participation.application.port.out.FeatureValue
import com.discordassistant.central.participation.application.port.out.FeatureVectorView
import com.discordassistant.central.participation.application.port.out.PolicyConfigView
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.SceneSnapshotRef
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * legacy 자동응답 정책 adapter(NEXA-P09-T006) golden test. 운영 동작(DiscordBot.onMessageReceived 두 분기)을
 * 정책 계약으로 충실히 미러하는지, baseline 들과 같은 계약 형태를 내는지 **golden 표**로 못박는다.
 *
 * acceptance(T006) — legacy 결과와 기존 운영 동작 차이를 golden 으로 기록한다.
 */
class LegacyAutoRespondPolicyTest {
    private val policy = LegacyAutoRespondPolicy()

    @Test
    fun `golden — 멘션 또는 자동응답 채널이면 SPEAK, 둘 다 아니면 IGNORE`() {
        // (mention, autoRespond, speechAllowed) → 기대 행동. 운영 두 분기의 충실한 미러.
        data class Case(
            val mention: Boolean,
            val auto: Boolean,
            val speech: Boolean,
            val expected: SocialActionKind,
        )
        val golden =
            listOf(
                Case(mention = true, auto = false, speech = true, SocialActionKind.SPEAK), // 분기①: 멘션 ask
                Case(mention = false, auto = true, speech = true, SocialActionKind.SPEAK), // 분기②: 자동응답 채널
                Case(mention = true, auto = true, speech = true, SocialActionKind.SPEAK), // 둘 다 → 발화
                Case(mention = false, auto = false, speech = true, SocialActionKind.IGNORE), // 봇 미관여
                Case(mention = true, auto = true, speech = false, SocialActionKind.IGNORE), // 게이트 닫힘 → 발화 금지
            )
        golden.forEach { c ->
            val actual = policy.decide(request(c.mention, c.auto, c.speech)).mostLikelyAction
            assertThat(actual).`as`("mention=${c.mention} auto=${c.auto} speech=${c.speech}").isEqualTo(c.expected)
        }
    }

    @Test
    fun `modelVersion 이 운영 정책 식별자다`() {
        assertThat(policy.decide(request(mention = true, auto = false, speech = true)).modelVersion)
            .isEqualTo(LegacyAutoRespondPolicy.MODEL_VERSION)
    }

    @Test
    fun `동기와 비동기 결과가 같다(replay 경로 동일)`() {
        val req = request(mention = false, auto = true, speech = true)
        assertThat(policy.predict(req).get()).isEqualTo(policy.decide(req))
    }

    private fun request(
        mention: Boolean,
        auto: Boolean,
        speech: Boolean,
    ): PolicyDecisionRequest {
        val features = mutableMapOf<FeatureId, FeatureValue>()
        features[FeatureCatalog.BURST_HAS_MENTION] = FeatureValue.present(if (mention) 1.0 else 0.0)
        return PolicyDecisionRequest(
            sceneSnapshotRef = SceneSnapshotRef("guild-x", "chan-1", sceneSeq = 1, contextVersion = 1),
            features = FeatureVectorView.of(FeatureCatalog.VERSION, features),
            config = PolicyConfigView(channelMode = "auto", autoRespondEnabled = auto, speechAllowed = speech),
            modelVersion = null,
            schemaVersion = 1,
            seed = 1L,
        )
    }
}
