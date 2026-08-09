package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.application.port.out.SpeechGenerationResult
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection

/**
 * 사람 말투 카드는 리듬 참고용이지 문장 복사용이 아니다. 선택된 사람 답변과 20자 이상 연속 일치하는 모델 후보는 버린다.
 */
class HumanSpeechStyleCopyGuard {
    fun removeCopiedCandidates(
        result: SpeechGenerationResult,
        selection: HumanSpeechStyleSelection,
    ): SpeechGenerationResult {
        if (selection.isEmpty || result.candidates.isEmpty()) return result
        val referenceReplies =
            selection.matches
                .flatMap { match -> match.example.responseBubbles }
                .map { bubble -> normalize(bubble.text) }
                .filter { it.length >= MIN_MATCH_CHARS }
        if (referenceReplies.isEmpty()) return result
        val remaining = result.candidates.filterNot { candidate -> copiedFromReference(candidate, referenceReplies) }
        return if (remaining.size == result.candidates.size) result else result.copy(candidates = remaining)
    }

    private fun copiedFromReference(
        candidate: SpeechCandidate,
        referenceReplies: List<String>,
    ): Boolean {
        val generated = normalize(candidate.bubbles.joinToString(" "))
        return referenceReplies.any { reference -> hasSharedSpan(generated, reference) }
    }

    private fun hasSharedSpan(
        generated: String,
        reference: String,
    ): Boolean {
        if (generated.length < MIN_MATCH_CHARS || reference.length < MIN_MATCH_CHARS) return false
        return generated.windowed(MIN_MATCH_CHARS).any(reference::contains)
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val MIN_MATCH_CHARS: Int = 20
    }
}
