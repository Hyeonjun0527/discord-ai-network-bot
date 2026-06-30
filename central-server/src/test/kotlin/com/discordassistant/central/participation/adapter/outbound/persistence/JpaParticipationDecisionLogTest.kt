package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.DecisionLogRecord
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * NEXA-P08-T023 결정 로그 persistence(Flyway V58) acceptance 단위 테스트. 실제 JPA(H2 Postgres 모드)에서
 * IGNORE 저장·멱등 append·보존(purge)·원문 비저장을 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaParticipationDecisionLog::class)
class JpaParticipationDecisionLogTest
    @Autowired
    constructor(
        val log: JpaParticipationDecisionLog,
    ) {
        @Test
        fun `T023 acceptance — IGNORE 도 저장되고 조회된다`() {
            log.append(record("corr-ignore", SocialActionKind.IGNORE, Instant.now()))
            val found = log.findByCorrelationId("corr-ignore")
            assertThat(found).isNotNull
            assertThat(found!!.actionKind).isEqualTo(SocialActionKind.IGNORE)
            assertThat(found.featureHash).isEqualTo("hash-corr-ignore")
        }

        @Test
        fun `T023 — 같은 correlationId 재기록은 멱등(1건으로 수렴)`() {
            log.append(record("corr-1", SocialActionKind.SPEAK, Instant.now()))
            log.append(record("corr-1", SocialActionKind.WAIT, Instant.now())) // 갱신
            val found = log.findByCorrelationId("corr-1")
            assertThat(found!!.actionKind).isEqualTo(SocialActionKind.WAIT)
        }

        @Test
        fun `T023 acceptance — 보존 정책으로 오래된 로그가 purge 된다`() {
            val old = Instant.now().minus(40, ChronoUnit.DAYS)
            val fresh = Instant.now()
            log.append(record("old", SocialActionKind.IGNORE, old))
            log.append(record("fresh", SocialActionKind.SPEAK, fresh))

            val purged = log.purgeExpired(olderThan = Instant.now().minus(30, ChronoUnit.DAYS))
            assertThat(purged).isEqualTo(1)
            assertThat(log.findByCorrelationId("old")).isNull()
            assertThat(log.findByCorrelationId("fresh")).isNotNull
        }

        @Test
        fun `T023 — removedKinds 가 라운드트립으로 보존된다`() {
            log.append(
                record("corr-rk", SocialActionKind.REACT, Instant.now())
                    .copy(removedKinds = setOf(SocialActionKind.SPEAK, SocialActionKind.CANCEL_PENDING)),
            )
            val found = log.findByCorrelationId("corr-rk")
            assertThat(found!!.removedKinds)
                .containsExactlyInAnyOrder(SocialActionKind.SPEAK, SocialActionKind.CANCEL_PENDING)
        }

        @Test
        fun `decision explainability metadata 는 원문 없이 라운드트립으로 보존된다`() {
            log.append(
                record("corr-explain", SocialActionKind.WAIT, Instant.now())
                    .copy(
                        reasonCode = "POLICY_LOW_CONFIDENCE",
                        judgeConfidence = 0.42,
                        decisionDelayMillis = 1_000,
                        lastWakeUpReason = "MENTION",
                        missingInputCodes = setOf("TIMESTAMP_MISSING", "RECENT_TURNS_MISSING"),
                        evidenceRefs = setOf("raw_context_message:v1:abc-123", "raw_context_window:hash=def456"),
                    ),
            )

            val found = log.findByCorrelationId("corr-explain")
            assertThat(found!!.reasonCode).isEqualTo("POLICY_LOW_CONFIDENCE")
            assertThat(found.judgeConfidence).isEqualTo(0.42)
            assertThat(found.decisionDelayMillis).isEqualTo(1_000)
            assertThat(found.lastWakeUpReason).isEqualTo("MENTION")
            assertThat(found.missingInputCodes).containsExactlyInAnyOrder("TIMESTAMP_MISSING", "RECENT_TURNS_MISSING")
            assertThat(found.evidenceRefs)
                .containsExactlyInAnyOrder("raw_context_message:v1:abc-123", "raw_context_window:hash=def456")
        }

        @Test
        fun `judge trace metadata 는 원문 없이 라운드트립으로 보존된다`() {
            log.append(
                record("corr-trace", SocialActionKind.SPEAK, Instant.now())
                    .copy(
                        judgeModelVersion = "nia-single-judge-shadow-v1",
                        judgePromptVersion = "nia-judge-prompt-v1",
                        fewShotSetId = "fewshot.global.nia",
                        fewShotVersion = 3,
                        rawWindowHash = "sha256=abc123",
                        rawWindowMessageRefs =
                            setOf(
                                "raw_context_message:msg_a",
                                "raw_context_message:msg_b",
                            ),
                        shadowBaselineAction = SocialActionKind.WAIT,
                        finalDecisionSource = "SINGLE_JUDGE_SHADOW",
                    ),
            )

            val found = log.findByCorrelationId("corr-trace")
            assertThat(found!!.judgeModelVersion).isEqualTo("nia-single-judge-shadow-v1")
            assertThat(found.judgePromptVersion).isEqualTo("nia-judge-prompt-v1")
            assertThat(found.fewShotSetId).isEqualTo("fewshot.global.nia")
            assertThat(found.fewShotVersion).isEqualTo(3)
            assertThat(found.rawWindowHash).isEqualTo("sha256=abc123")
            assertThat(found.rawWindowMessageRefs)
                .containsExactlyInAnyOrder("raw_context_message:msg_a", "raw_context_message:msg_b")
            assertThat(found.shadowBaselineAction).isEqualTo(SocialActionKind.WAIT)
            assertThat(found.finalDecisionSource).isEqualTo("SINGLE_JUDGE_SHADOW")
        }

        private fun record(
            correlationId: String,
            kind: SocialActionKind,
            decidedAt: Instant,
        ): DecisionLogRecord =
            DecisionLogRecord(
                correlationId = correlationId,
                guildPseudonym = "guild-x",
                channelId = "chan-1",
                contextVersion = 3,
                actionKind = kind,
                featureHash = "hash-$correlationId",
                featureVectorVersion = 1,
                modelVersion = "rules-1",
                seed = 42L,
                removedKinds = emptySet(),
                consumedGenerationQuota = kind == SocialActionKind.SPEAK,
                decidedAt = decidedAt,
            )
    }
