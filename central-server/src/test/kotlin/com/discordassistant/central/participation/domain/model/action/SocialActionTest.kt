package com.discordassistant.central.participation.domain.model.action

import com.discordassistant.central.participation.domain.model.decision.ActionDelay
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/** NEXA-P08-T001 SocialAction sealed hierarchy 의 acceptance 단위 테스트. */
class SocialActionTest {
    @Test
    fun `각 행동은 정확히 하나의 분기이며 다섯 종류가 모두 존재한다`() {
        val kinds =
            listOf(
                SocialAction.Ignore,
                SocialAction.Wait(ActionDelay.IMMEDIATE),
                SocialAction.React(listOf(ReactionCode("thumbs_up"))),
                SocialAction.Speak(SpeechRequestRef("corr-1")),
                SocialAction.CancelPending(PendingActionId("pa-1")),
            ).map { it.kind }
        assertThat(kinds).containsExactlyInAnyOrder(
            SocialActionKind.IGNORE,
            SocialActionKind.WAIT,
            SocialActionKind.REACT,
            SocialActionKind.SPEAK,
            SocialActionKind.CANCEL_PENDING,
        )
    }

    @Test
    fun `acceptance — SPEAK 없이 어떤 분기에도 문장 텍스트 필드가 존재하지 않는다`() {
        // 모든 SocialAction 분기의 프로퍼티 타입에 원문/문장(String content) 필드가 없어야 한다.
        // Speak 조차 텍스트가 아니라 SpeechRequestRef(참조)만 가진다.
        val speakProps = SocialAction.Speak::class.memberProperties.map { it.name }
        assertThat(speakProps).contains("speechRequest")
        assertThat(speakProps).doesNotContain("text", "content", "sentence", "message")

        // Speak.speechRequest 는 correlationId 만 — 원문 텍스트를 담는 필드가 없다.
        val refProps = SpeechRequestRef::class.memberProperties.map { it.name }
        assertThat(refProps).containsExactly("correlationId")
    }

    @Test
    fun `acceptance — 각 행동은 필요한 payload만 가진다`() {
        assertThat(SocialAction.Ignore).isInstanceOf(SocialAction::class.java)
        assertThat(SocialAction.Wait(ActionDelay.NEVER).delay).isEqualTo(ActionDelay.NEVER)
        assertThat(SocialAction.React(listOf(ReactionCode("eyes"))).reactionCodes).hasSize(1)
        assertThat(SocialAction.CancelPending(PendingActionId("x")).pendingActionId.value).isEqualTo("x")
    }

    @Test
    fun `React 는 빈 reactionCodes 를 허용하지 않는다`() {
        assertThatThrownBy { SocialAction.React(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `quota 경계 — SPEAK 만 generation quota 를 소모한다`() {
        assertThat(SocialAction.Ignore.consumesGenerationQuota).isFalse()
        assertThat(SocialAction.Wait(ActionDelay.IMMEDIATE).consumesGenerationQuota).isFalse()
        assertThat(SocialAction.React(listOf(ReactionCode("ok"))).consumesGenerationQuota).isFalse()
        assertThat(SocialAction.CancelPending(PendingActionId("x")).consumesGenerationQuota).isFalse()
        assertThat(SocialAction.Speak(SpeechRequestRef("c")).consumesGenerationQuota).isTrue()
    }

    @Test
    fun `value object 들은 빈 값을 거부한다`() {
        assertThatThrownBy { ReactionCode(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SpeechRequestRef("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { PendingActionId("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
