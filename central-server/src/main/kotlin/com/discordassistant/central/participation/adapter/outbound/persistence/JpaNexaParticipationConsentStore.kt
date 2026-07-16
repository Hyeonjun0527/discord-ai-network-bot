package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.participation.application.port.out.NexaParticipationConsentPort
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

/** LIVE 멤버 채널 활성화 상태를 V50 consent 테이블의 관찰·발화 범위와 맞춘다. */
@Repository
class JpaNexaParticipationConsentStore(
    private val guildConsents: NexaGuildConsentRepository,
    private val channelScopes: NexaChannelConsentScopeRepository,
) : NexaParticipationConsentPort {
    @Transactional
    override fun activateMemberChannel(
        guildId: Long,
        channelId: Long,
        actorId: Long?,
    ) {
        val now = Instant.now()
        val guild =
            guildConsents.findByGuildId(guildId)
                ?: NexaGuildConsentEntity(guildId = guildId, speakMode = MEMBER_MODE, createdAt = now)
        guild.enabled = true
        if (guild.speakMode == OFF_MODE) guild.speakMode = MEMBER_MODE
        actorId?.let { guild.consentedBy = it }
        guild.updatedAt = now
        guildConsents.saveAndFlush(guild)

        val channel =
            channelScopes.findByGuildIdAndChannelId(guildId, channelId)
                ?: NexaChannelConsentScopeEntity(guildId = guildId, channelId = channelId, createdAt = now)
        channel.observeAllowed = true
        channel.speakAllowed = true
        channel.updatedAt = now
        channelScopes.saveAndFlush(channel)
    }

    @Transactional
    override fun deactivateMemberChannel(
        guildId: Long,
        channelId: Long,
    ) {
        channelScopes.findByGuildIdAndChannelId(guildId, channelId)?.let {
            it.observeAllowed = false
            it.speakAllowed = false
            it.updatedAt = Instant.now()
            channelScopes.saveAndFlush(it)
        }
    }

    @Transactional
    override fun clearChannel(
        guildId: Long,
        channelId: Long,
    ) {
        channelScopes.deleteByGuildIdAndChannelId(guildId, channelId)
    }

    @Transactional
    override fun revokeGuild(guildId: Long) {
        val now = Instant.now()
        guildConsents.findByGuildId(guildId)?.let {
            it.enabled = false
            it.speakMode = OFF_MODE
            it.updatedAt = now
            guildConsents.saveAndFlush(it)
        }
        val revokedScopes =
            channelScopes.findAllByGuildId(guildId).onEach {
                it.observeAllowed = false
                it.speakAllowed = false
                it.updatedAt = now
            }
        channelScopes.saveAllAndFlush(revokedScopes)
    }

    private companion object {
        const val MEMBER_MODE = "MEMBER"
        const val OFF_MODE = "OFF"
    }
}

@Entity
@Table(name = "nexa_guild_consent")
class NexaGuildConsentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    var enabled: Boolean = false,
    @Column(name = "learning_opt_in") var learningOptIn: Boolean = false,
    @Column(name = "speak_mode") var speakMode: String = "OFF",
    @Column(name = "consented_by") var consentedBy: Long? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "nexa_channel_scope")
class NexaChannelConsentScopeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "observe_allowed") var observeAllowed: Boolean = false,
    @Column(name = "speak_allowed") var speakAllowed: Boolean = false,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

interface NexaGuildConsentRepository : JpaRepository<NexaGuildConsentEntity, Long> {
    fun findByGuildId(guildId: Long): NexaGuildConsentEntity?
}

interface NexaChannelConsentScopeRepository : JpaRepository<NexaChannelConsentScopeEntity, Long> {
    fun findByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): NexaChannelConsentScopeEntity?

    fun findAllByGuildId(guildId: Long): List<NexaChannelConsentScopeEntity>

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    )
}
