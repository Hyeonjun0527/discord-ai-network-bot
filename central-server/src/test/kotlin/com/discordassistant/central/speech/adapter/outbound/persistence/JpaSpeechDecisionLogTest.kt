package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.speech.application.port.out.SpeechDecisionLog
import com.discordassistant.central.speech.application.port.out.SpeechDecisionOutcome
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaSpeechDecisionLog::class)
class JpaSpeechDecisionLogTest
    @Autowired
    constructor(
        private val log: JpaSpeechDecisionLog,
        private val rows: NexaSpeechDecisionLogRepository,
    ) {
        @Test
        fun `speech decision log stores blocked trace without raw text`() {
            log.record(
                SpeechDecisionLog(
                    decisionId = "participation:chan:1",
                    correlationId = "participation:chan:1",
                    focusThreadKey = "discord:guild-pseudo:chan-pseudo",
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    outcome = SpeechDecisionOutcome.BLOCKED,
                    blockedStage = "SPEECH_GENERATION",
                    blockedReason = "CONSENT_REVOKED",
                    highRiskDowngraded = false,
                    consentBlocked = true,
                    generatedCandidateCount = 0,
                    criticBlockReasons = emptySet(),
                    selectedContentRef = null,
                    createdAt = Instant.parse("2026-06-30T11:00:00Z"),
                ),
            )

            val found = rows.findFirstByCorrelationIdOrderByCreatedAtDesc("participation:chan:1")!!.toDomain()
            assertThat(found.decisionId).isEqualTo("participation:chan:1")
            assertThat(found.outcome).isEqualTo(SpeechDecisionOutcome.BLOCKED)
            assertThat(found.blockedStage).isEqualTo("SPEECH_GENERATION")
            assertThat(found.blockedReason).isEqualTo("CONSENT_REVOKED")
            assertThat(found.consentBlocked).isTrue()
            assertThat(rows.findAll().single().toString()).doesNotContain("안녕", "raw", "prompt")
        }

        @Test
        fun `speech decision log stores selected candidate ref and critic reason codes`() {
            log.record(
                SpeechDecisionLog(
                    decisionId = "participation:chan:2",
                    correlationId = "participation:chan:2",
                    focusThreadKey = "discord:guild-pseudo:chan-pseudo",
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    outcome = SpeechDecisionOutcome.SPEAK,
                    highRiskDowngraded = false,
                    consentBlocked = false,
                    generatedCandidateCount = 2,
                    criticBlockReasons = setOf("SECRET_DISCLOSURE", "BURST_SHAPE_MISMATCH"),
                    selectedContentRef = "candidate-short",
                    createdAt = Instant.parse("2026-06-30T11:01:00Z"),
                ),
            )

            val found = rows.findFirstByCorrelationIdOrderByCreatedAtDesc("participation:chan:2")!!.toDomain()
            assertThat(found.outcome).isEqualTo(SpeechDecisionOutcome.SPEAK)
            assertThat(found.selectedContentRef).isEqualTo("candidate-short")
            assertThat(found.criticBlockReasons)
                .containsExactlyInAnyOrder("SECRET_DISCLOSURE", "BURST_SHAPE_MISMATCH")
        }
    }
