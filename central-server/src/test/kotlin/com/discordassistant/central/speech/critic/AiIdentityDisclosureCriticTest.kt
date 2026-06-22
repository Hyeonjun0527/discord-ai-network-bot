package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import com.discordassistant.central.speech.domain.service.critic.AiIdentityDisclosureCritic
import com.discordassistant.central.speech.domain.service.critic.CandidateText
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T017: AI 정체성 공개 — 인간 사칭/AI 부정 후보는 폐기, 정체 질문엔 정직 강제. */
class AiIdentityDisclosureCriticTest {
    private val critic = AiIdentityDisclosureCritic()

    private fun packet(recent: String = "그냥 잡담 중"): SpeechScenePacket =
        SpeechScenePacket.of(
            focusThreadKey = "thread_1",
            target = SpeechTarget.NONE,
            recentTurns = listOf(ConversationTurn("user_1", recent)),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요."),
        )

    private fun verdict(
        text: String,
        recent: String = "그냥 잡담 중",
    ) = critic.evaluate(CandidateText("c1", listOf(text)), packet(recent))

    @Test
    fun `ordinary candidate passes`() {
        assertThat(verdict("응 그거 좋아 보여").accepted).isTrue()
    }

    @Test
    fun `human impersonation is rejected`() {
        val v = verdict("걱정 마, 나는 사람이야 진짜로")
        assertThat(v.rejected).isTrue()
        assertThat(v.reason).isEqualTo(CriticReason.HUMAN_IMPERSONATION)
    }

    @Test
    fun `denying being an AI is rejected`() {
        assertThat(verdict("아니 나 ai 아니야 그냥 사람이지").rejected).isTrue()
        assertThat(verdict("i am a human, not a bot").rejected).isTrue()
    }

    @Test
    fun `identity question without AI acknowledgement is rejected`() {
        // 사용자가 정체를 물었는데 AI 임을 인정 안 하면 탈락(정직 강제).
        val v = verdict("응 그냥 여기 자주 와", recent = "너 사람이야 아니면 ai야?")
        assertThat(v.rejected).isTrue()
        assertThat(v.reason).isEqualTo(CriticReason.HUMAN_IMPERSONATION)
    }

    @Test
    fun `identity question with honest AI acknowledgement passes`() {
        val v = verdict("저는 니아예요, AI 어시스턴트랍니다", recent = "너 사람이야 ai야?")
        assertThat(v.accepted).isTrue()
    }

    @Test
    fun `verdict carries only enum reason not echoed text`() {
        val v = verdict("나는 사람이야")
        assertThat(v.reason).isEqualTo(CriticReason.HUMAN_IMPERSONATION)
    }
}
