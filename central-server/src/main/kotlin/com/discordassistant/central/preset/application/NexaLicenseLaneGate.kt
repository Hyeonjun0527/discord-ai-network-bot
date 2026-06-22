package com.discordassistant.central.preset.application

import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.domain.model.NexaFeatureEntitlement
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import org.springframework.stereotype.Service

/**
 * NEXA 운영 lane 의 라이선스 게이트(NEXA-P15-T015, preset application).
 *
 * NEXA 실제 발화(CANARY/LIVE)는 유료/체험 기능이다. 이 게이트는 운영자가 *원하는* lane 을 라이선스로 **상한**한다.
 * licensing 도메인의 순수 판정([NexaFeatureEntitlement])을 participation 의 운영 lane([ParticipationLane])으로
 * 번역하는 한 지점이다(licensing 도메인은 participation 을 모름 — 경계 유지).
 *
 * **acceptance(T015) — 라이선스 만료가 갑자기 legacy mention-always 동작으로 바뀌지 않는다**:
 *  - 유료 접근이 없으면 실제 전송 lane(CANARY/LIVE) 요청은 **SHADOW 로 다운그레이드**된다([effectiveLane]) — NEXA 가
 *    꺼지지(LEGACY) 않는다. shadow 는 관찰·예측만(전송 0)이라 안전하고, 운영자가 명시적으로 LEGACY 로 내리기 전엔
 *    기존 mention-always 자동응답으로 *전환되지 않는다*.
 *  - 운영자가 직접 고른 [ParticipationLane.LEGACY]/[ParticipationLane.SHADOW] 는 라이선스와 무관하게 그대로 통과한다
 *    (안전 방향 — 끄기·shadow 로 내리기는 항상 허용). [safetyAlwaysAllowed] 로 명시한다.
 *
 * 순수성: application — LicenseService(판정)·도메인 타입만. JDA/routing/GLM 미참조.
 */
@Service
class NexaLicenseLaneGate(
    private val licenses: LicenseService,
) {
    /**
     * [userId] 의 라이선스로 [requested] lane 을 상한해 **실제 적용할 lane** 을 돌려준다.
     * 실제 전송이 필요한 lane 인데 유료 접근이 없으면 [ParticipationLane.SHADOW] 로 내린다(LEGACY 아님).
     * 안전 방향(LEGACY/SHADOW)은 항상 그대로 통과한다.
     */
    fun effectiveLane(
        userId: Long,
        requested: ParticipationLane,
    ): ParticipationLane = capLane(requested, NexaFeatureEntitlement.from(licenses.resolve(userId)))

    companion object {
        /**
         * 순수 상한 규칙(테스트 가능 — LicenseService 불필요). 안전 방향(LEGACY/SHADOW)은 그대로, 실제 전송 lane 은
         * 라이선스가 허용할 때만 그대로·아니면 SHADOW 로 다운그레이드(LEGACY 아님 — NEXA 를 끄지 않음).
         */
        fun capLane(
            requested: ParticipationLane,
            entitlement: NexaFeatureEntitlement,
        ): ParticipationLane =
            when {
                !requested.allowsRealSend -> requested // 안전 방향은 항상 통과.
                entitlement.realSendAllowed -> requested
                else -> ParticipationLane.SHADOW // 유료 권한 없음 → shadow 다운그레이드(legacy 아님).
            }
    }
}
