package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class HumanSpeechStyleRagImportArtifactVerifierTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val verifier =
        HumanSpeechStyleRagImportArtifactVerifier(
            sourceCount = TEST_SOURCE_FINGERPRINTS.size,
            sourceFingerprintSetSha256 = sourceFingerprintSetSha256(TEST_SOURCE_FINGERPRINTS),
        )
    private val mapper = jacksonObjectMapper()

    @Test
    fun `candidate manifest와 세 PASS evidence가 같은 cards에 봉인된 artifact만 통과한다`() {
        val artifact = writeSealedArtifact()

        val cards = verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)

        assertThat(cards).isEqualTo(artifact.resolve("human-speech-style-cards.jsonl"))
    }

    @Test
    fun `정해진 원본 source set보다 적은 cards는 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        val expectedSourceFingerprints = TEST_SOURCE_FINGERPRINTS + "sha256:${"b".repeat(64)}"
        val sourceCoverageVerifier =
            HumanSpeechStyleRagImportArtifactVerifier(
                sourceCount = expectedSourceFingerprints.size,
                sourceFingerprintSetSha256 = sourceFingerprintSetSha256(expectedSourceFingerprints),
            )

        assertThatThrownBy {
            sourceCoverageVerifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import source coverage count is incomplete")
    }

    @Test
    fun `blind review evidence가 없는 JSONL 단독 artifact는 거절한다`() {
        val artifact = writeSealedArtifact()
        Files.delete(artifact.resolve("blind-quality-review.json"))

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import artifact is incomplete")
    }

    @Test
    fun `고정 enum baseline 대비 value review가 없는 artifact는 거절한다`() {
        val artifact = writeSealedArtifact()
        Files.delete(artifact.resolve("rag-value-review.json"))

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import artifact is incomplete")
    }

    @Test
    fun `봉인 뒤 cards가 바뀌면 manifest digest 불일치로 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        Files.writeString(
            artifact.resolve("human-speech-style-cards.jsonl"),
            Files.readString(artifact.resolve("human-speech-style-cards.jsonl")) + "\n",
        )

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import manifest jsonl_sha256 mismatch")
    }

    @Test
    fun `unknown card field는 digest 검증보다 먼저 fail closed로 거절한다`() {
        val artifact = writeSealedArtifact()
        val cardsFile = artifact.resolve("human-speech-style-cards.jsonl")
        val firstCard = mapper.readTree(Files.readAllLines(cardsFile).first()) as ObjectNode
        firstCard.put("raw_private_text", "synthetic forbidden field")
        val rewrittenCards =
            listOf(
                mapper.writeValueAsString(firstCard),
                *Files.readAllLines(cardsFile).drop(1).toTypedArray(),
            ).joinToString(separator = "\n", postfix = "\n")
        Files.writeString(
            cardsFile,
            rewrittenCards,
        )

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("import card is invalid at line 1")
            .hasRootCauseMessage("human speech style import card at line 1 fields are invalid")
    }

    @Test
    fun `unknown response move enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.put("response_move", "NOT_A_RESPONSE_MOVE") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response move is invalid")
    }

    @Test
    fun `response move가 response mode와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.put("response_move", "CARE_PHYSICAL") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response move does not match response mode")
    }

    @Test
    fun `unknown response form enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.put("response_form", "NOT_A_RESPONSE_FORM") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response form is invalid")
    }

    @Test
    fun `response form이 response mode와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.put("response_form", "SUPPORTIVE") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response form does not match response mode")
    }

    @Test
    fun `unknown response rhythm enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("response_rhythm").add("NOT_A_RESPONSE_RHYTHM") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response rhythm is invalid")
    }

    @Test
    fun `response rhythm이 response mode와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("response_rhythm").add("GENTLE_CARE") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response rhythm does not match response mode")
    }

    @Test
    fun `unknown scene trait enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("scene_traits").add("NOT_A_SCENE_TRAIT") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card scene traits are invalid")
    }

    @Test
    fun `scene trait가 response mode와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("scene_traits").add("CARE_PHYSICAL_CONDITION") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card scene traits do not match response mode")
    }

    @Test
    fun `scene traits는 중복되거나 두 개를 넘으면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card ->
            card
                .putArray("scene_traits")
                .add("REACTION_GOOD_NEWS")
                .add("REACTION_GOOD_NEWS")
                .add("REACTION_SURPRISE_OR_FUNNY")
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card scene traits are invalid")
    }

    @Test
    fun `unknown response move provenance enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.put("response_move_provenance", "UNVERIFIED") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response move provenance is invalid")
    }

    @Test
    fun `response move provenance가 move 유무와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putNull("response_move") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card response move provenance does not match response move")
    }

    @Test
    fun `candidate scene trait count가 cards와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "candidate-manifest.json") { manifest ->
            manifest.putObject("scene_trait_counts").put("REACTION_GOOD_NEWS", 99)
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style candidate manifest scene_trait_counts mismatch")
    }

    @Test
    fun `import response move provenance count가 cards와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "manifest.json") { manifest ->
            manifest.putObject("response_move_provenance_counts").put("FRESH_VERIFIED", 99)
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import manifest response_move_provenance_counts mismatch")
    }

    @Test
    fun `unknown provider style cue enum은 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("provider_style_cues").add("NOT_A_PROVIDER_STYLE_CUE") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card provider style cues are invalid")
    }

    @Test
    fun `provider style cue가 response mode와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("provider_style_cues").add("CARE_GENTLE_VALIDATE") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card provider style cues do not match response mode")
    }

    @Test
    fun `duplicate provider style cues는 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card ->
            card
                .putArray("provider_style_cues")
                .add("REACTION_IMMEDIATE")
                .add("REACTION_IMMEDIATE")
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card provider style cues are invalid")
    }

    @Test
    fun `STYLE_PATTERN provider style cue가 비어 있으면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card -> card.putArray("provider_style_cues") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import style pattern must have exactly one provider style cue")
    }

    @Test
    fun `provider style cues가 하나를 넘으면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateFirstCard(artifact) { card ->
            card
                .putArray("provider_style_cues")
                .add("REACTION_IMMEDIATE")
                .add("REACTION_LAUGH_ALONG")
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import card provider style cues are invalid")
    }

    @Test
    fun `candidate provider style cue count가 cards와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "candidate-manifest.json") { manifest ->
            manifest.putObject("provider_style_cue_counts").put("REACTION_IMMEDIATE", 99)
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style candidate manifest provider_style_cue_counts mismatch")
    }

    @Test
    fun `import provider style cue count가 cards와 다르면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "manifest.json") { manifest ->
            manifest.putObject("provider_style_cue_counts").put("REACTION_IMMEDIATE", 99)
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style import manifest provider_style_cue_counts mismatch")
    }

    @Test
    fun `independent holdout의 source diverse top2 건수가 반환 참조 건수보다 작으면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "retrieval-audit.json") { audit ->
            (audit["independent_holdout"] as ObjectNode).put("source_diverse_top2_case_count", 0)
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style retrieval audit independent holdout source diversity is insufficient")
    }

    @Test
    fun `v10 retrieval audit policy는 artifact를 다시 봉인해도 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "retrieval-audit.json") { audit ->
            audit.put("retrieval_policy", "response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v10")
        }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style retrieval audit retrieval policy is invalid")
    }

    @Test
    fun `retrieval audit에 policy가 없으면 import 전에 거절한다`() {
        val artifact = writeSealedArtifact()
        mutateJsonObject(artifact, "retrieval-audit.json") { audit -> audit.remove("retrieval_policy") }

        assertThatThrownBy {
            verifier.verify(artifact, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("human speech style retrieval audit fields are invalid")
    }

    private fun writeSealedArtifact(): Path {
        val artifact = temporaryDirectory.resolve("sealed-import")
        Files.createDirectory(artifact)
        val cards = CARD_METADATA.mapIndexed { index, metadata -> card(index + 1, metadata) }
        val cardsFile = artifact.resolve("human-speech-style-cards.jsonl")
        Files.writeString(cardsFile, cards.joinToString("\n") { mapper.writeValueAsString(it) } + "\n")
        val cardDigest = sha256(cardsFile)
        val byMode = RESPONSE_MODES.associateWith { 1 }
        val sceneTraitCounts =
            CARD_METADATA
                .flatMap(CardMetadata::sceneTraits)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        val providerStyleCueCounts =
            CARD_METADATA
                .flatMap(CardMetadata::providerStyleCues)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        val provenanceCounts =
            CARD_METADATA
                .groupingBy(CardMetadata::responseMoveProvenance)
                .eachCount()
                .toSortedMap()
        val responseMoveCounts =
            CARD_METADATA
                .mapNotNull(CardMetadata::responseMove)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        val responseFormCounts = CARD_METADATA.groupingBy(CardMetadata::responseForm).eachCount().toSortedMap()
        val responseRhythmCueCounts =
            CARD_METADATA
                .flatMap(CardMetadata::responseRhythm)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        val freshVerifiedResponseMoveCount = CARD_METADATA.count { it.responseMoveProvenance == "FRESH_VERIFIED" }
        val heuristicallyObservedResponseMoveCount = CARD_METADATA.count { it.responseMoveProvenance == "HEURISTIC_OBSERVED" }
        val rejectedResponseMoveReviewCount = CARD_METADATA.count { it.responseMoveProvenance == "FRESH_REJECTED" }
        val candidateManifest =
            linkedMapOf<String, Any>(
                "schema" to "nia-human-speech-style-runtime-candidate-manifest.v8",
                "input_jsonl_sha256" to cardDigest,
                "record_count" to cards.size,
                "jsonl_sha256" to cardDigest,
                "prompt_eligible_count" to cards.size,
                "prompt_disabled_count" to 0,
                "prompt_eligible_by_response_mode" to byMode,
                "prompt_ineligible_reason_counts" to emptyMap<String, Int>(),
                "response_move_metadata_counts" to responseMoveCounts,
                "prompt_eligible_response_move_counts" to responseMoveCounts,
                "prompt_eligible_response_move_source_counts" to responseMoveCounts.mapValues { 1 },
                "response_form_metadata_counts" to responseFormCounts,
                "response_rhythm_cue_counts" to responseRhythmCueCounts,
                "response_rhythm_coverage" to cards.size,
                "response_rhythm_behavior_coverage" to cards.size,
                "response_rhythm_delivery_only_count" to 0,
                "response_move_policy" to "observed_response_metadata_with_fresh_review_overlay_v1",
                "response_move_review_ledger_sha256" to "a".repeat(64),
                "fresh_verified_response_move_count" to freshVerifiedResponseMoveCount,
                "heuristically_observed_response_move_count" to heuristicallyObservedResponseMoveCount,
                "rejected_response_move_review_count" to rejectedResponseMoveReviewCount,
                "scene_trait_counts" to sceneTraitCounts,
                "provider_style_cue_counts" to providerStyleCueCounts,
                "response_move_provenance_counts" to provenanceCounts,
                "retrieval_policy" to "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                "prompt_surface_policy" to "closed_style_pattern_v1",
                "source_fingerprint_count" to TEST_SOURCE_FINGERPRINTS.size,
                "expected_source_count" to TEST_SOURCE_FINGERPRINTS.size,
                "expected_source_fingerprint_set_sha256" to sourceFingerprintSetSha256(TEST_SOURCE_FINGERPRINTS),
                "source_coverage_complete" to true,
                "quality_counts" to mapOf("USER_RELEASED_REVIEW" to cards.size),
                "purpose" to "synthetic sealed artifact test",
            )
        val candidateManifestFile = artifact.resolve("candidate-manifest.json")
        writeJson(candidateManifestFile, candidateManifest)
        val retrievalAudit =
            linkedMapOf<String, Any>(
                "schema" to "nia-human-speech-style-retrieval-audit.v4",
                "candidate_jsonl_sha256" to cardDigest,
                "retrieval_policy" to "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                "embedding_provider" to "openai",
                "embedding_model" to "text-embedding-3-small",
                "execution_scope" to "ephemeral_h2_only_no_judge_no_discord_no_provider_generation",
                "fixed_probe" to auditSuite(),
                "independent_holdout" to (auditSuite() + ("source_diverse_top2_case_count" to 35)),
                "verdict" to "PASS",
                "reason_codes" to emptyList<String>(),
            )
        val retrievalAuditFile = artifact.resolve("retrieval-audit.json")
        writeJson(retrievalAuditFile, retrievalAudit)
        val blindQualityReview =
            linkedMapOf<String, Any>(
                "schema" to "nia-human-speech-style-blind-quality-review.v2",
                "artifact_jsonl_sha256" to cardDigest,
                "verdict" to "PASS",
                "case_count" to 70,
                "case_count_by_response_mode" to RESPONSE_MODES.associateWith { 10 },
                "top1_useful_count" to 48,
                "top2_any_useful_count" to 56,
                "top1_useful_by_response_mode" to
                    mapOf(
                        "REACTION" to 7,
                        "ALIGNMENT" to 7,
                        "PLAY" to 7,
                        "FOLLOW_UP" to 7,
                        "SPECULATION" to 7,
                        "CARE" to 7,
                        "COORDINATION" to 6,
                    ),
                "top2_any_useful_by_response_mode" to RESPONSE_MODES.associateWith { 8 },
                "unsafe_provider_surface_count" to 0,
                "private_context_dependency_count" to 0,
                "copy_risk_count" to 0,
                "retrieval_audit_sha256" to sha256(retrievalAuditFile),
            )
        val blindQualityReviewFile = artifact.resolve("blind-quality-review.json")
        writeJson(blindQualityReviewFile, blindQualityReview)
        val ragValueReview =
            linkedMapOf<String, Any>(
                "schema" to "nia-human-speech-style-rag-value-review.v1",
                "artifact_jsonl_sha256" to cardDigest,
                "retrieval_audit_sha256" to sha256(retrievalAuditFile),
                "review_protocol" to "fixed_mode_baseline_v1",
                "reviewer_type" to "FRESH_COMPARATIVE_VERIFIER",
                "verdict" to "PASS",
                "case_count" to 70,
                "case_count_by_response_mode" to RESPONSE_MODES.associateWith { 10 },
                "top1_value_add_count" to 49,
                "top2_any_value_add_count" to 56,
                "top1_value_add_by_response_mode" to RESPONSE_MODES.associateWith { 7 },
                "top2_any_value_add_by_response_mode" to RESPONSE_MODES.associateWith { 8 },
                "worse_than_mode_baseline_count" to 0,
                "unsupported_specificity_count" to 0,
                "unsafe_provider_surface_count" to 0,
            )
        val ragValueReviewFile = artifact.resolve("rag-value-review.json")
        writeJson(ragValueReviewFile, ragValueReview)
        val importManifest =
            linkedMapOf<String, Any>(
                "schema" to "nia-human-speech-style-import-manifest.v10",
                "quality" to "USER_RELEASED_REVIEW",
                "consent_revision" to "synthetic-test",
                "record_count" to cards.size,
                "accepted_card_count" to cards.size,
                "prompt_eligible_count" to cards.size,
                "prompt_disabled_count" to 0,
                "prompt_eligible_by_response_mode" to byMode,
                "prompt_surface_counts" to mapOf("STYLE_PATTERN" to cards.size),
                "source_count" to TEST_SOURCE_FINGERPRINTS.size,
                "source_fingerprint_count" to TEST_SOURCE_FINGERPRINTS.size,
                "expected_source_count" to TEST_SOURCE_FINGERPRINTS.size,
                "expected_source_fingerprint_set_sha256" to sourceFingerprintSetSha256(TEST_SOURCE_FINGERPRINTS),
                "source_coverage_complete" to true,
                "response_rhythm_coverage" to cards.size,
                "jsonl_sha256" to cardDigest,
                "input_candidate_manifest_sha256" to sha256(candidateManifestFile),
                "input_candidate_jsonl_sha256" to cardDigest,
                "all_cards_formally_approved" to false,
                "all_cards_user_released" to true,
                "retrieval_policy" to "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11",
                "response_move_policy" to "observed_response_metadata_with_fresh_review_overlay_v1",
                "response_move_review_ledger_sha256" to "a".repeat(64),
                "fresh_verified_response_move_count" to freshVerifiedResponseMoveCount,
                "heuristically_observed_response_move_count" to heuristicallyObservedResponseMoveCount,
                "rejected_response_move_review_count" to rejectedResponseMoveReviewCount,
                "scene_trait_counts" to sceneTraitCounts,
                "provider_style_cue_counts" to providerStyleCueCounts,
                "response_move_provenance_counts" to provenanceCounts,
                "prompt_surface_policy" to "closed_style_pattern_v1",
                "retrieval_audit" to retrievalAudit,
                "retrieval_audit_sha256" to sha256(retrievalAuditFile),
                "blind_quality_review" to blindQualityReview,
                "rag_value_review" to ragValueReview,
                "rag_value_review_sha256" to sha256(ragValueReviewFile),
                "purpose" to "synthetic sealed artifact test",
            )
        writeJson(artifact.resolve("manifest.json"), importManifest)
        return artifact
    }

    private fun card(
        index: Int,
        metadata: CardMetadata,
    ): Map<String, Any?> =
        linkedMapOf(
            "schema" to "nia-human-speech-style-import-card.v4",
            "example_id" to "human-style-${index.toString().padStart(6, '0')}",
            "response_mode" to metadata.responseMode,
            "situation" to "synthetic situation",
            "style_signals" to listOf("synthetic"),
            "context_bubbles" to listOf(mapOf("speaker" to "synthetic-a", "text" to "synthetic context")),
            "response_bubbles" to listOf(mapOf("speaker" to "synthetic-b", "text" to "synthetic response")),
            "quality" to "USER_RELEASED_REVIEW",
            "source_fingerprint" to TEST_SOURCE_FINGERPRINTS.single(),
            "consent_revision" to "synthetic-test",
            "combined_chars" to 40,
            "prompt_eligible" to true,
            "prompt_surface" to "STYLE_PATTERN",
            "response_surface_has_card_local_alias" to false,
            "response_move" to metadata.responseMove,
            "scene_traits" to metadata.sceneTraits,
            "provider_style_cues" to metadata.providerStyleCues,
            "response_move_provenance" to metadata.responseMoveProvenance,
            "response_form" to metadata.responseForm,
            "response_rhythm" to metadata.responseRhythm,
            "embedding_model" to "text-embedding-3-small",
        )

    private fun mutateFirstCard(
        artifact: Path,
        mutation: (ObjectNode) -> Unit,
    ) {
        val cardsFile = artifact.resolve("human-speech-style-cards.jsonl")
        val cards =
            Files
                .readAllLines(cardsFile)
                .filter(String::isNotBlank)
                .map { mapper.readTree(it) as ObjectNode }
                .toMutableList()
        mutation(cards.first())
        Files.writeString(
            cardsFile,
            cards.joinToString(separator = "\n", postfix = "\n") { mapper.writeValueAsString(it) },
        )
        resealArtifact(artifact)
    }

    private fun mutateJsonObject(
        artifact: Path,
        fileName: String,
        mutation: (ObjectNode) -> Unit,
    ) {
        val path = artifact.resolve(fileName)
        val objectNode = readJsonObject(path)
        mutation(objectNode)
        writeJson(path, objectNode)
        resealArtifact(artifact)
    }

    private fun resealArtifact(artifact: Path) {
        val cardsFile = artifact.resolve("human-speech-style-cards.jsonl")
        val cardDigest = sha256(cardsFile)
        val candidateManifestFile = artifact.resolve("candidate-manifest.json")
        val candidateManifest = readJsonObject(candidateManifestFile)
        candidateManifest.put("input_jsonl_sha256", cardDigest)
        candidateManifest.put("jsonl_sha256", cardDigest)
        writeJson(candidateManifestFile, candidateManifest)

        val retrievalAuditFile = artifact.resolve("retrieval-audit.json")
        val retrievalAudit = readJsonObject(retrievalAuditFile)
        retrievalAudit.put("candidate_jsonl_sha256", cardDigest)
        writeJson(retrievalAuditFile, retrievalAudit)
        val retrievalAuditDigest = sha256(retrievalAuditFile)

        val blindQualityReviewFile = artifact.resolve("blind-quality-review.json")
        val blindQualityReview = readJsonObject(blindQualityReviewFile)
        blindQualityReview.put("artifact_jsonl_sha256", cardDigest)
        blindQualityReview.put("retrieval_audit_sha256", retrievalAuditDigest)
        writeJson(blindQualityReviewFile, blindQualityReview)

        val ragValueReviewFile = artifact.resolve("rag-value-review.json")
        val ragValueReview = readJsonObject(ragValueReviewFile)
        ragValueReview.put("artifact_jsonl_sha256", cardDigest)
        ragValueReview.put("retrieval_audit_sha256", retrievalAuditDigest)
        writeJson(ragValueReviewFile, ragValueReview)

        val importManifestFile = artifact.resolve("manifest.json")
        val importManifest = readJsonObject(importManifestFile)
        importManifest.put("jsonl_sha256", cardDigest)
        importManifest.put("input_candidate_jsonl_sha256", cardDigest)
        importManifest.put("input_candidate_manifest_sha256", sha256(candidateManifestFile))
        importManifest.replace("retrieval_audit", retrievalAudit)
        importManifest.put("retrieval_audit_sha256", retrievalAuditDigest)
        importManifest.replace("blind_quality_review", blindQualityReview)
        importManifest.replace("rag_value_review", ragValueReview)
        importManifest.put("rag_value_review_sha256", sha256(ragValueReviewFile))
        writeJson(importManifestFile, importManifest)
    }

    private fun readJsonObject(path: Path): ObjectNode = mapper.readTree(Files.readString(path)) as ObjectNode

    private fun auditSuite(): Map<String, Int> =
        linkedMapOf(
            "case_count" to 35,
            "exact_mode_return_case_count" to 35,
            "policy_abstention_count" to 0,
            "returned_reference_card_count" to 70,
            "returned_reference_case_count" to 35,
            "unexpected_post_query_empty_count" to 0,
        )

    private fun writeJson(
        path: Path,
        value: Any,
    ) {
        Files.writeString(path, mapper.writeValueAsString(value))
    }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun sourceFingerprintSetSha256(sourceFingerprints: Set<String>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(sourceFingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class CardMetadata(
        val responseMode: String,
        val responseMove: String?,
        val sceneTraits: List<String>,
        val providerStyleCues: List<String>,
        val responseMoveProvenance: String,
        val responseForm: String,
        val responseRhythm: List<String>,
    )

    private companion object {
        val RESPONSE_MODES = HumanSpeechResponseMode.entries.map(HumanSpeechResponseMode::name)
        val TEST_SOURCE_FINGERPRINTS = setOf("sha256:${"a".repeat(64)}")
        val CARD_METADATA =
            listOf(
                CardMetadata(
                    responseMode = "REACTION",
                    responseMove = "REACTION_SURPRISE",
                    sceneTraits = listOf("REACTION_SURPRISE_OR_FUNNY"),
                    providerStyleCues = listOf("REACTION_IMMEDIATE"),
                    responseMoveProvenance = "FRESH_VERIFIED",
                    responseForm = "EXPRESSIVE",
                    responseRhythm = listOf("SHORT_REACTION", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "ALIGNMENT",
                    responseMove = "ALIGNMENT_COMPLAINT",
                    sceneTraits = listOf("ALIGNMENT_COMPLAINT_OR_LOW_ENERGY"),
                    providerStyleCues = listOf("ALIGNMENT_LOW_KEY_ACK"),
                    responseMoveProvenance = "HEURISTIC_OBSERVED",
                    responseForm = "ALIGN_AND_ADD",
                    responseRhythm = listOf("AGREE_AND_ADD", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "PLAY",
                    responseMove = null,
                    sceneTraits = listOf("PLAY_BANTER"),
                    providerStyleCues = listOf("PLAY_COUNTERTEASE"),
                    responseMoveProvenance = "NONE",
                    responseForm = "PLAYFUL_RETURN",
                    responseRhythm = listOf("PLAYFUL_RETURN", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "FOLLOW_UP",
                    responseMove = null,
                    sceneTraits = listOf("FOLLOW_UP_STATUS_OR_PROGRESS"),
                    providerStyleCues = listOf("FOLLOW_UP_DIRECT_CHECK"),
                    responseMoveProvenance = "FRESH_REJECTED",
                    responseForm = "QUESTION",
                    responseRhythm = listOf("DIRECT_QUESTION", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "SPECULATION",
                    responseMove = "SPECULATION_FUTURE",
                    sceneTraits = listOf("SPECULATION_FUTURE"),
                    providerStyleCues = listOf("SPECULATION_LIGHT_HEDGE"),
                    responseMoveProvenance = "HEURISTIC_OBSERVED",
                    responseForm = "HEDGED_GUESS",
                    responseRhythm = listOf("HEDGED_GUESS", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "CARE",
                    responseMove = "CARE_PHYSICAL",
                    sceneTraits = listOf("CARE_PHYSICAL_CONDITION"),
                    providerStyleCues = listOf("CARE_GENTLE_VALIDATE"),
                    responseMoveProvenance = "HEURISTIC_OBSERVED",
                    responseForm = "SUPPORTIVE",
                    responseRhythm = listOf("GENTLE_CARE", "SINGLE_BUBBLE"),
                ),
                CardMetadata(
                    responseMode = "COORDINATION",
                    responseMove = "COORDINATION_ACTION",
                    sceneTraits = listOf("COORDINATION_ACTION_PROPOSAL"),
                    providerStyleCues = listOf("COORDINATION_PROPOSE"),
                    responseMoveProvenance = "HEURISTIC_OBSERVED",
                    responseForm = "PROPOSAL",
                    responseRhythm = listOf("ACTION_PROPOSAL", "SINGLE_BUBBLE"),
                ),
            )
    }
}
