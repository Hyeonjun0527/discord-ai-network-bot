package com.discordassistant.central.speech.privacy

import com.discordassistant.central.speech.application.privacy.ExternalPayloadAllowlistSerializer
import com.discordassistant.central.speech.application.privacy.ExternalPayloadMinimizer
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.MemoryRef
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T004: GLM payload allowlist serializer — deny-by-default, 새 필드 자동 미포함. */
class ExternalPayloadAllowlistSerializerTest {
    private val serializer = ExternalPayloadAllowlistSerializer()

    private fun packet(
        turns: List<ConversationTurn> = listOf(ConversationTurn("user_3", "안녕")),
        refs: List<MemoryRef> = emptyList(),
    ) = SpeechScenePacket.of(
        focusThreadKey = "thread_pseudo_1",
        target = SpeechTarget.member("user_3"),
        recentTurns = turns,
        socialAct = SpeechSocialAct.ACKNOWLEDGE,
        burstShape = SpeechBurstShape(1, 280, false),
        identity = IdentityKernelSection.of("니아", "[시스템 지침] 당신은 「니아」 예요."),
        memoryRefs = refs,
    )

    @Test
    fun `payload only contains allowlisted field keys`() {
        val payload = serializer.serialize(packet())
        val keysInPayload =
            payload
                .lines()
                .filter { it.isNotBlank() }
                .map { it.substringBefore("=") }
                .toSet()
        // 등장한 모든 필드 ⊆ allowlist(deny-by-default).
        assertThat(serializer.allowedFields()).containsAll(keysInPayload)
    }

    @Test
    fun `identity system prompt is never serialized`() {
        // identity 는 allowlist 에 없으므로 어떤 경로로도 payload 에 등장하지 않는다.
        val payload = serializer.serialize(packet())
        assertThat(payload).doesNotContain("시스템 지침")
        assertThat(payload).doesNotContain("니아")
        assertThat(serializer.allowedFields()).doesNotContain("identity")
    }

    @Test
    fun `residual snowflake and key are scrubbed by minimizer last line of defense`() {
        val turns =
            listOf(
                ConversationTurn("user_3", "id 123456789012345678 키 sk-ABCDEF1234567890XYZ"),
            )
        val payload = serializer.serialize(packet(turns))
        assertThat(ExternalPayloadMinimizer().isClean(payload)).isTrue()
        assertThat(payload).doesNotContain("123456789012345678")
        assertThat(payload).doesNotContain("sk-ABCDEF1234567890XYZ")
    }

    @Test
    fun `allowlist is the single source of truth for exported fields`() {
        // acceptance: allowlist 가 SSOT — 대화 원문은 별도 quoted scene 한 곳에서만 보내며 여기서 중복하지 않는다.
        assertThat(serializer.allowedFields())
            .containsExactlyInAnyOrder("target", "memory_refs")
    }

    @Test
    fun `empty optional fields are omitted not nulled`() {
        val payload = serializer.serialize(packet(refs = emptyList()))
        assertThat(payload).doesNotContain("memory_refs=")
    }

    @Test
    fun `memory claim is serialized under allowlisted key`() {
        val payload = serializer.serialize(packet(refs = listOf(MemoryRef("라면 좋아함", "stated", 0.8))))
        assertThat(payload).contains("memory_refs=")
        assertThat(payload).contains("라면 좋아함")
    }
}
