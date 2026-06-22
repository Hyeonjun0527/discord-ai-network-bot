package com.discordassistant.central.participation.application.evaluation

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.feature.FeatureMeta
import com.discordassistant.central.participation.application.feature.FeatureType
import com.discordassistant.central.participation.application.feature.PrivacyClass
import com.discordassistant.central.participation.application.port.out.FeatureId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * shadow feature leakage 감사 acceptance 단위 테스트(NEXA-P09-T023, security). 예측 cutoff 이후 관찰이 feature 에
 * 들어가는지, 금지 privacy class 를 쓰는지 검사한다. 결정론.
 */
class FeatureLeakageAuditTest {
    private val t0 = Instant.parse("2026-06-22T00:00:00Z")

    private fun watermark(
        id: String,
        observedAt: Instant,
        privacyClass: PrivacyClass = PrivacyClass.OBSERVABLE,
    ): FeatureWatermark {
        val fid = FeatureId(id)
        return FeatureWatermark(
            featureId = fid,
            observedAt = observedAt,
            meta = FeatureMeta(fid, FeatureType.COUNT, privacyClass),
        )
    }

    @Test
    fun `T023 — cutoff 이내 watermark 만 있으면 clean`() {
        val result =
            FeatureLeakageAudit.audit(
                predictedAt = t0,
                watermarks =
                    listOf(
                        watermark("burst.fragment_count", t0.minusSeconds(1)),
                        watermark("tempo.human_burst_rate", t0), // cutoff 와 정확히 같음은 허용(이후 아님)
                    ),
            )
        assertThat(result.isClean).isTrue()
        assertThat(result.violations).isEmpty()
    }

    @Test
    fun `T023 — cutoff 이후 관찰을 본 feature 는 미래 leakage 위반`() {
        val result =
            FeatureLeakageAudit.audit(
                predictedAt = t0,
                watermarks =
                    listOf(
                        watermark("burst.fragment_count", t0.minusSeconds(1)),
                        watermark("leaky.future", t0.plusSeconds(5)), // 예측 이후 관찰 → leakage
                    ),
            )
        assertThat(result.isClean).isFalse()
        assertThat(result.violations).hasSize(1)
        assertThat(result.violations.single().kind).isEqualTo(LeakageKind.FUTURE_OBSERVATION)
        assertThat(result.violations.single().featureId).isEqualTo(FeatureId("leaky.future"))
    }

    @Test
    fun `T023 — 허용되지 않은 privacy class 는 금지 추론 위반`() {
        // allowedClasses 에서 AGGREGATE 를 빼면 AGGREGATE feature 가 위반으로 잡힌다.
        val result =
            FeatureLeakageAudit.audit(
                predictedAt = t0,
                watermarks = listOf(watermark("relationship.familiarity", t0.minusSeconds(1), PrivacyClass.AGGREGATE)),
                allowedClasses = setOf(PrivacyClass.OBSERVABLE),
            )
        assertThat(result.violations.single().kind).isEqualTo(LeakageKind.FORBIDDEN_PRIVACY_CLASS)
    }

    @Test
    fun `T023 — 카탈로그 모든 feature 는 관찰 가능 또는 집계 class 만(금지 추론 미사용)`() {
        // observable-state-policy: 성격/감정 추론 같은 금지 class 가 카탈로그에 없어야 한다.
        val allowed = setOf(PrivacyClass.OBSERVABLE, PrivacyClass.AGGREGATE)
        assertThat(FeatureCatalog.all.values.map { it.privacyClass }).allMatch { it in allowed }
    }
}
