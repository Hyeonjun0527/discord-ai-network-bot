package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * 응답 Embed 고도화(차수 13 #156). 상태별 색상 badge 로 가독성을 높인다.
 * 순수 빌더라 JDA 연결 없이 단위 테스트 가능.
 */
object EmbedFactory {
    fun stateColor(state: ProviderState): Color = when (state) {
        ProviderState.ONLINE_IDLE, ProviderState.ONLINE_BUSY -> Color(0x57F287) // green
        ProviderState.LIMITED, ProviderState.PAUSED -> Color(0xFEE75C) // yellow
        ProviderState.UNHEALTHY, ProviderState.OFFLINE -> Color(0xED4245) // red
        else -> Color(0x5865F2) // blurple(중립)
    }

    /** 프로바이더 상태 Embed: 상태 색상 + 처리중/잔여/실패. */
    fun providerStatus(providerId: Long, state: ProviderState, inFlight: Int, failures: Int): MessageEmbed =
        EmbedBuilder()
            .setTitle("프로바이더 상태")
            .setColor(stateColor(state))
            .addField("상태", state.name, true)
            .addField("처리중", inFlight.toString(), true)
            .addField("실패", failures.toString(), true)
            .setFooter("provider:$providerId")
            .build()

    /** 풀 요약 Embed. */
    fun poolSummary(active: Int, models: Int, inFlight: Int): MessageEmbed =
        EmbedBuilder()
            .setTitle("Provider Pool 요약")
            .setColor(if (active > 0) Color(0x57F287) else Color(0xED4245))
            .addField("활성 프로바이더", "${active}명", true)
            .addField("제공 모델", "${models}종", true)
            .addField("처리중", "${inFlight}건", true)
            .build()
}
