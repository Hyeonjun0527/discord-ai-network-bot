package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.humanstyle.example
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class HumanSpeechStyleBlindReviewBundleWriterTest {
    @TempDir
    lateinit var privateDirectory: Path

    @Test
    fun `owner-only blind bundle exposes only the actual provider pattern`() {
        assertThat(HumanSpeechStyleBlindReviewBundleWriter.SCHEMA)
            .isEqualTo("nia-human-speech-style-blind-review-bundle.v6")
        assertThat(HumanSpeechStyleBlindReviewBundleWriter.PROVIDER_PATTERN_POLICY)
            .isEqualTo("primary_style_cue_plus_scene_trait_or_submove_v4")
        Files.setPosixFilePermissions(
            privateDirectory,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val output = privateDirectory.resolve("blind-review.json")
        val candidateDigest = "a".repeat(64)
        val auditDigest = "b".repeat(64)
        val match =
            HumanSpeechStyleMatch(
                example(
                    "human-style-000001",
                    sourceFingerprint = "sha256:${"c".repeat(64)}",
                    responseText = "private-style-response-marker-12345",
                ).copy(
                    situation = "private-situation-marker-12345",
                    styleSignals = listOf("private-style-signal-marker-12345"),
                ),
                0.9,
            )

        HumanSpeechStyleBlindReviewBundleWriter.write(
            output,
            candidateDigest,
            auditDigest,
            listOf(
                HumanSpeechStyleBlindReviewCase(
                    responseMode = HumanSpeechResponseMode.ALIGNMENT,
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    recentTurns = listOf(ConversationTurn("member", "현재 장면 marker")),
                    speechIntent = "불평에 짧게 맞장구친다",
                    matches = listOf(match),
                ),
            ),
        )

        val serialized = Files.readString(output)
        val referenceFields =
            jacksonObjectMapper()
                .readTree(serialized)
                .path("cases")
                .first()
                .path("references")
                .first()
                .fieldNames()
                .asSequence()
                .toSet()
        val rootFields =
            jacksonObjectMapper()
                .readTree(serialized)
                .fieldNames()
                .asSequence()
                .toSet()
        assertThat(serialized).contains(HumanSpeechStyleBlindReviewBundleWriter.SCHEMA, candidateDigest, auditDigest)
        assertThat(serialized).contains("provider_style_pattern", "반응 순서")
        assertThat(rootFields)
            .contains("provider_pattern_policy")
        assertThat(serialized).contains(HumanSpeechStyleBlindReviewBundleWriter.PROVIDER_PATTERN_POLICY)
        assertThat(referenceFields).containsExactlyInAnyOrder("rank", "prompt_surface", "provider_style_pattern")
        assertThat(serialized)
            .doesNotContain(
                "private-style-response-marker-12345",
                "private-situation-marker-12345",
                "private-style-signal-marker-12345",
                "human-style-000001",
                "sha256:${"c".repeat(64)}",
            )
        assertThat(Files.getPosixFilePermissions(output))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
