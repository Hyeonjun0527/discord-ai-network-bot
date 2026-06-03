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
    private val backfillIndexer: OnboardingBackfillIndexer,
    private val clock: Clock = Clock.systemUTC(),
    private val featureGate: AiNetworkFeatureGate = AiNetworkFeatureGate(),
) {
    /**
     * 이미 정제(sanitize)된 백필 입력. JDA 접근은 discord 레이어(CommandService/GuildHistoryBackfillService)가 하고,
     * 이 network 서비스는 **정제 결과(텍스트 + 카운트)만** 받아 RAG 색인·run 기록을 담당한다(레이어 규칙 — JDA 비의존).
     *
     * @param indexText 색인용 정제 텍스트(작성자 익명화·민감 라인 제거 완료). 비어 있으면 색인하지 않는다.
     * @param backfilledMessageCount 색인된 메시지 수(run 기록용).
     * @param scrubbedCount 스크럽된 민감 라인 수(run 기록용).
     */
    data class BackfillInput(
        val indexText: String,
        val backfilledMessageCount: Int,
        val scrubbedCount: Int,
    )

    @Transactional
    fun startOnboarding(
        guildId: Long,
        channelId: Long,
        actorUserId: Long?,
        actorRoleIds: Collection<Long> = emptyList(),
        actorIsGuildAdmin: Boolean = true,
        channelName: String? = null,
        channelWhitelist: Set<Long> = emptySet(),
        historyLimit: Int = 0,
        backfill: BackfillInput? = null,
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

        // 1) consent 기록 — 화이트리스트가 있으면 메시지 본문 백필 동의로 기록(채널 id CSV 직렬화).
        val optedIn = channelWhitelist.isNotEmpty()
        val consent =
            consents.save(
                GuildOnboardingConsentEntity(
                    guildId = guildId,
                    actorUserId = actorUserId,
                    channelWhitelist = serializeWhitelist(channelWhitelist),
                    messageBackfillOptedIn = optedIn,
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

        // 4) 백필 텍스트가 있으면 RAG 지식공간 생성 + source 색인(위험도 review/sensitive 면 자동 색인 안 됨 — 검토 큐).
        //    "파인튜닝 학습"이 아니라 RAG 색인이라 source row 삭제로 즉시 잊을 수 있다.
        //    색인은 OnboardingBackfillIndexer 의 REQUIRES_NEW 독립 트랜잭션에서 수행한다 → 색인 실패가
        //    consent/proposal/run(아래)을 롤백하지 않는다(S3 — 공유 트랜잭션 rollback-only 함정 차단).
        val indexed = indexBackfillIfPresent(guildId, channelId, wizard.channelAiId, actorUserId, backfill)

        // 5) run 추적(proposed).
        runs.save(
            GuildOnboardingRunEntity(
                guildId = guildId,
                channelId = channelId,
                consentId = consent.id,
                proposalId = wizard.proposalId,
                channelAiId = wizard.channelAiId,
                knowledgeSpaceId = indexed?.knowledgeSpaceId,
                analysisSource = "heuristic",
                status = "proposed",
                backfilledMessageCount = backfill?.backfilledMessageCount ?: 0,
                scrubbedCount = backfill?.scrubbedCount ?: 0,
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
            knowledgeSpaceId = indexed?.knowledgeSpaceId,
            backfilledMessageCount = backfill?.backfilledMessageCount ?: 0,
            scrubbedCount = backfill?.scrubbedCount ?: 0,
            knowledgeIndexed = indexed?.indexed ?: false,
        )
    }

    /**
     * 정제된 백필 텍스트를 RAG 지식공간/소스로 색인한다(텍스트가 있을 때만). 색인은 [OnboardingBackfillIndexer] 의
     * REQUIRES_NEW 독립 트랜잭션에서 수행하므로, 색인 실패는 이 호출부(startOnboarding)의 consent/proposal/run 을 롤백하지 않는다.
     * runCatching 으로 실패를 삼켜 온보딩 본체가 절대 막히지 않게 한다(색인 없이 진행).
     */
    private fun indexBackfillIfPresent(
        guildId: Long,
        channelId: Long,
        channelAiId: Long,
        actorUserId: Long?,
        backfill: BackfillInput?,
    ): BackfillIndexResult? {
        val text = backfill?.indexText?.trim()
        if (text.isNullOrBlank()) return null
        return runCatching {
            backfillIndexer.indexBackfill(
                guildId = guildId,
                channelId = channelId,
                channelAiId = channelAiId,
                actorUserId = actorUserId,
                indexText = text,
            )
        }.getOrElse {
            log.warn("onboarding backfill indexing failed guild={} channel={}: {}", guildId, channelId, it.message)
            null
        }
    }

    private fun serializeWhitelist(channelWhitelist: Set<Long>): String? =
        channelWhitelist.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")

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
    val knowledgeSpaceId: Long? = null,
    val backfilledMessageCount: Int = 0,
    val scrubbedCount: Int = 0,
    val knowledgeIndexed: Boolean = false,
)
