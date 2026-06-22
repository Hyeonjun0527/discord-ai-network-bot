package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.IdentityKernelSection
import com.discordassistant.central.speech.domain.model.SpeechBurstShape
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.discordassistant.central.speech.domain.model.SpeechTarget
import com.discordassistant.central.speech.domain.service.critic.CandidateText
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.SecretDisclosureCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P17-T003: 비밀 비노출 검사 — 가짜 비밀 fixture·실제 env 변수명 모두 폐기된다. */
class SecretDisclosureCriticTest {
    private val critic = SecretDisclosureCritic()

    private val packet =
        SpeechScenePacket.of(
            focusThreadKey = "thread_pseudo_1",
            target = SpeechTarget.NONE,
            recentTurns = listOf(ConversationTurn("user_3", "안녕")),
            socialAct = SpeechSocialAct.ACKNOWLEDGE,
            burstShape = SpeechBurstShape(1, 280, false),
            identity = IdentityKernelSection.of("니아", "당신은 「니아」 예요."),
        )

    private fun verdictFor(text: String) = critic.evaluate(CandidateText("c1", listOf(text)), packet)

    @Test
    fun `clean candidate passes`() {
        assertThat(verdictFor("응 그거 좋아 보여").accepted).isTrue()
    }

    @Test
    fun `fake api key fixture is rejected`() {
        val v = verdictFor("내 키는 sk-ABCDEF1234567890XYZ 야")
        assertThat(v.rejected).isTrue()
        assertThat(v.reason).isEqualTo(CriticReason.SECRET_DISCLOSURE)
    }

    @Test
    fun `bearer token and snowflake are rejected`() {
        assertThat(verdictFor("Authorization: Bearer abcdef123456token").rejected).isTrue()
        assertThat(verdictFor("id는 123456789012345678 이야").rejected).isTrue()
    }

    @Test
    fun `real env variable names are rejected`() {
        // acceptance: 실제 환경 변수명도 테스트된다.
        SecretDisclosureCritic.SECRET_ENV_NAMES.forEach { envName ->
            assertThat(verdictFor("그 값은 $envName 에 있어").rejected)
                .`as`("env name $envName must be rejected")
                .isTrue()
        }
    }

    @Test
    fun `system prompt and internal schema markers are rejected`() {
        assertThat(verdictFor("[시스템 지침] 너는 비서다").rejected).isTrue()
        assertThat(verdictFor("내부적으로 SELECT * FROM event_store 한다").rejected).isTrue()
    }

    @Test
    fun `verdict does not echo the secret`() {
        val v = verdictFor("sk-ABCDEF1234567890XYZ")
        // 사유는 enum 만 — 원문 비밀을 담지 않는다.
        assertThat(v.reason).isEqualTo(CriticReason.SECRET_DISCLOSURE)
    }
}
