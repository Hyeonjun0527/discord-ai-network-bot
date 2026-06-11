package com.discordassistant.central.network

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogRepository
import com.discordassistant.central.channelai.application.ChannelAiCustomizationService
import com.discordassistant.central.channelai.domain.model.ProposalStatus
import com.discordassistant.central.preset.adapter.outbound.persistence.AiPresetRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportEntity
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetImportRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PresetRevisionRepository
import com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetRepository
import com.discordassistant.central.preset.application.PresetBehaviorInput
import com.discordassistant.central.preset.application.PresetRegistryService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

/**
 * write-lifecycle(@Transactional 라이프사이클) TX 불변식 안전망.
 *
 * 선례 [NiaAffinityIsolationTest] 와 동일한 전략: **실제 Spring 컨테이너에서 @Service 프록시를 거쳐**
 * 라이프사이클 메서드를 호출해 TX 의미(채번 단조성·원자성·롤백)를 관측한다. 단위 [DataJpaTest] 와 달리
 * 여기서는 서비스를 수동 생성하지 않고 **autowire 된 빈**을 쓰므로, @Transactional 프록시·트랜잭션
 * 매니저가 실제 앱과 동일하게 적용된다.
 *
 * 이 테스트의 목적: PESSIMISTIC_WRITE 채번 헬퍼를 @Component 로 추출한 뒤에도(추출물에 @Transactional 미부여)
 * 채번 단조성·승인 원자성·import 원자성이 **불변**임을 회귀로 고정한다. 추출 후 이 테스트가 그린이면
 * 같은 TX 합류(self-invocation→별 빈 호출) 로 TX 의미가 보존됐다는 증명이 된다.
 *
 * 주의: 클래스에 @Transactional 을 붙이지 않는다 — 외부(테스트) 트랜잭션으로 라이프사이클을 감싸면
 * 라이프사이클의 커밋/롤백 경계를 관측할 수 없기 때문이다. 대신 [cleanup] 으로 마커 행을 정리한다.
 */
@SpringBootTest
@Import(WriteLifecycleTxInvariantTest.FailureInjectionBeans::class)
class WriteLifecycleTxInvariantTest
    @Autowired
    constructor(
        private val customization: ChannelAiCustomizationService,
        private val presets: PresetRegistryService,
        private val channelAis: ChannelAiRepository,
        private val versions: AiBehaviorVersionRepository,
        private val proposals: AiChangeProposalRepository,
        private val audits: CustomizationAuditLogRepository,
        private val presetRepo: AiPresetRepository,
        private val revisions: PresetRevisionRepository,
        private val publishedPresets: PublishedPresetRepository,
        private val imports: PresetImportRepository,
        private val auditFailToggle: AuditFailToggle,
        private val importFailToggle: ImportFailToggle,
    ) {
        @AfterEach
        fun cleanup() {
            auditFailToggle.armed = false
            importFailToggle.armed = false
            // 마커 길드 범위의 행을 모두 제거(라이프사이클이 실제로 커밋하므로 명시적 정리 필요).
            imports.findAll().filter { it.targetGuildId in MARKER_GUILDS }.forEach { imports.deleteById(it.id) }
            proposals.findAll().filter { it.guildId in MARKER_GUILDS }.forEach { proposals.deleteById(it.id) }
            versions
                .findAll()
                .filter { v -> channelAis.findById(v.channelAiId).map { it.guildId in MARKER_GUILDS }.orElse(false) }
                .forEach { versions.deleteById(it.id) }
            channelAis.findAll().filter { it.guildId in MARKER_GUILDS }.forEach { channelAis.deleteById(it.id) }
            audits.findAll().filter { it.guildId in MARKER_GUILDS }.forEach { audits.deleteById(it.id) }
            revisions
                .findAll()
                .filter { r -> presetRepo.findById(r.presetId).map { it.guildId in MARKER_GUILDS }.orElse(false) }
                .forEach { revisions.deleteById(it.id) }
            publishedPresets.findAll().filter { it.publisherGuildId in MARKER_GUILDS }.forEach { publishedPresets.deleteById(it.id) }
            presetRepo.findAll().filter { it.guildId in MARKER_GUILDS }.forEach { presetRepo.deleteById(it.id) }
        }

        // --- 1) ChannelAi behavior version 채번: 단조 증가 + 활성 갱신 + 이력 -----------------------------

        @Test
        fun `createFromWizard 와 rollback 이 순차 version 을 단조 채번하고 활성 버전을 갱신한다`() {
            val guildId = MARKER_GUILD_VERSION
            val channelId = 1_001L

            // requireApproval=false → 즉시 활성. 첫 생성은 v1.
            val first =
                customization.createFromWizard(
                    guildId = guildId,
                    channelId = channelId,
                    actorUserId = 7,
                    name = "코드 니아",
                    avatarUrl = null,
                    job = "Kotlin 개발 도움",
                    tone = "친근하게",
                    answerLength = "balanced",
                    constitution = null,
                    requireApproval = false,
                )
            assertEquals(1, first.version, "첫 behavior 버전은 v1 이어야 한다")
            val channelAiId = first.channelAiId
            assertEquals(
                first.behaviorVersionId,
                channelAis.findById(channelAiId).get().activeBehaviorVersionId,
                "즉시 활성이면 활성 버전이 v1 으로 갱신돼야 한다",
            )

            // 같은 채널에서 두 번째 생성은 v2 로 단조 증가.
            val second =
                customization.createFromWizard(
                    guildId = guildId,
                    channelId = channelId,
                    actorUserId = 7,
                    name = "코드 니아2",
                    avatarUrl = null,
                    job = "Spring 개발 도움",
                    tone = "정중하게",
                    answerLength = "long",
                    constitution = null,
                    requireApproval = false,
                )
            assertEquals(2, second.version, "같은 채널의 두 번째 버전은 v2(단조 증가)")
            assertEquals(channelAiId, second.channelAiId, "같은 채널 AI 행을 재사용해야 한다")

            // rollback(v1 로) 은 v1 을 복사한 새 버전 v3 을 만든다(단조 채번 유지).
            val rolledBack =
                customization.rollbackToVersion(
                    guildId = guildId,
                    channelId = channelId,
                    targetVersion = 1,
                    actorUserId = 7,
                    requireApproval = false,
                    reason = "v1 로 되돌리기",
                )
            assertEquals(3, rolledBack.version, "rollback 도 새 version 을 단조 채번해야 한다(v3)")
            assertEquals(
                rolledBack.behaviorVersionId,
                channelAis.findById(channelAiId).get().activeBehaviorVersionId,
                "rollback(즉시 활성)은 활성 버전을 새 v3 으로 갱신해야 한다",
            )

            // 이력: 같은 채널 AI 의 모든 버전이 1,2,3 으로 보존돼야 한다.
            val history = versions.findByChannelAiIdOrderByVersionDesc(channelAiId).map { it.version }
            assertEquals(listOf(3, 2, 1), history, "behavior 이력이 단조 채번 순서로 보존돼야 한다")
        }

        // --- 2) approveProposal 원자성: 상태전이 + 활성화가 한 TX, 중간 실패 시 전부 롤백 -------------------

        @Test
        fun `approveProposal 은 제안 승인과 behavior 활성화를 한 TX 로 원자적으로 적용한다`() {
            val (channelAiId, behaviorId, proposalId) = seedPendingProposal(MARKER_GUILD_APPROVE_OK, 2_001L)

            val review = customization.approveProposalAsTrustedDashboardAdmin(proposalId = proposalId, reviewerUserId = 9)

            assertEquals(ProposalStatus.APPROVED.wire, review.status, "제안이 APPROVED 로 전이돼야 한다")
            assertEquals(
                behaviorId,
                channelAis.findById(channelAiId).get().activeBehaviorVersionId,
                "승인된 behavior 가 활성 버전으로 설정돼야 한다",
            )
        }

        @Test
        fun `approveProposal 중간 실패 시 상태전이와 활성화가 모두 롤백된다(부분적용 없음)`() {
            val (channelAiId, behaviorId, proposalId) = seedPendingProposal(MARKER_GUILD_APPROVE_FAIL, 2_002L)
            val activeBefore = channelAis.findById(channelAiId).get().activeBehaviorVersionId
            assertNull(activeBefore, "사전 조건: 아직 활성 버전이 없어야 한다")

            // approveProposal 의 마지막 단계(audit save)에서 강제 실패 → 같은 TX 의 앞선 쓰기가 전부 롤백돼야 한다.
            auditFailToggle.armed = true
            assertThrows(RuntimeException::class.java) {
                customization.approveProposalAsTrustedDashboardAdmin(proposalId = proposalId, reviewerUserId = 9)
            }

            // 부분적용 관측: 제안은 여전히 PENDING, 활성 버전은 여전히 null, behavior 활성화 없음.
            assertEquals(
                ProposalStatus.PENDING,
                proposals.findById(proposalId).get().status,
                "롤백되어 제안이 PENDING 으로 남아야 한다(이중 APPROVED·부분전이 없음)",
            )
            assertNull(
                channelAis.findById(channelAiId).get().activeBehaviorVersionId,
                "롤백되어 활성 버전 갱신이 적용되지 않아야 한다",
            )
            assertNotNull(versions.findById(behaviorId), "behavior 행 자체는 사전 시드라 존재")
        }

        // --- 3) importPreset 원자성: 복사 + 채널 적용 + importCount++ + import row 가 한 TX -----------------

        @Test
        fun `importPreset 은 복사 적용 importCount 증가 import 기록을 한 TX 로 원자적으로 수행한다`() {
            val publishedId = seedPublishedPreset(MARKER_GUILD_IMPORT_OK)
            val targetGuild = MARKER_GUILD_IMPORT_OK
            val targetChannel = 3_001L
            val countBefore = publishedPresets.findById(publishedId).get().importCount

            val result =
                presets.importPreset(
                    publishedPresetId = publishedId,
                    targetGuildId = targetGuild,
                    targetChannelId = targetChannel,
                    importedBy = 11,
                    confirmConflicts = true,
                )

            assertEquals(countBefore + 1, publishedPresets.findById(publishedId).get().importCount, "importCount 가 1 증가해야 한다")
            assertNotNull(result.createdChannelAiId, "대상 채널에 채널 AI 가 생성/적용돼야 한다")
            assertNotNull(channelAis.findByGuildIdAndChannelId(targetGuild, targetChannel), "대상 채널 AI 행이 존재해야 한다")
            assertTrue(imports.findAll().any { it.id == result.id }, "import 기록 행이 저장돼야 한다")
        }

        @Test
        fun `importPreset 적용 단계 강제 실패 시 importCount 와 채널 적용이 전부 롤백된다`() {
            val publishedId = seedPublishedPreset(MARKER_GUILD_IMPORT_FAIL)
            val targetGuild = MARKER_GUILD_IMPORT_FAIL
            val targetChannel = 3_002L
            val countBefore = publishedPresets.findById(publishedId).get().importCount

            // importPreset 의 마지막 단계(imports.save)에서 강제 실패 → 복사 preset·채널 적용·importCount++ 전부 롤백.
            importFailToggle.armed = true
            assertThrows(RuntimeException::class.java) {
                presets.importPreset(
                    publishedPresetId = publishedId,
                    targetGuildId = targetGuild,
                    targetChannelId = targetChannel,
                    importedBy = 11,
                    confirmConflicts = true,
                )
            }

            assertEquals(
                countBefore,
                publishedPresets.findById(publishedId).get().importCount,
                "롤백되어 importCount 가 변하지 않아야 한다",
            )
            assertNull(
                channelAis.findByGuildIdAndChannelId(targetGuild, targetChannel),
                "롤백되어 대상 채널에 채널 AI 가 적용되지 않아야 한다",
            )
            assertTrue(
                imports.findAll().none { it.targetGuildId == targetGuild },
                "롤백되어 import 기록이 남지 않아야 한다",
            )
        }

        // --- 시드 헬퍼 -----------------------------------------------------------------------------------

        /** PENDING 제안 + 미활성 채널 AI/behavior 를 만든다(requireApproval=true 로 createFromWizard). */
        private fun seedPendingProposal(
            guildId: Long,
            channelId: Long,
        ): Triple<Long, Long, Long> {
            val created =
                customization.createFromWizard(
                    guildId = guildId,
                    channelId = channelId,
                    actorUserId = 7,
                    name = "승인대기냥",
                    avatarUrl = null,
                    job = "검토가 필요한 도움",
                    tone = "친근하게",
                    answerLength = "balanced",
                    constitution = null,
                    requireApproval = true,
                )
            // requireApproval=true → 즉시 활성 안 됨, 제안만 PENDING 으로 생성됨.
            assertEquals(ProposalStatus.PENDING.wire, created.status, "사전 조건: 제안이 PENDING 이어야 한다")
            val proposalId =
                proposals.findByGuildIdAndStatus(guildId, ProposalStatus.PENDING).first { it.channelAiId == created.channelAiId }.id
            return Triple(created.channelAiId, created.behaviorVersionId, proposalId)
        }

        /** 임포트 가능한 published preset 을 만든다(채널 미적용 standard 안전등급). */
        private fun seedPublishedPreset(guildId: Long): Long {
            val createResult =
                presets.createPreset(
                    guildId = guildId,
                    ownerUserId = 5,
                    name = "공유 프리셋",
                    summary = "테스트용 임포트 프리셋",
                    category = "general",
                    visibility = "guild_private",
                    behavior =
                        PresetBehaviorInput(
                            purpose = "general_assistant",
                            tone = "friendly",
                            answerLength = "balanced",
                            safetyLevel = "standard",
                        ),
                )
            val published =
                presets.publishPreset(
                    presetId = createResult.id,
                    publisherUserId = 5,
                    title = "공유 프리셋",
                    description = "테스트용 임포트 프리셋",
                )
            return published.id
        }

        /**
         * 실패 주입 빈. autowire 되는 @Service(ChannelAiCustomizationService/PresetRegistryService) 가
         * @Primary 로 이 래핑 리포지토리를 주입받아 라이프사이클의 마지막 쓰기에서 예외를 던진다 →
         * 같은 @Transactional 경계 안의 앞선 쓰기가 전부 롤백되는지 실 프록시로 관측한다.
         * armed 플래그로 정상/실패 케이스를 같은 컨텍스트에서 토글한다.
         */
        @TestConfiguration
        class FailureInjectionBeans {
            @Bean
            fun auditFailToggle() = AuditFailToggle()

            @Bean
            fun importFailToggle() = ImportFailToggle()

            @Bean
            @Primary
            fun failingAuditRepository(
                delegate: CustomizationAuditLogRepository,
                toggle: AuditFailToggle,
            ): CustomizationAuditLogRepository = FailingAuditLogRepository(delegate, toggle)

            @Bean
            @Primary
            fun failingImportRepository(
                delegate: PresetImportRepository,
                toggle: ImportFailToggle,
            ): PresetImportRepository = FailingImportRepository(delegate, toggle)
        }

        companion object {
            const val MARKER_GUILD_VERSION = 920_000_001L
            const val MARKER_GUILD_APPROVE_OK = 920_000_002L
            const val MARKER_GUILD_APPROVE_FAIL = 920_000_003L
            const val MARKER_GUILD_IMPORT_OK = 920_000_004L
            const val MARKER_GUILD_IMPORT_FAIL = 920_000_005L
            val MARKER_GUILDS =
                setOf(
                    MARKER_GUILD_VERSION,
                    MARKER_GUILD_APPROVE_OK,
                    MARKER_GUILD_APPROVE_FAIL,
                    MARKER_GUILD_IMPORT_OK,
                    MARKER_GUILD_IMPORT_FAIL,
                )
        }
    }

/** approveProposal 의 audit save 에서 실패를 켜는 토글. */
class AuditFailToggle {
    @Volatile var armed: Boolean = false
}

/** importPreset 의 imports.save 에서 실패를 켜는 토글. */
class ImportFailToggle {
    @Volatile var armed: Boolean = false
}

/**
 * 감사 로그 리포지토리 데코레이터. armed 일 때 [CustomizationAuditLogEntity] save 에서 예외를 던져
 * approveProposal 의 마지막 단계 실패를 흉내낸다. 그 외 모든 호출은 위임한다.
 */
private class FailingAuditLogRepository(
    private val delegate: CustomizationAuditLogRepository,
    private val toggle: AuditFailToggle,
) : CustomizationAuditLogRepository by delegate {
    override fun <S : CustomizationAuditLogEntity> save(entity: S): S {
        if (toggle.armed) throw IllegalStateException("injected audit save failure")
        return delegate.save(entity)
    }
}

/**
 * import 리포지토리 데코레이터. armed 일 때 [PresetImportEntity] save 에서 예외를 던져
 * importPreset 의 마지막 단계(채번·적용·importCount 증가 이후) 실패를 흉내낸다.
 */
private class FailingImportRepository(
    private val delegate: PresetImportRepository,
    private val toggle: ImportFailToggle,
) : PresetImportRepository by delegate {
    override fun <S : PresetImportEntity> save(entity: S): S {
        if (toggle.armed) throw IllegalStateException("injected import save failure")
        return delegate.save(entity)
    }
}
