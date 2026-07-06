package com.discordassistant.central.routing.domain.model

import com.discordassistant.central.shared.ModelBurden
import com.discordassistant.central.shared.RequestState

/** 프로바이더 정책 프로필(부담수준·허용·제한). DB(contribution policy) 또는 테스트 스텁이 제공. */
data class ProviderProfile(
    val supportedBurdens: Set<ModelBurden>,
    val allowedRoleIds: Set<Long>? = null,
    val allowedChannelIds: Set<Long>? = null,
    val maxPromptChars: Int = 100_000,
    val failureRate: Double = 0.0,
    val qualityTier: String = "standard",
    val privacyCapabilities: Set<RoutingPrivacyPolicy> = setOf(RoutingPrivacyPolicy.STANDARD),
)

/** 요청 입력. */
data class AiRequestInput(
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val prompt: String,
    val roleIds: Set<Long>,
    val command: String = "ask",
    val isAdmin: Boolean = false,
    val preferredModel: String? = null,
    val responseMode: String = "balanced",
    val webSearch: Boolean = false,
    /**
     * 요청 무게(부담 수준) 판단에 쓰는 길이. 비우면 [prompt] 길이를 쓴다. /ask 는 사용자의 실제 질문 길이를
     * 넘겨, 항상 주입되는 시스템 프롬프트(안전 가드레일·정체성·few-shot)가 부담 수준을 부풀려 정상 질문이
     * 상위 등급으로 거부되지 않게 한다.
     */
    val weighChars: Int? = null,
)

/** 사용자 표시 문구와 독립적으로 검증·집계할 수 있는 요청 거절 사유 코드. */
enum class RequestRejectionCode {
    DUPLICATE_REQUEST,
    BLOCKED_USER,
    QUOTA_EXCEEDED,
    CHANNEL_NOT_ALLOWED,
    BURDEN_NOT_ALLOWED,
    POLICY_DENIED,
}

/** 오케스트레이션 결과. */
data class OrchestrationResult(
    val state: RequestState,
    val text: String? = null,
    val providerId: Long? = null,
    val failReason: String? = null,
    val effectiveBurden: ModelBurden? = null,
    val requestId: String? = null,
    val sources: List<String> = emptyList(),
    val rejectionCode: RequestRejectionCode? = null,
)
