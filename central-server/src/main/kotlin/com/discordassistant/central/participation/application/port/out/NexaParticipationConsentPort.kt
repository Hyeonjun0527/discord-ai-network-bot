package com.discordassistant.central.participation.application.port.out

/**
 * 관리자에 의해 켜진 NEXA 멤버 채널의 관찰·발화 동의를 영속화하는 포트.
 *
 * LIVE 채널 플래그와 동의 범위가 따로 저장되므로, 활성화·비활성화 유스케이스가 두 상태를 함께 갱신해야 한다.
 * 읽기 판정은 기존 consent 정책 포트가 계속 단일 진실 원천으로 담당한다.
 */
interface NexaParticipationConsentPort {
    fun activateMemberChannel(
        guildId: Long,
        channelId: Long,
        actorId: Long?,
    )

    fun deactivateMemberChannel(
        guildId: Long,
        channelId: Long,
    )

    fun clearChannel(
        guildId: Long,
        channelId: Long,
    )

    fun revokeGuild(guildId: Long)

    data object Noop : NexaParticipationConsentPort {
        override fun activateMemberChannel(
            guildId: Long,
            channelId: Long,
            actorId: Long?,
        ) = Unit

        override fun deactivateMemberChannel(
            guildId: Long,
            channelId: Long,
        ) = Unit

        override fun clearChannel(
            guildId: Long,
            channelId: Long,
        ) = Unit

        override fun revokeGuild(guildId: Long) = Unit
    }
}
