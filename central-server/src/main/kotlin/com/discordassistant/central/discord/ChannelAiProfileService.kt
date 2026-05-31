package com.discordassistant.central.discord

import com.discordassistant.central.persistence.ChannelAiProfileEntity
import com.discordassistant.central.persistence.ChannelAiProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ChannelAiProfile(
    val guildId: Long,
    val channelId: Long,
    val displayName: String,
    val avatarUrl: String?,
)

@Service
class ChannelAiProfileService(
    private val profiles: ChannelAiProfileRepository,
) {
    fun get(
        guildId: Long,
        channelId: Long,
    ): ChannelAiProfile? = profiles.findByGuildIdAndChannelId(guildId, channelId)?.toProfile()

    @Transactional
    fun set(
        guildId: Long,
        channelId: Long,
        displayName: String,
        avatarUrl: String?,
    ): ChannelAiProfile {
        val normalizedName = displayName.trim().take(80)
        require(normalizedName.isNotBlank()) { "프로필 이름을 입력하세요." }
        val normalizedAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        val entity =
            profiles.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiProfileEntity(guildId = guildId, channelId = channelId)
        entity.displayName = normalizedName
        entity.avatarUrl = normalizedAvatar
        return profiles.save(entity).toProfile()
    }

    @Transactional
    fun clear(
        guildId: Long,
        channelId: Long,
    ) {
        profiles.deleteByGuildIdAndChannelId(guildId, channelId)
    }

    private fun ChannelAiProfileEntity.toProfile() =
        ChannelAiProfile(
            guildId = guildId,
            channelId = channelId,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
}
