package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.speech.humanstyle.example
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

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
            store.replaceAll(listOf(example("human-style-000001", responseText = responseText)))
            rows.flush()

            val storedPayload = jdbc.queryForObject("SELECT payload_json FROM nia_human_speech_style_example", String::class.java)
            val storedEmbedding = jdbc.queryForObject("SELECT embedding_json FROM nia_human_speech_style_example", String::class.java)
            val reloaded = store.listEnabled().single()

            assertThat(rows.count()).isEqualTo(1)
            assertThat(storedPayload).startsWith("enc1:").doesNotContain(responseText)
            assertThat(storedEmbedding).startsWith("enc1:")
            assertThat(reloaded.responseBubbles.single().text).isEqualTo(responseText)
            assertThat(reloaded.embedding).containsExactly(1f, 0f)
        }

        @Test
        fun `암호화 키가 없으면 카드 내용을 읽지 않는다`() {
            store.replaceAll(listOf(example("human-style-000001")))
            FieldCrypto.configure(null)

            assertThat(store.listEnabled()).isEmpty()
        }
    }
