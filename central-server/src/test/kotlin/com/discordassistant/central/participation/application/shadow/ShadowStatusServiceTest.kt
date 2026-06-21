package com.discordassistant.central.participation.application.shadow

import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.port.out.ShadowModeState
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionRecord
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionSummary
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 관리자 shadow 상태 서비스(NEXA-P09-T010) acceptance 단위 테스트. 집계만 노출(원문/개별 행동 비포함)·오류율 계산.
 */
class ShadowStatusServiceTest {
    private val now = Instant.parse("2026-06-22T00:00:00Z")

    @Test
    fun `상태는 모드·예측 수·수집 기간·오류율을 집계로 노출한다`() {
        val modes =
            FakeModeStore(
                mode = ShadowMode.SHADOW_PREDICT,
                states = listOf(ShadowModeState("g-1", ShadowMode.SHADOW_PREDICT, now)),
            )
        val preds =
            FakePredictionStore(summary = ShadowPredictionSummary(predictionCount = 90, firstPredictedAt = now, lastPredictedAt = now))
        val service = ShadowStatusService(modes, preds)
        service.recordError("g-1")
        service.recordError("g-1") // 오류 2 → 2 / (90+2)

        val status = service.statusFor("g-1")
        assertThat(status.mode).isEqualTo(ShadowMode.SHADOW_PREDICT)
        assertThat(status.predictionCount).isEqualTo(90)
        assertThat(status.errorCount).isEqualTo(2)
        assertThat(status.errorRate).isCloseTo(
            2.0 / 92.0,
            org.assertj.core.api.Assertions
                .within(1e-9),
        )
    }

    @Test
    fun `예측·오류 0 이면 오류율 0`() {
        val service =
            ShadowStatusService(
                FakeModeStore(ShadowMode.OFF, emptyList()),
                FakePredictionStore(ShadowPredictionSummary(0, null, null)),
            )
        assertThat(service.statusFor("g-x").errorRate).isEqualTo(0.0)
    }

    @Test
    fun `정책 비교는 정책별 발화율·침묵률을 돌려준다`() {
        val scene = SceneKey("g-1", "c-1", 1)
        val preds =
            FakePredictionStore(
                summary = ShadowPredictionSummary(2, now, now),
                sceneRecords =
                    listOf(
                        record(scene, "silent", speak = 0.0),
                        record(scene, "mention", speak = 1.0),
                    ),
            )
        val service = ShadowStatusService(FakeModeStore(ShadowMode.SHADOW_PREDICT, emptyList()), preds)
        val rows = service.comparePolicies(scene)
        assertThat(rows).hasSize(2)
        assertThat(rows.first { it.modelVersion == "silent" }.silenceRate).isEqualTo(1.0)
        assertThat(rows.first { it.modelVersion == "mention" }.speakRate).isEqualTo(1.0)
    }

    private fun record(
        scene: SceneKey,
        model: String,
        speak: Double,
    ) = ShadowPredictionRecord(
        scene = scene,
        contextVersion = 1,
        modelVersion = model,
        actionWeights = mapOf(SocialActionKind.SPEAK to speak, SocialActionKind.IGNORE to (1.0 - speak)),
        sampledAction = if (speak >= 0.5) SocialActionKind.SPEAK else SocialActionKind.IGNORE,
        expectedFireAt = null,
        seed = 1L,
        featureHash = "h",
        featureVectorVersion = 1,
        predictedAt = now,
    )

    private class FakeModeStore(
        val mode: ShadowMode,
        val states: List<ShadowModeState>,
    ) : ShadowModeStorePort {
        override fun currentMode(guildPseudonym: String): ShadowMode = mode

        override fun applyTransition(audit: ShadowModeAudit) = Unit

        override fun auditTrail(guildPseudonym: String): List<ShadowModeAudit> = emptyList()

        override fun listModes(): List<ShadowModeState> = states
    }

    private class FakePredictionStore(
        val summary: ShadowPredictionSummary,
        val sceneRecords: List<ShadowPredictionRecord> = emptyList(),
    ) : ShadowPredictionStorePort {
        override fun append(record: ShadowPredictionRecord) = Unit

        override fun findByScene(scene: SceneKey): List<ShadowPredictionRecord> = sceneRecords

        override fun summarizeGuild(guildPseudonym: String): ShadowPredictionSummary = summary

        override fun purgeExpired(olderThan: Instant): Int = 0
    }
}
