package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.multiresponse.adapter.outbound.persistence.CandidateAnswerEntity
import com.discordassistant.central.shared.ContentSafety.BLOCKING_SAFETY_FLAGS
import org.springframework.stereotype.Service

/**
 * 순수 후보 요약 협력자: [MultiResponseService] 에서 분리한 안전/품질 요약·차단 플래그·실패 요약
 * 계산. 부수효과 없는 순수 함수이며 @Transactional 이 없어 호출자 TX 와 무관하다.
 */
@Service
class CandidateSummaries {
    fun summarizeSafety(runCandidates: List<CandidateAnswerEntity>): String {
        val flags =
            runCandidates
                .flatMap { it.safetyFlags.orEmpty().split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("ok", ignoreCase = true) }
                .distinct()
        return if (flags.isEmpty()) "no candidate safety flags" else flags.joinToString(",")
    }

    fun summarizeQuality(runCandidates: List<CandidateAnswerEntity>): String {
        val scores = runCandidates.mapNotNull { it.qualityScore }
        if (scores.isEmpty()) return "quality score unavailable"
        val average = scores.average()
        val best = scores.max()
        return "avg=${"%.1f".format(average)}, best=$best, scored=${scores.size}"
    }

    fun CandidateAnswerEntity.hasBlockingSafetyFlag(): Boolean =
        safetyFlags
            .orEmpty()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { it in BLOCKING_SAFETY_FLAGS }

    fun failureSummary(runCandidates: List<CandidateAnswerEntity>): String {
        if (runCandidates.isEmpty()) return "multi-response failed: no candidates were planned"
        val statuses = runCandidates.groupingBy { it.status.wire }.eachCount()
        return "multi-response failed: no successful candidate; statuses=$statuses".take(500)
    }
}
