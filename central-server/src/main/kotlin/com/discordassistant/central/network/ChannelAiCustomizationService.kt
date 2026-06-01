package com.discordassistant.central.network

import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_ANSWER_LENGTH
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_CONSTITUTION
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_PURPOSE
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_SAFETY_LEVEL
import com.discordassistant.central.discord.DEFAULT_CHANNEL_AI_TONE
import com.discordassistant.central.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.persistence.AiChangeProposalEntity
import com.discordassistant.central.persistence.AiChangeProposalRepository
import com.discordassistant.central.persistence.ChannelAiEntity
import com.discordassistant.central.persistence.ChannelAiRepository
import com.discordassistant.central.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.persistence.CustomizationAuditLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ChannelAiCustomizationService(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
    private val proposals: AiChangeProposalRepository,
    private val audits: CustomizationAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun draftFromAnswers(
        job: String,
        tone: String,
        answerLength: String = "balanced",
        customName: String? = null,
    ): ChannelAiWizardDraft {
        val jobPreset = jobPreset(job)
        val tonePreset = tonePreset(tone)
        val normalizedAnswerLength = normalizeAnswerLength(answerLength)
        val name = customName?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: jobPreset.name
        return ChannelAiWizardDraft(
            name = name,
            job = jobPreset.purpose,
            tone = tonePreset,
            answerLength = normalizedAnswerLength,
            constitution = constitutionFor(jobPreset.key, tonePreset, normalizedAnswerLength),
            preview = "저는 $name 입니다. ${jobPreset.preview} 답변은 $tonePreset, 길이는 $normalizedAnswerLength 기준으로 맞출게요.",
        )
    }

    @Transactional
    fun createFromWizard(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        name: String,
        avatarUrl: String?,
        job: String,
        tone: String,
        answerLength: String,
        constitution: String?,
        requireApproval: Boolean,
    ): ChannelAiWizardResult {
        val now = Instant.now(clock)
        val channelAi =
            channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: ChannelAiEntity(guildId = guildId, channelId = channelId, source = "wizard", createdAt = now)
        channelAi.displayName = name.trim().take(80).ifBlank { "냥시스턴트" }
        channelAi.avatarUrl = avatarUrl?.trim()?.ifBlank { null }
        channelAi.updatedAt = now
        val savedChannel = channelAis.saveAndFlush(channelAi)

        val previous = savedChannel.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(savedChannel.id, it) }
        val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(savedChannel.id)?.version ?: 0) + 1
        val behavior =
            versions.saveAndFlush(
                AiBehaviorVersionEntity(
                    channelAiId = savedChannel.id,
                    version = nextVersion,
                    purpose = normalize(job, previous?.purpose, DEFAULT_CHANNEL_AI_PURPOSE, 200),
                    tone = normalize(tone, previous?.tone, DEFAULT_CHANNEL_AI_TONE, 80),
                    answerLength = normalize(answerLength, previous?.answerLength, DEFAULT_CHANNEL_AI_ANSWER_LENGTH, 40),
                    constitution = normalizeOptional(constitution, previous?.constitution ?: DEFAULT_CHANNEL_AI_CONSTITUTION, 2000),
                    safetyLevel = previous?.safetyLevel ?: DEFAULT_CHANNEL_AI_SAFETY_LEVEL,
                    createdBy = actorUserId,
                    createdAt = now,
                    changeSummary = "created from channel AI wizard",
                ),
            )
        val status = if (requireApproval) "pending" else "approved"
        if (!requireApproval) {
            savedChannel.activeBehaviorVersionId = behavior.id
            savedChannel.updatedAt = now
            channelAis.save(savedChannel)
        }
        val proposal =
            proposals.save(
                AiChangeProposalEntity(
                    guildId = guildId,
                    channelId = channelId,
                    channelAiId = savedChannel.id,
                    proposedBehaviorId = behavior.id,
                    status = status,
                    requestedBy = actorUserId,
                    reason = "channel AI wizard",
                    createdAt = now,
                    reviewedAt = if (requireApproval) null else now,
                    reviewedBy = if (requireApproval) null else actorUserId,
                ),
            )
        audit(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            action = if (requireApproval) "propose" else "publish",
            targetType = "ai_behavior_version",
            targetId = behavior.id,
            summary = "wizard created v${behavior.version} status=$status",
        )
        return ChannelAiWizardResult(savedChannel.id, behavior.id, behavior.version, proposal.id, status)
    }

    @Transactional
    fun approveProposal(
        proposalId: Long,
        reviewerUserId: Long?,
    ): AiChangeProposalEntity {
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == "pending") { "pending proposal only can be approved" }
        val channelAiId = proposal.channelAiId ?: throw IllegalArgumentException("proposal has no channel ai")
        val behaviorId = proposal.proposedBehaviorId ?: throw IllegalArgumentException("proposal has no behavior")
        val channelAi = channelAis.findById(channelAiId).orElseThrow { IllegalArgumentException("channel ai not found: $channelAiId") }
        channelAi.activeBehaviorVersionId = behaviorId
        channelAi.updatedAt = Instant.now(clock)
        channelAis.save(channelAi)
        proposal.status = "approved"
        proposal.reviewedBy = reviewerUserId
        proposal.reviewedAt = Instant.now(clock)
        val saved = proposals.save(proposal)
        val behavior = versions.findByChannelAiIdAndId(channelAiId, behaviorId)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "approve",
            targetType = "ai_behavior_version",
            targetId = behaviorId,
            summary = "approved v${behavior?.version ?: "-"}",
        )
        return saved
    }

    @Transactional
    fun rejectProposal(
        proposalId: Long,
        reviewerUserId: Long?,
        reason: String?,
    ): AiChangeProposalEntity {
        val proposal = proposals.findById(proposalId).orElseThrow { IllegalArgumentException("proposal not found: $proposalId") }
        require(proposal.status == "pending") { "pending proposal only can be rejected" }
        proposal.status = "rejected"
        proposal.reviewedBy = reviewerUserId
        proposal.reviewedAt = Instant.now(clock)
        proposal.reason = reason?.trim()?.take(500) ?: proposal.reason
        val saved = proposals.save(proposal)
        audit(
            guildId = proposal.guildId,
            channelId = proposal.channelId,
            actorUserId = reviewerUserId,
            action = "reject",
            targetType = "ai_change_proposal",
            targetId = proposal.id,
            summary = proposal.reason ?: "rejected",
        )
        return saved
    }

    fun pendingProposals(guildId: Long): List<AiChangeProposalEntity> = proposals.findByGuildIdAndStatus(guildId, "pending")

    fun channelHistory(
        guildId: Long,
        channelId: Long,
    ): ChannelAiHistory {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behaviorVersions = channelAi?.let { versions.findByChannelAiIdOrderByVersionDesc(it.id) } ?: emptyList()
        val proposalHistory = proposals.findByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        val auditHistory = audits.findTop10ByGuildIdAndChannelIdOrderByCreatedAtDesc(guildId, channelId)
        return ChannelAiHistory(channelAi, behaviorVersions, proposalHistory, auditHistory)
    }

    fun channelOnboarding(
        guildId: Long,
        channelId: Long,
    ): ChannelAiOnboarding {
        val channelAi = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        val behavior =
            channelAi?.activeBehaviorVersionId?.let { versions.findByChannelAiIdAndId(channelAi.id, it) }
                ?: channelAi?.let { versions.findTopByChannelAiIdOrderByVersionDesc(it.id) }
        val name = channelAi?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: "냥시스턴트"
        val purpose = behavior?.purpose ?: DEFAULT_CHANNEL_AI_PURPOSE
        val tone = behavior?.tone ?: DEFAULT_CHANNEL_AI_TONE
        val answerLength = behavior?.answerLength ?: DEFAULT_CHANNEL_AI_ANSWER_LENGTH
        val examples = examplesForPurpose(purpose)
        val safetyNotice = "비밀번호, API 키, 토큰, 개인정보 같은 민감정보는 보내지 마세요."
        val description = "$purpose\n말투는 $tone, 답변 길이는 $answerLength 기준으로 맞춰드릴게요."
        return ChannelAiOnboarding(
            guildId = guildId,
            channelId = channelId,
            channelAiId = channelAi?.id,
            name = name,
            title = "안녕하세요. 저는 이 채널의 $name 이에요.",
            description = description,
            safetyNotice = safetyNotice,
            examples = examples,
            message = onboardingMessage(name, description, safetyNotice, examples),
            empty = channelAi == null,
        )
    }

    private fun jobPreset(job: String): ChannelAiJobPreset =
        when (job.trim().lowercase()) {
            "development", "dev", "개발", "개발 질문", "1" ->
                ChannelAiJobPreset("development", "코드냥", "개발 질문, 에러 분석, 코드 리뷰, 테스트 작성을 돕습니다.", "개발 질문과 코드 문제를 도와드려요.")
            "translation", "translate", "번역", "2" ->
                ChannelAiJobPreset("translation", "번역냥", "한국어/영어 번역과 문장 다듬기를 돕습니다.", "번역과 문장 개선을 도와드려요.")
            "meeting", "minutes", "회의록", "3" ->
                ChannelAiJobPreset("meeting", "요약냥", "회의록 정리, 액션아이템 추출, 요약을 돕습니다.", "회의 내용을 보기 쉽게 정리해드려요.")
            "announcement", "notice", "공지", "공지 작성", "4" ->
                ChannelAiJobPreset("announcement", "공지냥", "공지 작성, 운영진 안내문, 릴리즈 노트 초안을 돕습니다.", "공지와 안내문 작성을 도와드려요.")
            else ->
                ChannelAiJobPreset("custom", "채널냥", job.trim().ifBlank { DEFAULT_CHANNEL_AI_PURPOSE }.take(200), "이 채널 목적에 맞춰 도와드려요.")
        }

    private fun tonePreset(tone: String): String =
        when (tone.trim().lowercase()) {
            "friendly", "친근", "친근하게", "1" -> "친근하게"
            "professional", "전문", "전문적으로", "2" -> "전문적으로"
            "concise", "short", "짧게", "짧고 명확하게", "3" -> "짧고 명확하게"
            else -> tone.trim().ifBlank { DEFAULT_CHANNEL_AI_TONE }.take(80)
        }

    private fun normalizeAnswerLength(answerLength: String): String =
        when (answerLength.trim().lowercase()) {
            "short", "짧게" -> "short"
            "long", "deep", "길게", "깊게" -> "long"
            else -> "balanced"
        }

    private fun constitutionFor(
        jobKey: String,
        tone: String,
        answerLength: String,
    ): String {
        val jobRule =
            when (jobKey) {
                "development" -> "코드는 실행 가능한 예시와 검증 방법을 함께 제안합니다."
                "translation" -> "원문의 의미를 보존하고, 필요한 경우 자연스러운 대안을 함께 제안합니다."
                "meeting" -> "결정사항, 할 일, 담당자, 기한을 분리해 정리합니다."
                "announcement" -> "사실과 일정은 명확히 쓰고, 과장되거나 확정되지 않은 표현을 피합니다."
                else -> "채널 목적에서 벗어난 질문은 범위를 확인한 뒤 답합니다."
            }
        return listOf(
            "확실하지 않으면 추측하지 말고 확인이 필요하다고 말합니다.",
            "민감정보(비밀번호, 토큰, 개인키, 개인정보)를 요구하거나 저장하지 않습니다.",
            "말투는 $tone 유지합니다.",
            "답변 길이는 $answerLength 정책을 따릅니다.",
            jobRule,
        ).joinToString("\n")
    }

    private fun examplesForPurpose(purpose: String): List<String> {
        val p = purpose.lowercase()
        return when {
            listOf("개발", "코드", "spring", "kotlin", "에러").any { it in p } ->
                listOf("이 에러가 왜 나는지 알려줘", "이 코드 리뷰해줘", "테스트 코드 만들어줘")
            listOf("번역", "영어", "문장").any { it in p } ->
                listOf("이 문장을 자연스럽게 번역해줘", "더 공손한 표현으로 바꿔줘", "영어 답장을 다듬어줘")
            listOf("회의", "요약", "회의록").any { it in p } ->
                listOf("회의 내용을 요약해줘", "결정사항과 할 일을 분리해줘", "액션아이템만 뽑아줘")
            listOf("공지", "안내", "릴리즈").any { it in p } ->
                listOf("공지 초안을 써줘", "운영진 말투로 다듬어줘", "짧은 안내문으로 바꿔줘")
            else ->
                listOf("이 내용을 쉽게 설명해줘", "핵심만 요약해줘", "다음 행동을 추천해줘")
        }
    }

    private fun onboardingMessage(
        name: String,
        description: String,
        safetyNotice: String,
        examples: List<String>,
    ): String =
        buildString {
            appendLine("❂ **$name 채널 AI가 준비됐어요**")
            appendLine()
            appendLine(description)
            appendLine()
            appendLine("**질문 예시**")
            examples.forEach { appendLine("- $it") }
            appendLine()
            appendLine("⚠️ $safetyNotice")
        }.trim()

    private fun audit(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        action: String,
        targetType: String,
        targetId: Long?,
        summary: String,
    ) {
        audits.save(
            CustomizationAuditLogEntity(
                guildId = guildId,
                channelId = channelId,
                actorId = actorUserId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                summary = summary.take(1000),
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun normalize(
        value: String?,
        previous: String?,
        default: String,
        max: Int,
    ): String = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous ?: default

    private fun normalizeOptional(
        value: String?,
        previous: String?,
        max: Int,
    ): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(max) ?: previous
}

data class ChannelAiOnboarding(
    val guildId: Long,
    val channelId: Long,
    val channelAiId: Long?,
    val name: String,
    val title: String,
    val description: String,
    val safetyNotice: String,
    val examples: List<String>,
    val message: String,
    val empty: Boolean,
)

data class ChannelAiWizardResult(
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val version: Int,
    val proposalId: Long,
    val status: String,
)

data class ChannelAiHistory(
    val channelAi: ChannelAiEntity?,
    val versions: List<AiBehaviorVersionEntity>,
    val proposals: List<AiChangeProposalEntity>,
    val audits: List<CustomizationAuditLogEntity>,
)

data class ChannelAiWizardDraft(
    val name: String,
    val job: String,
    val tone: String,
    val answerLength: String,
    val constitution: String,
    val preview: String,
)

private data class ChannelAiJobPreset(
    val key: String,
    val name: String,
    val purpose: String,
    val preview: String,
)
