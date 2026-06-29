package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** NEXA-P08-T018 SocialPolicyPort acceptance 단위 테스트. */
class SocialPolicyPortTest {
    @Test
    fun `T018 acceptance — port 는 routing GLM 타입을 import 하지 않는다(계약 타입만)`() {
        // SocialPolicyPort 가 의존하는 타입 이름에 routing/glm/zai 가 없다(계약 타입만).
        val deps =
            SocialPolicyPort::class.java.declaredMethods
                .flatMap { listOf(it.returnType.name) + it.parameterTypes.map(Class<*>::getName) }
        assertThat(deps).noneMatch {
            it.contains(".routing.") || it.contains("Glm", ignoreCase = true) || it.contains("Zai", ignoreCase = true)
        }
        // predict 는 비동기(CompletableFuture)로 분포를 약속한다.
        assertThat(deps).anyMatch { it == CompletableFuture::class.java.name }
    }

    @Test
    fun `T018 — predict 는 분포(PolicyDecisionResponse)를 비동기로 돌려준다`() {
        val port =
            object : SocialPolicyPort {
                override fun capabilities() = PolicyEngineCapabilities(setOf(1), emptySet())

                override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> =
                    CompletableFuture.completedFuture(
                        PolicyDecisionResponse(
                            actionWeights = mapOf(SocialActionKind.IGNORE to 1.0),
                            targetDistribution = ActionTargetDistribution.none("v1"),
                            delayDistribution = DelayDistribution.NEVER,
                            socialActWeights = emptyMap(),
                            burstProfile = BurstProfile.singleLine(),
                            uncertainty = 0.0,
                            modelVersion = "rules-1",
                        ),
                    )
            }
        val request =
            PolicyDecisionRequest(
                sceneSnapshotRef = SceneSnapshotRef("g", "c", 1, 0),
                features = FeatureVectorView.empty(version = FeatureCatalog.VERSION),
                config = PolicyConfigView("mention", autoRespondEnabled = true, speechAllowed = true),
                modelVersion = null,
                schemaVersion = 1,
                seed = 1L,
            )
        val response = port.predict(request).join()
        assertThat(response.mostLikelyAction).isEqualTo(SocialActionKind.IGNORE)
    }
}
