package com.discordassistant.central.network

import com.discordassistant.central.persistence.GuildOnboardingConsentEntity
import com.discordassistant.central.persistence.GuildOnboardingConsentRepository
import com.discordassistant.central.persistence.GuildOnboardingRunEntity
import com.discordassistant.central.persistence.GuildOnboardingRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 디스코드 서버 AI 자동 온보딩(Phase 1 — 동의 기반 골격).
 *
 * 관리자가 `/ai-onboard`(또는 입장 배너의 "AI 자동 설정하기")를 누르면:
 *  1) consent 를 기록하고(메시지 본문 백필은 기본 미동의),
 *  2) 채널명 기반 휴리스틱으로 채널 AI 페르소나 draft 를 만들고,
 *  3) [ChannelAiCustomizationService.createFromWizard] 를 `requireApproval=true` 로 호출해 **항상 PENDING 제안**을 만들고,
 *  4) onboarding run 으로 추적한다.
 *
 * 메시지 백필·RAG 색인·LLM 추론은 Phase 2/3 범위라 이 서비스에서는 하지 않는다(휴리스틱만).
 * 승인/거절은 기존 승인 워크플로([ChannelAiCustomizationService.approveProposal]/[rejectProposal])에 위임한다.
 */
@Service
class GuildOnboardingService(
    private val channelAiCustomization: ChannelAiCustomizationService,
    private val consents: GuildOnboardingConsentRepository,
    private val runs: GuildOnboardingRunRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    @Transactional
    fun startOnboarding(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
        channelName: String? = null,
    ): GuildOnboardingResult {
        featureGate.requireChannelAiEnabled()
        // 권한 게이트는 createFromWizard 안에서도 강제되지만, consent/run 행을 남기기 전에 먼저 확인한다.
        channelAiCustomization.requireCanManageChannelAi(
            guildId = guildId,
            channelId = channelId,
            actorUserId = actorUserId,
            actorRoleIds = actorRoleIds,
            actorIsGuildAdmin = actorIsGuildAdmin,
            action = "auto_onboard_start",
        )
        val now = Instant.now(clock)

        // 1) consent 기록 — Phase 1 은 메시지 백필 미동의·화이트리스트 없음.
        val consent =
            consents.save(
                GuildOnboardingConsentEntity(
                    guildId = guildId,
                    actorUserId = actorUserId,
                    channelWhitelist = null,
                    messageBackfillOptedIn = false,
                    createdAt = now,
                ),
            )

        // 2) 채널명 휴리스틱으로 job 추론 → draft.
        val job = inferJobFromChannelName(channelName)
        val draft = channelAiCustomization.draftFromAnswers(job = job, tone = "friendly", answerLength = "balanced")

        // 3) 항상 PENDING 제안 생성(requireApproval=true).
        val wizard =
            channelAiCustomization.createFromWizard(
                guildId = guildId,
                channelId = channelId,
                actorUserId = actorUserId,
                actorRoleIds = actorRoleIds,
                actorIsGuildAdmin = actorIsGuildAdmin,
                name = draft.name,
                avatarUrl = null,
                job = draft.job,
                tone = draft.tone,
                answerLength = draft.answerLength,
                constitution = draft.constitution,
                requireApproval = true,
            )

        // 4) run 추적(proposed).
        runs.save(
            GuildOnboardingRunEntity(
                guildId = guildId,
                channelId = channelId,
                consentId = consent.id,
                proposalId = wizard.proposalId,
                channelAiId = wizard.channelAiId,
                knowledgeSpaceId = null,
                analysisSource = "heuristic",
                status = "proposed",
                backfilledMessageCount = 0,
                scrubbedCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )

        return GuildOnboardingResult(
            proposalId = wizard.proposalId,
            channelAiId = wizard.channelAiId,
            behaviorVersionId = wizard.behaviorVersionId,
            version = wizard.version,
            status = wizard.status,
            consentId = consent.id,
            name = draft.name,
            job = draft.job,
            tone = draft.tone,
            answerLength = draft.answerLength,
            constitution = draft.constitution,
            preview = draft.preview,
        )
    }

    @Transactional
    fun approveOnboarding(
        proposalId: Long,
        reviewerUserId: Long?,
        reviewerRoleIds: Collection<Long> = emptyList(),
        reviewerIsGuildAdmin: Boolean = true,
        reason: String? = null,
    ): AiChangeProposalReview {
        featureGate.requireChannelAiEnabled()
        val review =
            channelAiCustomization.approveProposal(
                proposalId = proposalId,
                reviewerUserId = reviewerUserId,
                reviewerRoleIds = reviewerRoleIds,
                reviewerIsGuildAdmin = reviewerIsGuildAdmin,
                reason = reason,
            )
        markRunStatus(proposalId, "approved")
        return review
    }

    @Transactional
    fun rejectOnboarding(
        proposalId: Long,
        reviewerUserId: Long?,
        reviewerRoleIds: Collection<Long> = emptyList(),
        reviewerIsGuildAdmin: Boolean = true,
        reason: String? = null,
    ): AiChangeProposalReview {
        featureGate.requireChannelAiEnabled()
        val review =
            channelAiCustomization.rejectProposal(
                proposalId = proposalId,
                reviewerUserId = reviewerUserId,
                reviewerRoleIds = reviewerRoleIds,
                reviewerIsGuildAdmin = reviewerIsGuildAdmin,
                reason = reason,
            )
        markRunStatus(proposalId, "rejected")
        return review
    }

    private fun markRunStatus(
        proposalId: Long,
        status: String,
    ) {
        val tracked = runs.findByProposalId(proposalId)
        if (tracked == null) {
            // 제안은 위임 호출에서 이미 처리됐으나 run 추적 행이 없다(데이터 불일치). 승인 자체는 막지 않고 경고만 남긴다.
            log.warn("onboarding run not found for proposalId={}; status '{}' left untracked", proposalId, status)
            return
        }
        tracked.status = status
        tracked.updatedAt = Instant.now(clock)
        runs.save(tracked)
    }

    /** 채널명만으로 job preset 을 추론한다(휴리스틱). 매칭 없으면 채널명 자체를 목적 문구로 사용한다. */
    private fun inferJobFromChannelName(channelName: String?): String {
        val raw = channelName?.trim().orEmpty()
        val name = raw.lowercase()
        if (name.isBlank()) return "custom"
        return when {
            DEV_HINTS.any { it in name } -> "development"
            TRANSLATION_HINTS.any { it in name } -> "translation"
            MEETING_HINTS.any { it in name } -> "meeting"
            ANNOUNCEMENT_HINTS.any { it in name } -> "announcement"
            // 매칭 없으면 영어 "custom" 리터럴 대신 채널명을 목적으로 노출(예: "잡담").
            else -> raw
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(GuildOnboardingService::class.java)

        val DEV_HINTS = listOf("dev", "개발", "code", "코드", "프로그래밍", "engineering", "버그", "bug")
        val TRANSLATION_HINTS = listOf("번역", "translate", "translation", "language", "언어", "english", "영어")
        val MEETING_HINTS = listOf("회의", "meeting", "회의록", "minutes", "스탠드업", "standup", "스크럼", "scrum")
        val ANNOUNCEMENT_HINTS = listOf("공지", "announce", "notice", "릴리즈", "release", "안내", "운영")
    }
}

data class GuildOnboardingResult(
    val proposalId: Long,
    val channelAiId: Long,
    val behaviorVersionId: Long,
    val version: Int,
    val status: String,
    val consentId: Long,
    val name: String,
    val job: String,
    val tone: String,
    val answerLength: String,
    val constitution: String,
    val preview: String,
)
