package com.discordassistant.central.speech.routing

import com.discordassistant.central.speech.adapter.outbound.routing.SpeechCandidateResponseParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T012: GLM 응답 schema parser — malformed는 안전한 실패(빈 목록). */
class SpeechCandidateResponseParserTest {
    private val mapper = ObjectMapper()

    @Test
    fun `parses candidate bubbles style tags and uncertainty`() {
        val body =
            """
            {"candidates":[
              {"bubbles":["안녕","오늘 뭐해?"],"style_tags":["casual","warm"],"uncertainty":0.2},
              {"bubbles":["ㅎㅇ"],"style_tags":[],"uncertainty":0.5}
            ]}
            """.trimIndent()
        val candidates = SpeechCandidateResponseParser.parse(body, mapper, "cand")
        assertThat(candidates).hasSize(2)
        assertThat(candidates[0].bubbles).containsExactly("안녕", "오늘 뭐해?")
        assertThat(candidates[0].styleTags).containsExactly("casual", "warm")
        assertThat(candidates[0].uncertainty).isEqualTo(0.2)
        assertThat(candidates[0].candidateId).isEqualTo("cand-0")
    }

    @Test
    fun `strips code fences`() {
        val body = "```json\n{\"candidates\":[{\"bubbles\":[\"hi\"]}]}\n```"
        val candidates = SpeechCandidateResponseParser.parse(body, mapper, "c")
        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].bubbles).containsExactly("hi")
    }

    @Test
    fun `malformed json yields empty list (safe failure)`() {
        assertThat(SpeechCandidateResponseParser.parse("not json at all", mapper, "c")).isEmpty()
        assertThat(SpeechCandidateResponseParser.parse("", mapper, "c")).isEmpty()
        assertThat(SpeechCandidateResponseParser.parse("{\"oops\":1}", mapper, "c")).isEmpty()
    }

    @Test
    fun `candidate with no non-blank bubbles is dropped`() {
        val body = """{"candidates":[{"bubbles":["","   "]},{"bubbles":["ok"]}]}"""
        val candidates = SpeechCandidateResponseParser.parse(body, mapper, "c")
        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].bubbles).containsExactly("ok")
    }

    @Test
    fun `out of range uncertainty is clamped`() {
        val body = """{"candidates":[{"bubbles":["x"],"uncertainty":5.0}]}"""
        val candidates = SpeechCandidateResponseParser.parse(body, mapper, "c")
        assertThat(candidates[0].uncertainty).isEqualTo(1.0)
    }
}
