package com.discordassistant.central.speech.application

import com.discordassistant.central.speech.application.port.out.FactualKnowledgePort
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P15-T012 조건부 knowledge RAG 연결자 acceptance 단위 테스트.
 *
 * 핵심 acceptance: **잡담·반응 후보는 BM25/web search 미실행** — ASK/CORRECT 만 검색한다.
 */
class ConditionalKnowledgeConnectorTest {
    @Test
    fun `acceptance — 잡담·반응성 발화는 knowledge 포트를 호출하지 않는다`() {
        val nonFactual =
            listOf(
                SpeechSocialAct.ACKNOWLEDGE,
                SpeechSocialAct.AGREE,
                SpeechSocialAct.DISAGREE,
                SpeechSocialAct.TEASE,
                SpeechSocialAct.SELF_DISCLOSE,
                SpeechSocialAct.CHANGE_TOPIC,
                SpeechSocialAct.UNKNOWN,
            )
        for (act in nonFactual) {
            val port = CountingKnowledge()
            val result = ConditionalKnowledgeConnector(port).retrieveIfFactual(act, guildId = 7L, query = "x")
            assertThat(port.calls).withFailMessage("%s must not search", act).isZero()
            assertThat(result.retrieved).isFalse()
            assertThat(result.snippets).isEmpty()
        }
    }

    @Test
    fun `acceptance — ASK·ANSWER·CORRECT 는 knowledge 검색을 실행한다`() {
        for (act in listOf(SpeechSocialAct.ASK, SpeechSocialAct.ANSWER, SpeechSocialAct.CORRECT)) {
            val port = CountingKnowledge(listOf("사실 스니펫"))
            val result = ConditionalKnowledgeConnector(port).retrieveIfFactual(act, guildId = 7L, query = "오늘 날씨")
            assertThat(port.calls).withFailMessage("%s must search", act).isEqualTo(1)
            assertThat(result.retrieved).isTrue()
            assertThat(result.snippets).containsExactly("사실 스니펫")
        }
    }

    private class CountingKnowledge(
        private val result: List<String> = emptyList(),
    ) : FactualKnowledgePort {
        var calls = 0

        override fun retrieve(
            guildId: Long,
            query: String,
        ): List<String> {
            calls++
            return result
        }
    }
}
