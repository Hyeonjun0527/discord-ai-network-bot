package com.discordassistant.central.requestlog

import com.discordassistant.central.requestlog.application.GlmRequestAuditFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T012: 외부 GLM 요청 감사 hash — 원문 미저장, 사후 범위 감사 가능. */
class GlmRequestAuditTest {
    private val factory = GlmRequestAuditFactory()

    @Test
    fun `record stores hash and metadata but not raw payload`() {
        val payload = "focus_thread=thread_1\nsocial_act=ACKNOWLEDGE"
        val record =
            factory.create(
                payload = payload,
                fieldNames = listOf("social_act", "focus_thread"),
                model = "glm-5.1",
                purpose = "NEXA_SPEECH",
                consentSnapshotId = "consent_42",
            )
        // 원문은 어디에도 보관되지 않는다 — hash·필드 목록·메타만.
        assertThat(record.payloadHash).hasSize(64) // SHA-256 hex.
        assertThat(record.payloadHash).doesNotContain("thread_1")
        assertThat(record.fieldNames).containsExactly("focus_thread", "social_act") // 정렬됨.
        assertThat(record.model).isEqualTo("glm-5.1")
        assertThat(record.consentSnapshotId).isEqualTo("consent_42")
    }

    @Test
    fun `same canonical payload yields same hash (post-hoc scope audit)`() {
        val a = factory.canonicalHash("a=1\nb=2")
        val b = factory.canonicalHash("a=1\r\nb=2  ") // CRLF·trailing 공백 정규화.
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different payload yields different hash`() {
        assertThat(factory.canonicalHash("a=1")).isNotEqualTo(factory.canonicalHash("a=2"))
    }
}
