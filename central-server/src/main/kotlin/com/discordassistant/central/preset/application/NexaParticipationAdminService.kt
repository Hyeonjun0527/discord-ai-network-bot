package com.discordassistant.central.preset.application

import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowApprovalAuthority
import com.discordassistant.central.participation.domain.model.shadow.ShadowModeTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * NEXA participation 관리자 명령 통합 서비스(NEXA-P15-T013, preset application).
 *
 * 길드 lane(shadow/live) 전이, 채널 excluded(kill switch), 채널 lane override 를 **권한 게이트 + audit** 와 함께
 * 변경한다. 정책 변경은 웹 대시보드 전용 원칙(메모리 nexa_onboarding_and_policy_ui)을 따르되, 이 서비스가 그
 * 변경의 application 진입점이다(컨트롤러/명령 어댑터가 인증 주체를 [ShadowApprovalAuthority] 로 번역해 넘긴다).
 *
 * **acceptance(T013) — 권한 없는 사용자가 설정을 변경하지 못하고 변경 audit 가 남는다**:
 *  - 길드 lane 전이는 [ShadowModeTransition.transition] 을 거친다 — 권한([authority]) 부족이면 fail-closed
 *    (IllegalArgumentException)이고, 실제 전송(CANARY/LIVE) 진입은 더 강한 권한을 요구하며, 성공 전이는 audit 를
 *    append 한다([ShadowModeStorePort.applyTransition]).
 *  - 채널 excluded/override 변경도 [authority.canManageShadow] 가 없으면 거부한다(권한 게이트).
 *
 * **human_gate(T013)**: 이 서비스는 변경 *능력*만 제공한다 — 실제 운영 lane 상향(LIVE 진입)·배포는 인간 승인 게이트를
 * 거친다(authority.canEnableRealSend + 운영 배포 절차). 코드 기본값은 OFF(legacy)라 무심코 켜지지 않는다.
 *
 * 순수성: application — participation 포트·도메인 전이 규칙·표준 [Clock] 만. JDA/routing/GLM 미참조.
 */
@Service
class NexaParticipationAdminService(
    private val shadowModeStore: ShadowModeStorePort,
    private val flagPort: NexaParticipationFlagPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 길드 lane 을 [to] 로 전이한다(권한 게이트 + audit). 권한 부족·실제 전송 진입 권한 부족이면 거부(fail-closed).
     * 같은 lane 으로의 no-op 전이는 [ShadowModeTransition] 이 거부한다.
     */
    @Transactional
    fun setGuildLane(
        guildPseudonym: String,
        to: ParticipationLane,
        authority: ShadowApprovalAuthority,
        actorId: String,
        reason: String,
    ) {
        val from = ParticipationLane.fromShadowMode(shadowModeStore.currentMode(guildPseudonym))
        // 권한·실제전송 진입·audit 규칙은 도메인 전이 서비스가 fail-closed 로 강제한다.
        val audit =
            ShadowModeTransition.transition(
                from = from.shadowMode,
                to = to.shadowMode,
                authority = authority,
                guildPseudonym = guildPseudonym,
                actorId = actorId,
                reason = reason,
                at = now(),
            )
        shadowModeStore.applyTransition(audit)
    }

    /** 채널을 NEXA 에서 제외/복귀(kill switch). 권한 없으면 거부(fail-closed). */
    @Transactional
    fun setChannelExcluded(
        guildPseudonym: String,
        channelId: Long,
        excluded: Boolean,
        authority: ShadowApprovalAuthority,
    ) {
        requireManage(authority)
        flagPort.setChannelExcluded(guildPseudonym, channelId, excluded)
    }

    /** 채널 lane override 설정/해제(null=길드 lane 상속). 권한 없으면 거부. 실제 전송 진입은 더 강한 권한 요구. */
    @Transactional
    fun setChannelLane(
        guildPseudonym: String,
        channelId: Long,
        lane: ParticipationLane?,
        authority: ShadowApprovalAuthority,
    ) {
        requireManage(authority)
        require(lane?.allowsRealSend != true || authority.canEnableRealSend) {
            "채널 실제 전송 활성화($lane)에는 별도 승인 권한이 필요하다"
        }
        flagPort.setChannelOverride(guildPseudonym, channelId, lane)
    }

    private fun requireManage(authority: ShadowApprovalAuthority) {
        require(authority.canManageShadow) { "NEXA participation 설정 변경에는 운영 권한이 필요하다" }
    }

    private fun now(): Instant = clock.instant()
}
