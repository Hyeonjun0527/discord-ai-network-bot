package com.discordassistant.central.participation.application.feature

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P08-T009~T013 feature 카탈로그·빌더의 acceptance 단위 테스트. */
class FeatureBuildersTest {
    // ── T009 catalog ─────────────────────────────────────────────────────────
    @Test
    fun `T009 — feature ID 는 유일하고 버전이 코드 SSOT 다`() {
        val ids = FeatureCatalog.all.keys.map { it.id }
        assertThat(ids).doesNotHaveDuplicates()
        assertThat(FeatureCatalog.VERSION).isGreaterThanOrEqualTo(1)
        // member ID 같은 식별자 feature 가 카탈로그에 없다(집계만).
        assertThat(ids).noneMatch { it.contains("member_id") || it.contains("user_id") }
    }

    // ── T010 burst features ───────────────────────────────────────────────────
    @Test
    fun `T010 acceptance — content unavailable 에서 본문 파생 feature 는 missing 으로 보존된다`() {
        val obs =
            BurstObservation(
                fragmentCount = 2,
                totalLength = 0,
                gapSeconds = 1.5,
                isQuestion = false,
                hasMention = true,
                isReply = false,
                sourceType = BurstSourceType.HUMAN,
                contentAvailable = false,
            )
        val features = BurstFeatures.build(obs)
        // 본문 파생: missing 보존(0 으로 뭉개지 않음).
        assertThat(features.getValue(FeatureCatalog.BURST_TOTAL_LENGTH).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.BURST_IS_QUESTION).missing).isTrue()
        // 메타(본문 불필요)는 present.
        assertThat(features.getValue(FeatureCatalog.BURST_FRAGMENT_COUNT).missing).isFalse()
        assertThat(features.getValue(FeatureCatalog.BURST_FRAGMENT_COUNT).value).isEqualTo(2.0)
        assertThat(features.getValue(FeatureCatalog.BURST_HAS_MENTION).value).isEqualTo(1.0)
    }

    @Test
    fun `T010 — content available 면 본문 파생도 present 다`() {
        val obs =
            BurstObservation(
                fragmentCount = 1,
                totalLength = 42,
                gapSeconds = 0.0,
                isQuestion = true,
                hasMention = false,
                isReply = true,
                sourceType = BurstSourceType.HUMAN,
                contentAvailable = true,
            )
        val features = BurstFeatures.build(obs)
        assertThat(features.getValue(FeatureCatalog.BURST_TOTAL_LENGTH).value).isEqualTo(42.0)
        assertThat(features.getValue(FeatureCatalog.BURST_IS_QUESTION).value).isEqualTo(1.0)
        assertThat(features.getValue(FeatureCatalog.BURST_IS_REPLY).value).isEqualTo(1.0)
    }

    // ── T011 thread features ──────────────────────────────────────────────────
    @Test
    fun `T011 acceptance — 특정 member ID 없이 entropy 로 변환된다`() {
        // 집중(한 명)=낮은 entropy, 분산(고름)=높은 entropy. member ID 는 입력에 없다.
        assertThat(ThreadFeatures.targetEntropy(listOf(1.0))).isEqualTo(0.0)
        assertThat(ThreadFeatures.targetEntropy(listOf(0.9, 0.1))).isLessThan(0.6)
        assertThat(ThreadFeatures.targetEntropy(listOf(0.5, 0.5))).isCloseTo(
            1.0,
            org.assertj.core.api.Assertions
                .within(1e-9),
        )
    }

    @Test
    fun `T011 — thread feature 빌드`() {
        val obs =
            ThreadObservation(
                hasFocusThread = true,
                addresseeProbabilities = listOf(0.5, 0.5),
                activeSpeakerCount = 3,
                topicAgeSeconds = 120.0,
            )
        val features = ThreadFeatures.build(obs)
        assertThat(features.getValue(FeatureCatalog.THREAD_FOCUS_PRESENT).value).isEqualTo(1.0)
        assertThat(
            features.getValue(FeatureCatalog.THREAD_TARGET_ENTROPY).value,
        ).isCloseTo(
            1.0,
            org.assertj.core.api.Assertions
                .within(1e-9),
        )
        assertThat(features.getValue(FeatureCatalog.THREAD_ACTIVE_SPEAKERS).value).isEqualTo(3.0)
    }

    // ── T012 tempo features ───────────────────────────────────────────────────
    @Test
    fun `T012 acceptance — 봇·옵트아웃 제외 규칙(P06 동일)이 human 집계에 적용된다`() {
        val obs =
            TempoObservation(
                participants =
                    listOf(
                        TempoParticipant(burstCount = 4, includeInHumanAggregate = true),
                        TempoParticipant(burstCount = 10, includeInHumanAggregate = false), // 봇/옵트아웃 제외
                        TempoParticipant(burstCount = 2, includeInHumanAggregate = false, isNexa = true),
                    ),
                windowSeconds = 60.0,
                humanMedianGapSeconds = 5.0,
                humanOverlapRatio = 0.2,
            )
        val features = TempoFeatures.build(obs)
        // human burst rate = 4 (제외된 10·NEXA 2 미포함) / 1분 = 4.0.
        assertThat(features.getValue(FeatureCatalog.TEMPO_HUMAN_BURST_RATE).value).isEqualTo(4.0)
        // nexa share = 2 / (4+10+2) = 0.125.
        assertThat(features.getValue(FeatureCatalog.TEMPO_NEXA_SHARE).value).isCloseTo(
            0.125,
            org.assertj.core.api.Assertions
                .within(1e-9),
        )
    }

    @Test
    fun `T012 — human burst 가 부족하면 median gap 은 missing 이다`() {
        val obs =
            TempoObservation(
                participants = listOf(TempoParticipant(1, includeInHumanAggregate = true)),
                windowSeconds = 60.0,
                humanMedianGapSeconds = null,
                humanOverlapRatio = 0.0,
            )
        assertThat(TempoFeatures.build(obs).getValue(FeatureCatalog.TEMPO_MEDIAN_GAP_SECONDS).missing).isTrue()
    }

    @Test
    fun `T012 — NEXA 가 human 집계에 포함되면 거부한다`() {
        assertThatThrownBy { TempoParticipant(1, includeInHumanAggregate = true, isNexa = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── T013 relationship features ────────────────────────────────────────────
    @Test
    fun `T013 acceptance — 작은 표본 confidence 가 별도 feature 로 포함된다`() {
        val small =
            RelationshipObservation(
                familiarity = 0.8,
                reciprocity = 0.7,
                banterAcceptance = 0.9,
                sampleSize = 1,
                observed = true,
            )
        val large = small.copy(sampleSize = 100)
        val smallConf = RelationshipFeatures.build(small).getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).value
        val largeConf = RelationshipFeatures.build(large).getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).value
        // 별도 feature 존재 + 작은 표본 = 낮은 confidence.
        assertThat(smallConf).isLessThan(largeConf)
        assertThat(RelationshipFeatures.sampleConfidence(0)).isEqualTo(0.0)
    }

    @Test
    fun `T013 — 미관측 관계는 값 feature 가 missing 이고 confidence 만 0 으로 존재한다`() {
        val unseen =
            RelationshipObservation(
                familiarity = 0.0,
                reciprocity = 0.0,
                banterAcceptance = 0.0,
                sampleSize = 0,
                observed = false,
            )
        val features = RelationshipFeatures.build(unseen)
        assertThat(features.getValue(FeatureCatalog.REL_FAMILIARITY).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.REL_BANTER_ACCEPTANCE).missing).isTrue()
        assertThat(features.getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).missing).isFalse()
        assertThat(features.getValue(FeatureCatalog.REL_SAMPLE_CONFIDENCE).value).isEqualTo(0.0)
    }
}
