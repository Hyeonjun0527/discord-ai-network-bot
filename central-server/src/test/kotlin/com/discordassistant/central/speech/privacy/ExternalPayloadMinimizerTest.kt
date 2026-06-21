package com.discordassistant.central.speech.privacy

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

/** NEXA-P14-T005: 외부 payload minimizer — golden payload에 snowflake·API key·제외 content 없음. */
class ExternalPayloadMinimizerTest {
    private val minimizer = ExternalPayloadMinimizer()

    private fun packet(
        turns: List<ConversationTurn>,
        refs: List<MemoryRef> = emptyList(),
    ) = SpeechScenePacket.of(
        focusThreadKey = "thread_pseudo_1",
        target = SpeechTarget.member("user_3"),
        recentTurns = turns,
        socialAct = SpeechSocialAct.ACKNOWLEDGE,
        burstShape = SpeechBurstShape(1, 280, false),
        identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요."),
        memoryRefs = refs,
    )

    @Test
    fun `golden payload contains no discord snowflake api key or bearer token`() {
        val turns =
            listOf(
                // 혹시 새어 들어온 snowflake·키를 마지막 방어선에서 제거하는지 검증.
                ConversationTurn("user_3", "내 id는 123456789012345678 이고 키는 sk-ABCDEF1234567890XYZ 야"),
                ConversationTurn("nia", "Authorization: Bearer abcdef123456token 도 있었어"),
            )
        val payload = minimizer.minimizeUserPayload(packet(turns))
        assertThat(minimizer.isClean(payload)).isTrue()
        assertThat(payload).doesNotContain("123456789012345678")
        assertThat(payload).doesNotContain("sk-ABCDEF1234567890XYZ")
        assertThat(payload).contains("[redacted-id]")
        assertThat(payload).contains("[redacted-key]")
        assertThat(payload).contains("[redacted-token]")
    }

    @Test
    fun `pseudonym labels survive (short numbers are not snowflakes)`() {
        val payload = minimizer.minimizeUserPayload(packet(listOf(ConversationTurn("user_3", "안녕"))))
        assertThat(payload).contains("user_3")
        assertThat(payload).contains("안녕")
    }

    @Test
    fun `excluded content cannot enter because packet itself is minimized`() {
        // 패킷 계약(T004)이 turn을 상한·focus thread만 담으므로 다른 thread 원문이 payload에 들어올 수 없다.
        val refs = listOf(MemoryRef("좋아하는 음식은 라면", "stated", 0.8))
        val payload = minimizer.minimizeUserPayload(packet(listOf(ConversationTurn("user_3", "ㅎㅇ")), refs))
        assertThat(payload).contains("라면")
        assertThat(minimizer.isClean(payload)).isTrue()
    }
}
