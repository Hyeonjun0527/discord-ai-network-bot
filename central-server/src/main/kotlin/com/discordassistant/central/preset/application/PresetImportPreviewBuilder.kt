package com.discordassistant.central.preset.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.shared.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import org.springframework.stereotype.Service

/**
 * read-only 가져오기 미리보기 빌더: [PresetRegistryService] 에서 분리한 충돌/액션 계산 협력자.
 *
 * 채널 AI/라우팅 정책을 조회만 하고(write 없음) @Transactional 이 없어 호출자 TX 에 합류한다.
 * 공개 메타 마스킹은 [PresetContentSafety] 를 공유해 원본과 동일하다.
 */
@Service
class PresetImportPreviewBuilder(
    private val channelAis: ChannelAiRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository,
    private val safety: PresetContentSafety = PresetContentSafety(),
) {
    fun buildImportPreview(
        published: PublishedPresetEntity,
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long?,
    ): PresetImportPreview {
        val existingChannelAi = targetChannelId?.let { channelAis.findByGuildIdAndChannelId(targetGuildId, it) }
        val existingRouting = targetChannelId?.let { routingPolicies.findByGuildIdAndChannelId(targetGuildId, it) }
        val highRisk = sourceRevision.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS
        val conflicts = mutableListOf<PresetImportConflict>()
        if (targetChannelId == null) {
            conflicts +=
                PresetImportConflict(
                    code = "no_target_channel_import_only",
                    severity = "info",
                    message = "대상 채널이 없어 프리셋 보관함에만 가져오고 채널 AI에는 적용하지 않습니다.",
                )
        }
        if (existingChannelAi != null) {
            conflicts +=
                PresetImportConflict(
                    code = "existing_channel_ai_behavior",
                    severity = "warning",
                    message = "대상 채널에 이미 AI 프로필/행동 버전이 있어 적용 시 새 버전으로 덮어씁니다.",
                )
        }
        if (existingRouting != null) {
            conflicts +=
                PresetImportConflict(
                    code = "existing_routing_policy",
                    severity = "warning",
                    message = "대상 채널에 이미 응답 모드/모델 라우팅 정책이 있어 프리셋 정책으로 교체됩니다.",
                )
        }
        if (sourceRevision.maxCandidates > 1) {
            conflicts +=
                PresetImportConflict(
                    code = "multi_candidate_fanout",
                    severity = if (sourceRevision.maxCandidates >= 4) "warning" else "info",
                    message = "이 프리셋은 여러 Provider 후보를 사용할 수 있어 Provider 부담이 증가할 수 있습니다.",
                )
        }
        if (highRisk) {
            conflicts +=
                PresetImportConflict(
                    code = "high_risk_requires_review",
                    severity = "blocker",
                    message = "안전 등급이 높은 프리셋이라 바로 활성화하지 않고 승인 요청으로 전환합니다.",
                )
        }
        val action =
            when {
                targetChannelId == null -> "import_only"
                highRisk -> "propose_review"
                existingChannelAi != null -> "overwrite_channel_ai"
                else -> "create_channel_ai"
            }
        return PresetImportPreview(
            publishedPresetId = published.id,
            revisionId = sourceRevision.id,
            targetGuildId = targetGuildId,
            targetChannelId = targetChannelId,
            action = action,
            conflicts = conflicts,
            willImportPresetCopy = true,
            willApplyToChannel = targetChannelId != null,
            willOverwriteChannelAi = existingChannelAi != null,
            willOverwriteRoutingPolicy = existingRouting != null,
            willCreateApprovalProposal = highRisk && targetChannelId != null,
            title =
                with(safety) {
                    published.title.publicRequired(maxLength = 120, fallback = PresetContentSafety.REDACTED_PUBLIC_TITLE)
                },
            description = with(safety) { published.description.publicOptional(maxLength = 500) },
            purpose = sourceRevision.purpose,
            tone = sourceRevision.tone,
            answerLength = sourceRevision.answerLength,
            safetyLevel = sourceRevision.safetyLevel,
            responseMode = sourceRevision.responseMode,
            preferredModel = sourceRevision.preferredModel,
            minQualityTier = sourceRevision.minQualityTier,
            maxCandidates = sourceRevision.maxCandidates,
            providerTagFilter = safety.splitCsv(sourceRevision.providerTagFilter),
            tags = safety.splitCsv(sourceRevision.tags),
            costGuard = sourceRevision.costGuard,
            knowledgeSlotNames = safety.splitCsv(sourceRevision.knowledgeSlotNames),
            knowledgeGuide = sourceRevision.knowledgeGuide,
            exampleQuestions = safety.splitLines(sourceRevision.exampleQuestions),
        )
    }
}
