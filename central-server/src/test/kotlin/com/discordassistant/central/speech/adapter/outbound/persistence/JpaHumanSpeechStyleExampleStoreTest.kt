package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStylePromptSurface
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseForm
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMoveProvenance
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.discordassistant.central.speech.humanstyle.example
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(JpaHumanSpeechStyleExampleStore::class)
class JpaHumanSpeechStyleExampleStoreTest
    @Autowired
    constructor(
        private val store: JpaHumanSpeechStyleExampleStore,
        private val rows: HumanSpeechStyleExampleRepository,
        private val jdbc: JdbcTemplate,
    ) {
        @BeforeEach
        fun configureEncryption() {
            FieldCrypto.configure("human-speech-style-test-key")
        }

        @AfterEach
        fun clearEncryption() {
            FieldCrypto.configure(null)
        }

        @Test
        fun `사람 말투 카드와 벡터는 암호화해 저장하고 읽을 때만 복호화한다`() {
            val responseText = "private-style-response-marker-12345"
            store.replaceAll(
                listOf(
                    example("human-style-000001", responseText = responseText).copy(
                        promptSurface = HumanSpeechStylePromptSurface.STYLE_PATTERN,
                        sceneTraits = listOf(HumanSpeechSceneTrait.ALIGNMENT_COMPLAINT_OR_LOW_ENERGY),
                        providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING),
                        responseMoveProvenance = HumanSpeechStyleResponseMoveProvenance.FRESH_VERIFIED,
                    ),
                ),
            )
            rows.flush()

            val storedPayload = jdbc.queryForObject("SELECT payload_json FROM nia_human_speech_style_example", String::class.java)
            val storedEmbedding = jdbc.queryForObject("SELECT embedding_json FROM nia_human_speech_style_example", String::class.java)
            val reloaded = store.listEnabled().single()

            assertThat(rows.count()).isEqualTo(1)
            assertThat(storedPayload).startsWith("enc1:").doesNotContain(responseText)
            assertThat(storedEmbedding).startsWith("enc1:")
            assertThat(reloaded.responseBubbles.single().text).isEqualTo(responseText)
            assertThat(reloaded.embedding).containsExactly(1f, 0f)
            assertThat(reloaded.responseMove).isEqualTo(HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT)
            assertThat(reloaded.responseMoveProvenance).isEqualTo(HumanSpeechStyleResponseMoveProvenance.FRESH_VERIFIED)
            assertThat(reloaded.sceneTraits).containsExactly(HumanSpeechSceneTrait.ALIGNMENT_COMPLAINT_OR_LOW_ENERGY)
            assertThat(reloaded.providerStyleCues).containsExactly(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING)
            assertThat(reloaded.responseForm).isEqualTo(HumanSpeechStyleResponseForm.ALIGN_AND_ADD)
            assertThat(reloaded.responseRhythm)
                .containsExactly(HumanSpeechStyleRhythmCue.AGREE_AND_ADD, HumanSpeechStyleRhythmCue.SINGLE_BUBBLE)
            assertThat(reloaded.promptSurface).isEqualTo(HumanSpeechStylePromptSurface.STYLE_PATTERN)
        }

        @Test
        fun `legacy raw surface와 이전의 복수 style cue는 암호화 audit row로 남아도 runtime 검색 목록에서는 제외된다`() {
            val now = Instant.now()
            rows.saveAndFlush(
                HumanSpeechStyleExampleEntity(
                    exampleId = "human-style-000001",
                    responseMode = "ALIGNMENT",
                    quality = "CURATION_APPROVED",
                    sourceFingerprint = "sha256:" + "a".repeat(64),
                    consentRevision = "2026-08-04-curation-approved",
                    combinedChars = 60,
                    enabled = true,
                    payloadJson =
                        jacksonObjectMapper().writeValueAsString(
                            mapOf(
                                "situation" to "가벼운 불평에 맞장구친다",
                                "styleSignals" to listOf("짧게"),
                                "contextBubbles" to listOf(mapOf("speaker" to "가명1", "text" to "답답하네")),
                                "responseBubbles" to listOf(mapOf("speaker" to "가명2", "text" to "그러게")),
                                "promptSurface" to "CONTEXT_RESPONSE_PAIR",
                                "providerStyleCues" to listOf("ALIGNMENT_LOW_KEY_ACK", "ALIGNMENT_SHARED_FEELING"),
                            ),
                        ),
                    embeddingJson = jacksonObjectMapper().writeValueAsString(listOf(1f, 0f)),
                    embeddingModel = "text-embedding-3-small",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val storedPayload = jdbc.queryForObject("SELECT payload_json FROM nia_human_speech_style_example", String::class.java)

            assertThat(rows.count()).isEqualTo(1)
            assertThat(storedPayload).startsWith("enc1:")
            assertThat(store.listEnabled()).isEmpty()
        }

        @Test
        fun `암호화 키가 없으면 카드 내용을 읽지 않는다`() {
            store.replaceAll(listOf(example("human-style-000001")))
            FieldCrypto.configure(null)

            assertThat(store.listEnabled()).isEmpty()
        }

        @Test
        fun `비활성 카드는 암호화된 감사 저장소에는 남지만 검색 목록에서는 제외된다`() {
            store.replaceAll(
                listOf(
                    example("human-style-000001").copy(
                        promptEligible = false,
                        promptSurface = HumanSpeechStylePromptSurface.AUDIT_ONLY,
                        providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING),
                    ),
                ),
            )

            assertThat(rows.count()).isEqualTo(1)
            assertThat(store.listEnabled()).isEmpty()
        }

        @Test
        fun `보조 반응 metadata가 없는 활성 카드는 같은 enum 검색 목록에 남는다`() {
            store.replaceAll(
                listOf(
                    example("human-style-000001").copy(
                        responseMove = null,
                        responseMoveProvenance = HumanSpeechStyleResponseMoveProvenance.NONE,
                        responseForm = null,
                        responseRhythm = emptyList(),
                        providerStyleCues = listOf(HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK),
                    ),
                ),
            )

            val reloaded = store.listEnabled().single()

            assertThat(reloaded.promptEligible).isTrue()
            assertThat(reloaded.responseMove).isNull()
            assertThat(reloaded.responseForm).isNull()
            assertThat(reloaded.responseRhythm).isEmpty()
        }

        @Test
        fun `provenance 없는 legacy payload는 response move를 runtime에서 지운다`() {
            val now = Instant.now()
            rows.saveAndFlush(
                HumanSpeechStyleExampleEntity(
                    exampleId = "human-style-000001",
                    responseMode = "ALIGNMENT",
                    quality = "CURATION_APPROVED",
                    sourceFingerprint = "sha256:" + "a".repeat(64),
                    consentRevision = "2026-08-04-curation-approved",
                    combinedChars = 60,
                    enabled = true,
                    payloadJson =
                        jacksonObjectMapper().writeValueAsString(
                            mapOf(
                                "situation" to "가벼운 불평에 맞장구친다",
                                "styleSignals" to listOf("짧게"),
                                "contextBubbles" to listOf(mapOf("speaker" to "가명1", "text" to "답답하네")),
                                "responseBubbles" to listOf(mapOf("speaker" to "가명2", "text" to "그러게")),
                                "promptSurface" to "STYLE_PATTERN",
                                "providerStyleCues" to listOf("ALIGNMENT_LOW_KEY_ACK"),
                                "responseMove" to "ALIGNMENT_COMPLAINT",
                                "responseForm" to "ALIGN_AND_ADD",
                                "responseRhythm" to listOf("AGREE_AND_ADD", "SINGLE_BUBBLE"),
                                "rhythmEmbedding" to listOf(1f, 0f),
                            ),
                        ),
                    embeddingJson = jacksonObjectMapper().writeValueAsString(listOf(1f, 0f)),
                    embeddingModel = "text-embedding-3-small",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val reloaded = store.listEnabled().single()

            assertThat(reloaded.responseMove).isNull()
            assertThat(reloaded.responseMoveProvenance).isEqualTo(HumanSpeechStyleResponseMoveProvenance.FRESH_REJECTED)
            assertThat(reloaded.sceneTraits).isEmpty()
        }
    }
