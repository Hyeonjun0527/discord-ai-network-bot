package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkProfileEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkProfileRepository
import com.discordassistant.central.ainetwork.domain.model.AiLevelFormula
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 서버(길드) AI 활동 경험치 적립 서비스(Phase 1). 길드당 1개의 냥시스턴트 AI 가 /ask 답변 성공마다
 * 경험치를 쌓아 레벨업한다(채널 AI별 아님 — 길드 단위, ai_network_profile 1행).
 *
 * 동시성: 적립은 원자 UPDATE(addXp), 레벨업은 조건부 UPDATE(raiseLevel, ai_level < newLevel)로
 * read-modify-write 없이 처리한다. 레벨을 실제로 올린 트랜잭션만 1행에 영향 → ai_level_up 이벤트 멱등.
 */
@Service
class AiLevelService(
    private val profiles: AiNetworkProfileRepository,
    private val events: AiNetworkEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AiLevelService::class.java)

    /**
     * /ask 답변 성공에 대해 길드 AI 경험치를 적립하고, 임계 통과 시 레벨업 이벤트를 기록한다.
     * read-modify-write 없이 원자 UPDATE 만 사용한다.
     *
     * **반드시 독립 트랜잭션(REQUIRES_NEW)** 에서 동작한다. 호출자(UsageService.recordSuccess)와
     * 트랜잭션을 분리해, 이 안의 예외/제약위반이 호출자 트랜잭션을 globally rollback-only 로 전염시켜
     * 답변/usage/contribution 기록을 깨뜨리는 것을 막는다(게이미피케이션은 비핵심 — best-effort).
     * 별도 빈 호출(프록시 경유)이라 REQUIRES_NEW 가 실제 적용된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun awardAskXp(guildId: Long): AiLevelChange {
        val now = Instant.now(clock)
        ensureProfile(guildId, now)

        // 원자 증가. 프로필이 직전에 없었다면 ensureProfile 후 1행이 보장된다.
        if (profiles.addXp(guildId, AiLevelFormula.XP_PER_ASK_SUCCESS, now) == 0) {
            // 매우 드문 경합(동시 삭제 등) — 한 번 더 보장 후 재시도. 그래도 없으면 적립 생략.
            ensureProfile(guildId, now)
            if (profiles.addXp(guildId, AiLevelFormula.XP_PER_ASK_SUCCESS, now) == 0) {
                log.warn("AI 경험치 적립 대상 프로필을 찾지 못해 적립을 건너뜀(guildId={}). 동시 삭제 경합 의심.", guildId)
                return AiLevelChange(guildId = guildId, previousLevel = 1, currentLevel = 1, totalXp = 0, xpToNext = 0, leveledUp = false)
            }
        }

        val profile = profiles.findByGuildId(guildId)
        val totalXp = profile?.totalXp ?: 0L
        val previousLevel = profile?.aiLevel ?: 1
        val newLevel = AiLevelFormula.levelForXp(totalXp)

        var leveledUp = false
        if (newLevel > previousLevel && profiles.raiseLevel(guildId, newLevel) == 1) {
            leveledUp = true
            recordLevelUpEvent(guildId, newLevel, totalXp, now)
        }

        return AiLevelChange(
            guildId = guildId,
            previousLevel = previousLevel,
            currentLevel = if (leveledUp) newLevel else previousLevel,
            totalXp = totalXp,
            xpToNext = AiLevelFormula.xpToNextLevel(totalXp),
            leveledUp = leveledUp,
        )
    }

    /** 현재 길드 AI 의 활동 레벨/경험치/진행도 view(없으면 기본값). 표시 경로에서 직접 read. */
    @Transactional(readOnly = true)
    fun levelView(guildId: Long): AiLevelView {
        val profile = profiles.findByGuildId(guildId)
        val totalXp = profile?.totalXp ?: 0L
        val level = profile?.aiLevel ?: AiLevelFormula.levelForXp(totalXp)
        val (gained, needed) = AiLevelFormula.progressInLevel(totalXp)
        return AiLevelView(
            guildId = guildId,
            aiLevel = level,
            totalXp = totalXp,
            xpToNext = AiLevelFormula.xpToNextLevel(totalXp),
            progressInLevel = gained,
            levelSpan = needed,
            lastXpAt = profile?.lastXpAt,
        )
    }

    private fun ensureProfile(
        guildId: Long,
        now: Instant,
    ) {
        if (profiles.findByGuildId(guildId) != null) return
        runCatching {
            profiles.save(
                AiNetworkProfileEntity(
                    guildId = guildId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } // UNIQUE(guild_id) 경합으로 동시 생성 시 한쪽이 실패할 수 있다 — 무시(다른 트랜잭션이 만든 행 사용).
    }

    private fun recordLevelUpEvent(
        guildId: Long,
        newLevel: Int,
        totalXp: Long,
        now: Instant,
    ) {
        events.save(
            AiNetworkEventEntity(
                guildId = guildId,
                eventType = "ai_level_up",
                title = "냥시스턴트 활동 레벨 $newLevel 달성",
                summary = "질문 답변이 쌓여 활동 레벨이 $newLevel 로 올랐어요.",
                metadata = "level=$newLevel;xp=$totalXp",
                createdAt = now,
            ),
        )
    }
}

/** 적립 결과 DTO — 이전/현재 레벨, 누적 경험치, 다음 레벨까지 남은 경험치, 레벨업 여부. */
data class AiLevelChange(
    val guildId: Long,
    val previousLevel: Int,
    val currentLevel: Int,
    val totalXp: Long,
    val xpToNext: Long,
    val leveledUp: Boolean,
)

/** 표시용 활동 레벨 view(슬래시 `/level`·네트워크 지도·대시보드 공용). */
data class AiLevelView(
    val guildId: Long,
    val aiLevel: Int,
    val totalXp: Long,
    val xpToNext: Long,
    val progressInLevel: Long,
    val levelSpan: Long,
    val lastXpAt: Instant?,
)
