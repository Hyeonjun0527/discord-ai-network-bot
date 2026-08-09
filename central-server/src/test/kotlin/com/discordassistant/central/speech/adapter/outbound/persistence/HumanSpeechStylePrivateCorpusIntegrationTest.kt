package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.platform.openai.OpenAiSpeechStyleEmbeddingAdapter
import com.discordassistant.central.speech.adapter.inbound.cli.HumanSpeechStyleRagImportArtifactVerifier
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStylePromptRenderer
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagService
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.generation.SpeechGenerationFixtures
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale

class HumanSpeechStyleSealedImportArtifactVerifierIntegrationTest {
    private val verifier = HumanSpeechStyleRagImportArtifactVerifier()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `sealed user released import artifact is accepted by production verifier without exposing card content`() {
        val artifactDirectoryValue = System.getenv(PRIVATE_IMPORT_ARTIFACT_DIR_ENV).orEmpty()
        assumeTrue(artifactDirectoryValue.isNotBlank(), "private import artifact directory is unavailable")
        val artifactDirectory = Path.of(artifactDirectoryValue)
        assumeTrue(Files.isDirectory(artifactDirectory), "private import artifact directory is unavailable")

        val cardsFile = verifier.verify(artifactDirectory, HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        val importManifest = mapper.readTree(artifactDirectory.resolve(MANIFEST_FILE_NAME).toFile())

        assertThat(cardsFile.fileName.toString()).isEqualTo(CARDS_FILE_NAME)
        assertThat(importManifest.path("quality").asText()).isEqualTo(HumanSpeechStyleQuality.USER_RELEASED_REVIEW.name)
        assertThat(importManifest.path("all_cards_user_released").asBoolean()).isTrue()
        assertThat(importManifest.path("record_count").asInt()).isPositive()
        assertThat(importManifest.path("accepted_card_count").asInt()).isEqualTo(importManifest.path("record_count").asInt())
        assertThat(importManifest.path("prompt_eligible_count").asInt()).isPositive()
    }

    private companion object {
        const val PRIVATE_IMPORT_ARTIFACT_DIR_ENV = "NIA_PRIVATE_HUMAN_STYLE_IMPORT_ARTIFACT_DIR"
        const val CARDS_FILE_NAME = "human-speech-style-cards.jsonl"
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}

/**
 * 로컬에서만 명시적으로 실행하는 사용자 공개 승인 비공개 코퍼스 검증이다. CI에는 입력 파일과 시스템 속성이 없으므로 실행되지 않는다.
 *
 * 일반화된 카드 검색 필드만 임베딩 API에 보내고, 하나의 현재 장면을 검색한 결과가 Speech 프롬프트에는
 * 원문 대화·답변 없이 닫힌 비식별 style pattern으로만 붙되 추적 정보에는 붙지 않는지를 검증한다.
 * Discord, Judge, 생성 모델 호출은 하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(JpaHumanSpeechStyleExampleStore::class)
@EnabledIfEnvironmentVariable(named = "NIA_PRIVATE_HUMAN_STYLE_CORPUS_FILE", matches = ".+")
class HumanSpeechStylePrivateCorpusIntegrationTest
    @Autowired
    constructor(
        private val store: JpaHumanSpeechStyleExampleStore,
        private val jdbc: JdbcTemplate,
    ) {
        @BeforeEach
        fun configureEncryption() {
            FieldCrypto.configure("private-human-speech-style-verification-key")
        }

        @AfterEach
        fun clearEncryption() {
            FieldCrypto.configure(null)
        }

        @Test
        fun `private human review corpus imports and meets retrieval gates while staying out of traces`() {
            val sourceFile = privateCorpusFile()
            val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
            assumeTrue(Files.isRegularFile(sourceFile), "private corpus JSONL is unavailable")
            assumeTrue(apiKey.isNotBlank(), "OPENAI_API_KEY is unavailable")

            val embedding =
                OpenAiSpeechStyleEmbeddingAdapter(
                    apiKey = apiKey,
                    baseUrl = System.getenv("OPENAI_BASE_URL").orEmpty().ifBlank { "https://api.openai.com/v1" },
                    model = EMBEDDING_MODEL,
                    timeoutSeconds = 20,
                )
            val imported =
                HumanSpeechStyleRagImportService(store, embedding).importJsonLines(
                    sourceFile,
                    allowedQualities = setOf(HumanSpeechStyleQuality.USER_RELEASED_REVIEW),
                )
            val retrievalEmbedding = RecordingSpeechStyleEmbeddingPort(embedding)
            val rag = HumanSpeechStyleRagService(store, retrievalEmbedding)
            val availableModes = store.listEnabled().map { it.responseMode }.toSet()
            val importedExamples = store.listEnabled()
            val rawPayload = jdbc.queryForObject("SELECT payload_json FROM nia_human_speech_style_example LIMIT 1", String::class.java)
            val storedCardCount = jdbc.queryForObject("SELECT COUNT(*) FROM nia_human_speech_style_example", Int::class.java)

            assertThat(imported.importedCount).isEqualTo(Files.readAllLines(sourceFile).count(String::isNotBlank))
            assertThat(imported.promptEligibleCount).isPositive().isLessThan(imported.importedCount)
            assertThat(storedCardCount).isEqualTo(imported.importedCount)
            assertThat(importedExamples).hasSize(imported.promptEligibleCount)
            assertThat(importedExamples).allSatisfy { example -> assertThat(example.promptEligible).isTrue() }
            assertThat(importedExamples.map { it.embeddingModel }.toSet()).containsExactly("text-embedding-3-small")
            assertThat(importedExamples.map { it.embedding.size }.toSet()).containsExactly(1_536)
            assertThat(importedExamples.map { it.rhythmEmbedding.size }.toSet()).containsExactly(1_536)
            assertThat(importedExamples).allSatisfy { example -> assertThat(example.responseRhythm).isNotEmpty() }
            assertThat(importedExamples).allSatisfy { example ->
                assertThat(example.providerStyleCues)
                    .withFailMessage("private prompt-eligible corpus has a card without a provider style cue")
                    .hasSize(1)
                assertThat(example.providerStyleCues).allSatisfy { cue ->
                    assertThat(cue.responseMode).isEqualTo(example.responseMode)
                }
            }
            assertThat(importedExamples.any { it.sceneTraits.isNotEmpty() })
                .withFailMessage("private prompt-eligible corpus has no scene-trait candidate")
                .isTrue()
            assertThat(importedExamples).allSatisfy { example ->
                assertThat(example.sceneTraits).allSatisfy { trait ->
                    assertThat(trait.responseMode).isEqualTo(example.responseMode)
                }
                assertThat(example.responseMoveProvenance.matches(example.responseMove)).isTrue()
            }
            assertThat(importedExamples).allSatisfy { example ->
                assertThat(example.promptSurface).isEqualTo(HumanSpeechStylePromptSurface.STYLE_PATTERN)
            }
            val selection = assertRuntimeQueryPath(rag, retrievalEmbedding)
            val payload = HumanSpeechStylePromptRenderer().appendTo("현재 장면", selection)
            assertThat(selection.matches).hasSizeLessThanOrEqualTo(2)
            assertThat(payload.providerUserPrompt).contains("사람 말투 리듬 참고", "비식별 추출 패턴")
            assertThat(payload.traceUserPrompt).contains("private human-style examples omitted")
            assertThat(rawPayload).startsWith("enc1:")
            assertPromptSurfaceRendering(importedExamples)
            val fixedProbeResults = assertJudgeSelectedModesArePreserved(rag, retrievalEmbedding, availableModes)
            assertEveryPromptEligibleCardIsSearchableFromItsGeneralizedMetadata(importedExamples)
            val candidateDigest = sha256File(sourceFile)
            assertManuallyReviewedPrivateRetrievalBenchmark(
                rag = rag,
                embedding = retrievalEmbedding,
                candidateDigest = candidateDigest,
            )
            val independentHoldoutResults = assertIndependentHoldout(rag, retrievalEmbedding)
            val retrievalAudit = writePrivateRetrievalAuditIfRequested(candidateDigest, fixedProbeResults, independentHoldoutResults)
            writeBlindQualityReviewBundleIfRequested(candidateDigest, retrievalAudit, fixedProbeResults, independentHoldoutResults)
        }

        private fun assertRuntimeQueryPath(
            rag: HumanSpeechStyleRagService,
            embedding: RecordingSpeechStyleEmbeddingPort,
        ): HumanSpeechStyleSelection {
            val requestCountBefore = embedding.requestCount()
            val selection =
                rag.retrieve(
                    SpeechGenerationFixtures.packet(
                        socialAct = SpeechSocialAct.UNKNOWN,
                        styleResponseMode = HumanSpeechResponseMode.REACTION,
                        turns = listOf(ConversationTurn("member", "그냥 평범한 얘기야")),
                        speechIntent = "reason_code=live_rag_transport; intent_summary=짧고 가볍게 반응한다",
                    ),
                )
            assertThat(embedding.inputCountsSince(requestCountBefore)).containsExactly(RUNTIME_QUERY_EMBEDDING_COUNT)
            assertThat(selection.matches).isNotEmpty().hasSizeLessThanOrEqualTo(HumanSpeechStyleSelection.MAX_MATCHES)
            return selection
        }

        private fun assertPromptSurfaceRendering(importedExamples: List<HumanSpeechStyleExample>) {
            val renderer = HumanSpeechStylePromptRenderer()
            val pattern =
                requireNotNull(importedExamples.firstOrNull { it.promptSurface == HumanSpeechStylePromptSurface.STYLE_PATTERN }) {
                    "private corpus has no closed style pattern"
                }
            val patternPayload =
                renderer.appendTo(
                    "현재 장면",
                    HumanSpeechStyleSelection(listOf(HumanSpeechStyleMatch(pattern, 1.0))),
                )
            assertThat(patternPayload.providerUserPrompt).contains("사람 말투 리듬 참고", "비식별 추출 패턴")
            pattern.contextBubbles.forEach { bubble ->
                assertThat(patternPayload.providerUserPrompt).doesNotContain(bubble.text)
                assertThat(patternPayload.traceUserPrompt).doesNotContain(bubble.text)
            }
            pattern.responseBubbles.forEach { bubble ->
                assertThat(patternPayload.providerUserPrompt).doesNotContain(bubble.text)
                assertThat(patternPayload.traceUserPrompt).doesNotContain(bubble.text)
            }
        }

        private fun assertJudgeSelectedModesArePreserved(
            rag: HumanSpeechStyleRagService,
            embedding: RecordingSpeechStyleEmbeddingPort,
            availableModes: Set<HumanSpeechResponseMode>,
        ): List<RetrievalResult> {
            val eligibleProbes = retrievalProbes.filter { it.expectedMode in availableModes }
            require(eligibleProbes.isNotEmpty()) { "private corpus has no response-mode cards" }
            val results =
                eligibleProbes.mapIndexed { index, probe ->
                    val requestCountBefore = embedding.requestCount()
                    val selection =
                        rag.retrieve(
                            SpeechGenerationFixtures.packet(
                                socialAct = probe.socialAct,
                                styleResponseMode = probe.expectedMode,
                                turns = listOf(ConversationTurn("member", probe.memberMessage)),
                                speechIntent = probe.speechIntent,
                            ),
                        )
                    RetrievalResult(
                        probeId = "fixed-v1-%04d".format(Locale.ROOT, index + 1),
                        expectedMode = probe.expectedMode,
                        socialAct = probe.socialAct,
                        recentTurns = listOf(ConversationTurn("member", probe.memberMessage)),
                        speechIntent = probe.speechIntent,
                        returnedExampleIds = selection.matches.map { it.example.exampleId },
                        matches = selection.matches,
                        returnedModes = selection.matches.map { it.example.responseMode },
                        promptSurfaces = selection.matches.map { it.example.promptSurface },
                        sourceFingerprints = selection.matches.map { it.example.sourceFingerprint },
                        embeddingInputCounts = embedding.inputCountsSince(requestCountBefore),
                        scores = selection.matches.map { it.score },
                    )
                }
            val exactModeMatches =
                results.count { result ->
                    result.returnedModes.isNotEmpty() && result.returnedModes.all { it == result.expectedMode }
                }
            val selectionsWithReferences = results.count { it.returnedModes.isNotEmpty() }
            val policyAbstentions = results.count { it.isPolicyAbstention() }
            val selectionCoverage = selectionsWithReferences.toDouble() / results.size
            val allScores = results.flatMap(RetrievalResult::scores)
            val minimumScore = allScores.minOrNull()
            val maximumScore = allScores.maxOrNull()
            val p50Score = percentile(allScores, 0.50)
            val p95Score = percentile(allScores, 0.95)
            val byMode =
                HumanSpeechResponseMode.entries.joinToString(",") { mode ->
                    val matches =
                        results.count { result ->
                            result.expectedMode == mode &&
                                result.returnedModes.isNotEmpty() &&
                                result.returnedModes.all { it == mode }
                        }
                    val probes = results.count { it.expectedMode == mode }
                    "$mode:$matches/$probes"
                }

            println(
                "LIVE_HUMAN_SPEECH_STYLE_RAG_MODE_GUARD " +
                    "probes=${results.size} exact_mode=$exactModeMatches returned=${allScores.size} " +
                    "policy_abstentions=$policyAbstentions " +
                    "selection_coverage=${String.format(Locale.ROOT, "%.4f", selectionCoverage)} " +
                    "min_score=${minimumScore?.let { String.format(Locale.ROOT, "%.4f", it) }} " +
                    "p50_score=${p50Score?.let { String.format(Locale.ROOT, "%.4f", it) }} " +
                    "p95_score=${p95Score?.let { String.format(Locale.ROOT, "%.4f", it) }} " +
                    "max_score=${maximumScore?.let { String.format(Locale.ROOT, "%.4f", it) }} by_mode=$byMode",
            )
            assertThat(results).allSatisfy { result -> assertThat(result.returnedModes).hasSizeLessThanOrEqualTo(2) }
            assertThat(exactModeMatches)
                .withFailMessage(
                    "Judge-selected response mode was not preserved for " +
                        "${selectionsWithReferences - exactModeMatches}/$selectionsWithReferences retrieved probes",
                ).isEqualTo(selectionsWithReferences)
            assertPolicyAwareRetrievalResults(results)
            return results
        }

        private fun assertPolicyAwareRetrievalResults(results: List<RetrievalResult>) {
            val returned = results.filter { it.returnedExampleIds.isNotEmpty() }
            val empty = results.filter { it.returnedExampleIds.isEmpty() }
            returned.forEach { result ->
                assertThat(result.embeddingInputCounts).containsExactly(RUNTIME_QUERY_EMBEDDING_COUNT)
                assertThat(result.returnedModes).containsOnly(result.expectedMode)
                assertThat(result.sourceFingerprints).doesNotHaveDuplicates()
                assertThat(result.promptSurfaces).containsOnly(HumanSpeechStylePromptSurface.STYLE_PATTERN)
            }
            empty.forEach { result ->
                assertThat(result.embeddingInputCounts)
                    .withFailMessage("retrieval queried OpenAI but did not produce a usable reference for ${result.probeId}")
                    .isEmpty()
            }
        }

        private fun percentile(
            values: List<Double>,
            quantile: Double,
        ): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val index = ((sorted.lastIndex) * quantile).toInt()
            return sorted[index]
        }

        private fun assertEveryPromptEligibleCardIsSearchableFromItsGeneralizedMetadata(examples: List<HumanSpeechStyleExample>) {
            // 이 단계는 embedding surface가 원문 대화 대신 일반화 metadata로 바뀐 뒤에도 실제 vector가 전부 적재됐는지
            // 검증한다. "카드 자신의 vector로 자기 자신을 top-2로 검색"하는 것은 metadata가 같은 카드가 많으면 의미 없는
            // 순위 의존 assertion이 되므로, 실제 relevance는 아래의 고정 probe와 independent holdout에서 검증한다.
            val selfSimilarities = examples.map { example -> cosineSimilarity(example.embedding, example.embedding) }
            val perMode =
                HumanSpeechResponseMode.entries.joinToString(",") { mode ->
                    "$mode:${examples.count { it.responseMode == mode }}"
                }

            println(
                "LIVE_HUMAN_SPEECH_STYLE_RAG_CARD_EMBEDDING_INTEGRITY " +
                    "cards=${examples.size} min_self_similarity=${String.format(Locale.ROOT, "%.4f", selfSimilarities.min())} " +
                    "per_mode=$perMode",
            )
            assertThat(selfSimilarities).allSatisfy { similarity ->
                assertThat(similarity).isGreaterThanOrEqualTo(0.9999)
            }
            HumanSpeechResponseMode.entries.forEach { mode ->
                assertThat(examples.count { it.responseMode == mode })
                    .withFailMessage("private prompt-eligible corpus has no $mode cards")
                    .isPositive()
            }
        }

        private fun cosineSimilarity(
            left: FloatArray,
            right: FloatArray,
        ): Double {
            require(left.size == right.size && left.isNotEmpty()) { "coverage vector dimensions are invalid" }
            var dot = 0.0
            var leftMagnitude = 0.0
            var rightMagnitude = 0.0
            left.indices.forEach { index ->
                val leftValue = left[index].toDouble()
                val rightValue = right[index].toDouble()
                dot += leftValue * rightValue
                leftMagnitude += leftValue * leftValue
                rightMagnitude += rightValue * rightValue
            }
            return dot / (kotlin.math.sqrt(leftMagnitude) * kotlin.math.sqrt(rightMagnitude))
        }

        private fun assertManuallyReviewedPrivateRetrievalBenchmark(
            rag: HumanSpeechStyleRagService,
            embedding: RecordingSpeechStyleEmbeddingPort,
            candidateDigest: String,
        ) {
            val evaluationFilePath = System.getenv("NIA_PRIVATE_HUMAN_STYLE_RETRIEVAL_EVAL_FILE").orEmpty()
            if (evaluationFilePath.isBlank()) return
            val evaluationFile = Path.of(evaluationFilePath)
            require(Files.isRegularFile(evaluationFile)) { "manually reviewed private retrieval benchmark is unavailable" }

            val evaluationCases = readRetrievalEvaluationCases(evaluationFile)
            require(evaluationCases.size >= MIN_EVALUATION_CASES) {
                "private retrieval benchmark needs at least $MIN_EVALUATION_CASES manually reviewed cases"
            }
            assertEvaluationCasesAreBoundToCandidate(evaluationCases, candidateDigest)
            val importedModesById = store.listEnabled().associate { it.exampleId to it.responseMode }
            require(
                evaluationCases.all { evaluation ->
                    evaluation.acceptedExampleIds.all { exampleId -> importedModesById[exampleId] == evaluation.expectedMode }
                },
            ) {
                "private retrieval benchmark refers to an absent or wrong-mode approved example"
            }
            val results =
                evaluationCases.map { evaluation ->
                    val requestCountBefore = embedding.requestCount()
                    val selection =
                        rag.retrieve(
                            SpeechGenerationFixtures.packet(
                                socialAct = evaluation.socialAct,
                                styleResponseMode = evaluation.expectedMode,
                                turns = evaluation.recentTurns,
                                speechIntent = evaluation.speechIntent,
                            ),
                        )
                    RetrievalQualityResult(
                        probeId = evaluation.probeId,
                        expectedMode = evaluation.expectedMode,
                        verdict = evaluation.verdict,
                        acceptedExampleIds = evaluation.acceptedExampleIds,
                        returnedExampleIds = selection.matches.map { it.example.exampleId },
                        returnedModes = selection.matches.map { it.example.responseMode },
                        embeddingInputCounts = embedding.inputCountsSince(requestCountBefore),
                    )
                }

            val resultsWithAcceptedReferences = results.filter { it.verdict != NO_ACCEPTABLE_VERDICT }
            val resultsExpectingAbstention = results.filter { it.verdict == NO_ACCEPTABLE_VERDICT }
            require(resultsWithAcceptedReferences.size >= MIN_ACCEPTABLE_REFERENCE_CASES) {
                "private retrieval benchmark needs enough cases with a manually accepted reference"
            }
            val exactModeMatches =
                results.count { result ->
                    result.returnedModes.isNotEmpty() &&
                        result.returnedModes.all { it == result.expectedMode }
                }
            val selectionsReturned = results.count { it.returnedExampleIds.isNotEmpty() }
            val selectionsWithAcceptedReferences =
                resultsWithAcceptedReferences.count { it.returnedExampleIds.isNotEmpty() }
            val selectionCoverage = selectionsWithAcceptedReferences.toDouble() / resultsWithAcceptedReferences.size
            val hitAtOne =
                resultsWithAcceptedReferences.count { result ->
                    result.returnedExampleIds.firstOrNull() in result.acceptedExampleIds
                }
            val hitAtTwo =
                resultsWithAcceptedReferences.count { result ->
                    result.returnedExampleIds.any(result.acceptedExampleIds::contains)
                }
            val hitAtOneRate = hitAtOne.toDouble() / resultsWithAcceptedReferences.size
            val hitAtTwoRate = hitAtTwo.toDouble() / resultsWithAcceptedReferences.size
            val correctAbstentions = resultsExpectingAbstention.count { it.returnedExampleIds.isEmpty() }
            val unsafeOrUnnecessaryAbstentionQueries =
                resultsExpectingAbstention.count { it.embeddingInputCounts.isNotEmpty() }
            val topTwoMissProbeIds =
                resultsWithAcceptedReferences
                    .filter { result -> result.returnedExampleIds.none(result.acceptedExampleIds::contains) }
                    .joinToString("|") { it.probeId }

            println(
                "LIVE_HUMAN_SPEECH_STYLE_RAG_SEMANTIC_EVAL " +
                    "cases=${results.size} accepted=${resultsWithAcceptedReferences.size} " +
                    "abstention_cases=${resultsExpectingAbstention.size} exact_mode=$exactModeMatches " +
                    "hit_at_1=$hitAtOne hit_at_2=$hitAtTwo correct_abstentions=$correctAbstentions " +
                    "top2_miss_probe_ids=$topTwoMissProbeIds",
            )
            assertThat(selectionCoverage)
                .withFailMessage("semantic retrieval coverage was $selectionCoverage, below $MIN_SELECTION_COVERAGE")
                .isGreaterThanOrEqualTo(MIN_SELECTION_COVERAGE)
            assertThat(exactModeMatches).isEqualTo(selectionsReturned)
            assertThat(correctAbstentions)
                .withFailMessage("semantic retrieval abstention was $correctAbstentions/${resultsExpectingAbstention.size}")
                .isEqualTo(resultsExpectingAbstention.size)
            assertThat(unsafeOrUnnecessaryAbstentionQueries)
                .withFailMessage(
                    "semantic retrieval sent an embedding request for " +
                        "$unsafeOrUnnecessaryAbstentionQueries no-reference benchmark cases",
                ).isZero()
            assertThat(hitAtOneRate)
                .withFailMessage("semantic retrieval hit@1 was $hitAtOneRate, below $MIN_HIT_AT_ONE")
                .isGreaterThanOrEqualTo(MIN_HIT_AT_ONE)
            assertThat(hitAtTwoRate)
                .withFailMessage("semantic retrieval hit@2 was $hitAtTwoRate, below $MIN_HIT_AT_TWO")
                .isGreaterThanOrEqualTo(MIN_HIT_AT_TWO)
        }

        private fun assertIndependentHoldout(
            rag: HumanSpeechStyleRagService,
            embedding: RecordingSpeechStyleEmbeddingPort,
        ): List<IndependentHoldoutResult> {
            val evaluationFilePath = System.getenv(INDEPENDENT_HOLDOUT_ENV).orEmpty()
            if (evaluationFilePath.isBlank()) return emptyList()
            val evaluationFile = Path.of(evaluationFilePath)
            require(Files.isRegularFile(evaluationFile)) { "independent retrieval holdout is unavailable" }

            val holdouts = readIndependentHoldoutCases(evaluationFile)
            assertIndependentHoldoutShape(holdouts)
            val results =
                holdouts.map { holdout ->
                    val requestCountBefore = embedding.requestCount()
                    val selection =
                        rag.retrieve(
                            SpeechGenerationFixtures.packet(
                                socialAct = holdout.socialAct,
                                styleResponseMode = holdout.expectedMode,
                                turns = holdout.recentTurns,
                                speechIntent = holdout.speechIntent,
                            ),
                        )
                    IndependentHoldoutResult(
                        probeId = holdout.probeId,
                        expectedMode = holdout.expectedMode,
                        socialAct = holdout.socialAct,
                        recentTurns = holdout.recentTurns,
                        speechIntent = holdout.speechIntent,
                        returnedExampleIds = selection.matches.map { it.example.exampleId },
                        matches = selection.matches,
                        returnedModes = selection.matches.map { it.example.responseMode },
                        promptSurfaces = selection.matches.map { it.example.promptSurface },
                        sourceFingerprints = selection.matches.map { it.example.sourceFingerprint },
                        embeddingInputCounts = embedding.inputCountsSince(requestCountBefore),
                        scores = selection.matches.map { it.score },
                    )
                }

            val completeTopTwo = results.count { it.returnedExampleIds.size == HumanSpeechStyleSelection.MAX_MATCHES }
            val exactModeTopTwo =
                results.count { result ->
                    result.returnedModes.size == HumanSpeechStyleSelection.MAX_MATCHES &&
                        result.returnedModes.all { it == result.expectedMode }
                }
            val sourceDiverseTopTwo =
                results.count { result ->
                    result.sourceFingerprints.size == HumanSpeechStyleSelection.MAX_MATCHES &&
                        result.sourceFingerprints.distinct().size == result.sourceFingerprints.size
                }
            val policyAbstentions = results.filter { it.isPolicyAbstention() }
            val referenceCoverage = results.count { it.returnedExampleIds.isNotEmpty() }.toDouble() / results.size
            val promptSurfaceCounts =
                results
                    .flatMap(IndependentHoldoutResult::promptSurfaces)
                    .groupingBy(HumanSpeechStylePromptSurface::name)
                    .eachCount()
            val byMode =
                HumanSpeechResponseMode.entries.joinToString(",") { mode ->
                    val modeResults = results.filter { it.expectedMode == mode }
                    "$mode:${modeResults.count { it.returnedExampleIds.size == HumanSpeechStyleSelection.MAX_MATCHES }}/${modeResults.size}"
                }

            println(
                "LIVE_HUMAN_SPEECH_STYLE_RAG_INDEPENDENT_HOLDOUT " +
                    "cases=${results.size} complete_top2=$completeTopTwo exact_mode_top2=$exactModeTopTwo " +
                    "source_diverse_top2=$sourceDiverseTopTwo policy_abstentions=${policyAbstentions.size} " +
                    "reference_coverage=${String.format(Locale.ROOT, "%.4f", referenceCoverage)} " +
                    "prompt_surfaces=$promptSurfaceCounts by_mode=$byMode",
            )
            assertPolicyAwareIndependentHoldoutResults(results)
            assertThat(referenceCoverage)
                .withFailMessage(
                    "independent holdout reference coverage was $referenceCoverage, below " +
                        "$MIN_INDEPENDENT_HOLDOUT_REFERENCE_COVERAGE",
                ).isGreaterThanOrEqualTo(MIN_INDEPENDENT_HOLDOUT_REFERENCE_COVERAGE)
            return results
        }

        private fun assertPolicyAwareIndependentHoldoutResults(results: List<IndependentHoldoutResult>) {
            val returned = results.filter { it.returnedExampleIds.isNotEmpty() }
            val empty = results.filter { it.returnedExampleIds.isEmpty() }
            returned.forEach { result ->
                assertThat(result.embeddingInputCounts).containsExactly(RUNTIME_QUERY_EMBEDDING_COUNT)
                assertThat(result.returnedModes).containsOnly(result.expectedMode)
                assertThat(result.sourceFingerprints).doesNotHaveDuplicates()
                assertThat(result.promptSurfaces).containsOnly(HumanSpeechStylePromptSurface.STYLE_PATTERN)
            }
            empty.forEach { result ->
                assertThat(result.embeddingInputCounts)
                    .withFailMessage("holdout queried OpenAI but did not produce a usable reference for ${result.probeId}")
                    .isEmpty()
            }
        }

        /**
         * 새 검수자가 볼 수 있는 로컬 전용 집계 감사면이다. 대화·프롬프트·카드·출처 지문·점수는 넣지 않고, 후보 digest와
         * 실제 OpenAI 실행 조건 및 통과한 집계 수치만 기록한다. 명시적으로 요청될 때만 만들며 실행 경로는 읽지 않는다.
         */
        private fun writePrivateRetrievalAuditIfRequested(
            candidateDigest: String,
            fixedProbeResults: List<RetrievalResult>,
            independentHoldoutResults: List<IndependentHoldoutResult>,
        ): Path? {
            val auditFile = System.getenv(RETRIEVAL_AUDIT_FILE_ENV).orEmpty()
            if (auditFile.isBlank()) return null
            val output = Path.of(auditFile).toAbsolutePath().normalize()
            require(fixedProbeResults.isNotEmpty() && independentHoldoutResults.isNotEmpty()) {
                "private retrieval audit requires both fixed and independent results"
            }
            requireNotNull(output.parent) { "private retrieval audit output requires a parent directory" }
            Files.createDirectories(output.parent)
            jacksonObjectMapper().writeValue(
                output.toFile(),
                mapOf(
                    "schema" to RETRIEVAL_AUDIT_SCHEMA,
                    "candidate_jsonl_sha256" to candidateDigest,
                    "retrieval_policy" to RETRIEVAL_POLICY,
                    "embedding_provider" to EMBEDDING_PROVIDER,
                    "embedding_model" to EMBEDDING_MODEL,
                    "execution_scope" to EXECUTION_SCOPE,
                    "fixed_probe" to fixedProbeAudit(fixedProbeResults),
                    "independent_holdout" to independentHoldoutAudit(independentHoldoutResults),
                    "verdict" to "PASS",
                    "reason_codes" to emptyList<String>(),
                ),
            )
            Files.setPosixFilePermissions(output, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            return output
        }

        private fun writeBlindQualityReviewBundleIfRequested(
            candidateDigest: String,
            retrievalAudit: Path?,
            fixedProbeResults: List<RetrievalResult>,
            independentHoldoutResults: List<IndependentHoldoutResult>,
        ) {
            val bundleFile = System.getenv(BLIND_REVIEW_BUNDLE_FILE_ENV).orEmpty()
            if (bundleFile.isBlank()) return
            val audit =
                requireNotNull(retrievalAudit) {
                    "blind quality review bundle requires an aggregate-only actual retrieval audit"
                }
            val cases =
                buildList {
                    fixedProbeResults.forEach { result ->
                        add(
                            HumanSpeechStyleBlindReviewCase(
                                responseMode = result.expectedMode,
                                socialAct = result.socialAct,
                                recentTurns = result.recentTurns,
                                speechIntent = result.speechIntent,
                                matches = result.matches,
                            ),
                        )
                    }
                    independentHoldoutResults.forEach { result ->
                        add(
                            HumanSpeechStyleBlindReviewCase(
                                responseMode = result.expectedMode,
                                socialAct = result.socialAct,
                                recentTurns = result.recentTurns,
                                speechIntent = result.speechIntent,
                                matches = result.matches,
                            ),
                        )
                    }
                }
            require(cases.size == BLIND_REVIEW_CASES) {
                "blind quality review bundle must contain exactly $BLIND_REVIEW_CASES cases"
            }
            HumanSpeechResponseMode.entries.forEach { mode ->
                require(cases.count { it.responseMode == mode } == BLIND_REVIEW_CASES_PER_MODE) {
                    "blind quality review bundle response-mode coverage is invalid for $mode"
                }
            }
            HumanSpeechStyleBlindReviewBundleWriter.write(
                output = Path.of(bundleFile),
                candidateDigest = candidateDigest,
                retrievalAuditDigest = sha256File(audit),
                cases = cases,
            )
        }

        private fun fixedProbeAudit(results: List<RetrievalResult>): Map<String, Int> =
            mapOf(
                "case_count" to results.size,
                "exact_mode_return_case_count" to
                    results.count { result ->
                        result.returnedModes.isNotEmpty() && result.returnedModes.all { it == result.expectedMode }
                    },
                "policy_abstention_count" to results.count(RetrievalResult::isPolicyAbstention),
                "returned_reference_card_count" to results.sumOf { it.returnedExampleIds.size },
                "returned_reference_case_count" to results.count { it.returnedExampleIds.isNotEmpty() },
                "unexpected_post_query_empty_count" to
                    results.count { result ->
                        result.returnedExampleIds.isEmpty() && result.embeddingInputCounts.isNotEmpty()
                    },
            )

        private fun independentHoldoutAudit(results: List<IndependentHoldoutResult>): Map<String, Int> =
            mapOf(
                "case_count" to results.size,
                "exact_mode_return_case_count" to
                    results.count { result ->
                        result.returnedModes.isNotEmpty() && result.returnedModes.all { it == result.expectedMode }
                    },
                "policy_abstention_count" to results.count(IndependentHoldoutResult::isPolicyAbstention),
                "returned_reference_card_count" to results.sumOf { it.returnedExampleIds.size },
                "returned_reference_case_count" to results.count { it.returnedExampleIds.isNotEmpty() },
                "source_diverse_top2_case_count" to
                    results.count { result ->
                        result.sourceFingerprints.size == HumanSpeechStyleSelection.MAX_MATCHES &&
                            result.sourceFingerprints.distinct().size == result.sourceFingerprints.size
                    },
                "unexpected_post_query_empty_count" to
                    results.count { result ->
                        result.returnedExampleIds.isEmpty() && result.embeddingInputCounts.isNotEmpty()
                    },
            )

        private data class RetrievalProbe(
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val memberMessage: String,
            val speechIntent: String,
        )

        private data class RetrievalResult(
            val probeId: String,
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val recentTurns: List<ConversationTurn>,
            val speechIntent: String,
            val returnedExampleIds: List<String>,
            val matches: List<HumanSpeechStyleMatch>,
            val returnedModes: List<HumanSpeechResponseMode>,
            val promptSurfaces: List<HumanSpeechStylePromptSurface>,
            val sourceFingerprints: List<String>,
            val embeddingInputCounts: List<Int>,
            val scores: List<Double>,
        ) {
            fun isPolicyAbstention(): Boolean = returnedExampleIds.isEmpty() && embeddingInputCounts.isEmpty()
        }

        private data class RetrievalQualityCase(
            val probeId: String,
            val candidateDigest: String,
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val recentTurns: List<ConversationTurn>,
            val speechIntent: String,
            val verdict: String,
            val acceptedExampleIds: Set<String>,
        )

        private data class RetrievalQualityResult(
            val probeId: String,
            val expectedMode: HumanSpeechResponseMode,
            val verdict: String,
            val acceptedExampleIds: Set<String>,
            val returnedExampleIds: List<String>,
            val returnedModes: List<HumanSpeechResponseMode>,
            val embeddingInputCounts: List<Int>,
        )

        private data class IndependentHoldoutCase(
            val probeId: String,
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val recentTurns: List<ConversationTurn>,
            val speechIntent: String,
            val focus: String,
        )

        private data class IndependentHoldoutResult(
            val probeId: String,
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val recentTurns: List<ConversationTurn>,
            val speechIntent: String,
            val returnedExampleIds: List<String>,
            val matches: List<HumanSpeechStyleMatch>,
            val returnedModes: List<HumanSpeechResponseMode>,
            val promptSurfaces: List<HumanSpeechStylePromptSurface>,
            val sourceFingerprints: List<String>,
            val embeddingInputCounts: List<Int>,
            val scores: List<Double>,
        ) {
            fun isPolicyAbstention(): Boolean = returnedExampleIds.isEmpty() && embeddingInputCounts.isEmpty()
        }

        private class RecordingSpeechStyleEmbeddingPort(
            private val delegate: SpeechStyleEmbeddingPort,
        ) : SpeechStyleEmbeddingPort {
            private val inputCounts = mutableListOf<Int>()

            override fun embedAll(texts: List<String>): List<FloatArray>? {
                inputCounts += texts.size
                return delegate.embedAll(texts)
            }

            fun requestCount(): Int = inputCounts.size

            fun inputCountsSince(requestCount: Int): List<Int> = inputCounts.drop(requestCount)
        }

        private fun privateCorpusFile(): Path = Path.of(System.getenv("NIA_PRIVATE_HUMAN_STYLE_CORPUS_FILE"))

        private fun sha256File(path: Path): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun readRetrievalEvaluationCases(path: Path): List<RetrievalQualityCase> {
            val mapper = jacksonObjectMapper()
            val seenProbeIds = mutableSetOf<String>()
            return Files
                .readAllLines(path)
                .filter(String::isNotBlank)
                .mapIndexed { index, line ->
                    val row = mapper.readTree(line)
                    require(row.path("schema").asText() == RETRIEVAL_EVALUATION_SCHEMA) {
                        "private retrieval benchmark schema is invalid at line ${index + 1}"
                    }
                    require(row.fieldNames().asSequence().toSet() == RETRIEVAL_EVALUATION_FIELDS) {
                        "private retrieval benchmark fields are invalid at line ${index + 1}"
                    }
                    val probeId = row.path("probe_id").asText()
                    require(probeId.matches(PROBE_ID)) { "private retrieval benchmark probe id is invalid at line ${index + 1}" }
                    require(seenProbeIds.add(probeId)) { "private retrieval benchmark probe id is duplicated" }
                    val candidateDigest = row.path("candidate_jsonl_sha256").asText()
                    require(candidateDigest.matches(SHA256_HEX)) {
                        "private retrieval benchmark candidate digest is invalid at line ${index + 1}"
                    }
                    val turns = readRecentTurns(row, index + 1, "private retrieval benchmark")
                    val acceptedExampleIds =
                        row
                            .path("accepted_example_ids")
                            .map { it.asText() }
                            .toSet()
                    require(acceptedExampleIds.all { it.matches(EXAMPLE_ID) }) {
                        "private retrieval benchmark accepted example ids are invalid at line ${index + 1}"
                    }
                    val verdict = row.path("verdict").asText()
                    require(verdict in RETRIEVAL_EVALUATION_VERDICTS) {
                        "private retrieval benchmark verdict is invalid at line ${index + 1}"
                    }
                    val reasonCode = row.path("reason_code").asText().trim()
                    require(reasonCode.matches(REASON_CODE)) {
                        "private retrieval benchmark reason code is invalid at line ${index + 1}"
                    }
                    require(
                        (verdict == NO_ACCEPTABLE_VERDICT) == acceptedExampleIds.isEmpty(),
                    ) {
                        "private retrieval benchmark verdict and accepted example ids disagree at line ${index + 1}"
                    }
                    val speechIntent = row.path("speech_intent").asText().trim()
                    require(speechIntent.isNotEmpty()) { "private retrieval benchmark speech intent is invalid at line ${index + 1}" }
                    RetrievalQualityCase(
                        probeId = probeId,
                        candidateDigest = candidateDigest,
                        expectedMode = readResponseMode(row.path("response_mode").asText(), index + 1, "private retrieval benchmark"),
                        socialAct = readSocialAct(row.path("social_act").asText(), index + 1, "private retrieval benchmark"),
                        recentTurns = turns,
                        speechIntent = speechIntent,
                        verdict = verdict,
                        acceptedExampleIds = acceptedExampleIds,
                    )
                }
        }

        private fun readIndependentHoldoutCases(path: Path): List<IndependentHoldoutCase> {
            val mapper = jacksonObjectMapper()
            val seenProbeIds = mutableSetOf<String>()
            return Files
                .readAllLines(path)
                .filter(String::isNotBlank)
                .mapIndexed { index, line ->
                    val row = mapper.readTree(line)
                    val lineNumber = index + 1
                    require(row.path("schema").asText() == INDEPENDENT_HOLDOUT_SCHEMA) {
                        "independent retrieval holdout schema is invalid at line $lineNumber"
                    }
                    require(row.fieldNames().asSequence().toSet() == INDEPENDENT_HOLDOUT_FIELDS) {
                        "independent retrieval holdout fields are invalid at line $lineNumber"
                    }
                    val probeId = row.path("probe_id").asText()
                    require(probeId.matches(INDEPENDENT_HOLDOUT_PROBE_ID)) {
                        "independent retrieval holdout probe id is invalid at line $lineNumber"
                    }
                    require(seenProbeIds.add(probeId)) {
                        "independent retrieval holdout probe id is duplicated"
                    }
                    val speechIntent = row.path("speech_intent").asText().trim()
                    val focus = row.path("focus").asText().trim()
                    require(speechIntent.isNotEmpty() && focus.isNotEmpty()) {
                        "independent retrieval holdout metadata is invalid at line $lineNumber"
                    }
                    IndependentHoldoutCase(
                        probeId = probeId,
                        expectedMode = readResponseMode(row.path("response_mode").asText(), lineNumber, "independent retrieval holdout"),
                        socialAct = readSocialAct(row.path("social_act").asText(), lineNumber, "independent retrieval holdout"),
                        recentTurns = readRecentTurns(row, lineNumber, "independent retrieval holdout"),
                        speechIntent = speechIntent,
                        focus = focus,
                    )
                }
        }

        private fun assertIndependentHoldoutShape(holdouts: List<IndependentHoldoutCase>) {
            require(holdouts.size == INDEPENDENT_HOLDOUT_CASES) {
                "independent retrieval holdout must contain exactly $INDEPENDENT_HOLDOUT_CASES cases"
            }
            val expectedProbeIds = (1..INDEPENDENT_HOLDOUT_CASES).map { "holdout-v1-%04d".format(Locale.ROOT, it) }.toSet()
            require(holdouts.map(IndependentHoldoutCase::probeId).toSet() == expectedProbeIds) {
                "independent retrieval holdout probe coverage is incomplete"
            }
            HumanSpeechResponseMode.entries.forEach { mode ->
                require(holdouts.count { it.expectedMode == mode } == INDEPENDENT_HOLDOUT_CASES_PER_MODE) {
                    "independent retrieval holdout response mode coverage is invalid for $mode"
                }
            }
        }

        private fun readRecentTurns(
            row: com.fasterxml.jackson.databind.JsonNode,
            lineNumber: Int,
            label: String,
        ): List<ConversationTurn> {
            val turns =
                row.path("recent_turns").map { turn ->
                    val speaker = turn.path("speaker").asText().trim()
                    val text = turn.path("text").asText().trim()
                    require(speaker.isNotEmpty() && text.isNotEmpty()) {
                        "$label turn is invalid at line $lineNumber"
                    }
                    ConversationTurn(speaker, text)
                }
            require(turns.isNotEmpty() && turns.size <= MAX_RECENT_TURNS) {
                "$label turn count is invalid at line $lineNumber"
            }
            return turns
        }

        private fun readResponseMode(
            value: String,
            lineNumber: Int,
            label: String,
        ): HumanSpeechResponseMode =
            HumanSpeechResponseMode.entries.singleOrNull { it.name == value }
                ?: throw IllegalArgumentException("$label response mode is invalid at line $lineNumber")

        private fun readSocialAct(
            value: String,
            lineNumber: Int,
            label: String,
        ): SpeechSocialAct =
            SpeechSocialAct.entries.singleOrNull { it.name == value }
                ?: throw IllegalArgumentException("$label social act is invalid at line $lineNumber")

        private fun assertEvaluationCasesAreBoundToCandidate(
            evaluationCases: List<RetrievalQualityCase>,
            candidateDigest: String,
        ) {
            require(evaluationCases.all { it.candidateDigest == candidateDigest }) {
                "private retrieval benchmark is bound to a different candidate"
            }
            HumanSpeechResponseMode.entries.forEach { responseMode ->
                require(evaluationCases.count { it.expectedMode == responseMode } >= MIN_EVALUATION_CASES_PER_MODE) {
                    "private retrieval benchmark response-mode coverage is insufficient for $responseMode"
                }
            }
        }

        private companion object {
            const val RETRIEVAL_EVALUATION_SCHEMA = "nia-human-speech-style-retrieval-eval.v2"
            const val INDEPENDENT_HOLDOUT_SCHEMA = "nia-human-speech-style-independent-holdout.v1"
            const val INDEPENDENT_HOLDOUT_ENV = "NIA_PRIVATE_HUMAN_STYLE_INDEPENDENT_HOLDOUT_FILE"
            const val RETRIEVAL_AUDIT_FILE_ENV = "NIA_PRIVATE_HUMAN_STYLE_RETRIEVAL_AUDIT_FILE"
            const val BLIND_REVIEW_BUNDLE_FILE_ENV = "NIA_PRIVATE_HUMAN_STYLE_BLIND_REVIEW_BUNDLE_FILE"
            const val RETRIEVAL_AUDIT_SCHEMA = "nia-human-speech-style-retrieval-audit.v4"
            const val RETRIEVAL_POLICY = "closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11"
            const val EMBEDDING_PROVIDER = "openai"
            const val EMBEDDING_MODEL = "text-embedding-3-small"
            const val EXECUTION_SCOPE = "ephemeral_h2_only_no_judge_no_discord_no_provider_generation"
            const val MIN_EVALUATION_CASES = 30
            const val MIN_EVALUATION_CASES_PER_MODE = 4
            const val MIN_ACCEPTABLE_REFERENCE_CASES = 24
            const val MIN_HIT_AT_ONE = 0.70
            const val MIN_HIT_AT_TWO = 0.90
            const val MIN_SELECTION_COVERAGE = 0.95
            const val MIN_INDEPENDENT_HOLDOUT_REFERENCE_COVERAGE = 0.80
            const val MIN_RETRIEVAL_SCORE = 0.28
            const val RUNTIME_QUERY_EMBEDDING_COUNT = 2
            const val INDEPENDENT_HOLDOUT_CASES = 35
            const val INDEPENDENT_HOLDOUT_CASES_PER_MODE = 5
            const val BLIND_REVIEW_CASES = 70
            const val BLIND_REVIEW_CASES_PER_MODE = 10
            const val MAX_RECENT_TURNS = 6
            const val NO_ACCEPTABLE_VERDICT = "NO_ACCEPTABLE"
            val PROBE_ID = Regex("human-style-eval-v2-[0-9]{4}")
            val INDEPENDENT_HOLDOUT_PROBE_ID = Regex("holdout-v1-[0-9]{4}")
            val EXAMPLE_ID = Regex("human-style-[0-9]{6}")
            val REASON_CODE = Regex("[a-z0-9_]{1,96}")
            val SHA256_HEX = Regex("[0-9a-f]{64}")
            val RETRIEVAL_EVALUATION_FIELDS =
                setOf(
                    "schema",
                    "candidate_jsonl_sha256",
                    "probe_id",
                    "response_mode",
                    "social_act",
                    "recent_turns",
                    "speech_intent",
                    "verdict",
                    "accepted_example_ids",
                    "reason_code",
                )
            val INDEPENDENT_HOLDOUT_FIELDS =
                setOf(
                    "schema",
                    "probe_id",
                    "response_mode",
                    "social_act",
                    "recent_turns",
                    "speech_intent",
                    "focus",
                )
            val RETRIEVAL_EVALUATION_VERDICTS =
                setOf(
                    "ACCEPT_TOP1",
                    "ACCEPT_TOP2_FALLBACK",
                    NO_ACCEPTABLE_VERDICT,
                )

            val retrievalProbes =
                buildList {
                    addAll(
                        probes(
                            HumanSpeechResponseMode.REACTION,
                            SpeechSocialAct.UNKNOWN,
                            "방금 그거 봤어? 진짜 웃기다" to "뜻밖의 이야기를 듣고 짧은 놀람이나 웃음으로 바로 반응한다",
                            "나 이거 드디어 샀어" to "상대의 반가운 소식에 짧은 감탄을 먼저 둔다",
                            "헐 이게 된다고?" to "흥미로운 말을 들은 직후 짧고 가볍게 반응한다",
                            "아니 방금 뭐임ㅋㅋ" to "예상 못 한 일을 들은 직후 짧은 놀람으로 반응한다",
                            "와 이걸 해냈네" to "상대의 반가운 결과에 가볍게 감탄한다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.ALIGNMENT,
                            SpeechSocialAct.ACKNOWLEDGE,
                            "오늘 사람 너무 많아서 기빨린다" to "상대의 가벼운 불평 분위기에 맞춰 공감하고 내 감각을 짧게 보탠다",
                            "이 비 언제 그치냐 너무 싫다" to "불편하다는 말에 동의한 뒤 내 불만도 짧게 보탠다",
                            "오늘 수업 진짜 길게 느껴짐" to "처진 분위기에 맞장구치고 같은 감각을 짧게 덧붙인다",
                            "버스 왜 이렇게 안 와" to "일상적인 불편에 같은 편으로 짧게 맞장구친다",
                            "나 오늘 진짜 집중 안됨" to "처진 기분을 과장하지 않고 같이 받아 준다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.PLAY,
                            SpeechSocialAct.TEASE,
                            "내가 오늘은 너 이길 듯" to "가벼운 자신감 표현을 장난스럽게 받아쳐 티키타카를 이어 간다",
                            "너 오늘 왜 이렇게 착함" to "친한 사이의 가벼운 놀림을 과하지 않게 되받는다",
                            "나 오늘 너무 일찍 일어났어" to "사소한 말을 가벼운 과장이나 농담으로 이어 간다",
                            "ㅋㅋ 너답다 진짜" to "친한 사이의 가벼운 놀림을 짧게 이어 간다",
                            "내가 더 빠름" to "가벼운 경쟁을 부담 없이 장난으로 받는다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.FOLLOW_UP,
                            SpeechSocialAct.ASK,
                            "나 오늘 병원 다녀왔어" to "상대 상태를 단정하지 않고 필요한 부분을 짧게 더 묻는다",
                            "아까 말한 일 결국 어떻게 됐어?" to "진행 중인 일을 자연스럽게 확인하는 질문을 짧게 잇는다",
                            "나 일정이 갑자기 바뀜" to "이유나 바뀐 내용을 짧게 확인하며 대화를 잇는다",
                            "근데 그건 왜 그렇게 된 거야?" to "말한 일의 원인이나 경과를 부담 없이 더 확인한다",
                            "어디가 아픈데?" to "상대 상태를 단정하지 않고 필요한 부분만 묻는다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.SPECULATION,
                            SpeechSocialAct.ASK,
                            "왜 아직 답이 없지?" to "확실하지 않은 이유를 단정하지 않고 가능성으로 가볍게 짐작한다",
                            "내일 비 올까?" to "모르는 앞으로의 일을 확신 없이 조심스럽게 추측한다",
                            "그 사람 오늘 안 올 것 같아" to "상대의 불확실한 판단에 가능성을 남기는 말투로 짧게 반응한다",
                            "걔 지금 자고 있나" to "알 수 없는 현재 상황을 단정하지 않고 짐작한다",
                            "아마 늦는 거 아닐까" to "확신 없는 가능성을 가볍게 이어 말한다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.CARE,
                            SpeechSocialAct.ACKNOWLEDGE,
                            "오늘 머리가 너무 아파서 아무것도 못 하겠다" to "아프거나 힘든 상태를 들었을 때 조언보다 부담 없는 돌봄을 먼저 둔다",
                            "요즘 너무 지친다" to "기운 없는 말을 들으면 과장하지 않고 상태를 짧게 챙긴다",
                            "잠을 거의 못 잤어" to "피곤한 상태에 압박 없는 걱정과 돌봄으로 반응한다",
                            "감기 기운 있어서 누워있음" to "가볍게 아픈 상태를 들으면 부담 없이 챙긴다",
                            "나 오늘 너무 예민해" to "힘든 기분을 먼저 받아 주고 과한 조언을 피한다",
                        ),
                    )
                    addAll(
                        probes(
                            HumanSpeechResponseMode.COORDINATION,
                            SpeechSocialAct.CHANGE_TOPIC,
                            "우리 저녁 뭐 먹을까" to "선택지를 같이 좁혀 다음 행동을 짧게 정한다",
                            "내일 몇 시에 만날래?" to "약속의 시간과 다음 행동을 가볍게 조율한다",
                            "지금 갈까 조금 있다 갈까?" to "실행할 선택을 함께 정하도록 현실적인 대안을 짧게 제안한다",
                            "그럼 누가 먼저 할래?" to "함께 할 일의 순서나 역할을 가볍게 조율한다",
                            "주말에 시간 되는 날 있어?" to "가능한 일정과 다음 행동을 짧게 맞춘다",
                        ),
                    )
                }

            private fun probes(
                expectedMode: HumanSpeechResponseMode,
                socialAct: SpeechSocialAct,
                vararg scenes: Pair<String, String>,
            ): List<RetrievalProbe> =
                scenes.map { (memberMessage, intentSummary) ->
                    probe(
                        expectedMode = expectedMode,
                        socialAct = socialAct,
                        memberMessage = memberMessage,
                        intentSummary = intentSummary,
                    )
                }

            private fun probe(
                expectedMode: HumanSpeechResponseMode,
                socialAct: SpeechSocialAct,
                memberMessage: String,
                intentSummary: String,
            ): RetrievalProbe =
                RetrievalProbe(
                    expectedMode = expectedMode,
                    socialAct = socialAct,
                    memberMessage = memberMessage,
                    speechIntent = "reason_code=live_rag_eval; intent_summary=$intentSummary; scene_direction=$intentSummary",
                )
        }
    }
