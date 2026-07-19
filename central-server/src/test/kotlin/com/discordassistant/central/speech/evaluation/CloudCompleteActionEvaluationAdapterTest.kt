package com.discordassistant.central.speech.evaluation

import com.discordassistant.central.routing.application.CloudLlm
import com.discordassistant.central.routing.application.CloudLlmResult
import com.discordassistant.central.routing.application.CloudThinking
import com.discordassistant.central.routing.application.CloudToolResponse
import com.discordassistant.central.routing.application.CloudTurn
import com.discordassistant.central.routing.application.ImageReview
import com.discordassistant.central.speech.adapter.outbound.evaluation.CloudCompleteActionEvaluationAdapter
import com.discordassistant.central.speech.application.port.out.CompleteActionCandidate
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationRequest
import com.discordassistant.central.speech.application.port.out.CompleteActionKind
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CloudCompleteActionEvaluationAdapterTest {
    @Test
    fun `evaluator compares knowledge answers as one social trajectory`() {
        val cloud = CapturingCloudLlm()
        val evaluation = CloudCompleteActionEvaluationAdapter(cloud, "test-model").select(request())

        assertThat(evaluation!!.selectedCandidateId).isEqualTo("social_answer")
        assertThat(cloud.prompt).contains(
            "최근 대화를 하나의 궤적으로 평가한다",
            "시험·장난·반응 확인으로 변했는지",
            "백과사전 답안처럼 완성하는 후보는 낮게 평가한다",
            "speech_intent가 정한 정보 깊이",
            "전환의 뜬금없음에 반응한 웃음",
            "피해·비극 자체를 웃음거리로 만든 태도",
            "다익스트라 알고리즘 말해봐",
            "벨만포드 알고리즘 말해봐",
            "플로이드워셜 알고리즘 말해봐",
            "다음은 이거 물어볼 줄 알았음",
        )
        assertThat(cloud.thinking).isEqualTo(CloudThinking.DISABLED)
    }

    private fun request() =
        CompleteActionEvaluationRequest(
            focusThreadKey = "discord:guild:channel",
            provisionalDecision = "SPEAK",
            provisionalConfidence = 0.76,
            speechIntent =
                "interaction_reading=알고리즘 구술시험처럼 이어짐; " +
                    "information_depth=패턴을 짚은 뒤 한 문장 핵심만",
            socialAct = SpeechSocialAct.TEASE,
            stateRefs = emptyList(),
            recentTurns =
                listOf(
                    ConversationTurn("member", "다익스트라 알고리즘 말해봐"),
                    ConversationTurn("nia", "가까운 정점부터 확정하는 방식이야"),
                    ConversationTurn("member", "벨만포드 알고리즘 말해봐"),
                    ConversationTurn("nia", "모든 간선을 반복해서 갱신해"),
                    ConversationTurn("member", "플로이드워셜 알고리즘 말해봐"),
                ),
            rawContextSceneData = null,
            contextVersion = 5,
            seed = 19,
            triggerMessageRef = "msg_5",
            enforcementConstraints = setOf("SAFETY_CRITICS_PASSED"),
            candidates =
                listOf(
                    CompleteActionCandidate(
                        candidateId = "textbook_answer",
                        kind = CompleteActionKind.SEND,
                        bubbles = listOf("플로이드 워셜은 모든 정점 쌍 사이의 최단거리를 구하는 알고리즘이야"),
                    ),
                    CompleteActionCandidate(
                        candidateId = "social_answer",
                        kind = CompleteActionKind.SEND,
                        bubbles = listOf("다음은 이거 물어볼 줄 알았음\n얘는 모든 정점 쌍의 최단거리를 한꺼번에 구해"),
                    ),
                    CompleteActionCandidate("action_ignore", CompleteActionKind.IGNORE),
                ),
        )

    private class CapturingCloudLlm : CloudLlm {
        lateinit var prompt: String
        var thinking: CloudThinking? = null

        override fun isEnabled(): Boolean = true

        override fun generate(
            prompt: String,
            model: String,
        ): CloudLlmResult = error("multi-turn path expected")

        override fun generate(
            prompt: String,
            model: String,
            history: List<CloudTurn>,
            thinking: CloudThinking?,
        ): CloudLlmResult {
            this.prompt = prompt
            this.thinking = thinking
            return CloudLlmResult(
                """{"selected_candidate_id":"social_answer","predicted_outcome":"recognizes the test and stays useful","reason_code":"TRAJECTORY_COHERENT","confidence":0.91}""",
            )
        }

        override fun generateWithTools(
            systemPrompt: String,
            userPrompt: String,
            toolsJson: String,
            model: String,
        ): CloudToolResponse = error("unused")

        override fun reviewImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): ImageReview = error("unused")

        override fun translateImagePrompt(
            prompt: String,
            systemPrompt: String,
        ): String = error("unused")
    }
}
