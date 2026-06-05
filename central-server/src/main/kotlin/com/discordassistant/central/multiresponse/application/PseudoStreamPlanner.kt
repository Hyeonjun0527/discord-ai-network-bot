package com.discordassistant.central.multiresponse.application

import com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate
import org.springframework.stereotype.Service

/**
 * 순수 의사-스트리밍 플래너: [MultiResponseService] 에서 분리한 Discord 편집 스냅샷 계산 협력자.
 * 부수효과 없는 순수 계산이며 @Transactional 이 없다(feature gate 체크만 위임받아 수행).
 */
@Service
class PseudoStreamPlanner(
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    fun pseudoStreamPlan(
        answer: String,
        requestedSteps: List<Int> = emptyList(),
        maxDiscordChars: Int = DISCORD_MESSAGE_SAFE_LIMIT,
    ): PseudoStreamPlan {
        featureGate.requireMultiResponseEnabled()
        val normalized = answer.trim()
        if (normalized.isBlank()) {
            return PseudoStreamPlan(
                finalLength = 0,
                truncated = false,
                editIntervalMs = PSEUDO_STREAM_EDIT_INTERVAL_MS,
                snapshots = emptyList(),
                warning = "empty_answer",
            )
        }
        val limit = maxDiscordChars.coerceIn(100, DISCORD_MESSAGE_SAFE_LIMIT)
        val visibleAnswer = normalized.take(limit)
        val truncated = normalized.length > visibleAnswer.length
        val steps = normalizePseudoStreamSteps(requestedSteps)
        val snapshots =
            steps
                .mapIndexed { index, percent ->
                    val length =
                        if (index == steps.lastIndex) {
                            visibleAnswer.length
                        } else {
                            ((visibleAnswer.length * percent) / 100).coerceIn(1, visibleAnswer.length)
                        }
                    PseudoStreamSnapshot(
                        sequence = index + 1,
                        percent = percent,
                        content = visibleAnswer.take(length),
                        charCount = length,
                        final = index == steps.lastIndex,
                    )
                }.dedupeSnapshots()
        return PseudoStreamPlan(
            finalLength = visibleAnswer.length,
            truncated = truncated,
            editIntervalMs = PSEUDO_STREAM_EDIT_INTERVAL_MS,
            snapshots = snapshots,
            warning = if (truncated) "discord_message_truncated_to_$limit" else null,
        )
    }

    private fun normalizePseudoStreamSteps(requestedSteps: List<Int>): List<Int> {
        val normalized =
            requestedSteps
                .ifEmpty { listOf(33, 66, 100) }
                .map { it.coerceIn(1, 100) }
                .distinct()
                .sorted()
                .filter { it > 0 }
                .toMutableList()
        if (normalized.isEmpty() || normalized.last() != 100) normalized += 100
        return normalized
    }

    private fun List<PseudoStreamSnapshot>.dedupeSnapshots(): List<PseudoStreamSnapshot> {
        val deduped = mutableListOf<PseudoStreamSnapshot>()
        forEach { snapshot ->
            if (deduped.lastOrNull()?.content == snapshot.content && !snapshot.final) return@forEach
            deduped += snapshot.copy(sequence = deduped.size + 1)
        }
        val last = deduped.lastOrNull() ?: return emptyList()
        return if (last.final) deduped else deduped.dropLast(1) + last.copy(final = true, percent = 100)
    }

    companion object {
        const val DISCORD_MESSAGE_SAFE_LIMIT = 1_900
        const val PSEUDO_STREAM_EDIT_INTERVAL_MS = 1_200
    }
}
