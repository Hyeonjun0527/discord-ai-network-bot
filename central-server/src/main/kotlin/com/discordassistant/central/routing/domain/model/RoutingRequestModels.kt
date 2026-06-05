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
)

/** 오케스트레이션 결과. */
data class OrchestrationResult(
    val state: RequestState,
    val text: String? = null,
    val providerId: Long? = null,
    val failReason: String? = null,
    val effectiveBurden: ModelBurden? = null,
    val requestId: String? = null,
    val sources: List<String> = emptyList(),
)
