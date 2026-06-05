package com.discordassistant.central.preset.application

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyEntity
import com.discordassistant.central.ainetwork.adapter.outbound.persistence.ChannelAiRoutingPolicyRepository
import com.discordassistant.central.ainetwork.application.ChannelAiRoutingSnapshot
import com.discordassistant.central.ainetwork.domain.model.AI_NETWORK_MAX_CANDIDATES
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity
import com.discordassistant.central.preset.domain.model.PresetImportStatus
import com.discordassistant.central.shared.ContentSafety.HIGH_RISK_SAFETY_LEVELS
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant

/**
 * preset → 채널 적용·채번 비-@Transactional 헬퍼 협력자.
 *
 * **@Transactional 미부여(의도적)**: @Transactional 인 importPreset(파사드 잔존)이 이 협력자를 호출하면
 * `channelAis.findByIdForUpdate`(PESSIMISTIC_WRITE)·`behaviorVersions.saveAndFlush`·`routingPolicies.save`·
 * `proposals.save`·`audits.save` 가 모두 호출자의 활성 트랜잭션에 그대로 합류한다 — 별 @Component 빈으로
 * 빼도 락 점유·재시도·채널 적용/제안/감사의 원자성이 추출 전과 1바이트도 다르지 않다. 여기에
 * @Transactional/REQUIRES_NEW 를 붙이면 새 TX 가 열려 락·재시도·원자성 의미가 깨진다 — 절대 부여 금지.
 */
@Component
class PresetChannelApplier(
    private val channelAis: ChannelAiRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val routingPolicies: ChannelAiRoutingPolicyRepository,
    private val revisionFactory: PresetRevisionFactory,
) {
    fun applyRevisionToChannel(
        published: PublishedPresetEntity,
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long,
        importedBy: Long?,
        now: Instant,
    ): AppliedPresetChannelAi {
        val channelAi =
            channelAis.findByGuildIdAndChannelId(targetGuildId, targetChannelId)
                ?: ChannelAiEntity(
                    guildId = targetGuildId,
                    channelId = targetChannelId,
                    source = "preset_import",
                    createdAt = now,
                )
        channelAi.displayName =
            published.title
                .trim()
                .take(80)
                .ifBlank { sourceRevision.name.take(80).ifBlank { "냥시스턴트" } }
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)
        val behavior =
            saveNextBehaviorVersion(savedChannel.id) { nextVersion ->
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = sourceRevision.purpose,
                    tone = sourceRevision.tone,
                    answerLength = sourceRevision.answerLength,
                    constitution = sourceRevision.constitution,
                    safetyLevel = sourceRevision.safetyLevel,
                    createdBy = importedBy,
                    createdAt = now,
                    changeSummary = "imported from published preset #${published.id} revision #${sourceRevision.revision}",
                )
            }
        val highRisk = sourceRevision.safetyLevel.lowercase() in HIGH_RISK_SAFETY_LEVELS
        val status = if (highRisk) PresetImportStatus.NEEDS_REVIEW else PresetImportStatus.APPLIED
        if (highRisk) {
            proposals.save(
                AiChangeProposalEntity(
                    guildId = targetGuildId,
                    channelId = targetChannelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = ProposalStatus.PENDING,
                    requestedBy = importedBy,
                    reason = "preset import requires review: ${sourceRevision.safetyLevel}",
                    payloadHash = behavior.payloadHash(),
                    routingSnapshot = ChannelAiRoutingSnapshot.fromRevision(sourceRevision).encode(),
                    createdAt = now,
                ),
            )
        } else {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
            applyRoutingPolicySnapshot(sourceRevision, targetGuildId, targetChannelId, savedChannel.id, now)
        }
        audits.save(
            CustomizationAuditLogEntity(
                guildId = targetGuildId,
                channelId = targetChannelId,
                actorId = importedBy,
                action = if (highRisk) "preset_import_proposed" else "preset_import_applied",
                targetType = "ai_behavior_version",
                targetId = behavior.id,
                summary = "publishedPreset=${published.id} revision=${sourceRevision.revision} status=${status.wire}",
                createdAt = now,
            ),
        )
        return AppliedPresetChannelAi(savedChannel.id, behavior.id, status)
    }

    /**
     * behavior version 채번(`MAX(version)+1`)+insert 를 동시성 안전하게 수행한다(#2와 동일 패턴).
     * 채널 AI 행을 PESSIMISTIC_WRITE 로 잠가 같은 채널의 채번을 직렬화하고, 유니크 위반 시
     * version 을 재조회해 최대 [MAX_VERSION_RETRIES] 회 재시도한다.
     */
    private fun saveNextBehaviorVersion(
        channelAiId: Long,
        build: (Int) -> AiBehaviorVersionEntity,
    ): AiBehaviorVersionEntity {
        var attempt = 0
        while (true) {
            channelAis.findByIdForUpdate(channelAiId)
            val nextVersion = (behaviorVersions.findTopByChannelAiIdOrderByVersionDesc(channelAiId)?.version ?: 0) + 1
            try {
                return behaviorVersions.saveAndFlush(build(nextVersion))
            } catch (ex: org.springframework.dao.DataIntegrityViolationException) {
                attempt += 1
                if (attempt >= MAX_VERSION_RETRIES) {
                    throw IllegalStateException(
                        "프리셋 적용 중 채널 AI 행동 버전 채번이 동시 변경과 계속 충돌했어요. 잠시 후 다시 시도해 주세요.",
                        ex,
                    )
                }
            }
        }
    }

    private fun applyRoutingPolicySnapshot(
        sourceRevision: PresetRevisionEntity,
        targetGuildId: Long,
        targetChannelId: Long,
        channelAiId: Long,
        now: Instant,
    ) {
        val policy =
            routingPolicies.findByGuildIdAndChannelId(targetGuildId, targetChannelId)
                ?: ChannelAiRoutingPolicyEntity(guildId = targetGuildId, channelId = targetChannelId, createdAt = now)
        policy.channelAiId = channelAiId
        policy.responseMode = normalizeResponseMode(sourceRevision.responseMode)
        policy.preferredModel = sourceRevision.preferredModel?.trim()?.ifBlank { null }
        policy.minQualityTier = sourceRevision.minQualityTier.trim().ifBlank { "standard" }
        policy.maxCandidates = sourceRevision.maxCandidates.coerceIn(1, AI_NETWORK_MAX_CANDIDATES)
        policy.providerTagFilter = sourceRevision.providerTagFilter?.trim()?.ifBlank { null }
        policy.costGuard = sourceRevision.costGuard.trim().ifBlank { "provider_safe" }
        policy.updatedAt = now
        routingPolicies.save(policy)
    }

    private fun normalizeResponseMode(value: String): String = revisionFactory.normalizeResponseMode(value)

    private fun AiBehaviorVersionEntity.payloadHash(): String =
        sha256(
            listOf(
                channelAiId.toString(),
                version.toString(),
                purpose,
                tone,
                answerLength,
                constitution.orEmpty(),
                safetyLevel,
                changeSummary.orEmpty(),
                // ChannelAiCustomizationService.payloadHash 와 동일 필드 구성을 유지해야 한다(preset import 제안도
                // 같은 approveProposal 에서 해시 검증을 받기 때문). 자유 지침 컬럼 추가에 맞춰 같이 포함한다.
                customInstruction.orEmpty(),
            ).joinToString(""),
        )

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_VERSION_RETRIES = 5
    }
}

data class AppliedPresetChannelAi(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val status: PresetImportStatus,
)
