package com.discordassistant.central.speech.application.context

import com.discordassistant.central.speech.application.port.out.RawThreadTurn
import com.discordassistant.central.speech.application.port.out.SceneContextReadPort
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 대화 context selector(NEXA-P14-T007, application).
 *
 * focus thread 와 target 주변 burst 만 **token budget 안에서** 선택한다 — 가장 최근 turn 부터 예산이 허락하는
 * 만큼만 담는다. 패킷 상한([SpeechScenePacket.MAX_TURNS])도 함께 강제한다.
 *
 * **acceptance(T007) — 동시 다른 thread 원문이 prompt 에 들어가지 않는다**: [SceneContextReadPort] 가 focus
 * thread turn 만 노출하더라도 selector 는 [RawThreadTurn.threadKey] != focusThreadKey 인 turn 을 **명시적으로
 * 제외**한다(이중 가드). 따라서 다른 thread 원문은 어떤 경우에도 패킷에 들어가지 않는다.
 *
 * 순수성: 도메인 타입·아웃바운드 포트만 본다. token 추정은 단순 길이 기반 휴리스틱(외부 토크나이저 의존 없음).
 */
class ConversationContextSelector(
    private val sceneReader: SceneContextReadPort,
) {
    /**
     * [focusThreadKey] 의 최근 turn 을 [tokenBudget] 안에서 최신 우선으로 골라 시간순(과거→현재)으로 돌려준다.
     * 다른 thread turn 은 제외하고, [SpeechScenePacket.MAX_TURNS] 상한도 적용한다.
     */
    fun select(
        focusThreadKey: String,
        tokenBudget: Int,
    ): List<ConversationTurn> {
        require(tokenBudget > 0) { "tokenBudget 는 양수여야 한다: $tokenBudget" }
        // 포트에서 상한만큼 읽되, focus thread 외 turn 은 이중으로 제외한다(acceptance T007).
        val focusTurns =
            sceneReader
                .recentTurns(focusThreadKey, SpeechScenePacket.MAX_TURNS)
                .filter { it.threadKey == focusThreadKey }

        // 최신 우선으로 예산을 채운다(가장 최근 맥락이 발화에 가장 중요). 채운 뒤 시간순으로 되돌린다.
        val selectedNewestFirst = mutableListOf<ConversationTurn>()
        var usedTokens = 0
        for (raw in focusTurns.asReversed()) {
            val cost = estimateTokens(raw.text) + SPEAKER_LABEL_TOKENS
            if (usedTokens + cost > tokenBudget) break
            usedTokens += cost
            selectedNewestFirst.add(ConversationTurn(speakerLabel = raw.speakerLabel, text = raw.text))
            if (selectedNewestFirst.size >= SpeechScenePacket.MAX_TURNS) break
        }
        return selectedNewestFirst.asReversed()
    }

    /** 단순 token 추정(외부 토크나이저 의존 없음): 대략 4자 = 1 token. 최소 1 token 보장. */
    private fun estimateTokens(text: String): Int = maxOf(1, (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)

    companion object {
        /** 휴리스틱: 평균 4자 ≈ 1 token. */
        const val CHARS_PER_TOKEN: Int = 4

        /** 화자 라벨·구분자 고정 비용(turn 당). */
        const val SPEAKER_LABEL_TOKENS: Int = 3
    }
}
