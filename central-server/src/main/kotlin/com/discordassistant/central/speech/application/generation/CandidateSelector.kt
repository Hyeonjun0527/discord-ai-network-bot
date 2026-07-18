package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.service.critic.CandidateText
import com.discordassistant.central.speech.domain.service.critic.CriticReason
import com.discordassistant.central.speech.domain.service.critic.CriticVerdict
import com.discordassistant.central.speech.domain.service.critic.SpeechCritic
import kotlin.math.exp
import kotlin.random.Random

/**
 * 후보 확률적 선택기(NEXA-P14-T021, application).
 *
 * 비평가(T017~T020)를 모두 통과한 후보 중에서 **score-softmax + seed** 로 하나를 고른다. 항상 최고 점수만 택해 문체가
 * 고정되는 것을 막되(다양성), 같은 seed·후보면 결정론적으로 재현된다. 통과 후보가 0이면 [SelectionResult.Silence]
 * — fallback(T016)이 침묵/리액션으로 안전 하강한다.
 *
 * **acceptance(T021) — 항상 최고 점수만 택해 문체가 고정되는 것을 방지하면서 최소 기준은 지킨다**: 선택은 비평가를
 * 통과한 후보 집합에서만 일어나고([rejected] 후보는 후보 풀에 없다 — 최소 기준), 그 안에서 softmax 확률로 뽑아
 * 결정론 seed 별로 다른 후보가 선택될 수 있다(문체 고정 방지). [temperature] 가 0 에 가까우면 argmax 로 수렴한다.
 *
 * 순수성: application — 도메인 critic + application 의 SpeechCandidate + 표준 타입만. Spring/JPA/JDA·glm/zai 미참조.
 */
class CandidateSelector(
    private val critics: List<SpeechCritic>,
    /** softmax 온도(>0). 낮을수록 고점수 편향, 높을수록 다양. */
    private val temperature: Double = DEFAULT_TEMPERATURE,
) {
    init {
        require(temperature > 0.0) { "temperature 는 양수여야 한다: $temperature" }
    }

    /**
     * [candidates] 에서 비평 통과 후보를 골라 [seed] 로 하나를 확률 선택한다. 통과 0이면 [SelectionResult.Silence].
     */
    fun select(
        candidates: List<SpeechCandidate>,
        packet: SpeechScenePacket,
        seed: Long,
    ): SelectionResult {
        val survivors = survivors(candidates, packet)
        if (survivors.isEmpty()) return SelectionResult.Silence

        val weights = survivors.map { softmaxWeight(score(it)) }
        val picked = sample(survivors, weights, Random(seed))
        return SelectionResult.Selected(picked)
    }

    /** 비평을 모두 통과해 완전 행동 평가에 올릴 수 있는 실제 SEND 후보만 돌려준다. */
    fun survivors(
        candidates: List<SpeechCandidate>,
        packet: SpeechScenePacket,
    ): List<SpeechCandidate> = candidates.filter { survives(it, packet) }

    /** 모든 비평가가 이 후보를 통과시키는가(하나라도 탈락이면 후보 풀에서 제외). */
    private fun survives(
        candidate: SpeechCandidate,
        packet: SpeechScenePacket,
    ): Boolean {
        val text = CandidateText(candidate.candidateId, candidate.bubbles)
        if (text.joined.isBlank()) return false // 빈 후보는 발화 불가.
        return critics.all { it.evaluate(text, packet).accepted }
    }

    /** 후보를 평가해 탈락 사유들을 모은다(shadow 평가 T024 가 사유별 rate 집계에 쓴다). */
    fun rejectionReasons(
        candidate: SpeechCandidate,
        packet: SpeechScenePacket,
    ): List<CriticReason> {
        val text = CandidateText(candidate.candidateId, candidate.bubbles)
        return critics
            .map { it.evaluate(text, packet) }
            .filter { it.rejected }
            .mapNotNull(CriticVerdict::reason)
    }

    /** 후보 score: 모델 불확실성이 낮을수록 높은 점수(베이스). [0,1]. */
    private fun score(candidate: SpeechCandidate): Double = (1.0 - candidate.uncertainty).coerceIn(0.0, 1.0)

    /** softmax 가중치 exp(score/T). 정규화는 [sample] 의 누적합에서 한다. */
    private fun softmaxWeight(score: Double): Double = exp(score / temperature)

    /** 가중치 비례 추출(결정론 — 같은 random 시퀀스면 같은 선택). */
    private fun sample(
        candidates: List<SpeechCandidate>,
        weights: List<Double>,
        random: Random,
    ): SpeechCandidate {
        val total = weights.sum()
        if (total <= 0.0) return candidates.first() // 모든 가중치 0(이상치) — 첫 후보로 안전.
        val threshold = random.nextDouble() * total
        var cumulative = 0.0
        for ((index, weight) in weights.withIndex()) {
            cumulative += weight
            if (threshold <= cumulative) return candidates[index]
        }
        return candidates.last() // 부동소수 오차 보정.
    }

    companion object {
        /** 기본 온도(중간 다양성 — 고점수 선호하되 차점 후보도 충분히 뽑힘). */
        const val DEFAULT_TEMPERATURE: Double = 0.3
    }
}

/**
 * 선택 결과(NEXA-P14-T021, sealed). 발화할 후보가 정해지거나(Selected), 통과 후보가 없어 침묵한다(Silence).
 */
sealed interface SelectionResult {
    /** 확률 선택된 단일 후보. */
    data class Selected(
        val candidate: SpeechCandidate,
    ) : SelectionResult

    /** 통과 후보 0 — 침묵(fallback T016 이 무발화/리액션으로 하강). */
    data object Silence : SelectionResult
}
