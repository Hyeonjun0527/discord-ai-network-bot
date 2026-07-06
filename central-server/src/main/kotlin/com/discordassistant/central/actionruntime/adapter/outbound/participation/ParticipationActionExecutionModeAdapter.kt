package com.discordassistant.central.actionruntime.adapter.outbound.participation

import com.discordassistant.central.actionruntime.application.port.out.ActionExecutionModePort
import com.discordassistant.central.actionruntime.domain.model.ActionTarget
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.NexaParticipationGate
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * actionruntime 전송 경계가 실행 직전 현재 NEXA rollout 모드를 다시 읽게 하는 어댑터.
 *
 * 예약된 action 이 LIVE 에서 만들어졌더라도 due 전 운영자가 SHADOW/OFF 로 내리면 현재 모드가 우선한다.
 * channelId 를 숫자로 해석할 수 없으면 운영 Discord 채널로 확정할 수 없으므로 fail-closed 로 OFF 를 반환한다.
 */
@Component
class ParticipationActionExecutionModeAdapter(
    private val shadowModeStore: ShadowModeStorePort,
    private val flagPort: NexaParticipationFlagPort,
    @Value("\${central.nexa.participation.global-default-lane:OFF}") globalDefaultLaneName: String,
) : ActionExecutionModePort {
    private val globalDefaultLane: ParticipationLane =
        runCatching { ParticipationLane.valueOf(globalDefaultLaneName.trim().uppercase()) }
            .getOrDefault(ParticipationLane.LEGACY)

    override fun currentMode(
        target: ActionTarget,
        requestedMode: ShadowMode,
    ): ShadowMode {
        val channelId = target.channelId.toLongOrNull() ?: return ShadowMode.OFF
        val storedMode = shadowModeStore.currentMode(target.guildPseudonym)
        val guildLane = if (storedMode == ShadowMode.OFF) globalDefaultLane else ParticipationLane.fromShadowMode(storedMode)
        return NexaParticipationGate.resolve(
            channelId = channelId,
            guildLane = guildLane,
            channelOverride = flagPort.channelOverride(target.guildPseudonym, channelId),
            excludedChannelIds = flagPort.excludedChannelIds(target.guildPseudonym),
        )
    }
}
