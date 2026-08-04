package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.platform.openai.OpenAiSpeechStyleEmbeddingAdapter
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStylePromptRenderer
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagService
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.generation.SpeechGenerationFixtures
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

/**
 * 로컬에서만 opt-in하는 private corpus 검증이다. CI에는 source file/시스템 속성이 없으므로 실행되지 않는다.
 *
 * 전체 private JSONL의 retrieval fields만 embedding API에 보내고, 하나의 현재 장면을 검색한 결과가 Speech payload에는
 * 붙되 trace에는 붙지 않는지를 검증한다. Discord/Judge/생성 모델 호출은 하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(JpaHumanSpeechStyleExampleStore::class)
@EnabledIfEnvironmentVariable(named = "NIA_PRIVATE_CORPUS_FILE", matches = ".+")
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
        fun `private human review corpus imports embeds retrieves and stays out of traces`() {
            val sourceFile = Path.of(System.getenv("NIA_PRIVATE_CORPUS_FILE"))
            val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
            assumeTrue(Files.isRegularFile(sourceFile), "private corpus JSONL is unavailable")
            assumeTrue(apiKey.isNotBlank(), "OPENAI_API_KEY is unavailable")

            val embedding =
                OpenAiSpeechStyleEmbeddingAdapter(
                    apiKey = apiKey,
                    baseUrl = System.getenv("OPENAI_BASE_URL").orEmpty().ifBlank { "https://api.openai.com/v1" },
                    model = "text-embedding-3-small",
                    timeoutSeconds = 20,
                )
            val imported = HumanSpeechStyleRagImportService(store, embedding).importJsonLines(sourceFile)
            val rag = HumanSpeechStyleRagService(store, embedding)
            val packet =
                SpeechGenerationFixtures.packet(
                    socialAct = SpeechSocialAct.ACKNOWLEDGE,
                    styleResponseMode = HumanSpeechResponseMode.ALIGNMENT,
                    turns = listOf(ConversationTurn("member", "오늘 분위기가 좀 답답하네")),
                    speechIntent = "상대의 가벼운 불평 분위기에 맞춰 짧게 반응한다",
                )

            val selection = rag.retrieve(packet)
            val payload = HumanSpeechStylePromptRenderer().appendTo("현재 장면", selection)
            val importedExamples = store.listEnabled()
            val rawPayload = jdbc.queryForObject("SELECT payload_json FROM nia_human_speech_style_example LIMIT 1", String::class.java)

            assertThat(imported.importedCount).isEqualTo(Files.readAllLines(sourceFile).count(String::isNotBlank))
            assertThat(importedExamples).hasSize(imported.importedCount)
            assertThat(importedExamples.map { it.embeddingModel }.toSet()).containsExactly("text-embedding-3-small")
            assertThat(importedExamples.map { it.embedding.size }.toSet()).containsExactly(1_536)
            assertThat(selection.matches).isNotEmpty().hasSizeLessThanOrEqualTo(2)
            assertThat(payload.providerUserPrompt).contains("사람 말투 참고 예시")
            assertThat(payload.traceUserPrompt).contains("private human-style examples omitted")
            assertThat(rawPayload).startsWith("enc1:")
        }

        @Test
        fun `private corpus retrieves the expected response mode for unseen everyday scenes`() {
            val sourceFile = Path.of(System.getenv("NIA_PRIVATE_CORPUS_FILE"))
            val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
            assumeTrue(Files.isRegularFile(sourceFile), "private corpus JSONL is unavailable")
            assumeTrue(apiKey.isNotBlank(), "OPENAI_API_KEY is unavailable")

            val embedding =
                OpenAiSpeechStyleEmbeddingAdapter(
                    apiKey = apiKey,
                    baseUrl = System.getenv("OPENAI_BASE_URL").orEmpty().ifBlank { "https://api.openai.com/v1" },
                    model = "text-embedding-3-small",
                    timeoutSeconds = 20,
                )
            HumanSpeechStyleRagImportService(store, embedding).importJsonLines(sourceFile)
            val rag = HumanSpeechStyleRagService(store, embedding)
            val results =
                retrievalProbes.map { probe ->
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
                        id = probe.id,
                        expectedMode = probe.expectedMode,
                        returnedModes = selection.matches.map { it.example.responseMode },
                        returnedExampleIds = selection.matches.map { it.example.exampleId },
                    )
                }
            val hitAtOne = results.count { it.returnedModes.firstOrNull() == it.expectedMode }
            val hitAtTwo = results.count { it.expectedMode in it.returnedModes }
            val missedModes = results.filterNot { it.expectedMode in it.returnedModes }.map(RetrievalResult::expectedMode)
            val byMode =
                HumanSpeechResponseMode.entries.joinToString(",") { mode ->
                    val matches = results.count { it.expectedMode == mode && mode in it.returnedModes }
                    val probes = results.count { it.expectedMode == mode }
                    "$mode:$matches/$probes"
                }

            println("LIVE_HUMAN_SPEECH_STYLE_RAG probes=${results.size} hit_at_1=$hitAtOne hit_at_2=$hitAtTwo by_mode=$byMode")
            println(
                "LIVE_HUMAN_SPEECH_STYLE_RAG_DETAILS " +
                    results.joinToString("|") { result ->
                        "${result.id}:${result.expectedMode}>${result.returnedModes.joinToString("/")}" +
                            " examples=${result.returnedExampleIds.joinToString("/")}"
                    },
            )
            assertThat(results).allSatisfy { result ->
                assertThat(result.returnedModes).isNotEmpty().hasSizeLessThanOrEqualTo(2)
            }
            assertThat(missedModes)
                .withFailMessage("Expected response mode was absent from top-2 for: $missedModes")
                .isEmpty()
            assertThat(hitAtOne)
                .withFailMessage("Expected response mode was absent from top-1 for ${results.size - hitAtOne}/${results.size} probes")
                .isEqualTo(results.size)
        }

        private data class RetrievalProbe(
            val id: String,
            val expectedMode: HumanSpeechResponseMode,
            val socialAct: SpeechSocialAct,
            val memberMessage: String,
            val speechIntent: String,
        )

        private data class RetrievalResult(
            val id: String,
            val expectedMode: HumanSpeechResponseMode,
            val returnedModes: List<HumanSpeechResponseMode>,
            val returnedExampleIds: List<String>,
        )

        private companion object {
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
                scenes.mapIndexed { index, (memberMessage, intentSummary) ->
                    probe(
                        id = "${expectedMode}_${index + 1}",
                        expectedMode = expectedMode,
                        socialAct = socialAct,
                        memberMessage = memberMessage,
                        intentSummary = intentSummary,
                    )
                }

            private fun probe(
                id: String,
                expectedMode: HumanSpeechResponseMode,
                socialAct: SpeechSocialAct,
                memberMessage: String,
                intentSummary: String,
            ): RetrievalProbe =
                RetrievalProbe(
                    id = id,
                    expectedMode = expectedMode,
                    socialAct = socialAct,
                    memberMessage = memberMessage,
                    speechIntent = "reason_code=live_rag_eval; intent_summary=$intentSummary; scene_direction=$intentSummary",
                )
        }
    }
