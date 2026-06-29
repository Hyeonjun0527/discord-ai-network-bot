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
                directAddressPressure = 0.6,
                replyChainDepth = 4,
                previousIgnoredRequestCount = 2,
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
        assertThat(features.getValue(FeatureCatalog.THREAD_DIRECT_ADDRESS_PRESSURE).value).isEqualTo(0.6)
        assertThat(features.getValue(FeatureCatalog.THREAD_REPLY_CHAIN_DEPTH).value).isEqualTo(4.0)
        assertThat(features.getValue(FeatureCatalog.THREAD_PREVIOUS_IGNORED_REQUEST_COUNT).value).isEqualTo(2.0)
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
                rateLimitPressure = 0.3,
                antiSpamPressure = 0.4,
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
        assertThat(features.getValue(FeatureCatalog.TEMPO_RATE_LIMIT_PRESSURE).value).isEqualTo(0.3)
        assertThat(features.getValue(FeatureCatalog.TEMPO_ANTI_SPAM_PRESSURE).value).isEqualTo(0.4)
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

    // ── T014 memory features ──────────────────────────────────────────────────
    @Test
    fun `T014 acceptance — 입력 뷰에 기억 원문·민감 object 필드가 없다`() {
        // MemoryObservation 은 수치 요약만 — 원문/식별자 object 필드가 없음을 필드명으로 가드.
        val props =
            MemoryObservation::class
                .java.declaredFields
                .map { it.name }
        assertThat(props).noneMatch {
            it.contains("summary") ||
                it.contains("text") ||
                it.contains("topic") ||
                it.contains("subject") ||
                it.contains("eventId") ||
                it.contains("content")
        }
    }

    @Test
    fun `T014 — 관련 기억 없으면 confidence·age 는 missing, 존재·pending 은 present`() {
        val none =
            MemoryObservation(
                relevantPresent = false,
                topConfidence = 0.0,
                freshestAgeSeconds = 0.0,
                pendingIntentActive = false,
            )
        val f = MemoryFeatures.build(none)
        assertThat(f.getValue(FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE).missing).isTrue()
        assertThat(f.getValue(FeatureCatalog.MEMORY_RELEVANT_AGE_SECONDS).missing).isTrue()
        assertThat(f.getValue(FeatureCatalog.MEMORY_RELEVANT_PRESENT).value).isEqualTo(0.0)
        assertThat(f.getValue(FeatureCatalog.MEMORY_PENDING_INTENT_ACTIVE).missing).isFalse()
    }

    @Test
    fun `T014 — 관련 기억 있으면 confidence·age 가 present`() {
        val present =
            MemoryObservation(
                relevantPresent = true,
                topConfidence = 0.8,
                freshestAgeSeconds = 42.0,
                pendingIntentActive = true,
            )
        val f = MemoryFeatures.build(present)
        assertThat(f.getValue(FeatureCatalog.MEMORY_RELEVANT_CONFIDENCE).value).isEqualTo(0.8)
        assertThat(f.getValue(FeatureCatalog.MEMORY_RELEVANT_AGE_SECONDS).value).isEqualTo(42.0)
        assertThat(f.getValue(FeatureCatalog.MEMORY_PENDING_INTENT_ACTIVE).value).isEqualTo(1.0)
    }

    // ── T015 agent saturation features ────────────────────────────────────────
    @Test
    fun `T015 acceptance — 입력은 burst 단위이고 share 는 메시지 곱이 아니다`() {
        // 입력 필드는 burstCount(메시지 수 아님) — 필드명 가드.
        val props =
            AgentStateObservation::class
                .java.declaredFields
                .map { it.name }
        assertThat(props).anyMatch { it.contains("BurstCount") }
        assertThat(props).noneMatch { it.contains("messageCount") || it.contains("avgMessage") }

        val obs =
            AgentStateObservation(
                nexaBurstCount = 3,
                humanBurstCount = 9,
                lastSpokeAgeSeconds = 10.0,
                pendingActionCount = 1,
            )
        val f = AgentStateFeatures.build(obs)
        // share = 3 / (3+9) = 0.25 (burst 점유율 — 메시지 곱 아님).
        assertThat(f.getValue(FeatureCatalog.AGENT_SHARE).value).isCloseTo(0.25, within(1e-9))
        assertThat(f.getValue(FeatureCatalog.AGENT_RECENT_BURST_COUNT).value).isEqualTo(3.0)
    }

    @Test
    fun `T015 — 발화 전이면 age 는 missing`() {
        val obs =
            AgentStateObservation(
                nexaBurstCount = 0,
                humanBurstCount = 0,
                lastSpokeAgeSeconds = null,
                pendingActionCount = 0,
            )
        val f = AgentStateFeatures.build(obs)
        assertThat(f.getValue(FeatureCatalog.AGENT_LAST_SPOKE_AGE_SECONDS).missing).isTrue()
        assertThat(f.getValue(FeatureCatalog.AGENT_SHARE).value).isEqualTo(0.0)
    }

    // ── T016 eligibility mask ─────────────────────────────────────────────────
    @Test
    fun `T016 acceptance — kill switch 면 발화 불가(후처리가 금지 행동 제거 근거)`() {
        val mask =
            EligibilityFeatures.mask(
                consentGranted = true,
                channelMuted = false,
                hasSendPermission = true,
                killSwitchEngaged = true,
            )
        // 모델 확률이 높아도 mask 가 발화/외부전송을 끈다(후처리가 제거할 근거).
        assertThat(mask.canSpeak).isFalse()
        assertThat(mask.canSendExternal).isFalse()
        assertThat(mask.canObserve).isTrue() // 관찰은 동의 있으면 유지.
    }

    @Test
    fun `T016 — 행동 차원은 포함 관계를 강제한다(관찰 불가인데 발화 가능 거부)`() {
        assertThatThrownBy {
            EligibilityMask(canObserve = false, canReact = false, canSpeak = true, canSendExternal = false)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `T016 — 권한 없으면 외부 전송만 막히고 발화는 가능`() {
        val mask =
            EligibilityFeatures.mask(
                consentGranted = true,
                channelMuted = false,
                hasSendPermission = false,
                killSwitchEngaged = false,
            )
        assertThat(mask.canSpeak).isTrue()
        assertThat(mask.canSendExternal).isFalse()
    }

    private fun within(offset: Double) =
        org.assertj.core.api.Assertions
            .within(offset)
}
