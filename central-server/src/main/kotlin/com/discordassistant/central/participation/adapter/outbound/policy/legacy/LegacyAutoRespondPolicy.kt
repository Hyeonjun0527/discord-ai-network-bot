package com.discordassistant.central.participation.adapter.outbound.policy.legacy

import com.discordassistant.central.participation.application.feature.FeatureCatalog
import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.participation.application.port.out.PolicyDecisionRequest
import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.application.port.out.PolicyEngineCapabilities
import com.discordassistant.central.participation.application.port.out.SocialPolicyPort
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import java.util.concurrent.CompletableFuture

/**
 * 현재 channelai 자동응답 로직을 **수정 없이** policy contract 뒤에서 측정 가능하게 감싼 adapter(NEXA-P09-T006).
 *
 * 운영 동작([com.discordassistant.central.platform.discord.DiscordBot] `onMessageReceived` 두 분기)을 정책 계약으로
 * 그대로 표현한다 — 실제 봇 코드는 한 줄도 바꾸지 않고, shadow 비교에서 "지금 운영 정책이 이 장면에 무슨 결정을
 * 냈을지"를 baseline 들과 같은 계약/로그/replay 경로로 측정한다.
 *
 * **운영 동작의 충실한 미러(DiscordBot.onMessageReceived)**:
 *  1. 봇이 직접 mention([FeatureCatalog.BURST_HAS_MENTION]) → 멘션 ask 흐름 = SPEAK.
 *  2. 아니면 자동응답 채널([PolicyConfigView.autoRespondEnabled]=true) → 자동 응답 = SPEAK.
 *  3. 둘 다 아니면 봇이 관여하지 않음 = IGNORE.
 *
 * **운영과의 경계(golden 으로 기록되는 차이, acceptance T006)**: 실제 봇은 정책 호출 *이전에* `.` 시작/빈 내용
 * 제외([com.discordassistant.central.channelai.application.AutoRespondChannelRegistry.shouldRespond])와 채널 분당
 * rate-limit(throttle)을 **사전 필터**로 적용한다. 그 두 가지는 콘텐츠 원문/시간창 상태라 정책 계약(원문·시계
 * 비참조)의 책임이 아니다 — 이 adapter 는 "정책 호출에 도달한 장면"에 대한 운영 결정만 표현하고, pre-filter 로
 * 걸러진 장면(=IGNORE)과의 차이는 golden test 가 명시 기록한다(legacy ↔ 신규 baseline diff).
 *
 * speechAllowed=false(feature gate 차단)면 mention/auto 여도 SPEAK 금지(계약 안전 — 분포 밖 발화 금지).
 */
class LegacyAutoRespondPolicy :
    ParticipationPolicyPort,
    SocialPolicyPort {
    override fun capabilities(): PolicyEngineCapabilities =
        PolicyEngineCapabilities(supportedSchemaVersions = setOf(1), supportedModelVersions = emptySet())

    override fun decide(request: PolicyDecisionRequest): PolicyDecisionResponse {
        val mentioned = request.features[FeatureCatalog.BURST_HAS_MENTION]?.let { !it.missing && it.value >= 0.5 } ?: false
        val speaks = request.config.speechAllowed && (mentioned || request.config.autoRespondEnabled)
        val weights =
            if (speaks) {
                mapOf(SocialActionKind.SPEAK to 1.0)
            } else {
                mapOf(SocialActionKind.IGNORE to 1.0)
            }
        return PolicyDecisionResponse(
            actionWeights = weights,
            targetDistribution = ActionTargetDistribution.none(resolverVersion = MODEL_VERSION),
            delayDistribution = DelayDistribution.IMMEDIATE,
            socialActWeights = emptyMap(),
            burstProfile = BurstProfile.singleLine(),
            uncertainty = 0.0,
            modelVersion = MODEL_VERSION,
        )
    }

    override fun predict(request: PolicyDecisionRequest): CompletableFuture<PolicyDecisionResponse> =
        CompletableFuture.completedFuture(decide(request))

    companion object {
        /** 결정 추적·shadow 비교용 안정 모델 버전 식별자(현 운영 자동응답 로직). */
        const val MODEL_VERSION: String = "legacy-auto-respond-1"
    }
}
