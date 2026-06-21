package com.discordassistant.central.preset.application

import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingConsentRepository
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.config.TalkativenessMultiplier
import com.discordassistant.central.participation.domain.model.shadow.ShadowApprovalAuthority
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 NEXA 설정 통합 application 서비스(NEXA-P15-T018, preset application).
 *
 * **웹 대시보드 전용** NEXA 설정의 조회/변경 진입점이다(디스코드 명령 금지 — 메모리 nexa_onboarding_and_policy_ui).
 * 컨트롤러([com.discordassistant.central.channelai.adapter.inbound.web.NexaSettingsController])가 인증 주체
 * ([com.discordassistant.central.global.security.DashboardActor])를 [ShadowApprovalAuthority] 로 번역해 넘기면,
 * 이 서비스가 라이선스 상한([NexaLicenseLaneGate], T015) → 권한 게이트·audit([NexaParticipationAdminService], T013)
 * 순으로 변경을 적용한다.
 *
 * **acceptance(T018) — mode/multiplier/model/channel scope/consent 를 조회·변경하고 권한·audit 가 적용된다**:
 *  - [status]: 길드 유효 모드·multiplier 후보·채널 override·제외 채널·온보딩 동의(목적별)를 한 번에 읽는다(read-only).
 *  - 변경([setGuildLane]/[setChannelLane]/[setChannelExcluded])은 라이선스 상한 후 [NexaParticipationAdminService]
 *    의 권한 게이트(fail-closed)·도메인 전이 audit 를 그대로 거친다 — 권한 없으면 거부, 성공이면 audit append.
 *
 * 낙관적 동시성: shadow 단계 전이는 [com.discordassistant.central.participation.domain.model.shadow.ShadowModeTransition]
 * 이 from→to 를 명시 검사하므로(같은 단계 no-op 거부), 오래된 화면 상태로 같은 전이를 두 번 적용하면 거부된다.
 *
 * 순수성: application — 포트·도메인·라이선스 게이트·온보딩 동의 read 만. JDA/routing/GLM 미참조.
 */
@Service
class NexaSettingsAdminService(
    private val flagService: NexaParticipationFlagService,
    private val adminService: NexaParticipationAdminService,
    private val licenseLaneGate: NexaLicenseLaneGate,
    private val onboardingConsents: GuildOnboardingConsentRepository,
) {
    /** 한 길드의 NEXA 설정 스냅샷(웹 대시보드 표시용). 원문/개별 사용자 행동 비포함 — 설정값만. */
    @Transactional(readOnly = true)
    fun status(
        guildId: Long,
        channelIds: List<Long> = emptyList(),
    ): NexaSettingsStatus {
        val guildMode = flagService.effectiveMode(guildId = guildId, channelId = NO_CHANNEL)
        val guildLane = ParticipationLane.fromShadowMode(guildMode)
        val channelLanes =
            channelIds.associateWith { ch ->
                ParticipationLane.fromShadowMode(flagService.effectiveMode(guildId = guildId, channelId = ch))
            }
        val consent = latestConsent(guildId)
        return NexaSettingsStatus(
            guildLane = guildLane,
            realSendActive = guildLane.allowsRealSend,
            // multiplier 최종 기본값은 human gate(T017) — 후보값만 노출하고 "승인 대기" 의미를 드러낸다.
            talkativenessCandidate = TalkativenessMultiplier.DEFAULT_CANDIDATE,
            channelLanes = channelLanes,
            consent = consent,
        )
    }

    /** 길드 lane 변경(라이선스 상한 → 권한 게이트·audit). */
    @Transactional
    fun setGuildLane(
        guildId: Long,
        requested: ParticipationLane,
        authority: ShadowApprovalAuthority,
        actorUserId: Long?,
        reason: String,
    ) {
        val effective = licenseLaneGate.effectiveLane(actorUserId ?: SYSTEM_USER, requested)
        adminService.setGuildLane(
            guildPseudonym = guildPseudonym(guildId),
            to = effective,
            authority = authority,
            actorId = actorUserId?.toString() ?: SYSTEM_ACTOR,
            reason = reason,
        )
    }

    /** 채널 lane override 변경(라이선스 상한 → 권한 게이트). null=길드 lane 상속. */
    @Transactional
    fun setChannelLane(
        guildId: Long,
        channelId: Long,
        requested: ParticipationLane?,
        authority: ShadowApprovalAuthority,
        actorUserId: Long?,
    ) {
        val effective = requested?.let { licenseLaneGate.effectiveLane(actorUserId ?: SYSTEM_USER, it) }
        adminService.setChannelLane(
            guildPseudonym = guildPseudonym(guildId),
            channelId = channelId,
            lane = effective,
            authority = authority,
        )
    }

    /** 채널 제외(kill switch) — 라이선스와 무관하게 항상 허용(끄기는 안전 방향). 권한 게이트만 적용. */
    @Transactional
    fun setChannelExcluded(
        guildId: Long,
        channelId: Long,
        excluded: Boolean,
        authority: ShadowApprovalAuthority,
    ) {
        adminService.setChannelExcluded(
            guildPseudonym = guildPseudonym(guildId),
            channelId = channelId,
            excluded = excluded,
            authority = authority,
        )
    }

    private fun latestConsent(guildId: Long): NexaConsentStatus {
        val row = onboardingConsents.findByGuildIdOrderByCreatedAtDesc(guildId).firstOrNull()
        return NexaConsentStatus(
            observeScope = row?.nexaObserveScope ?: false,
            externalGlmAllowed = row?.nexaExternalGlmAllowed ?: false,
            liveSendAllowed = row?.nexaLiveSendAllowed ?: false,
            learningOptIn = row?.nexaLearningOptIn ?: false,
        )
    }

    private fun guildPseudonym(guildId: Long): String =
        ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = guildId)

    private companion object {
        // status read 시 길드 lane 만 보려고 채널을 안 거치게 하는 표지 채널(override/제외에 매칭되지 않는 값).
        const val NO_CHANNEL = -1L
        const val SYSTEM_USER = 0L
        const val SYSTEM_ACTOR = "system"
    }
}

/** 한 길드 NEXA 설정 스냅샷(설정값만 — 원문/개별 행동 비포함). */
data class NexaSettingsStatus(
    val guildLane: ParticipationLane,
    val realSendActive: Boolean,
    val talkativenessCandidate: Double,
    val channelLanes: Map<Long, ParticipationLane>,
    val consent: NexaConsentStatus,
)

/** NEXA 멤버 채널 온보딩 동의 상태(목적별 — 포괄 동의 아님, T014). */
data class NexaConsentStatus(
    val observeScope: Boolean,
    val externalGlmAllowed: Boolean,
    val liveSendAllowed: Boolean,
    val learningOptIn: Boolean,
)
