package com.discordassistant.central.speech.application.generation

import com.discordassistant.central.speech.application.port.out.SpeechCandidate
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.service.critic.CriticReason

/**
 * 발화 품질·비용 **shadow 평가기**(NEXA-P14-T024, application).
 *
 * 가명화 장면 후보들에 대해 자연스러움 proxy, **assistant-style rate**, contradiction rate, latency, cost 를
 * 집계한다 — **실제 Discord 전송 없이** 측정한다(shadow). 운영 미적용: 이 평가기는 전송 포트를 호출하지 않고
 * 후보·비평 결과만 본다(전송 0). 사람 blind review 점수는 외부에서 주입한다([ShadowSample.humanScore]).
 *
 * **acceptance(T024) — 실제 Discord 전송 없이 blind human review 와 자동 metric 을 함께 기록한다**: [evaluate] 는
 * 입력 샘플에서 자동 metric(critic 사유별 rate·latency·cost·후보 통과율)을 계산하고, 함께 들어온 human review 점수를
 * 평균낸다. 전송 부수효과가 없다(순수 집계 — actionruntime/DiscordSendPort 미참조).
 *
 * 순수성: application — 도메인 selector/critic + 표준 타입만. Spring/JPA/JDA·glm/zai·DiscordSendPort 미참조.
 */
class SpeechShadowEvaluator(
    private val selector: CandidateSelector,
) {
    /** [samples] 를 shadow 평가해 집계 리포트를 만든다(전송 없음). */
    fun evaluate(samples: List<ShadowSample>): ShadowReport {
        if (samples.isEmpty()) return ShadowReport.EMPTY

        var totalCandidates = 0
        var rejectedCandidates = 0
        val reasonCounts = mutableMapOf<CriticReason, Int>()
        var totalLatencyMillis = 0L
        var totalCostTokens = 0L
        var humanScoreSum = 0.0
        var humanScoreCount = 0

        for (sample in samples) {
            totalLatencyMillis += sample.latencyMillis
            totalCostTokens += sample.costTokens
            sample.humanScore?.let {
                humanScoreSum += it
                humanScoreCount++
            }
            for (candidate in sample.candidates) {
                totalCandidates++
                val reasons = selector.rejectionReasons(candidate, sample.packet)
                if (reasons.isNotEmpty()) {
                    rejectedCandidates++
                    reasons.forEach { reasonCounts.merge(it, 1, Int::plus) }
                }
            }
        }

        val candidateDenom = totalCandidates.coerceAtLeast(1)
        return ShadowReport(
            sampleCount = samples.size,
            candidateCount = totalCandidates,
            rejectionRate = rejectedCandidates.toDouble() / candidateDenom,
            assistantStyleRate = reasonRate(reasonCounts, CriticReason.ASSISTANT_STYLE, candidateDenom),
            repetitionRate = reasonRate(reasonCounts, CriticReason.REPETITION, candidateDenom),
            contradictionRate = reasonRate(reasonCounts, CriticReason.MEMORY_CONTRADICTION, candidateDenom),
            targetSceneMismatchRate = reasonRate(reasonCounts, CriticReason.TARGET_OR_SCENE_MISMATCH, candidateDenom),
            avgLatencyMillis = totalLatencyMillis.toDouble() / samples.size,
            avgCostTokens = totalCostTokens.toDouble() / samples.size,
            avgHumanScore = if (humanScoreCount == 0) null else humanScoreSum / humanScoreCount,
        )
    }

    private fun reasonRate(
        counts: Map<CriticReason, Int>,
        reason: CriticReason,
        denom: Int,
    ): Double = (counts[reason] ?: 0).toDouble() / denom
}

/**
 * shadow 평가 입력 샘플(NEXA-P14-T024). 가명화 장면 + 그 장면의 후보 + 측정 메타. 전송 없이 평가만 한다.
 */
data class ShadowSample(
    val packet: SpeechScenePacket,
    val candidates: List<SpeechCandidate>,
    /** 후보 생성에 걸린 시간(ms) — latency metric. */
    val latencyMillis: Long = 0,
    /** 후보 생성에 든 token(prompt+completion) — cost metric. */
    val costTokens: Long = 0,
    /** blind human review 점수 [0,5](없으면 null — 자동 metric 만). */
    val humanScore: Double? = null,
) {
    init {
        require(latencyMillis >= 0) { "latencyMillis 는 음수일 수 없다: $latencyMillis" }
        require(costTokens >= 0) { "costTokens 는 음수일 수 없다: $costTokens" }
        humanScore?.let { require(it in 0.0..5.0) { "humanScore 는 [0,5] 범위여야 한다: $it" } }
    }
}

/**
 * shadow 평가 리포트(NEXA-P14-T024). 자동 metric + 평균 human 점수. 운영 전환 게이트(P14-T025)가 임계와 비교한다.
 */
data class ShadowReport(
    val sampleCount: Int,
    val candidateCount: Int,
    val rejectionRate: Double,
    val assistantStyleRate: Double,
    val repetitionRate: Double,
    val contradictionRate: Double,
    val targetSceneMismatchRate: Double,
    val avgLatencyMillis: Double,
    val avgCostTokens: Double,
    /** 평균 blind human review 점수(없으면 null). */
    val avgHumanScore: Double?,
) {
    companion object {
        val EMPTY =
            ShadowReport(
                sampleCount = 0,
                candidateCount = 0,
                rejectionRate = 0.0,
                assistantStyleRate = 0.0,
                repetitionRate = 0.0,
                contradictionRate = 0.0,
                targetSceneMismatchRate = 0.0,
                avgLatencyMillis = 0.0,
                avgCostTokens = 0.0,
                avgHumanScore = null,
            )
    }
}
