package com.discordassistant.central.ainetwork.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity
import com.discordassistant.central.ainetwork.domain.model.OverloadRisk
import com.discordassistant.central.ainetwork.domain.model.ProviderAvailability
import com.discordassistant.central.shared.ModelQualityTier
import com.discordassistant.central.shared.ResponseMode
import org.springframework.stereotype.Component

/**
 * 채널 AI 라우팅 정책의 **순수 계산 SSOT**(모델 후보 산출·정책 해석·사용자 노출 문구·정규화).
 *
 * `ChannelAiRoutingPolicyService` 가 @Transactional 경계 안에서 리포지토리로 읽은 엔티티/시그널을
 * 입력으로 받아, TX 와 무관한 순수 함수만 수행한다. **저장(write)·@Transactional 메서드는 절대
 * 여기로 이동하지 않는다**(별 빈 프록시 경유 시 새 TX 로 의미가 바뀜). 모델 선택·정책 임계값·
 * 사용자 노출 문구·ineligible 사유는 기존 본문 그대로(1바이트 불변) 옮겼다.
 *
 * 파사드는 public 시그니처를 유지한 채 위임만 하고, 이 협력자는 생성자 기본값으로 와이어돼
 * 기존 호출자(테스트 포함)는 무수정이다.
 */
@Component
class ChannelAiRoutingPolicyResolver {
    fun defaultPolicy(guildDefaultModel: String?): EffectiveRoutingPolicy =
        EffectiveRoutingPolicy(
            responseMode = "balanced",
            preferredModel = guildDefaultModel,
            allowedModels = emptyList(),
            minQualityTier = "standard",
            maxCandidates = 1,
            providerTagFilter = emptyList(),
            costGuard = "provider_safe",
        )

    fun resolveModelChoice(
        effective: EffectiveRoutingPolicy,
        availableModels: List<String>,
        requestedModel: String?,
    ): ModelChoiceDecision {
        val allowedModels = effective.allowedModels.toSet()
        val requested = requestedModel?.trim()?.ifBlank { null }
        val desired = requested ?: effective.preferredModel?.trim()?.ifBlank { null }
        val selected = desired?.takeIf { availableModels.contains(it) } ?: availableModels.firstOrNull()
        val fallbackReason = fallbackReason(desired, selected, availableModels, allowedModels)
        return ModelChoiceDecision(
            requestedModel = requested,
            preferredModel = effective.preferredModel,
            selectedModel = selected,
            availableModels = availableModels,
            fallbackReason = fallbackReason,
            explanation = explanation(desired, selected, fallbackReason),
            userMessage = userMessage(fallbackReason),
            nextAction = nextAction(fallbackReason),
            responseMode = effective.responseMode,
            costGuard = effective.costGuard,
            requiresAvailableModel =
                effective.preferredModel != null ||
                    allowedModels.isNotEmpty() ||
                    !effective.minQualityTier.equals("standard", ignoreCase = true) ||
                    effective.providerTagFilter.isNotEmpty(),
        )
    }

    fun modelCandidates(
        guildId: Long,
        channelId: Long,
        effective: EffectiveRoutingPolicy,
        providers: List<ProviderCapabilityProfileEntity>,
        feedbackSignals: Map<Long, ProviderFeedbackSignal>,
    ): ModelCandidateCatalog {
        val allowedModels = effective.allowedModels.toSet()
        val tagFilter = effective.providerTagFilter.toSet()
        val candidates =
            providers
                .flatMap { provider ->
                    val feedback = feedbackSignals[provider.providerUserId] ?: ProviderFeedbackSignal.EMPTY
                    splitCsv(provider.modelNames).map { modelName ->
                        val providerTags = splitCsv(provider.capabilityTags).toSet()
                        val reasons = ineligibleReasons(provider, modelName, providerTags, effective, allowedModels, tagFilter)
                        ModelCandidate(
                            modelName = modelName,
                            providerUserId = provider.providerUserId,
                            providerState = provider.providerState.wire,
                            qualityTier = provider.qualityTier.wire,
                            maxBurden = provider.maxBurden.name,
                            overloadRisk = provider.overloadRisk.wire,
                            tags = providerTags.sorted(),
                            shadowQualityScore = feedback.shadowScore,
                            feedbackPositive = feedback.positive,
                            feedbackNegative = feedback.negative,
                            feedbackReports = feedback.reports,
                            eligible = reasons.isEmpty(),
                            ineligibleReasons = reasons,
                        )
                    }
                }.sortedWith(
                    compareByDescending<ModelCandidate> { it.eligible }
                        .thenByDescending { it.shadowQualityScore }
                        .thenBy { it.modelName }
                        .thenBy { it.providerUserId },
                )
        val availableModels =
            candidates
                .filter { it.eligible }
                .map { it.modelName }
                .distinct()
                .sorted()
        val modelSummaries = summarizeModelCandidates(candidates, effective, availableModels)
        return ModelCandidateCatalog(
            guildId = guildId,
            channelId = channelId,
            responseMode = effective.responseMode,
            preferredModel = effective.preferredModel,
            allowedModels = effective.allowedModels,
            minQualityTier = effective.minQualityTier,
            providerTagFilter = effective.providerTagFilter,
            availableModels = availableModels,
            unavailableAllowedModels = effective.allowedModels.filter { it !in availableModels }.sorted(),
            safetySummary = modelCandidateSafetySummary(candidates, effective.allowedModels, availableModels),
            modelSummaries = modelSummaries,
            recommendedModel = modelSummaries.firstOrNull { it.recommended }?.modelName,
            candidates = candidates,
        )
    }

    fun availableModelNames(
        providers: List<ProviderCapabilityProfileEntity>,
        allowedModels: Set<String>,
        minQualityTier: String,
        providerTagFilter: Set<String>,
    ): List<String> =
        providers
            .asSequence()
            .filter { it.providerState == ProviderAvailability.ONLINE }
            .filter { it.overloadRisk != OverloadRisk.CRITICAL }
            .filter { it.qualityTier.rank >= ModelQualityTier.rankOf(minQualityTier) }
            .filter { provider -> providerMatchesTags(provider.capabilityTags, providerTagFilter) }
            .flatMap { splitCsv(it.modelNames).asSequence() }
            .filter { allowedModels.isEmpty() || allowedModels.contains(it) }
            .distinct()
            .sorted()
            .toList()

    fun normalizeResponseMode(value: String): String = ResponseMode.normalize(value).wire

    fun normalizedCsv(values: List<String>): String? =
        values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
            .ifBlank { null }

    fun splitCsv(value: String?): List<String> =
        value
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /** 한 (provider, model) 후보가 제외되는 사유 목록(빈 목록 = eligible). 규칙·코드·순서는 분해 전과 동일. */
    private fun ineligibleReasons(
        provider: ProviderCapabilityProfileEntity,
        modelName: String,
        providerTags: Set<String>,
        effective: EffectiveRoutingPolicy,
        allowedModels: Set<String>,
        tagFilter: Set<String>,
    ): List<String> =
        buildList {
            if (provider.providerState != ProviderAvailability.ONLINE) add("provider_offline")
            if (provider.overloadRisk == OverloadRisk.CRITICAL) add("provider_critical_overload")
            if (provider.qualityTier.rank < ModelQualityTier.rankOf(effective.minQualityTier)) add("quality_below_minimum")
            if (allowedModels.isNotEmpty() && modelName !in allowedModels) add("model_not_allowed")
            if (tagFilter.isNotEmpty() && providerTags.intersect(tagFilter).isEmpty()) add("provider_tag_mismatch")
        }

    private fun summarizeModelCandidates(
        candidates: List<ModelCandidate>,
        effective: EffectiveRoutingPolicy,
        availableModels: List<String>,
    ): List<ModelCandidateSummary> {
        val preferred = effective.preferredModel?.trim()?.ifBlank { null }
        return candidates
            .groupBy { it.modelName }
            .map { (modelName, modelCandidates) ->
                val eligible = modelCandidates.filter { it.eligible }
                val reasons = modelCandidates.flatMap { it.ineligibleReasons }.distinct().sorted()
                val bestQuality = modelCandidates.maxOfOrNull { ModelQualityTier.rankOf(it.qualityTier) } ?: 0
                val protectedCount = modelCandidates.count { OverloadRisk.normalize(it.overloadRisk) == OverloadRisk.CRITICAL }
                val shadowScore = modelCandidates.filter { it.eligible }.sumOf { it.shadowQualityScore }
                ModelCandidateSummary(
                    modelName = modelName,
                    eligibleProviderCount = eligible.size,
                    totalProviderCount = modelCandidates.size,
                    protectedProviderCount = protectedCount,
                    bestQualityTier = ModelQualityTier.ofRank(bestQuality).wire,
                    shadowQualityScore = shadowScore,
                    feedbackPositive = modelCandidates.sumOf { it.feedbackPositive },
                    feedbackNegative = modelCandidates.sumOf { it.feedbackNegative },
                    feedbackReports = modelCandidates.sumOf { it.feedbackReports },
                    available = modelName in availableModels,
                    preferred = modelName == preferred,
                    recommended = false,
                    blockingReasons = if (eligible.isEmpty()) reasons else emptyList(),
                    tags = modelCandidates.flatMap { it.tags }.distinct().sorted(),
                )
            }.sortedWith(
                compareByDescending<ModelCandidateSummary> { it.preferred && it.available }
                    .thenByDescending { it.available }
                    .thenByDescending { it.eligibleProviderCount }
                    .thenByDescending { ModelQualityTier.rankOf(it.bestQualityTier) }
                    .thenByDescending { it.shadowQualityScore }
                    .thenBy { it.modelName },
            ).markRecommended()
    }

    private fun List<ModelCandidateSummary>.markRecommended(): List<ModelCandidateSummary> =
        mapIndexed { index, summary -> summary.copy(recommended = index == 0 && summary.available) }

    private fun modelCandidateSafetySummary(
        candidates: List<ModelCandidate>,
        allowedModels: List<String>,
        availableModels: List<String>,
    ): String {
        if (availableModels.isNotEmpty()) return "available"
        if (candidates.isEmpty()) return "no_provider_models_reported"
        val scopedCandidates =
            if (allowedModels.isEmpty()) {
                candidates
            } else {
                candidates.filter { it.modelName in allowedModels }
            }
        if (scopedCandidates.isEmpty()) return "allowed_models_not_reported"
        val reasons = scopedCandidates.flatMap { it.ineligibleReasons }.toSet()
        return when {
            reasons.any { it == "provider_critical_overload" } -> "provider_protection_blocks_all_allowed_models"
            reasons.any { it == "provider_offline" } -> "providers_offline_for_allowed_models"
            reasons.any { it == "provider_tag_mismatch" } -> "provider_tag_filter_blocks_allowed_models"
            reasons.any { it == "quality_below_minimum" } -> "quality_policy_blocks_allowed_models"
            reasons.any { it == "model_not_allowed" } -> "model_policy_blocks_all_models"
            else -> "no_eligible_model_candidate"
        }
    }

    private fun providerMatchesTags(
        capabilityTags: String?,
        providerTagFilter: Set<String>,
    ): Boolean =
        providerTagFilter.isEmpty() ||
            splitCsv(capabilityTags)
                .toSet()
                .intersect(providerTagFilter)
                .isNotEmpty()

    private fun fallbackReason(
        desired: String?,
        selected: String?,
        availableModels: List<String>,
        allowedModels: Set<String>,
    ): String? =
        when {
            selected == null -> "no_available_model"
            desired == null -> null
            desired == selected -> null
            allowedModels.isNotEmpty() && !allowedModels.contains(desired) -> "requested_model_not_allowed"
            !availableModels.contains(desired) -> "requested_model_unavailable"
            else -> "fallback_selected"
        }

    private fun explanation(
        desired: String?,
        selected: String?,
        fallbackReason: String?,
    ): String =
        when (fallbackReason) {
            null -> if (selected == null) "사용 가능한 모델이 없습니다." else "요청한 모델을 사용할 수 있어요."
            "requested_model_not_allowed" -> "요청한 모델은 이 채널 정책에서 허용되지 않아 사용 가능한 모델로 대체했어요."
            "requested_model_unavailable" -> "요청한 모델을 처리할 온라인 Provider가 없어 사용 가능한 모델로 대체했어요."
            "no_available_model" -> "현재 채널 정책과 Provider 상태를 만족하는 모델이 없습니다."
            else -> "${desired ?: "선호 모델"} 대신 ${selected ?: "대체 모델"}을 사용합니다."
        }

    private fun userMessage(fallbackReason: String?): String? =
        when (fallbackReason) {
            "no_available_model" ->
                "현재 채널 모델 정책과 Provider 보호 상태를 만족하는 모델이 없어 요청을 보내지 않았습니다. " +
                    "잠시 후 다시 시도하거나 관리자에게 모델 정책을 확인해 달라고 해주세요."
            "requested_model_not_allowed" ->
                "선택한 모델은 이 채널에서 허용되지 않아 사용 가능한 모델로 대체됩니다."
            "requested_model_unavailable" ->
                "선택한 모델을 처리할 온라인 Provider가 없어 사용 가능한 모델로 대체됩니다."
            "fallback_selected" ->
                "선택한 모델 대신 현재 안전하게 사용할 수 있는 모델로 대체됩니다."
            else -> null
        }

    private fun nextAction(fallbackReason: String?): String? =
        when (fallbackReason) {
            "no_available_model" -> "retry_later_or_adjust_channel_model_policy"
            "requested_model_not_allowed" -> "choose_allowed_model_or_update_policy"
            "requested_model_unavailable" -> "choose_available_model_or_wait_for_provider"
            "fallback_selected" -> "review_selected_fallback_model"
            else -> null
        }
}
