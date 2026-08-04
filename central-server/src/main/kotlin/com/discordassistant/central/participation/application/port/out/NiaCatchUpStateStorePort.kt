package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.catchup.NiaCatchUpClaim
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.application.catchup.NiaCatchUpState
import java.time.Instant

/** CATCH_UP 채널 상태의 영속·lease 경계다. */
interface NiaCatchUpStateStorePort {
    fun lock(scope: NiaCatchUpScope): NiaCatchUpState?

    fun lockClaim(claim: NiaCatchUpClaim): NiaCatchUpState?

    fun save(state: NiaCatchUpState): NiaCatchUpState

    fun claimDue(
        now: Instant,
        leaseOwner: String,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<NiaCatchUpClaim>

    fun deleteScope(scope: NiaCatchUpScope)

    fun deleteChannel(
        guildId: Long,
        channelId: Long,
    )

    fun deleteGuild(guildId: Long)
}
