package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository
import com.discordassistant.central.ainetwork.domain.model.AffinityStage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class NiaAffinityService(
    private val affinities: UserAffinityRepository,
    private val events: AiNetworkEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(NiaAffinityService::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun awardInteraction(
        guildId: Long,
        userId: Long,
    ): NiaAffinityChange {
        val now = Instant.now(clock)
        ensureAffinity(userId, now)
        if (affinities.addScore(userId, AffinityStage.SCORE_PER_INTERACTION, now) == 0) {
            ensureAffinity(userId, now)
            if (affinities.addScore(userId, AffinityStage.SCORE_PER_INTERACTION, now) == 0) {
                log.warn("니아 호감도 적립 대상 행을 찾지 못해 적립을 건너뜀(userId={}).", userId)
                return NiaAffinityChange.empty(userId)
            }
        }

        val affinity = affinities.findByUserId(userId) ?: return NiaAffinityChange.empty(userId)
        val previousStage = AffinityStage.entries.getOrElse(affinity.stageOrdinal) { AffinityStage.STRANGER }
        val newStage = AffinityStage.forScore(affinity.score)
        val stageUp = newStage.ordinal > previousStage.ordinal && affinities.raiseStage(userId, newStage.name, newStage.ordinal) == 1
        if (stageUp) recordStageUpEvent(guildId, userId, newStage, affinity.score, now)

        return NiaAffinityChange(
            userId = userId,
            previousStage = previousStage,
            currentStage = if (stageUp) newStage else previousStage,
            score = affinity.score,
            scoreToNext = AffinityStage.scoreToNext(affinity.score),
            stageUp = stageUp,
        )
    }

    @Transactional(readOnly = true)
    fun view(userId: Long): NiaAffinityView {
        val affinity = affinities.findByUserId(userId)
        val score = affinity?.score ?: 0L
        val stage = affinity?.stage?.let { runCatching { AffinityStage.valueOf(it) }.getOrNull() } ?: AffinityStage.forScore(score)
        return NiaAffinityView(
            userId = userId,
            score = score,
            stage = stage,
            scoreToNext = AffinityStage.scoreToNext(score),
            nextStage = AffinityStage.next(stage),
            lastInteractionAt = affinity?.lastInteractionAt,
        )
    }

    private fun ensureAffinity(
        userId: Long,
        now: Instant,
    ) {
        if (affinities.findByUserId(userId) != null) return
        runCatching {
            affinities.save(
                UserAffinityEntity(
                    userId = userId,
                    stage = AffinityStage.STRANGER.name,
                    stageOrdinal = AffinityStage.STRANGER.ordinal,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun recordStageUpEvent(
        guildId: Long,
        userId: Long,
        stage: AffinityStage,
        score: Long,
        now: Instant,
    ) {
        events.save(
            AiNetworkEventEntity(
                guildId = guildId,
                eventType = "nia_affinity_stage_up",
                actorUserId = userId,
                title = "니아 호감도 ${stage.displayName} 달성",
                summary = "니아와 더 가까워졌어요. 현재 단계: ${stage.displayName}",
                metadata = "stage=${stage.name};score=$score",
                createdAt = now,
            ),
        )
    }
}

data class NiaAffinityChange(
    val userId: Long,
    val previousStage: AffinityStage,
    val currentStage: AffinityStage,
    val score: Long,
    val scoreToNext: Long,
    val stageUp: Boolean,
) {
    companion object {
        fun empty(userId: Long): NiaAffinityChange =
            NiaAffinityChange(
                userId = userId,
                previousStage = AffinityStage.STRANGER,
                currentStage = AffinityStage.STRANGER,
                score = 0,
                scoreToNext = AffinityStage.scoreToNext(0),
                stageUp = false,
            )
    }
}

data class NiaAffinityView(
    val userId: Long,
    val score: Long,
    val stage: AffinityStage,
    val scoreToNext: Long,
    val nextStage: AffinityStage?,
    val lastInteractionAt: Instant?,
)
