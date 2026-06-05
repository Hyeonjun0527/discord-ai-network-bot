package com.discordassistant.central.ainetwork.adapter.inbound.web.dto

import com.discordassistant.central.ainetwork.application.ChannelAiRoutingPolicySummary
import com.discordassistant.central.ainetwork.application.EffectiveRoutingPolicy
import com.discordassistant.central.ainetwork.application.ModelChoiceDecision
import com.discordassistant.central.ainetwork.application.SavedChannelAiRoutingPolicy

// 채널 AI 라우팅 정책 API 응답 DTO 모음(인바운드 웹 어댑터의 와이어 계약).
//
// - 응답 JSON 키·값·순서·null·중첩은 분해 이전과 1바이트도 다르지 않다.
// - 컨트롤러는 핸들러 반환 타입(Map/List)을 유지한 채 toMap()/toMaps() 로 위임한다(테스트 map-index 접근 보존).
// - 엔티티/리포 의존 0(application 결과만 받는다).

data class SavedChannelAiRoutingPolicyResponse(
    val saved: SavedChannelAiRoutingPolicy,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "id" to saved.id,
            "responseMode" to saved.responseMode,
            "preferredModel" to saved.preferredModel,
            "allowedModels" to saved.allowedModels,
            "costGuard" to saved.costGuard,
        )

    companion object {
        fun from(saved: SavedChannelAiRoutingPolicy): SavedChannelAiRoutingPolicyResponse = SavedChannelAiRoutingPolicyResponse(saved)
    }
}

data class ModelChoiceDecisionResponse(
    val decision: ModelChoiceDecision,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "requestedModel" to decision.requestedModel,
            "preferredModel" to decision.preferredModel,
            "selectedModel" to decision.selectedModel,
            "availableModels" to decision.availableModels,
            "fallbackReason" to decision.fallbackReason,
            "explanation" to decision.explanation,
            "userMessage" to decision.userMessage,
            "nextAction" to decision.nextAction,
            "responseMode" to decision.responseMode,
            "costGuard" to decision.costGuard,
            "requiresAvailableModel" to decision.requiresAvailableModel,
            "routingBlocked" to (decision.selectedModel == null && decision.requiresAvailableModel),
        )

    companion object {
        fun from(decision: ModelChoiceDecision): ModelChoiceDecisionResponse = ModelChoiceDecisionResponse(decision)
    }
}

data class EffectiveRoutingPolicyResponse(
    val effective: EffectiveRoutingPolicy,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "responseMode" to effective.responseMode,
            "preferredModel" to effective.preferredModel,
            "allowedModels" to effective.allowedModels,
            "minQualityTier" to effective.minQualityTier,
            "maxCandidates" to effective.maxCandidates,
            "providerTagFilter" to effective.providerTagFilter,
            "costGuard" to effective.costGuard,
        )

    companion object {
        fun from(effective: EffectiveRoutingPolicy): EffectiveRoutingPolicyResponse = EffectiveRoutingPolicyResponse(effective)
    }
}

data class ChannelAiRoutingPolicySummaryResponse(
    val summary: ChannelAiRoutingPolicySummary,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "channelId" to summary.channelId,
            "responseMode" to summary.responseMode,
            "preferredModel" to summary.preferredModel,
            "allowedModels" to summary.allowedModels,
            "minQualityTier" to summary.minQualityTier,
            "maxCandidates" to summary.maxCandidates,
        )

    companion object {
        fun from(summaries: List<ChannelAiRoutingPolicySummary>): List<Map<String, Any?>> =
            summaries.map { ChannelAiRoutingPolicySummaryResponse(it).toMap() }
    }
}
