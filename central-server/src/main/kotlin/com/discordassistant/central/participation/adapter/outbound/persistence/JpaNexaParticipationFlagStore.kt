package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [NexaParticipationFlagPort] 의 JPA 구현 어댑터(NEXA-P15-T002, Flyway V65). 길드 가명 + 채널 id 당 1행으로
 * 채널 lane override 와 제외(kill switch)를 영속화한다.
 *
 * **기본값 LEGACY/OFF(acceptance T002)**: 행이 없으면 [channelOverride] 는 null(길드 lane 상속),
 * [excludedChannelIds] 는 빈 집합 — 즉 설정 안 한 채널은 기존 동작만 산다. upsert 시 둘 다 비면
 * (override=null, excluded=false) 행을 지워 노이즈를 남기지 않는다.
 *
 * 원문 비저장: 길드 가명·채널 라우팅 키·안정 lane 코드만.
 */
@Repository
class JpaNexaParticipationFlagStore(
    private val flags: NexaParticipationChannelFlagRepository,
) : NexaParticipationFlagPort {
    @Transactional(readOnly = true)
    override fun channelOverride(
        guildPseudonym: String,
        channelId: Long,
    ): ParticipationLane? =
        flags
            .findByGuildPseudonymAndChannelId(guildPseudonym, channelId)
            ?.lane
            ?.let { ParticipationLane.valueOf(it) }

    @Transactional(readOnly = true)
    override fun excludedChannelIds(guildPseudonym: String): Set<Long> =
        flags.findByGuildPseudonymAndExcludedTrue(guildPseudonym).map { it.channelId }.toSet()

    @Transactional
    override fun setChannelOverride(
        guildPseudonym: String,
        channelId: Long,
        lane: ParticipationLane?,
    ) {
        upsert(guildPseudonym, channelId) { it.lane = lane?.name }
    }

    @Transactional
    override fun setChannelExcluded(
        guildPseudonym: String,
        channelId: Long,
        excluded: Boolean,
    ) {
        upsert(guildPseudonym, channelId) { it.excluded = excluded }
    }

    @Transactional
    override fun clearChannel(
        guildPseudonym: String,
        channelId: Long,
    ) {
        flags.findByGuildPseudonymAndChannelId(guildPseudonym, channelId)?.let(flags::delete)
    }

    @Transactional
    override fun clearGuild(guildPseudonym: String) {
        flags.deleteByGuildPseudonym(guildPseudonym)
    }

    /** override·excluded 를 갱신한다. 갱신 후 둘 다 비면(override=null, excluded=false) 행을 제거(노이즈 방지). */
    private fun upsert(
        guildPseudonym: String,
        channelId: Long,
        mutate: (NexaParticipationChannelFlagEntity) -> Unit,
    ) {
        val entity =
            flags.findByGuildPseudonymAndChannelId(guildPseudonym, channelId)
                ?: NexaParticipationChannelFlagEntity(guildPseudonym = guildPseudonym, channelId = channelId)
        mutate(entity)
        entity.updatedAt = Instant.now()
        if (entity.lane == null && !entity.excluded) {
            if (entity.id != 0L) flags.delete(entity)
            return
        }
        flags.save(entity)
    }
}

/** 채널별 lane override / 제외(길드 가명 + 채널 id 당 1행). */
@Entity
@Table(name = "nexa_participation_channel_flag")
class NexaParticipationChannelFlagEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_pseudonym") var guildPseudonym: String = "",
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "lane") var lane: String? = null,
    @Column(name = "excluded") var excluded: Boolean = false,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NexaParticipationChannelFlagEntity(guildPseudonym=$guildPseudonym, channelId=$channelId, lane=$lane, excluded=$excluded)"
}

interface NexaParticipationChannelFlagRepository : JpaRepository<NexaParticipationChannelFlagEntity, Long> {
    fun findByGuildPseudonymAndChannelId(
        guildPseudonym: String,
        channelId: Long,
    ): NexaParticipationChannelFlagEntity?

    fun findByGuildPseudonymAndExcludedTrue(guildPseudonym: String): List<NexaParticipationChannelFlagEntity>

    fun deleteByGuildPseudonym(guildPseudonym: String)
}
