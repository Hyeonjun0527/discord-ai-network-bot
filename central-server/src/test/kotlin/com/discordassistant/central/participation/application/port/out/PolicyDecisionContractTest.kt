package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/** NEXA-P08-T006/T007 정책 결정 요청/응답 계약의 acceptance 단위 테스트. */
class PolicyDecisionContractTest {
    @Test
    fun `T006 acceptance — 요청 계약에 Discord 원문·JPA entity 필드가 없다`() {
        val req = sampleRequest()
        // 직렬화 가능한 참조·feature·설정만. 원문/엔티티 타입 필드 부재를 필드명으로 가드.
        val reqProps = PolicyDecisionRequest::class.memberProperties.map { it.name }
        assertThat(reqProps).doesNotContain("rawContent", "messageText", "entity", "content")
        val refProps = SceneSnapshotRef::class.memberProperties.map { it.name }
        assertThat(refProps).doesNotContain("text", "content", "rawMessage")
        assertThat(req.features.version).isEqualTo(req.features.version) // smoke
    }

    @Test
    fun `T006 — schemaVersion 은 1 이상이어야 한다`() {
        assertThatThrownBy { sampleRequest().copy(schemaVersion = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T007 acceptance — 단일 답 강제 없이 확률분포가 검증을 통과한다`() {
        val response =
            PolicyDecisionResponse(
                actionWeights =
                    mapOf(
                        SocialActionKind.IGNORE to 0.2,
                        SocialActionKind.WAIT to 0.2,
                        SocialActionKind.SPEAK to 0.6,
                    ),
                targetDistribution =
                    ActionTargetDistribution.none("v1"),
                delayDistribution =
                    DelayDistribution(mapOf(DelayBucket.IMMEDIATE to 0.5, DelayBucket.SHORT to 0.5)),
                socialActWeights = mapOf(SocialAct.ACKNOWLEDGE to 0.4, SocialAct.ASK to 0.6),
                burstProfile = BurstProfile.singleLine(),
                uncertainty = 0.3,
                modelVersion = "rules-1",
            )
        // 여러 행동에 확률이 분산돼도 유효(단일 답 비강제). argmax 는 편의일 뿐.
        assertThat(response.actionWeights).hasSize(3)
        assertThat(response.mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
    }

    @Test
    fun `T007 — actionWeights 합이 1이 아니면 거부한다`() {
        assertThatThrownBy {
            PolicyDecisionResponse(
                actionWeights = mapOf(SocialActionKind.IGNORE to 0.3, SocialActionKind.SPEAK to 0.3),
                targetDistribution = ActionTargetDistribution.none("v1"),
                delayDistribution = DelayDistribution.IMMEDIATE,
                socialActWeights = emptyMap(),
                burstProfile = BurstProfile.singleLine(),
                uncertainty = 0.0,
                modelVersion = "m",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T007 — socialActWeights 가 비면 SPEAK 미정으로 허용된다`() {
        val response =
            PolicyDecisionResponse(
                actionWeights = mapOf(SocialActionKind.IGNORE to 1.0),
                targetDistribution = ActionTargetDistribution.none("v1"),
                delayDistribution = DelayDistribution.NEVER,
                socialActWeights = emptyMap(),
                burstProfile = BurstProfile.singleLine(),
                uncertainty = 0.0,
                modelVersion = "m",
            )
        assertThat(response.socialActWeights).isEmpty()
    }

    private fun sampleRequest(): PolicyDecisionRequest =
        PolicyDecisionRequest(
            sceneSnapshotRef = SceneSnapshotRef("g", "c", 3, 1),
            features = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
            config = PolicyConfigView("mention", autoRespondEnabled = true, speechAllowed = true),
            modelVersion = null,
            schemaVersion = 1,
            seed = 99L,
        )
}
