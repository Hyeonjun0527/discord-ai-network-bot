package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.application.port.out.SceneKey
import com.discordassistant.central.participation.application.shadow.PolicyComparisonRow
import com.discordassistant.central.participation.application.shadow.ShadowGuildStatus
import com.discordassistant.central.participation.application.shadow.ShadowStatusService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 관리자 shadow 상태 read-only API(NEXA-P09-T010). 길드별 모드·수집 기간·예측 수·오류율을 제공한다.
 *
 * **인증**: 경로(`/api/ai-network/shadow` 하위)는 [com.discordassistant.central.global.security.AiNetworkApiSecurityFilter]
 * 의 sensitive read 목록에 등록돼 OAuth(허용목록) 또는 admin-token 헤더 없이는 403 이다(기존 admin 대시보드 가드 재사용).
 *
 * **acceptance(T010) — 원문과 개별 사용자 행동은 기본 응답에 노출되지 않는다**:
 * 응답 DTO([ShadowStatusDto])는 **집계 수치·단계 코드만** 담는다 — 개별 예측 레코드·feature hash·사용자 행동을
 * 포함하지 않는다(서비스가 집계만 노출). read-only(GET) 라 상태를 바꾸지 않는다.
 */
@RestController
@RequestMapping("/api/ai-network/shadow")
class ShadowStatusController(
    private val shadowStatus: ShadowStatusService,
) {
    /** 모드가 설정된 모든 길드의 shadow 상태(집계만). */
    @GetMapping("/status")
    fun listStatus(): List<ShadowStatusDto> = shadowStatus.listStatuses().map { it.toDto() }

    /** 한 길드의 shadow 상태(집계만). */
    @GetMapping("/{guildPseudonym}/status")
    fun guildStatus(
        @PathVariable guildPseudonym: String,
    ): ShadowStatusDto = shadowStatus.statusFor(guildPseudonym).toDto()

    /**
     * 한 장면(guild/channel/sceneSeq)의 정책별 발화율·침묵률 비교(T011 시각화). 같은 장면을 여러 baseline 이
     * 어떻게 다르게 봤는지 — 집계 분포만(원문/개별 행동 비포함).
     */
    @GetMapping("/{guildPseudonym}/scene")
    fun sceneComparison(
        @PathVariable guildPseudonym: String,
        @RequestParam channelId: String,
        @RequestParam sceneSeq: Long,
    ): List<PolicyComparisonDto> =
        shadowStatus
            .comparePolicies(SceneKey(guildPseudonym = guildPseudonym, channelId = channelId, sceneSeq = sceneSeq))
            .map { it.toDto() }

    private fun PolicyComparisonRow.toDto(): PolicyComparisonDto =
        PolicyComparisonDto(
            modelVersion = modelVersion,
            speakRate = speakRate,
            silenceRate = silenceRate,
            sampledAction = sampledAction,
            expectedFireAt = expectedFireAt,
        )

    private fun ShadowGuildStatus.toDto(): ShadowStatusDto =
        ShadowStatusDto(
            guildPseudonym = guildPseudonym,
            mode = mode.name,
            // OBSERVE_ONLY/SHADOW_PREDICT 는 실제 전송이 없음을 응답에서 명시(LIVE 혼동 방지 — T011 배지 근거).
            realSendActive = mode.allowsRealSend,
            predictionCount = predictionCount,
            firstPredictedAt = firstPredictedAt,
            lastPredictedAt = lastPredictedAt,
            errorCount = errorCount,
            errorRate = errorRate,
        )
}

/**
 * shadow 상태 응답 DTO(adapter 레이어). **집계 수치·단계 코드만** — 원문/개별 사용자 행동 비포함(acceptance T010).
 */
data class ShadowStatusDto(
    val guildPseudonym: String,
    /** 현재 단계 코드(OFF/OBSERVE_ONLY/SHADOW_PREDICT/CANARY/LIVE). */
    val mode: String,
    /** 이 단계에서 실제 Discord 전송이 일어나는가(CANARY/LIVE 만 true) — OBSERVE_ONLY/LIVE 혼동 방지. */
    val realSendActive: Boolean,
    /** 기록된 예측 수(수집량). */
    val predictionCount: Long,
    /** 수집 시작 시각(없으면 null). */
    val firstPredictedAt: Instant?,
    /** 수집 끝 시각(없으면 null). */
    val lastPredictedAt: Instant?,
    /** 예측 오류 수. */
    val errorCount: Long,
    /** 오류율 [0,1] = 오류 / (예측 + 오류). */
    val errorRate: Double,
)

/**
 * 정책별 장면 비교 DTO(adapter 레이어). 정책별 발화율/침묵률·샘플 결과만(집계 — 원문/개별 행동 비포함, T011).
 */
data class PolicyComparisonDto(
    val modelVersion: String,
    /** SPEAK 확률(발화율). */
    val speakRate: Double,
    /** IGNORE 확률(침묵률). */
    val silenceRate: Double,
    /** seed 로 접은 샘플 행동 코드. */
    val sampledAction: String,
    /** 예상 발사 시각(NEVER/IGNORE 면 null). */
    val expectedFireAt: Instant?,
)
