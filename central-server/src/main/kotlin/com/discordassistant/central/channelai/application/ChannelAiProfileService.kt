package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

const val DEFAULT_CHANNEL_AI_PURPOSE = "general_assistant"
const val DEFAULT_CHANNEL_AI_TONE = "friendly"
const val DEFAULT_CHANNEL_AI_ANSWER_LENGTH = "balanced"
const val DEFAULT_CHANNEL_AI_SAFETY_LEVEL = "standard"
const val DEFAULT_CHANNEL_AI_CONSTITUTION = "민감정보(비밀번호·API 키·개인정보)는 입력하지 않도록 안내하고, 확실하지 않은 내용은 단정하지 않습니다."

data class ChannelAiProfile(
    val guildId: Long,
    val channelId: Long,
    val displayName: String,
    val avatarUrl: String?,
    val behaviorVersionId: Long? = null,
    val version: Int = 0,
    val purpose: String = DEFAULT_CHANNEL_AI_PURPOSE,
    val tone: String = DEFAULT_CHANNEL_AI_TONE,
    val answerLength: String = DEFAULT_CHANNEL_AI_ANSWER_LENGTH,
    val constitution: String? = DEFAULT_CHANNEL_AI_CONSTITUTION,
)

/** 길드의 채널 AI 프로필 목록 읽기(데스크톱 앱 관리채널 브리지용 좁은 포트). [ChannelAiProfileService] 가 구현. */
interface GuildChannelAiQuery {
    fun listChannelAis(guildId: Long): List<ChannelAiProfile>
}

@Service
class ChannelAiProfileService(
    private val channelAis: ChannelAiRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    // PESSIMISTIC_WRITE 채번 헬퍼(@Transactional 미부여). 파사드 write 의 활성 TX 에 합류해 락·재시도 의미가 보존된다.
    private val behaviorVersionWriter: BehaviorVersionWriter = BehaviorVersionWriter(channelAis, behaviorVersions),
) : GuildChannelAiQuery {
    fun get(
        guildId: Long,
        channelId: Long,
    ): ChannelAiProfile? {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        return channelAi?.toProfile()
    }

    /** 이 길드에 설정된 채널 AI 프로필 전체(관리 화면 09 읽기). */
    @Transactional(readOnly = true)
    override fun listChannelAis(guildId: Long): List<ChannelAiProfile> = channelAis.findByGuildId(guildId).map { it.toProfile() }

    @Transactional
    fun set(
        guildId: Long,
        channelId: Long,
        displayName: String,
        avatarUrl: String?,
        actorId: Long? = null,
        purpose: String? = null,
        tone: String? = null,
        answerLength: String? = null,
        constitution: String? = null,
    ): ChannelAiProfile {
        val normalizedName = displayName.trim().take(80)
        require(normalizedName.isNotBlank()) { "프로필 이름을 입력하세요." }
        val normalizedAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        val now = Instant.now()
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiEntity(
                    guildId = guildId,
                    channelId = channelId,
                    source = "manual",
                    createdAt = now,
                )
        channelAi.displayName = normalizedName
        channelAi.avatarUrl = normalizedAvatar
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)

        val previous = savedChannel.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(savedChannel.id, it) }
        // 채번(MAX(version)+1)은 PESSIMISTIC_WRITE 락 + 유니크 위반 재시도로 직렬화한다(동시 set 이 같은 version
        // 으로 insert 해 uk_ai_behavior_version 을 깨는 race 방지). BehaviorVersionWriter 로 위임(중복 구현 회피).
        val behavior =
            behaviorVersionWriter.saveNextBehaviorVersion(savedChannel.id) { nextVersion ->
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = normalized(purpose, previous?.purpose, DEFAULT_CHANNEL_AI_PURPOSE, 200),
                    tone = normalized(tone, previous?.tone, DEFAULT_CHANNEL_AI_TONE, 80),
                    answerLength = normalized(answerLength, previous?.answerLength, DEFAULT_CHANNEL_AI_ANSWER_LENGTH, 40),
                    constitution = normalizedOptional(constitution, previous?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION, 2000),
                    safetyLevel = previous?.safetyLevel ?: DEFAULT_CHANNEL_AI_SAFETY_LEVEL,
                    createdBy = actorId,
                    createdAt = now,
                    changeSummary = "channel AI profile updated",
                )
            }
        savedChannel.activeBehaviorVersionId = behavior.id
        savedChannel.updatedAt = now
        channelAis.save(savedChannel)
        proposals.save(
            AiChangeProposalEntity(
                guildId = guildId,
                channelId = channelId,
                channelAiId = savedChannel.id,
                proposedBehaviorId = behavior.id,
                status = ProposalStatus.APPROVED,
                requestedBy = actorId,
                reviewedBy = actorId,
                reason = "Release 1 direct publish",
                createdAt = now,
                reviewedAt = now,
            ),
        )
        audit(guildId, channelId, actorId, "publish", "ai_behavior_version", behavior.id, "v${behavior.version} published")
        return savedChannel.toProfile(behavior)
    }

    @Transactional
    fun rollback(
        guildId: Long,
        channelId: Long,
        actorId: Long? = null,
    ): ChannelAiProfile? {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId) ?: return null
        val versions = behaviorVersions.findByChannelAiIdOrderByVersionDesc(channelAi.id)
        if (versions.size < 2) return channelAi.toProfile()
        val previous = versions.first { it.id != channelAi.activeBehaviorVersionId }
        channelAi.activeBehaviorVersionId = previous.id
        channelAi.updatedAt = Instant.now()
        channelAis.save(channelAi)
        audit(guildId, channelId, actorId, "rollback", "ai_behavior_version", previous.id, "rolled back to v${previous.version}")
        return channelAi.toProfile(previous)
    }

    @Transactional
    fun clear(
        guildId: Long,
        channelId: Long,
    ) {
        channelAis.findByGuildIdAndChannelId(guildId, channelId)?.let { channelAi ->
            proposals.deleteByChannelAiId(channelAi.id)
            channelAis.delete(channelAi)
        }
        audits.deleteByGuildIdAndChannelId(guildId, channelId)
    }

    @Transactional
    fun clearGuild(guildId: Long) {
        channelAis.findByGuildId(guildId).forEach { channelAi ->
            proposals.deleteByChannelAiId(channelAi.id)
            channelAis.delete(channelAi)
        }
        audits.deleteByGuildId(guildId)
        proposals.deleteByGuildId(guildId)
    }

    fun recentAudit(
        guildId: Long,
        channelId: Long,
    ): List<String> =
        audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId).map {
            "${it.action}:${it.targetType}:${it.targetId ?: "-"}"
        }

    private fun ChannelAiEntity.activeBehaviorVersion(): AiBehaviorVersionEntity? =
        activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(id, it) }

    private fun ChannelAiEntity.toProfile(behavior: AiBehaviorVersionEntity? = activeBehaviorVersion()): ChannelAiProfile =
        ChannelAiProfile(
            guildId = guildId,
            channelId = channelId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            behaviorVersionId = behavior?.id,
            version = behavior?.version ?: 0,
            purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE,
            tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE,
            answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH,
            constitution = behavior?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION,
        )

    private fun audit(
        guildId: Long,
        channelId: Long,
        actorId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId,
                actorId = actorId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary.take(1000),
                createdAt = Instant.now(),
            ),
        )
    }

    private fun normalized(
        value: String?,
        previous: String?,
        default: String,
        max: Int,
    ): String = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous ?: default

    private fun normalizedOptional(
        value: String?,
        previous: String?,
        max: Int,
    ): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous
}
