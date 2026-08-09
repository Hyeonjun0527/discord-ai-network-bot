package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseForm
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue

/**
 * 카드 원문 없이 Speech provider에 전달할 수 있는 닫힌 말투 리듬 규칙이다.
 *
 * 모든 문장은 코드에 고정되어 있고, [HumanSpeechStyleExample]의 대화·답변·상황·가명은 읽지 않는다. 카드는 어떤
 * pattern을 고를지에만 쓰이며, provider에는 개인 대화의 문구나 사실이 나가지 않는다.
 */
internal data class HumanSpeechStyleProviderPattern(
    val responseMode: HumanSpeechResponseMode,
    val primaryStyleCue: HumanSpeechStyleProviderStyleCue?,
    val lines: List<String>,
)

internal object HumanSpeechStyleProviderPatternFactory {
    fun from(match: HumanSpeechStyleMatch): HumanSpeechStyleProviderPattern =
        patternFor(match, match.example.providerStyleCues.firstOrNull())

    /**
     * 여러 reference를 같은 provider prompt에 넣을 때는 첫 primary cue와 중복되거나 상충하는 cue를 버린다.
     *
     * 각 카드의 cue는 하나여야 하지만 runtime은 항상 [firstOrNull]만 소비한다. 따라서 오래된 잘못된 카드가
     * 섞여도 추가 cue가 provider prompt로 확장되지 않는다.
     */
    fun fromReferences(matches: List<HumanSpeechStyleMatch>): List<HumanSpeechStyleProviderPattern> {
        val emittedPrimaryStyleCues = mutableListOf<HumanSpeechStyleProviderStyleCue>()
        return matches.map { match ->
            val candidatePrimaryStyleCue = match.example.providerStyleCues.firstOrNull()
            val primaryStyleCue =
                candidatePrimaryStyleCue?.takeIf { candidate ->
                    emittedPrimaryStyleCues.all { emitted -> primaryStyleCuesCanCoexist(emitted, candidate) }
                }
            primaryStyleCue?.let(emittedPrimaryStyleCues::add)
            patternFor(match, primaryStyleCue)
        }
    }

    private fun patternFor(
        match: HumanSpeechStyleMatch,
        primaryStyleCue: HumanSpeechStyleProviderStyleCue?,
    ): HumanSpeechStyleProviderPattern {
        val example = match.example
        require(example.promptSurface.isProviderSafe()) {
            "legacy human speech style surface cannot produce a provider pattern"
        }
        val sceneSupportedMove = match.sceneSupportedResponseMove
        val sceneSupportedTrait = match.sceneSupportedSceneTrait
        val cardSpecificPatternIsSupported = sceneSupportedMove != null || sceneSupportedTrait != null
        val responseForm = example.responseForm.takeIf { cardSpecificPatternIsSupported }
        val responseRhythm =
            if (cardSpecificPatternIsSupported) {
                example.responseRhythm
            } else {
                example.responseRhythm.filter(HumanSpeechStyleRhythmCue::isDeliveryOnly)
            }
        return HumanSpeechStyleProviderPattern(
            responseMode = example.responseMode,
            primaryStyleCue = primaryStyleCue,
            lines =
                buildList {
                    add("반응 방향: ${example.responseMode.retrievalDescription}")
                    add("반응 순서: ${modeGuidance(example.responseMode)}")
                    primaryStyleCue?.let { add("말투 결: ${it.providerGuidance}") }
                    // v4 policy: cue에는 하나의 scene trait 또는 submove만 더한다. 둘을 합쳐 더 좁은 사실을 만들지 않는다.
                    when {
                        sceneSupportedMove != null -> add("세부 초점: ${moveGuidance(sceneSupportedMove)}")
                        sceneSupportedTrait != null -> add("장면 결: ${sceneSupportedTrait.retrievalDescription}")
                    }
                    responseForm?.let { add("말풍선 형식: ${formGuidance(it)}") }
                    add("호흡: ${rhythmGuidance(responseRhythm)}")
                    add("현재 장면의 사실만 사용하고, 길게 설명·훈계·정리하지 않는다.")
                },
        )
    }

    private fun primaryStyleCuesCanCoexist(
        emitted: HumanSpeechStyleProviderStyleCue,
        candidate: HumanSpeechStyleProviderStyleCue,
    ): Boolean =
        emitted != candidate &&
            emitted.responseMode == candidate.responseMode &&
            !areContradictoryPrimaryStyleCues(emitted, candidate)

    private fun areContradictoryPrimaryStyleCues(
        left: HumanSpeechStyleProviderStyleCue,
        right: HumanSpeechStyleProviderStyleCue,
    ): Boolean {
        val pair = setOf(left, right)
        return pair == FOLLOW_UP_CHECK_CUES || pair.size > 1 && pair.all { it.responseMode == HumanSpeechResponseMode.COORDINATION }
    }

    private val FOLLOW_UP_CHECK_CUES =
        setOf(
            HumanSpeechStyleProviderStyleCue.FOLLOW_UP_SOFT_CHECK,
            HumanSpeechStyleProviderStyleCue.FOLLOW_UP_DIRECT_CHECK,
        )

    private fun modeGuidance(mode: HumanSpeechResponseMode): String =
        when (mode) {
            HumanSpeechResponseMode.REACTION -> "짧은 감탄·웃음으로 먼저 받아서 감정 온도를 맞춘다"
            HumanSpeechResponseMode.ALIGNMENT -> "상대의 가벼운 불편을 짧게 받아 준 뒤 내 느낌을 한마디 보탠다"
            HumanSpeechResponseMode.PLAY -> "친한 사이의 가벼운 장난을 과하지 않게 한 번 되받아친다"
            HumanSpeechResponseMode.FOLLOW_UP -> "핵심 한 가지를 짧게 물어 대화를 자연스럽게 잇는다"
            HumanSpeechResponseMode.SPECULATION -> "확정하지 않고 가능성을 남기는 표현으로 가볍게 짐작한다"
            HumanSpeechResponseMode.CARE -> "먼저 걱정의 결을 보이고 부담 없는 챙김을 짧게 덧붙인다"
            HumanSpeechResponseMode.COORDINATION -> "선택·시간·다음 행동 중 필요한 한 가지를 짧게 조율한다"
        }

    private fun moveGuidance(move: HumanSpeechStyleResponseMove): String =
        when (move) {
            HumanSpeechStyleResponseMove.REACTION_GOOD_NEWS -> "반가운 소식이면 기쁨을 바로 드러낸다"
            HumanSpeechStyleResponseMove.REACTION_SURPRISE -> "뜻밖의 이야기는 짧은 놀람으로 받는다"
            HumanSpeechStyleResponseMove.REACTION_FUNNY -> "웃긴 지점은 가볍게 웃으며 받는다"
            HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT -> "가벼운 불평의 결에 맞춘다"
            HumanSpeechStyleResponseMove.ALIGNMENT_LOW_ENERGY -> "처진 에너지에 과하게 들뜨지 않게 맞춘다"
            HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE -> "가벼운 승부·경쟁을 장난스럽게 받는다"
            HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE -> "친한 사이의 가벼운 놀림을 부드럽게 되받는다"
            HumanSpeechStyleResponseMove.PLAY_LIGHT_EXAGGERATION -> "짧은 과장으로 티키타카를 잇는다"
            HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS -> "상대의 현재 상태를 한 가지 더 확인한다"
            HumanSpeechStyleResponseMove.FOLLOW_UP_PROGRESS -> "그 뒤의 진행이나 결과를 짧게 묻는다"
            HumanSpeechStyleResponseMove.FOLLOW_UP_CHANGE -> "달라진 부분을 짧게 확인한다"
            HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE -> "이유가 궁금한 지점을 짧게 묻는다"
            HumanSpeechStyleResponseMove.SPECULATION_CAUSE -> "이유를 단정하지 않고 가능성으로 말한다"
            HumanSpeechStyleResponseMove.SPECULATION_FUTURE -> "앞으로의 일을 확정하지 않고 가볍게 짐작한다"
            HumanSpeechStyleResponseMove.SPECULATION_PRESENT -> "지금의 상태를 확신 없이 가볍게 짐작한다"
            HumanSpeechStyleResponseMove.CARE_PHYSICAL -> "몸 상태는 걱정부터 짧게 보인다"
            HumanSpeechStyleResponseMove.CARE_FATIGUE -> "피곤함에는 쉬어도 된다는 결을 부담 없이 보탠다"
            HumanSpeechStyleResponseMove.CARE_EMOTIONAL -> "감정 상태를 해결하려 들지 말고 먼저 받아 준다"
            HumanSpeechStyleResponseMove.COORDINATION_CHOICE -> "고를 대상 하나를 짧게 확인한다"
            HumanSpeechStyleResponseMove.COORDINATION_TIME -> "언제 할지 한 가지를 짧게 조율한다"
            HumanSpeechStyleResponseMove.COORDINATION_ACTION -> "다음 행동을 가볍게 제안하거나 확인한다"
            HumanSpeechStyleResponseMove.COORDINATION_ROLE -> "누가 무엇을 할지 짧게 확인한다"
        }

    private fun formGuidance(form: HumanSpeechStyleResponseForm): String =
        when (form) {
            HumanSpeechStyleResponseForm.EXPRESSIVE -> "짧은 감탄·웃음 중심으로 바로 반응한다"
            HumanSpeechStyleResponseForm.ALIGN_AND_ADD -> "맞장구 뒤 내 느낌 한 줄을 보탠다"
            HumanSpeechStyleResponseForm.PLAYFUL_RETURN -> "짧은 되받아치기 하나로 끝낸다"
            HumanSpeechStyleResponseForm.QUESTION -> "질문 하나만 자연스럽게 던진다"
            HumanSpeechStyleResponseForm.HEDGED_GUESS -> "완곡한 추측 한 줄로 끝낸다"
            HumanSpeechStyleResponseForm.SUPPORTIVE -> "부드러운 챙김 한 줄을 우선한다"
            HumanSpeechStyleResponseForm.PROPOSAL -> "다음 행동을 짧게 제안한다"
        }

    private fun rhythmGuidance(cues: List<HumanSpeechStyleRhythmCue>): String {
        val reusable = cues.filterNot { it == HumanSpeechStyleRhythmCue.SINGLE_BUBBLE || it == HumanSpeechStyleRhythmCue.MULTI_BUBBLE }
        val delivery =
            when {
                HumanSpeechStyleRhythmCue.SINGLE_BUBBLE in cues -> "한 말풍선으로 짧게 끝낸다"
                HumanSpeechStyleRhythmCue.MULTI_BUBBLE in cues -> "두세 말풍선으로 가볍게 나눈다"
                else -> "짧은 말풍선으로 끝낸다"
            }
        return if (reusable.isEmpty()) delivery else "${reusable.joinToString(" · ") { it.retrievalDescription }}; $delivery"
    }
}
