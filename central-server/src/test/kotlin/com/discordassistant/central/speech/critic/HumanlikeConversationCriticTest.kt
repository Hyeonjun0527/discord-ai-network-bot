package com.discordassistant.central.speech.critic

import com.discordassistant.central.speech.application.NexaSpeechPipelineService
import com.discordassistant.central.speech.application.generation.SelectionResult
import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.HumanlikeConversationCritic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HumanlikeConversationCriticTest {
    private val critic = HumanlikeConversationCritic()

    @Test
    fun `rejects dismissive phrases that push the user away`() {
        val packet = SpeechCriticFixtures.packet()

        listOf(
            "뭐라도 하자면 말해봐",
            "뭐가 궁금한데",
            "알았으니까 얘기해봐",
            "왜 자꾸 불러 ㅋㅋ",
            "할 말 있으면 해봐",
        ).forEach { response ->
            assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", response), packet).reason)
                .isEqualTo(CriticReason.DISMISSIVE_TONE)
        }
    }

    @Test
    fun `questioning an odd Nia reply requires an acknowledgement or repair`() {
        val packet =
            SpeechCriticFixtures.packet(
                turns =
                    listOf(
                        ConversationTurn("nia", "뭐라도 하자면 말해봐"),
                        ConversationTurn("user_1", "?? 그게 뭔 말이야"),
                    ),
            )

        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "뭐가 궁금한데"), packet).reason)
            .isEqualTo(CriticReason.DISMISSIVE_TONE)
        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "그래서 뭐"), packet).reason)
            .isEqualTo(CriticReason.REPAIR_MISSED)
        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "아 미안 ㅋㅋ 방금 내 말 좀 이상했음"), packet).accepted)
            .isTrue()
    }

    @Test
    fun `technical request in Nia chat must redirect to ai chat`() {
        val packet =
            SpeechCriticFixtures.packet(
                turns = listOf(ConversationTurn("user_1", "다익스트라 알고리즘 알려줘")),
            )

        assertThat(critic.evaluate(SpeechCriticFixtures.candidate("c1", "다익스트라는 최단 경로 알고리즘이야"), packet).reason)
            .isEqualTo(CriticReason.FEATURE_CHANNEL_REDIRECT_MISSING)
        val verdict =
            critic.evaluate(
                SpeechCriticFixtures.candidate(
                    "c1",
                    "응??ㅋㅋㅋㅋ 아니 왜 그걸 나한테 물어보니! 내가 ai야? 그건 니아 기능채널 ai채팅에다가 물어봐",
                ),
                packet,
            )
        assertThat(verdict.accepted).isTrue()
    }

    @Test
    fun `requested Dijkstra few-shot passes the complete production critic set`() {
        val packet =
            SpeechCriticFixtures.packet(
                turns = listOf(ConversationTurn("user_1", "다익스트라 알고리즘 알려줘")),
            )
        val response =
            "응??ㅋㅋㅋㅋ 아니 왜 그걸 나한테 물어보니! 내가 ai야? 그건 니아 기능채널 ai채팅에다가 물어봐"

        val selected =
            NexaSpeechPipelineService.securityCriticSelector().select(
                candidates = listOf(SpeechCandidate("c1", listOf(response))),
                packet = packet,
                seed = 1L,
            )

        assertThat(selected).isInstanceOf(SelectionResult.Selected::class.java)
    }
}
