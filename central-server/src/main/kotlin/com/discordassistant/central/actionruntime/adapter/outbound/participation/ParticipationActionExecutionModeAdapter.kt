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
 * 예약 당시 [ShadowMode]는 실행 권한의 상한이고, due 전 현재 모드는 그 권한을 더 좁힐 수만 있다. 따라서 SHADOW에서
 * 예약된 action은 이후 채널이 LIVE로 승격돼도 실제 전송될 수 없다.
 * 암호화 저장 후 복원된 routingChannelId가 없거나 숫자가 아니면 운영 Discord 채널로 확정할 수 없으므로 fail-closed한다.
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
        originRolloutMode: ShadowMode,
    ): ShadowMode {
        val channelId = target.discordChannelId()?.toLongOrNull() ?: return ShadowMode.OFF
        val storedMode = shadowModeStore.currentMode(target.guildPseudonym)
        val guildLane = if (storedMode == ShadowMode.OFF) globalDefaultLane else ParticipationLane.fromShadowMode(storedMode)
        val currentMode =
            NexaParticipationGate.resolve(
                channelId = channelId,
                guildLane = guildLane,
                channelOverride = flagPort.channelOverride(target.guildPseudonym, channelId),
                excludedChannelIds = flagPort.excludedChannelIds(target.guildPseudonym),
            )
        return originRolloutMode.restrictiveIntersection(currentMode)
    }
}
