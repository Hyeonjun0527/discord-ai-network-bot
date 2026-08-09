package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseForm
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMoveProvenance
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal data class HumanSpeechStyleSourceCoveragePolicy(
    val sourceCount: Int,
    val sourceFingerprintSetSha256: String,
)

/**
 * One-shot import가 받을 private artifact directory의 완결성과 품질 증거를 확인한다.
 *
 * JSONL 하나만 지정해 startup import를 우회할 수 없게, candidate manifest·실제 OpenAI retrieval audit·blind
 * quality review·고정 enum baseline 대비 value review를 같은 디렉터리의 고정 파일명으로 함께 요구한다. 이 경계는
 * 카드 문구를 log/exception에 싣지 않는다.
 */
@Component
class HumanSpeechStyleRagImportArtifactVerifier {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val sourceCoveragePolicy: HumanSpeechStyleSourceCoveragePolicy

    constructor() {
        sourceCoveragePolicy = readSourceCoveragePolicy()
    }

    internal constructor(
        sourceCount: Int,
        sourceFingerprintSetSha256: String,
    ) {
        sourceCoveragePolicy =
            HumanSpeechStyleSourceCoveragePolicy(
                sourceCount = sourceCount,
                sourceFingerprintSetSha256 = sourceFingerprintSetSha256,
            )
    }

    fun verify(
        artifactDirectory: Path,
        allowedQuality: HumanSpeechStyleQuality,
    ): Path {
        require(Files.isDirectory(artifactDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "human speech style import artifact directory does not exist"
        }
        val cardsFile = artifactDirectory.resolve(CARDS_FILE_NAME)
        val importManifestFile = artifactDirectory.resolve(IMPORT_MANIFEST_FILE_NAME)
        val candidateManifestFile = artifactDirectory.resolve(CANDIDATE_MANIFEST_FILE_NAME)
        val retrievalAuditFile = artifactDirectory.resolve(RETRIEVAL_AUDIT_FILE_NAME)
        val blindQualityReviewFile = artifactDirectory.resolve(BLIND_QUALITY_REVIEW_FILE_NAME)
        val ragValueReviewFile = artifactDirectory.resolve(RAG_VALUE_REVIEW_FILE_NAME)
        listOf(cardsFile, importManifestFile, candidateManifestFile, retrievalAuditFile, blindQualityReviewFile, ragValueReviewFile)
            .forEach(::requireRegularFile)

        val importManifest = readObject(importManifestFile, "import manifest")
        val candidateManifest = readObject(candidateManifestFile, "candidate manifest")
        val retrievalAudit = readObject(retrievalAuditFile, "retrieval audit")
        val blindQualityReview = readObject(blindQualityReviewFile, "blind quality review")
        val ragValueReview = readObject(ragValueReviewFile, "RAG value review")
        val cards = readCards(cardsFile)
        validate(
            importManifest = importManifest,
            candidateManifest = candidateManifest,
            retrievalAudit = retrievalAudit,
            blindQualityReview = blindQualityReview,
            ragValueReview = ragValueReview,
            cards = cards,
            cardsFile = cardsFile,
            candidateManifestFile = candidateManifestFile,
            retrievalAuditFile = retrievalAuditFile,
            ragValueReviewFile = ragValueReviewFile,
            allowedQuality = allowedQuality,
        )
        return cardsFile
    }

    private fun validate(
        importManifest: JsonNode,
        candidateManifest: JsonNode,
        retrievalAudit: JsonNode,
        blindQualityReview: JsonNode,
        ragValueReview: JsonNode,
        cards: List<JsonNode>,
        cardsFile: Path,
        candidateManifestFile: Path,
        retrievalAuditFile: Path,
        ragValueReviewFile: Path,
        allowedQuality: HumanSpeechStyleQuality,
    ) {
        requireExactFields(importManifest, IMPORT_MANIFEST_FIELDS, "import manifest")
        requireText(importManifest, "schema", "import manifest")
            .also { require(it == IMPORT_MANIFEST_SCHEMA) { "human speech style import manifest schema is unsupported" } }
        requireText(importManifest, "quality", "import manifest")
            .also {
                require(
                    it == allowedQuality.name,
                ) { "human speech style import manifest quality does not match the explicit import quality" }
            }
        val cardDigest = sha256File(cardsFile)
        requireDigest(importManifest, "jsonl_sha256", cardDigest, "import manifest")
        requireDigest(importManifest, "input_candidate_jsonl_sha256", cardDigest, "import manifest")
        requireDigest(importManifest, "input_candidate_manifest_sha256", sha256File(candidateManifestFile), "import manifest")
        requireExactFields(candidateManifest, CANDIDATE_MANIFEST_FIELDS, "candidate manifest")
        requireText(candidateManifest, "schema", "candidate manifest")
            .also { require(it == CANDIDATE_MANIFEST_SCHEMA) { "human speech style candidate manifest schema is unsupported" } }
        requireDigest(candidateManifest, "jsonl_sha256", cardDigest, "candidate manifest")
        requireText(candidateManifest, "retrieval_policy", "candidate manifest")
            .also { require(it == RETRIEVAL_POLICY) { "human speech style candidate retrieval policy is invalid" } }
        requireText(candidateManifest, "response_move_policy", "candidate manifest")
            .also { require(it == RESPONSE_MOVE_POLICY) { "human speech style candidate response-move policy is invalid" } }
        requireText(candidateManifest, "prompt_surface_policy", "candidate manifest")
            .also { require(it == PROMPT_SURFACE_POLICY) { "human speech style candidate prompt surface policy is invalid" } }

        validateRetrievalAudit(retrievalAudit, cardDigest)
        val retrievalAuditDigest = sha256File(retrievalAuditFile)
        requireDigest(importManifest, "retrieval_audit_sha256", retrievalAuditDigest, "import manifest")
        require(importManifest["retrieval_audit"] == retrievalAudit) {
            "human speech style import manifest retrieval audit does not match its sealed file"
        }
        validateBlindQualityReview(blindQualityReview, cardDigest, retrievalAuditDigest)
        require(importManifest["blind_quality_review"] == blindQualityReview) {
            "human speech style import manifest blind quality review does not match its sealed file"
        }
        validateRagValueReview(ragValueReview, cardDigest, retrievalAuditDigest)
        requireDigest(importManifest, "rag_value_review_sha256", sha256File(ragValueReviewFile), "import manifest")
        require(importManifest["rag_value_review"] == ragValueReview) {
            "human speech style import manifest RAG value review does not match its sealed file"
        }

        validateCardSummary(
            cards = cards,
            importManifest = importManifest,
            candidateManifest = candidateManifest,
            allowedQuality = allowedQuality,
            sourceCoveragePolicy = sourceCoveragePolicy,
        )
    }

    private fun validateCardSummary(
        cards: List<JsonNode>,
        importManifest: JsonNode,
        candidateManifest: JsonNode,
        allowedQuality: HumanSpeechStyleQuality,
        sourceCoveragePolicy: HumanSpeechStyleSourceCoveragePolicy,
    ) {
        require(cards.isNotEmpty() && cards.size <= MAX_IMPORT_CARDS) { "human speech style import card count is invalid" }
        val ids = cards.map { requireText(it, "example_id", "import card") }
        require(ids.toSet().size == ids.size) { "human speech style import has duplicate example ids" }
        val qualities = cards.map { requireText(it, "quality", "import card") }.toSet()
        require(
            qualities == setOf(allowedQuality.name),
        ) { "human speech style import card quality does not match the explicit import quality" }
        val consentRevisions = cards.map { requireText(it, "consent_revision", "import card") }.toSet()
        require(
            consentRevisions.size == 1 && consentRevisions.single() == requireText(importManifest, "consent_revision", "import manifest"),
        ) {
            "human speech style import consent revision is inconsistent"
        }
        val cardMetadata = cards.map(::validateCard)
        val enabled = cardMetadata.filter { it.promptEligible }
        val promptSurfaces = cardMetadata.groupingBy { it.promptSurface }.eachCount()
        val enabledModeCounts = enabled.groupingBy { it.responseMode.name }.eachCount().toSortedMap()
        require(enabledModeCounts.keys == RESPONSE_MODES) { "human speech style import searchable response-mode coverage is incomplete" }
        val sourceFingerprints = cards.map { requireText(it, "source_fingerprint", "import card") }.toSet()
        val sourceCount = sourceFingerprints.size
        require(sourceCount == sourceCoveragePolicy.sourceCount) {
            "human speech style import source coverage count is incomplete"
        }
        require(sourceFingerprintSetSha256(sourceFingerprints) == sourceCoveragePolicy.sourceFingerprintSetSha256) {
            "human speech style import source coverage set is incomplete"
        }
        val rhythmCoverage = cardMetadata.count { it.responseRhythm.isNotEmpty() }
        val observedResponseMoveCount = cardMetadata.count { it.responseMove != null }
        val sceneTraitCounts =
            cardMetadata
                .flatMap { it.sceneTraits }
                .groupingBy { it.name }
                .eachCount()
                .toSortedMap()
        val providerStyleCueCounts =
            cardMetadata
                .flatMap { it.providerStyleCues }
                .groupingBy { it.name }
                .eachCount()
                .toSortedMap()
        val responseMoveProvenanceCounts =
            cardMetadata
                .groupingBy { it.responseMoveProvenance.name }
                .eachCount()
                .toSortedMap()
        val freshVerifiedResponseMoveCount =
            responseMoveProvenanceCounts[HumanSpeechStyleResponseMoveProvenance.FRESH_VERIFIED.name] ?: 0
        val heuristicallyObservedResponseMoveCount =
            responseMoveProvenanceCounts[HumanSpeechStyleResponseMoveProvenance.HEURISTIC_OBSERVED.name] ?: 0
        val rejectedResponseMoveReviewCount =
            responseMoveProvenanceCounts[HumanSpeechStyleResponseMoveProvenance.FRESH_REJECTED.name] ?: 0
        require(freshVerifiedResponseMoveCount > 0) {
            "human speech style candidate manifest has no fresh verified response-move evidence"
        }
        require(freshVerifiedResponseMoveCount + heuristicallyObservedResponseMoveCount == observedResponseMoveCount) {
            "human speech style candidate manifest response-move provenance count mismatch"
        }

        requireInt(importManifest, "record_count", "import manifest", cards.size)
        requireInt(importManifest, "accepted_card_count", "import manifest", cards.size)
        requireInt(importManifest, "prompt_eligible_count", "import manifest", enabled.size)
        requireInt(importManifest, "prompt_disabled_count", "import manifest", cards.size - enabled.size)
        requireInt(importManifest, "source_count", "import manifest", sourceCount)
        requireInt(importManifest, "source_fingerprint_count", "import manifest", sourceCount)
        requireInt(importManifest, "expected_source_count", "import manifest", sourceCoveragePolicy.sourceCount)
        requireDigest(
            importManifest,
            "expected_source_fingerprint_set_sha256",
            sourceCoveragePolicy.sourceFingerprintSetSha256,
            "import manifest",
        )
        requireBoolean(importManifest, "source_coverage_complete", "import manifest", expected = true)
        requireInt(importManifest, "response_rhythm_coverage", "import manifest", rhythmCoverage)
        requireCountMap(importManifest, "prompt_eligible_by_response_mode", enabledModeCounts, "import manifest")
        requireCountMap(importManifest, "prompt_surface_counts", promptSurfaces.toSortedMap(), "import manifest")
        requireCountMap(importManifest, "scene_trait_counts", sceneTraitCounts, "import manifest")
        requireCountMap(importManifest, "provider_style_cue_counts", providerStyleCueCounts, "import manifest")
        requireCountMap(importManifest, "response_move_provenance_counts", responseMoveProvenanceCounts, "import manifest")
        requireText(importManifest, "retrieval_policy", "import manifest")
            .also { require(it == RETRIEVAL_POLICY) { "human speech style import retrieval policy is invalid" } }
        requireText(importManifest, "response_move_policy", "import manifest")
            .also { require(it == RESPONSE_MOVE_POLICY) { "human speech style import response-move policy is invalid" } }
        requireInt(importManifest, "fresh_verified_response_move_count", "import manifest", freshVerifiedResponseMoveCount)
        requireInt(importManifest, "heuristically_observed_response_move_count", "import manifest", heuristicallyObservedResponseMoveCount)
        requireInt(importManifest, "rejected_response_move_review_count", "import manifest", rejectedResponseMoveReviewCount)
        requireText(importManifest, "prompt_surface_policy", "import manifest")
            .also { require(it == PROMPT_SURFACE_POLICY) { "human speech style import prompt surface policy is invalid" } }
        when (allowedQuality) {
            HumanSpeechStyleQuality.CURATION_APPROVED -> {
                requireBoolean(importManifest, "all_cards_formally_approved", "import manifest", expected = true)
                requireBoolean(importManifest, "all_cards_user_released", "import manifest", expected = false)
            }
            HumanSpeechStyleQuality.USER_RELEASED_REVIEW -> {
                requireBoolean(importManifest, "all_cards_formally_approved", "import manifest", expected = false)
                requireBoolean(importManifest, "all_cards_user_released", "import manifest", expected = true)
            }
        }

        requireInt(candidateManifest, "record_count", "candidate manifest", cards.size)
        requireInt(candidateManifest, "prompt_eligible_count", "candidate manifest", enabled.size)
        requireInt(candidateManifest, "prompt_disabled_count", "candidate manifest", cards.size - enabled.size)
        requireInt(candidateManifest, "source_fingerprint_count", "candidate manifest", sourceCount)
        requireInt(candidateManifest, "expected_source_count", "candidate manifest", sourceCoveragePolicy.sourceCount)
        requireDigest(
            candidateManifest,
            "expected_source_fingerprint_set_sha256",
            sourceCoveragePolicy.sourceFingerprintSetSha256,
            "candidate manifest",
        )
        requireBoolean(candidateManifest, "source_coverage_complete", "candidate manifest", expected = true)
        requireCountMap(candidateManifest, "prompt_eligible_by_response_mode", enabledModeCounts, "candidate manifest")
        requireCountMap(candidateManifest, "quality_counts", mapOf(allowedQuality.name to cards.size), "candidate manifest")
        requireCountMap(candidateManifest, "scene_trait_counts", sceneTraitCounts, "candidate manifest")
        requireCountMap(candidateManifest, "provider_style_cue_counts", providerStyleCueCounts, "candidate manifest")
        requireCountMap(candidateManifest, "response_move_provenance_counts", responseMoveProvenanceCounts, "candidate manifest")
        requireInt(candidateManifest, "fresh_verified_response_move_count", "candidate manifest", freshVerifiedResponseMoveCount)
        requireInt(
            candidateManifest,
            "heuristically_observed_response_move_count",
            "candidate manifest",
            heuristicallyObservedResponseMoveCount,
        )
        requireInt(candidateManifest, "rejected_response_move_review_count", "candidate manifest", rejectedResponseMoveReviewCount)
    }

    private fun validateCard(card: JsonNode): ValidatedCard {
        val label = "import card"
        val responseMode = requireResponseMode(card, label)
        val promptSurface = requireText(card, "prompt_surface", label)
        require(promptSurface in PROMPT_SURFACES) { "human speech style import prompt surface is invalid" }
        val promptEligible = requireBoolean(card, "prompt_eligible", label)
        require(promptEligible == (promptSurface == STYLE_PATTERN)) {
            "human speech style import prompt surface and eligibility disagree"
        }
        requireBoolean(card, "response_surface_has_card_local_alias", label)
        requireText(card, "schema", label)
            .also { require(it == IMPORT_CARD_SCHEMA) { "human speech style import card schema is invalid" } }

        val responseMove = requireResponseMove(card, responseMode, label)
        val sceneTraits = requireSceneTraits(card, responseMode, label)
        val providerStyleCues = requireProviderStyleCues(card, responseMode, promptSurface, label)
        val responseMoveProvenance = requireResponseMoveProvenance(card, label)
        require(responseMoveProvenance.matches(responseMove)) {
            "human speech style import card response move provenance does not match response move"
        }
        val responseForm = requireResponseForm(card, responseMode, label)
        val responseRhythm = requireRhythmCues(card, responseMode, label)
        if (promptEligible) {
            require(responseForm != null) { "human speech style import style pattern has no response form" }
            require(responseRhythm.any(HumanSpeechStyleRhythmCue::isObservedResponseBehavior)) {
                "human speech style import style pattern has no observed response rhythm"
            }
        }
        return ValidatedCard(
            responseMode = responseMode,
            responseMove = responseMove,
            sceneTraits = sceneTraits,
            providerStyleCues = providerStyleCues,
            responseMoveProvenance = responseMoveProvenance,
            promptEligible = promptEligible,
            promptSurface = promptSurface,
            responseRhythm = responseRhythm,
        )
    }

    private fun validateRetrievalAudit(
        retrievalAudit: JsonNode,
        cardDigest: String,
    ) {
        requireExactFields(retrievalAudit, RETRIEVAL_AUDIT_FIELDS, "retrieval audit")
        requireText(retrievalAudit, "schema", "retrieval audit")
            .also { require(it == RETRIEVAL_AUDIT_SCHEMA) { "human speech style retrieval audit schema is invalid" } }
        requireDigest(retrievalAudit, "candidate_jsonl_sha256", cardDigest, "retrieval audit")
        requireText(retrievalAudit, "retrieval_policy", "retrieval audit")
            .also { require(it == RETRIEVAL_POLICY) { "human speech style retrieval audit retrieval policy is invalid" } }
        requireText(retrievalAudit, "embedding_provider", "retrieval audit")
            .also { require(it == "openai") { "human speech style retrieval audit embedding provider is invalid" } }
        requireText(retrievalAudit, "embedding_model", "retrieval audit")
            .also { require(it == EMBEDDING_MODEL) { "human speech style retrieval audit embedding model is invalid" } }
        requireText(retrievalAudit, "execution_scope", "retrieval audit")
            .also { require(it == RETRIEVAL_AUDIT_EXECUTION_SCOPE) { "human speech style retrieval audit execution scope is invalid" } }
        requireText(retrievalAudit, "verdict", "retrieval audit")
            .also { require(it == "PASS") { "human speech style retrieval audit does not pass quality gates" } }
        require(retrievalAudit["reason_codes"].isArray && retrievalAudit["reason_codes"].isEmpty) {
            "human speech style retrieval audit has unresolved findings"
        }
        validateAuditSuite(retrievalAudit["fixed_probe"], FIXED_PROBE_FIELDS, "fixed probe")
        val holdout = retrievalAudit["independent_holdout"]
        validateAuditSuite(holdout, INDEPENDENT_HOLDOUT_FIELDS, "independent holdout")
        val returnedReferenceCaseCount = holdout["returned_reference_case_count"].asInt()
        val holdoutCaseCount = holdout["case_count"].asInt()
        require(
            returnedReferenceCaseCount * 100 >= holdoutCaseCount * MIN_HOLDOUT_REFERENCE_COVERAGE_PERCENT,
        ) {
            "human speech style retrieval audit independent holdout coverage is insufficient"
        }
        require(holdout["source_diverse_top2_case_count"].asInt() == returnedReferenceCaseCount) {
            "human speech style retrieval audit independent holdout source diversity is insufficient"
        }
    }

    private fun validateAuditSuite(
        suite: JsonNode?,
        fields: Set<String>,
        label: String,
    ) {
        requireExactFields(suite, fields, "retrieval audit $label")
        val values = fields.associateWith { requireNonnegativeInt(requireNotNull(suite), it, "retrieval audit $label") }
        require(
            values.getValue("case_count") >= MIN_RETRIEVAL_CASES,
        ) { "human speech style retrieval audit $label coverage is insufficient" }
        require(values.getValue("returned_reference_case_count") <= values.getValue("case_count")) {
            "human speech style retrieval audit $label reference count is invalid"
        }
        require(values.getValue("exact_mode_return_case_count") == values.getValue("returned_reference_case_count")) {
            "human speech style retrieval audit $label response mode preservation is invalid"
        }
        require(values.getValue("policy_abstention_count") <= values.getValue("case_count")) {
            "human speech style retrieval audit $label abstention count is invalid"
        }
        require(values.getValue("unexpected_post_query_empty_count") == 0) {
            "human speech style retrieval audit $label has post-query empty results"
        }
    }

    private fun validateBlindQualityReview(
        blindQualityReview: JsonNode,
        cardDigest: String,
        retrievalAuditDigest: String,
    ) {
        requireExactFields(blindQualityReview, BLIND_QUALITY_REVIEW_FIELDS, "blind quality review")
        requireText(blindQualityReview, "schema", "blind quality review")
            .also { require(it == BLIND_QUALITY_REVIEW_SCHEMA) { "human speech style blind quality review schema is invalid" } }
        requireDigest(blindQualityReview, "artifact_jsonl_sha256", cardDigest, "blind quality review")
        requireDigest(blindQualityReview, "retrieval_audit_sha256", retrievalAuditDigest, "blind quality review")
        requireText(blindQualityReview, "verdict", "blind quality review")
            .also { require(it == "PASS") { "human speech style blind quality review does not pass quality gates" } }
        val cases = requireModeCountMap(blindQualityReview, "case_count_by_response_mode", "blind quality review")
        val top1 = requireModeCountMap(blindQualityReview, "top1_useful_by_response_mode", "blind quality review")
        val top2 = requireModeCountMap(blindQualityReview, "top2_any_useful_by_response_mode", "blind quality review")
        val caseCount = requireNonnegativeInt(blindQualityReview, "case_count", "blind quality review")
        val top1Count = requireNonnegativeInt(blindQualityReview, "top1_useful_count", "blind quality review")
        val top2Count = requireNonnegativeInt(blindQualityReview, "top2_any_useful_count", "blind quality review")
        require(caseCount == cases.values.sum() && caseCount >= MIN_BLIND_CASES && cases.values.all { it >= MIN_BLIND_CASES_PER_MODE }) {
            "human speech style blind quality review case coverage is insufficient"
        }
        require(top1Count == top1.values.sum() && top2Count == top2.values.sum() && top1Count <= top2Count && top2Count <= caseCount) {
            "human speech style blind quality review usefulness counts are invalid"
        }
        require(top1Count * 100 >= caseCount * MIN_TOP1_USEFUL_PERCENT && top2Count * 100 >= caseCount * MIN_TOP2_USEFUL_PERCENT) {
            "human speech style blind quality review does not meet usefulness thresholds"
        }
        RESPONSE_MODES.forEach { mode ->
            require(top1.getValue(mode) <= top2.getValue(mode) && top2.getValue(mode) <= cases.getValue(mode)) {
                "human speech style blind quality review response mode usefulness is invalid"
            }
            require(
                top1.getValue(mode) * 100 >= cases.getValue(mode) * modeTop1Threshold(mode) &&
                    top2.getValue(mode) * 100 >= cases.getValue(mode) * modeTop2Threshold(mode),
            ) {
                "human speech style blind quality review does not meet response mode usefulness thresholds"
            }
        }
        SAFETY_COUNT_FIELDS.forEach { field ->
            requireNonnegativeInt(blindQualityReview, field, "blind quality review")
                .also { require(it == 0) { "human speech style blind quality review has unresolved safety findings" } }
        }
    }

    private fun validateRagValueReview(
        ragValueReview: JsonNode,
        cardDigest: String,
        retrievalAuditDigest: String,
    ) {
        requireExactFields(ragValueReview, RAG_VALUE_REVIEW_FIELDS, "RAG value review")
        requireText(ragValueReview, "schema", "RAG value review")
            .also { require(it == RAG_VALUE_REVIEW_SCHEMA) { "human speech style RAG value review schema is invalid" } }
        requireDigest(ragValueReview, "artifact_jsonl_sha256", cardDigest, "RAG value review")
        requireDigest(ragValueReview, "retrieval_audit_sha256", retrievalAuditDigest, "RAG value review")
        requireText(ragValueReview, "review_protocol", "RAG value review")
            .also { require(it == RAG_VALUE_REVIEW_PROTOCOL) { "human speech style RAG value review protocol is invalid" } }
        requireText(ragValueReview, "reviewer_type", "RAG value review")
            .also { require(it == RAG_VALUE_REVIEWER_TYPE) { "human speech style RAG value reviewer is invalid" } }
        requireText(ragValueReview, "verdict", "RAG value review")
            .also { require(it == "PASS") { "human speech style RAG value review does not pass quality gates" } }
        val cases = requireModeCountMap(ragValueReview, "case_count_by_response_mode", "RAG value review")
        val top1 = requireModeCountMap(ragValueReview, "top1_value_add_by_response_mode", "RAG value review")
        val top2 = requireModeCountMap(ragValueReview, "top2_any_value_add_by_response_mode", "RAG value review")
        val caseCount = requireNonnegativeInt(ragValueReview, "case_count", "RAG value review")
        val top1Count = requireNonnegativeInt(ragValueReview, "top1_value_add_count", "RAG value review")
        val top2Count = requireNonnegativeInt(ragValueReview, "top2_any_value_add_count", "RAG value review")
        require(
            caseCount == cases.values.sum() && caseCount >= MIN_RAG_VALUE_CASES && cases.values.all { it >= MIN_RAG_VALUE_CASES_PER_MODE },
        ) {
            "human speech style RAG value review case coverage is insufficient"
        }
        require(top1Count == top1.values.sum() && top2Count == top2.values.sum() && top1Count <= top2Count && top2Count <= caseCount) {
            "human speech style RAG value review counts are invalid"
        }
        require(top1Count * 100 >= caseCount * MIN_TOP1_VALUE_ADD_PERCENT && top2Count * 100 >= caseCount * MIN_TOP2_VALUE_ADD_PERCENT) {
            "human speech style RAG value review does not meet added-value thresholds"
        }
        RESPONSE_MODES.forEach { mode ->
            require(top1.getValue(mode) <= top2.getValue(mode) && top2.getValue(mode) <= cases.getValue(mode)) {
                "human speech style RAG value review response mode counts are invalid"
            }
            require(
                top1.getValue(mode) * 100 >= cases.getValue(mode) * MIN_MODE_TOP1_VALUE_ADD_PERCENT &&
                    top2.getValue(mode) * 100 >= cases.getValue(mode) * MIN_MODE_TOP2_VALUE_ADD_PERCENT,
            ) {
                "human speech style RAG value review does not meet response-mode thresholds"
            }
        }
        RAG_VALUE_ZERO_FINDING_FIELDS.forEach { field ->
            requireNonnegativeInt(ragValueReview, field, "RAG value review")
                .also { require(it == 0) { "human speech style RAG value review has unresolved findings" } }
        }
    }

    private fun readCards(cardsFile: Path): List<JsonNode> =
        Files
            .newBufferedReader(cardsFile)
            .useLines { lines ->
                lines
                    .filter(String::isNotBlank)
                    .mapIndexed { index, line ->
                        try {
                            val node = mapper.readTree(line)
                            requireExactFields(node, CARD_FIELDS, "import card at line ${index + 1}")
                            node
                        } catch (error: Exception) {
                            throw IllegalArgumentException("human speech style import card is invalid at line ${index + 1}", error)
                        }
                    }.toList()
            }

    private fun readObject(
        path: Path,
        label: String,
    ): JsonNode =
        try {
            mapper.readTree(path.toFile()).also { node -> require(node.isObject) { "human speech style $label is invalid" } }
        } catch (error: Exception) {
            throw IllegalArgumentException("human speech style $label is invalid", error)
        }

    private fun readSourceCoveragePolicy(): HumanSpeechStyleSourceCoveragePolicy {
        val resource =
            javaClass.classLoader.getResourceAsStream(SOURCE_COVERAGE_POLICY_RESOURCE)
                ?: throw IllegalStateException("human speech style source coverage policy is unavailable")
        resource.use { stream ->
            val policy = mapper.readTree(stream)
            requireExactFields(policy, SOURCE_COVERAGE_POLICY_FIELDS, "source coverage policy")
            requireText(policy, "schema", "source coverage policy")
                .also {
                    require(it == SOURCE_COVERAGE_POLICY_SCHEMA) {
                        "human speech style source coverage policy schema is invalid"
                    }
                }
            val sourceCount = requireNonnegativeInt(policy, "source_count", "source coverage policy")
            require(sourceCount > 0) { "human speech style source coverage policy source_count is invalid" }
            val sourceFingerprintSetSha256 =
                requireText(policy, "source_fingerprint_set_sha256", "source coverage policy")
            require(sourceFingerprintSetSha256.matches(SHA256_HEX)) {
                "human speech style source coverage policy source_fingerprint_set_sha256 is invalid"
            }
            return HumanSpeechStyleSourceCoveragePolicy(sourceCount, sourceFingerprintSetSha256)
        }
    }

    private fun requireRegularFile(path: Path) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "human speech style import artifact is incomplete" }
    }

    private fun requireExactFields(
        node: JsonNode?,
        expected: Set<String>,
        label: String,
    ) {
        require(node != null && node.isObject && node.fieldNames().asSequence().toSet() == expected) {
            "human speech style $label fields are invalid"
        }
    }

    private fun requireText(
        node: JsonNode,
        field: String,
        label: String,
    ): String {
        val value = node[field]
        require(value != null && value.isTextual && value.asText().isNotBlank()) { "human speech style $label $field is invalid" }
        return value.asText()
    }

    private fun requireOptionalText(
        node: JsonNode,
        field: String,
        label: String,
    ): String? {
        val value = node[field]
        require(value != null && (value.isNull || (value.isTextual && value.asText().isNotBlank()))) {
            "human speech style $label $field is invalid"
        }
        return value.takeUnless(JsonNode::isNull)?.asText()
    }

    private fun requireResponseMode(
        node: JsonNode,
        label: String,
    ): HumanSpeechResponseMode {
        val serializedMode = requireText(node, "response_mode", label)
        return HumanSpeechResponseMode.entries.singleOrNull { it.name == serializedMode }
            ?: throw IllegalArgumentException("human speech style $label response mode is invalid")
    }

    private fun requireResponseMove(
        node: JsonNode,
        responseMode: HumanSpeechResponseMode,
        label: String,
    ): HumanSpeechStyleResponseMove? {
        val serializedMove = requireOptionalText(node, "response_move", label) ?: return null
        val responseMove =
            HumanSpeechStyleResponseMove.entries.singleOrNull { it.name == serializedMove }
                ?: throw IllegalArgumentException("human speech style $label response move is invalid")
        require(responseMove.responseMode == responseMode) {
            "human speech style $label response move does not match response mode"
        }
        return responseMove
    }

    private fun requireSceneTraits(
        node: JsonNode,
        responseMode: HumanSpeechResponseMode,
        label: String,
    ): List<HumanSpeechSceneTrait> {
        val traits = node["scene_traits"]
        require(traits != null && traits.isArray) { "human speech style $label scene traits are invalid" }
        val serializedTraits =
            traits.map { trait ->
                require(trait.isTextual && trait.asText().isNotBlank()) {
                    "human speech style $label scene traits are invalid"
                }
                trait.asText()
            }
        require(serializedTraits.distinct().size == serializedTraits.size && serializedTraits.size <= MAX_SCENE_TRAITS) {
            "human speech style $label scene traits are invalid"
        }
        val sceneTraits =
            serializedTraits.map { serializedTrait ->
                HumanSpeechSceneTrait.entries.singleOrNull { it.name == serializedTrait }
                    ?: throw IllegalArgumentException("human speech style $label scene traits are invalid")
            }
        require(sceneTraits.all { it.responseMode == responseMode }) {
            "human speech style $label scene traits do not match response mode"
        }
        return sceneTraits
    }

    private fun requireProviderStyleCues(
        node: JsonNode,
        responseMode: HumanSpeechResponseMode,
        promptSurface: String,
        label: String,
    ): List<HumanSpeechStyleProviderStyleCue> {
        val cues = node["provider_style_cues"]
        require(cues != null && cues.isArray) { "human speech style $label provider style cues are invalid" }
        val serializedCues =
            cues.map { cue ->
                require(cue.isTextual && cue.asText().isNotBlank()) {
                    "human speech style $label provider style cues are invalid"
                }
                cue.asText()
            }
        require(serializedCues.distinct().size == serializedCues.size && serializedCues.size <= MAX_PROVIDER_STYLE_CUES) {
            "human speech style $label provider style cues are invalid"
        }
        require(promptSurface != STYLE_PATTERN || serializedCues.size == STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT) {
            "human speech style import style pattern must have exactly one provider style cue"
        }
        val providerStyleCues =
            serializedCues.map { serializedCue ->
                HumanSpeechStyleProviderStyleCue.entries.singleOrNull { it.name == serializedCue }
                    ?: throw IllegalArgumentException("human speech style $label provider style cues are invalid")
            }
        require(providerStyleCues.all { it.responseMode == responseMode }) {
            "human speech style $label provider style cues do not match response mode"
        }
        return providerStyleCues
    }

    private fun requireResponseMoveProvenance(
        node: JsonNode,
        label: String,
    ): HumanSpeechStyleResponseMoveProvenance {
        val serializedProvenance = requireText(node, "response_move_provenance", label)
        return HumanSpeechStyleResponseMoveProvenance.entries.singleOrNull { it.name == serializedProvenance }
            ?: throw IllegalArgumentException("human speech style $label response move provenance is invalid")
    }

    private fun requireResponseForm(
        node: JsonNode,
        responseMode: HumanSpeechResponseMode,
        label: String,
    ): HumanSpeechStyleResponseForm? {
        val serializedForm = requireOptionalText(node, "response_form", label) ?: return null
        val responseForm =
            HumanSpeechStyleResponseForm.entries.singleOrNull { it.name == serializedForm }
                ?: throw IllegalArgumentException("human speech style $label response form is invalid")
        require(responseForm.supports(responseMode)) {
            "human speech style $label response form does not match response mode"
        }
        return responseForm
    }

    private fun requireBoolean(
        node: JsonNode,
        field: String,
        label: String,
        expected: Boolean? = null,
    ): Boolean {
        val value = node[field]
        require(value != null && value.isBoolean) { "human speech style $label $field is invalid" }
        return value.booleanValue().also { actual ->
            if (expected != null) require(actual == expected) { "human speech style $label $field is invalid" }
        }
    }

    private fun requireNonnegativeInt(
        node: JsonNode,
        field: String,
        label: String,
    ): Int {
        val value = node[field]
        require(value != null && value.isIntegralNumber && value.canConvertToInt() && value.asInt() >= 0) {
            "human speech style $label $field is invalid"
        }
        return value.asInt()
    }

    private fun requireInt(
        node: JsonNode,
        field: String,
        label: String,
        expected: Int,
    ) {
        require(requireNonnegativeInt(node, field, label) == expected) { "human speech style $label $field mismatch" }
    }

    private fun requireDigest(
        node: JsonNode,
        field: String,
        expected: String,
        label: String,
    ) {
        val actual = requireText(node, field, label)
        require(actual.matches(SHA256_HEX) && actual == expected) { "human speech style $label $field mismatch" }
    }

    private fun sourceFingerprintSetSha256(sourceFingerprints: Set<String>): String {
        require(sourceFingerprints.isNotEmpty()) { "human speech style import source coverage set is invalid" }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(sourceFingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun requireCountMap(
        node: JsonNode,
        field: String,
        expected: Map<String, Int>,
        label: String,
    ) {
        val value = node[field]
        require(value != null && value.isObject) { "human speech style $label $field is invalid" }
        val actual =
            value.fields().asSequence().associate { (key, count) ->
                require(count.isIntegralNumber && count.canConvertToInt() && count.asInt() >= 0) {
                    "human speech style $label $field is invalid"
                }
                key to count.asInt()
            }
        require(actual == expected) { "human speech style $label $field mismatch" }
    }

    private fun requireModeCountMap(
        node: JsonNode,
        field: String,
        label: String,
    ): Map<String, Int> {
        val value = node[field]
        require(value != null && value.isObject && value.fieldNames().asSequence().toSet() == RESPONSE_MODES) {
            "human speech style $label $field is invalid"
        }
        return RESPONSE_MODES.associateWith { mode -> requireNonnegativeInt(value, mode, "$label $field") }
    }

    private fun requireRhythmCues(
        node: JsonNode,
        responseMode: HumanSpeechResponseMode,
        label: String,
    ): List<HumanSpeechStyleRhythmCue> {
        val rhythm = node["response_rhythm"]
        require(rhythm != null && rhythm.isArray) { "human speech style $label response rhythm is invalid" }
        val serializedCues =
            rhythm.map { cue ->
                require(cue.isTextual && cue.asText().isNotBlank()) { "human speech style $label response rhythm is invalid" }
                cue.asText()
            }
        require(serializedCues.distinct().size == serializedCues.size && serializedCues.size <= MAX_RESPONSE_RHYTHM_CUES) {
            "human speech style $label response rhythm is invalid"
        }
        return serializedCues.map { serializedCue ->
            val responseRhythmCue =
                HumanSpeechStyleRhythmCue.entries.singleOrNull { it.name == serializedCue }
                    ?: throw IllegalArgumentException("human speech style $label response rhythm is invalid")
            require(responseRhythmCue.supports(responseMode)) {
                "human speech style $label response rhythm does not match response mode"
            }
            responseRhythmCue
        }
    }

    private fun sha256File(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun modeTop1Threshold(mode: String): Int = if (mode == "COORDINATION") 60 else 50

    private fun modeTop2Threshold(mode: String): Int = if (mode == "COORDINATION") 80 else 70

    private data class ValidatedCard(
        val responseMode: HumanSpeechResponseMode,
        val responseMove: HumanSpeechStyleResponseMove?,
        val sceneTraits: List<HumanSpeechSceneTrait>,
        val providerStyleCues: List<HumanSpeechStyleProviderStyleCue>,
        val responseMoveProvenance: HumanSpeechStyleResponseMoveProvenance,
        val promptEligible: Boolean,
        val promptSurface: String,
        val responseRhythm: List<HumanSpeechStyleRhythmCue>,
    )

    private companion object {
        const val CARDS_FILE_NAME = "human-speech-style-cards.jsonl"
        const val IMPORT_MANIFEST_FILE_NAME = "manifest.json"
        const val CANDIDATE_MANIFEST_FILE_NAME = "candidate-manifest.json"
        const val RETRIEVAL_AUDIT_FILE_NAME = "retrieval-audit.json"
        const val BLIND_QUALITY_REVIEW_FILE_NAME = "blind-quality-review.json"
        const val RAG_VALUE_REVIEW_FILE_NAME = "rag-value-review.json"
        const val IMPORT_MANIFEST_SCHEMA = "nia-human-speech-style-import-manifest.v10"
        const val CANDIDATE_MANIFEST_SCHEMA = "nia-human-speech-style-runtime-candidate-manifest.v8"
        const val IMPORT_CARD_SCHEMA = "nia-human-speech-style-import-card.v4"
        const val RETRIEVAL_AUDIT_SCHEMA = "nia-human-speech-style-retrieval-audit.v4"
        const val BLIND_QUALITY_REVIEW_SCHEMA = "nia-human-speech-style-blind-quality-review.v2"
        const val RAG_VALUE_REVIEW_SCHEMA = "nia-human-speech-style-rag-value-review.v1"
        const val RAG_VALUE_REVIEW_PROTOCOL = "fixed_mode_baseline_v1"
        const val RAG_VALUE_REVIEWER_TYPE = "FRESH_COMPARATIVE_VERIFIER"
        const val RETRIEVAL_POLICY = "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11"
        const val RESPONSE_MOVE_POLICY = "observed_response_metadata_with_fresh_review_overlay_v1"
        const val PROMPT_SURFACE_POLICY = "closed_style_pattern_v1"
        const val STYLE_PATTERN = "STYLE_PATTERN"
        const val EMBEDDING_MODEL = "text-embedding-3-small"
        const val RETRIEVAL_AUDIT_EXECUTION_SCOPE = "ephemeral_h2_only_no_judge_no_discord_no_provider_generation"
        const val SOURCE_COVERAGE_POLICY_RESOURCE = "human-speech-style-source-coverage.json"
        const val SOURCE_COVERAGE_POLICY_SCHEMA = "nia-human-speech-style-source-coverage.v1"
        const val MAX_IMPORT_CARDS = 2_000
        const val MIN_RETRIEVAL_CASES = 35
        const val MIN_HOLDOUT_REFERENCE_COVERAGE_PERCENT = 80
        const val MIN_BLIND_CASES = 70
        const val MIN_BLIND_CASES_PER_MODE = 10
        const val MIN_TOP1_USEFUL_PERCENT = 65
        const val MIN_TOP2_USEFUL_PERCENT = 80
        const val MIN_RAG_VALUE_CASES = 70
        const val MIN_RAG_VALUE_CASES_PER_MODE = 10
        const val MIN_TOP1_VALUE_ADD_PERCENT = 60
        const val MIN_TOP2_VALUE_ADD_PERCENT = 75
        const val MIN_MODE_TOP1_VALUE_ADD_PERCENT = 50
        const val MIN_MODE_TOP2_VALUE_ADD_PERCENT = 70
        const val MAX_RESPONSE_RHYTHM_CUES = 8
        const val MAX_SCENE_TRAITS = 2
        const val MAX_PROVIDER_STYLE_CUES = 1
        const val STYLE_PATTERN_PROVIDER_STYLE_CUE_COUNT = 1
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val RESPONSE_MODES = HumanSpeechResponseMode.entries.map(HumanSpeechResponseMode::name).toSet()
        val PROMPT_SURFACES = setOf(STYLE_PATTERN, "AUDIT_ONLY")
        val SAFETY_COUNT_FIELDS = setOf("unsafe_provider_surface_count", "private_context_dependency_count", "copy_risk_count")
        val SOURCE_COVERAGE_POLICY_FIELDS = setOf("schema", "source_count", "source_fingerprint_set_sha256")
        val CARD_FIELDS =
            setOf(
                "schema",
                "example_id",
                "response_mode",
                "situation",
                "style_signals",
                "context_bubbles",
                "response_bubbles",
                "quality",
                "source_fingerprint",
                "consent_revision",
                "combined_chars",
                "prompt_eligible",
                "prompt_surface",
                "response_surface_has_card_local_alias",
                "response_move",
                "scene_traits",
                "provider_style_cues",
                "response_move_provenance",
                "response_form",
                "response_rhythm",
                "embedding_model",
            )
        val IMPORT_MANIFEST_FIELDS =
            setOf(
                "schema",
                "quality",
                "consent_revision",
                "record_count",
                "accepted_card_count",
                "prompt_eligible_count",
                "prompt_disabled_count",
                "prompt_eligible_by_response_mode",
                "prompt_surface_counts",
                "source_count",
                "source_fingerprint_count",
                "expected_source_count",
                "expected_source_fingerprint_set_sha256",
                "source_coverage_complete",
                "response_rhythm_coverage",
                "jsonl_sha256",
                "input_candidate_manifest_sha256",
                "input_candidate_jsonl_sha256",
                "all_cards_formally_approved",
                "all_cards_user_released",
                "retrieval_policy",
                "response_move_policy",
                "response_move_review_ledger_sha256",
                "fresh_verified_response_move_count",
                "heuristically_observed_response_move_count",
                "rejected_response_move_review_count",
                "scene_trait_counts",
                "provider_style_cue_counts",
                "response_move_provenance_counts",
                "prompt_surface_policy",
                "retrieval_audit",
                "retrieval_audit_sha256",
                "blind_quality_review",
                "rag_value_review",
                "rag_value_review_sha256",
                "purpose",
            )
        val CANDIDATE_MANIFEST_FIELDS =
            setOf(
                "schema",
                "input_jsonl_sha256",
                "record_count",
                "jsonl_sha256",
                "prompt_eligible_count",
                "prompt_disabled_count",
                "prompt_eligible_by_response_mode",
                "prompt_ineligible_reason_counts",
                "response_move_metadata_counts",
                "prompt_eligible_response_move_counts",
                "prompt_eligible_response_move_source_counts",
                "response_form_metadata_counts",
                "response_rhythm_cue_counts",
                "response_rhythm_coverage",
                "response_rhythm_behavior_coverage",
                "response_rhythm_delivery_only_count",
                "response_move_policy",
                "response_move_review_ledger_sha256",
                "fresh_verified_response_move_count",
                "heuristically_observed_response_move_count",
                "rejected_response_move_review_count",
                "scene_trait_counts",
                "provider_style_cue_counts",
                "response_move_provenance_counts",
                "retrieval_policy",
                "prompt_surface_policy",
                "source_fingerprint_count",
                "expected_source_count",
                "expected_source_fingerprint_set_sha256",
                "source_coverage_complete",
                "quality_counts",
                "purpose",
            )
        val RETRIEVAL_AUDIT_FIELDS =
            setOf(
                "schema",
                "candidate_jsonl_sha256",
                "retrieval_policy",
                "embedding_provider",
                "embedding_model",
                "execution_scope",
                "fixed_probe",
                "independent_holdout",
                "verdict",
                "reason_codes",
            )
        val FIXED_PROBE_FIELDS =
            setOf(
                "case_count",
                "exact_mode_return_case_count",
                "policy_abstention_count",
                "returned_reference_card_count",
                "returned_reference_case_count",
                "unexpected_post_query_empty_count",
            )
        val INDEPENDENT_HOLDOUT_FIELDS = FIXED_PROBE_FIELDS + "source_diverse_top2_case_count"
        val BLIND_QUALITY_REVIEW_FIELDS =
            setOf(
                "schema",
                "artifact_jsonl_sha256",
                "verdict",
                "case_count",
                "case_count_by_response_mode",
                "top1_useful_count",
                "top2_any_useful_count",
                "top1_useful_by_response_mode",
                "top2_any_useful_by_response_mode",
                "unsafe_provider_surface_count",
                "private_context_dependency_count",
                "copy_risk_count",
                "retrieval_audit_sha256",
            )
        val RAG_VALUE_REVIEW_FIELDS =
            setOf(
                "schema",
                "artifact_jsonl_sha256",
                "retrieval_audit_sha256",
                "review_protocol",
                "reviewer_type",
                "verdict",
                "case_count",
                "case_count_by_response_mode",
                "top1_value_add_count",
                "top2_any_value_add_count",
                "top1_value_add_by_response_mode",
                "top2_any_value_add_by_response_mode",
                "worse_than_mode_baseline_count",
                "unsupported_specificity_count",
                "unsafe_provider_surface_count",
            )
        val RAG_VALUE_ZERO_FINDING_FIELDS =
            setOf(
                "worse_than_mode_baseline_count",
                "unsupported_specificity_count",
                "unsafe_provider_surface_count",
            )
    }
}
