# Central package dependency graph baseline

- Snapshot date: 2026-06-20 KST
- Source root: `central-server/src/main/kotlin/com/discordassistant/central`
- Extraction: Kotlin `package` and `import com.discordassistant.central.*` declarations
- Compile nodes: 24 (`<root>` plus top-level central packages)
- Kotlin files scanned: 646
- Cross-node import edges: 483

## Nodes

| Node | Kotlin files |
| --- | ---: |
| `<root>` | 1 |
| `actionruntime` | 36 |
| `ainetwork` | 52 |
| `channelai` | 19 |
| `conversation` | 77 |
| `dev` | 1 |
| `global` | 44 |
| `globalpromptset` | 3 |
| `guild` | 6 |
| `knowledge` | 30 |
| `licensing` | 19 |
| `multiresponse` | 22 |
| `onboarding` | 15 |
| `participation` | 90 |
| `platform` | 45 |
| `preset` | 25 |
| `provider` | 15 |
| `quota` | 6 |
| `relay` | 12 |
| `requestlog` | 7 |
| `routing` | 19 |
| `shared` | 7 |
| `socialmemory` | 51 |
| `speech` | 44 |

## Directed cross-node imports

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `actionruntime` | `participation` | 6 | `central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/ParticipationActionRouter.kt` | `com.discordassistant.central.participation.domain.model.action.SocialAction` |
| `ainetwork` | `channelai` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity` |
| `ainetwork` | `guild` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/ChannelAiRoutingPolicyController.kt` | `com.discordassistant.central.guild.application.PolicyService` |
| `ainetwork` | `knowledge` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity` |
| `ainetwork` | `multiresponse` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/dto/AiNetworkDashboardResponses.kt` | `com.discordassistant.central.multiresponse.adapter.inbound.web.dto.MultiResponseOperationsDashboardResponse` |
| `ainetwork` | `platform` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.platform.discord.BotChannelInfo` |
| `ainetwork` | `preset` | 6 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity` |
| `ainetwork` | `provider` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/domain/model/ProviderAvailability.kt` | `com.discordassistant.central.provider.domain.model.ProviderState` |
| `ainetwork` | `relay` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `ainetwork` | `requestlog` | 5 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.requestlog.application.AnalyticsService` |
| `ainetwork` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/ProviderSafetyService.kt` | `com.discordassistant.central.routing.application.port.ProviderSafetyChecker` |
| `ainetwork` | `shared` | 16 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/outbound/persistence/AiNetworkEntities.kt` | `com.discordassistant.central.shared.ModelBurden` |
| `channelai` | `ainetwork` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiAccessControlService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `channelai` | `global` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/ChannelAiCustomizationController.kt` | `com.discordassistant.central.global.security.AiNetworkApiSecurityFilter` |
| `channelai` | `guild` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiAccessControlService.kt` | `com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleEntity` |
| `channelai` | `knowledge` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiApprovalPolicy.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSafety` |
| `channelai` | `participation` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/NexaSettingsController.kt` | `com.discordassistant.central.participation.domain.model.config.ParticipationLane` |
| `channelai` | `preset` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/NexaSettingsController.kt` | `com.discordassistant.central.preset.application.NexaSettingsAdminService` |
| `channelai` | `shared` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/ChannelAiCustomizationController.kt` | `com.discordassistant.central.shared.ContentSafety` |
| `conversation` | `global` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/NexaEventEntities.kt` | `com.discordassistant.central.global.crypto.EncryptedStringConverter` |
| `dev` | `provider` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/dev/DevController.kt` | `com.discordassistant.central.provider.application.ProviderRegistrationService` |
| `dev` | `relay` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/dev/DevController.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `dev` | `routing` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/dev/DevController.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `global` | `relay` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/global/health/PoolHealthIndicator.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `global` | `shared` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/global/i18n/I18n.kt` | `com.discordassistant.central.shared.SupportedLanguage` |
| `globalpromptset` | `global` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/globalpromptset/adapter/inbound/web/GlobalPromptSetController.kt` | `com.discordassistant.central.global.security.DashboardActor` |
| `globalpromptset` | `shared` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/globalpromptset/application/GlobalPromptSetService.kt` | `com.discordassistant.central.shared.NexaIdentity` |
| `guild` | `channelai` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/GuildRemovalCleanupService.kt` | `com.discordassistant.central.channelai.application.AutoRespondChannelRegistry` |
| `guild` | `global` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/guild/adapter/inbound/web/DashboardWriteController.kt` | `com.discordassistant.central.global.security.AiNetworkApiSecurityFilter` |
| `guild` | `provider` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/GuildRemovalCleanupService.kt` | `com.discordassistant.central.provider.application.ContributionPolicyService` |
| `guild` | `quota` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/GuildRemovalCleanupService.kt` | `com.discordassistant.central.quota.application.BlocklistService` |
| `guild` | `relay` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/GuildRemovalCleanupService.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `guild` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/PolicyService.kt` | `com.discordassistant.central.routing.application.port.RoutingPolicy` |
| `guild` | `shared` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/guild/adapter/inbound/web/DashboardWriteController.kt` | `com.discordassistant.central.shared.ModelBurden` |
| `knowledge` | `ainetwork` | 5 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/application/KnowledgeIndexingPlanner.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `knowledge` | `channelai` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/application/KnowledgeAuditWriter.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity` |
| `knowledge` | `global` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/adapter/outbound/persistence/KnowledgeEntities.kt` | `com.discordassistant.central.global.crypto.EncryptedStringConverter` |
| `knowledge` | `shared` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/application/KnowledgeIndexingPlanner.kt` | `com.discordassistant.central.shared.ContentSafety.USABLE_KNOWLEDGE_RISK_LEVELS` |
| `licensing` | `provider` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/licensing/adapter/inbound/web/LicenseController.kt` | `com.discordassistant.central.provider.application.TokenService` |
| `licensing` | `requestlog` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/licensing/adapter/outbound/ContributionAdapter.kt` | `com.discordassistant.central.requestlog.adapter.outbound.persistence.ContributionLogRepository` |
| `multiresponse` | `ainetwork` | 20 | `central-server/src/main/kotlin/com/discordassistant/central/multiresponse/adapter/inbound/web/MultiResponseController.kt` | `com.discordassistant.central.ainetwork.application.DashboardAudience` |
| `multiresponse` | `knowledge` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/multiresponse/application/MultiResponseService.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSearchService` |
| `multiresponse` | `relay` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/multiresponse/application/MultiResponseFanoutPlanner.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `multiresponse` | `shared` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/multiresponse/application/CandidateSummaries.kt` | `com.discordassistant.central.shared.ContentSafety.BLOCKING_SAFETY_FLAGS` |
| `onboarding` | `ainetwork` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildOnboardingService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `onboarding` | `channelai` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildOnboardingService.kt` | `com.discordassistant.central.channelai.application.AiChangeProposalReview` |
| `onboarding` | `guild` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/ProviderConnectOnboardingService.kt` | `com.discordassistant.central.guild.application.AutoApprovePolicy` |
| `onboarding` | `knowledge` | 5 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildHistoryBackfillService.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSafety` |
| `onboarding` | `platform` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildHistoryBackfillService.kt` | `com.discordassistant.central.platform.discord.DiscordBot` |
| `onboarding` | `provider` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/ProviderConnectOnboardingService.kt` | `com.discordassistant.central.provider.application.ProviderRegistrationService` |
| `onboarding` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/RequestOrchestratorOnboardingLlm.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `onboarding` | `shared` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/RequestOrchestratorOnboardingLlm.kt` | `com.discordassistant.central.shared.RequestState` |
| `participation` | `conversation` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/participation/application/SceneParticipationConnector.kt` | `com.discordassistant.central.conversation.domain.event.SceneUpdated` |
| `participation` | `global` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/participation/application/NexaParticipationFlagService.kt` | `com.discordassistant.central.global.crypto.ScopedPseudonymizer` |
| `platform` | `actionruntime` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/DiscordMessageExecutor.kt` | `com.discordassistant.central.actionruntime.application.port.out.ExecutionResult` |
| `platform` | `ainetwork` | 11 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AiNetworkCommandHandler.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkLaunchChecklistService` |
| `platform` | `channelai` | 16 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/ChannelProfilePanelRenderer.kt` | `com.discordassistant.central.channelai.application.ChannelAiProfileService` |
| `platform` | `conversation` | 43 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/DiscordEventMapper.kt` | `com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent` |
| `platform` | `global` | 15 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordBot.kt` | `com.discordassistant.central.global.i18n.I18n` |
| `platform` | `globalpromptset` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.globalpromptset.application.GlobalPromptSetService` |
| `platform` | `guild` | 9 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.guild.application.PolicyService` |
| `platform` | `knowledge` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSearchService` |
| `platform` | `licensing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/InfoCommandHandler.kt` | `com.discordassistant.central.licensing.application.LicenseService` |
| `platform` | `multiresponse` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.multiresponse.application.MultiResponseService` |
| `platform` | `onboarding` | 11 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.onboarding.application.GuildOnboardingResult` |
| `platform` | `participation` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaInboundBridge.kt` | `com.discordassistant.central.participation.application.NexaParticipationFlagService` |
| `platform` | `provider` | 13 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.provider.application.ContributionPolicyService` |
| `platform` | `quota` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordBot.kt` | `com.discordassistant.central.quota.application.RateLimiter` |
| `platform` | `relay` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `platform` | `requestlog` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/GuildAdminCommandHandler.kt` | `com.discordassistant.central.requestlog.application.UsageService` |
| `platform` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `platform` | `shared` | 10 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.shared.ModelBurden` |
| `preset` | `ainetwork` | 9 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetCatalogQueryService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `preset` | `channelai` | 14 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetChannelApplier.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity` |
| `preset` | `global` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/preset/adapter/outbound/persistence/PresetEntities.kt` | `com.discordassistant.central.global.crypto.EncryptedStringConverter` |
| `preset` | `knowledge` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetCatalogMapper.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSafety` |
| `preset` | `licensing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/NexaLicenseLaneGate.kt` | `com.discordassistant.central.licensing.application.LicenseService` |
| `preset` | `onboarding` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/NexaSettingsAdminService.kt` | `com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingConsentRepository` |
| `preset` | `participation` | 10 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/NexaLicenseLaneGate.kt` | `com.discordassistant.central.participation.domain.model.config.ParticipationLane` |
| `preset` | `shared` | 7 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetCatalogMapper.kt` | `com.discordassistant.central.shared.ContentSafety` |
| `provider` | `channelai` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.channelai.application.GuildChannelAiQuery` |
| `provider` | `global` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/provider/application/ContributionPolicyService.kt` | `com.discordassistant.central.global.audit.AuditLog` |
| `provider` | `globalpromptset` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.globalpromptset.application.GlobalPromptSetService` |
| `provider` | `guild` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.guild.application.GuildChannelPolicy` |
| `provider` | `knowledge` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.knowledge.application.GuildKnowledgeAdmin` |
| `provider` | `licensing` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.licensing.application.PremiumFeatureGate` |
| `provider` | `platform` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.platform.discord.BotGuildLister` |
| `provider` | `preset` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.preset.application.GuildPresetAdmin` |
| `provider` | `relay` | 7 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAgentSyncController.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `provider` | `requestlog` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/provider/application/ProviderRosterInfo.kt` | `com.discordassistant.central.requestlog.application.UsageService` |
| `provider` | `shared` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.shared.NexaIdentity` |
| `quota` | `global` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/quota/application/BlocklistService.kt` | `com.discordassistant.central.global.audit.AuditLog` |
| `quota` | `guild` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/quota/application/QuotaService.kt` | `com.discordassistant.central.guild.application.PolicyService` |
| `quota` | `requestlog` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/quota/application/QuotaService.kt` | `com.discordassistant.central.requestlog.adapter.outbound.persistence.UsageLogRepository` |
| `quota` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/quota/application/BlocklistService.kt` | `com.discordassistant.central.routing.application.port.BlocklistChecker` |
| `relay` | `ainetwork` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/relay/RelayWebSocketHandler.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkGrowthService` |
| `relay` | `provider` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/relay/ProviderSession.kt` | `com.discordassistant.central.provider.domain.model.ProviderState` |
| `requestlog` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/AnalyticsService.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository` |
| `requestlog` | `provider` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt` | `com.discordassistant.central.provider.adapter.outbound.persistence.ProviderHealthEntity` |
| `requestlog` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt` | `com.discordassistant.central.routing.application.port.UsageRecorder` |
| `requestlog` | `shared` | 6 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/adapter/outbound/persistence/RequestLogEntities.kt` | `com.discordassistant.central.shared.RequestState` |
| `routing` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity` |
| `routing` | `knowledge` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt` | `com.discordassistant.central.knowledge.application.NoWebSearch` |
| `routing` | `provider` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyEntity` |
| `routing` | `relay` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `routing` | `shared` | 17 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.shared.ModelBurden` |
| `socialmemory` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/ainetwork/JpaNiaAffinityBridge.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository` |
| `socialmemory` | `globalpromptset` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/globalpromptset/GlobalPromptSetIdentityKernelBridge.kt` | `com.discordassistant.central.globalpromptset.application.GlobalPromptSetService` |
| `socialmemory` | `participation` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/domain/event/SocialStateUpdate.kt` | `com.discordassistant.central.participation.domain.model.state.ChannelScope` |
| `socialmemory` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/extraction/CloudLlmMemoryCandidateExtractor.kt` | `com.discordassistant.central.routing.application.CloudLlm` |
| `socialmemory` | `shared` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/globalpromptset/GlobalPromptSetIdentityKernelBridge.kt` | `com.discordassistant.central.shared.NexaIdentity` |
| `speech` | `global` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt` | `com.discordassistant.central.global.privacy.ConsentGate` |
| `speech` | `globalpromptset` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/globalpromptset/SpeechIdentityKernelBridge.kt` | `com.discordassistant.central.globalpromptset.application.GlobalPromptSetService` |
| `speech` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/routing/RoutingCloudSpeechGenerationAdapter.kt` | `com.discordassistant.central.routing.application.CloudLlm` |
| `speech` | `shared` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/globalpromptset/SpeechIdentityKernelBridge.kt` | `com.discordassistant.central.shared.NexaIdentity` |

## Cycles

Top-level compile-node cycles detected:
- `actionruntime` ↔ `ainetwork` ↔ `channelai` ↔ `conversation` ↔ `global` ↔ `globalpromptset` ↔ `guild` ↔ `knowledge` ↔ `licensing` ↔ `multiresponse` ↔ `onboarding` ↔ `participation` ↔ `platform` ↔ `preset` ↔ `provider` ↔ `quota` ↔ `relay` ↔ `requestlog` ↔ `routing`

## Required focus paths

### `routing`

Outgoing:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `routing` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity` |
| `routing` | `knowledge` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt` | `com.discordassistant.central.knowledge.application.NoWebSearch` |
| `routing` | `provider` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.provider.adapter.outbound.persistence.ProviderContributionPolicyEntity` |
| `routing` | `relay` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `routing` | `shared` | 17 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.shared.ModelBurden` |

Incoming:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `ainetwork` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/ProviderSafetyService.kt` | `com.discordassistant.central.routing.application.port.ProviderSafetyChecker` |
| `dev` | `routing` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/dev/DevController.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `guild` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/PolicyService.kt` | `com.discordassistant.central.routing.application.port.RoutingPolicy` |
| `onboarding` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/RequestOrchestratorOnboardingLlm.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `platform` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `quota` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/quota/application/BlocklistService.kt` | `com.discordassistant.central.routing.application.port.BlocklistChecker` |
| `requestlog` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt` | `com.discordassistant.central.routing.application.port.UsageRecorder` |
| `socialmemory` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/extraction/CloudLlmMemoryCandidateExtractor.kt` | `com.discordassistant.central.routing.application.CloudLlm` |
| `speech` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/routing/RoutingCloudSpeechGenerationAdapter.kt` | `com.discordassistant.central.routing.application.CloudLlm` |

### `channelai`

Outgoing:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `channelai` | `ainetwork` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiAccessControlService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `channelai` | `global` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/ChannelAiCustomizationController.kt` | `com.discordassistant.central.global.security.AiNetworkApiSecurityFilter` |
| `channelai` | `guild` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiAccessControlService.kt` | `com.discordassistant.central.guild.adapter.outbound.persistence.AiAdminRoleEntity` |
| `channelai` | `knowledge` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiApprovalPolicy.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSafety` |
| `channelai` | `participation` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/NexaSettingsController.kt` | `com.discordassistant.central.participation.domain.model.config.ParticipationLane` |
| `channelai` | `preset` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/NexaSettingsController.kt` | `com.discordassistant.central.preset.application.NexaSettingsAdminService` |
| `channelai` | `shared` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/adapter/inbound/web/ChannelAiCustomizationController.kt` | `com.discordassistant.central.shared.ContentSafety` |

Incoming:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `ainetwork` | `channelai` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity` |
| `guild` | `channelai` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/guild/application/GuildRemovalCleanupService.kt` | `com.discordassistant.central.channelai.application.AutoRespondChannelRegistry` |
| `knowledge` | `channelai` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/application/KnowledgeAuditWriter.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.CustomizationAuditLogEntity` |
| `onboarding` | `channelai` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildOnboardingService.kt` | `com.discordassistant.central.channelai.application.AiChangeProposalReview` |
| `platform` | `channelai` | 16 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/ChannelProfilePanelRenderer.kt` | `com.discordassistant.central.channelai.application.ChannelAiProfileService` |
| `preset` | `channelai` | 14 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetChannelApplier.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity` |
| `provider` | `channelai` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.channelai.application.GuildChannelAiQuery` |

### `ainetwork`

Outgoing:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `ainetwork` | `channelai` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.channelai.adapter.outbound.persistence.AiChangeProposalEntity` |
| `ainetwork` | `guild` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/ChannelAiRoutingPolicyController.kt` | `com.discordassistant.central.guild.application.PolicyService` |
| `ainetwork` | `knowledge` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.knowledge.adapter.outbound.persistence.KnowledgeSourceEntity` |
| `ainetwork` | `multiresponse` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/dto/AiNetworkDashboardResponses.kt` | `com.discordassistant.central.multiresponse.adapter.inbound.web.dto.MultiResponseOperationsDashboardResponse` |
| `ainetwork` | `platform` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.platform.discord.BotChannelInfo` |
| `ainetwork` | `preset` | 6 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/AiNetworkDashboardMapper.kt` | `com.discordassistant.central.preset.adapter.outbound.persistence.PublishedPresetEntity` |
| `ainetwork` | `provider` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/domain/model/ProviderAvailability.kt` | `com.discordassistant.central.provider.domain.model.ProviderState` |
| `ainetwork` | `relay` | 4 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `ainetwork` | `requestlog` | 5 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.requestlog.application.AnalyticsService` |
| `ainetwork` | `routing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/ProviderSafetyService.kt` | `com.discordassistant.central.routing.application.port.ProviderSafetyChecker` |
| `ainetwork` | `shared` | 16 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/outbound/persistence/AiNetworkEntities.kt` | `com.discordassistant.central.shared.ModelBurden` |

Incoming:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `channelai` | `ainetwork` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiAccessControlService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `knowledge` | `ainetwork` | 5 | `central-server/src/main/kotlin/com/discordassistant/central/knowledge/application/KnowledgeIndexingPlanner.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `multiresponse` | `ainetwork` | 20 | `central-server/src/main/kotlin/com/discordassistant/central/multiresponse/adapter/inbound/web/MultiResponseController.kt` | `com.discordassistant.central.ainetwork.application.DashboardAudience` |
| `onboarding` | `ainetwork` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildOnboardingService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `platform` | `ainetwork` | 11 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AiNetworkCommandHandler.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkLaunchChecklistService` |
| `preset` | `ainetwork` | 9 | `central-server/src/main/kotlin/com/discordassistant/central/preset/application/PresetCatalogQueryService.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkFeatureGate` |
| `relay` | `ainetwork` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/relay/RelayWebSocketHandler.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkGrowthService` |
| `requestlog` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/AnalyticsService.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.AiNetworkEventRepository` |
| `routing` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/routing/adapter/outbound/DbProviderProfileProvider.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.ProviderCapabilityProfileEntity` |
| `socialmemory` | `ainetwork` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/socialmemory/adapter/outbound/ainetwork/JpaNiaAffinityBridge.kt` | `com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository` |

### `platform/discord`

Outgoing imports from `com.discordassistant.central.platform.discord*`:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `platform` | `actionruntime` | 12 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/DiscordMessageExecutor.kt` | `com.discordassistant.central.actionruntime.application.port.out.ExecutionResult` |
| `platform` | `ainetwork` | 11 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AiNetworkCommandHandler.kt` | `com.discordassistant.central.ainetwork.application.AiNetworkLaunchChecklistService` |
| `platform` | `channelai` | 16 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/ChannelProfilePanelRenderer.kt` | `com.discordassistant.central.channelai.application.ChannelAiProfileService` |
| `platform` | `conversation` | 43 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/DiscordEventMapper.kt` | `com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent` |
| `platform` | `global` | 15 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordBot.kt` | `com.discordassistant.central.global.i18n.I18n` |
| `platform` | `globalpromptset` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.globalpromptset.application.GlobalPromptSetService` |
| `platform` | `guild` | 9 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.guild.application.PolicyService` |
| `platform` | `knowledge` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.knowledge.application.KnowledgeSearchService` |
| `platform` | `licensing` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/InfoCommandHandler.kt` | `com.discordassistant.central.licensing.application.LicenseService` |
| `platform` | `multiresponse` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.multiresponse.application.MultiResponseService` |
| `platform` | `onboarding` | 11 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.onboarding.application.GuildOnboardingResult` |
| `platform` | `participation` | 1 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaInboundBridge.kt` | `com.discordassistant.central.participation.application.NexaParticipationFlagService` |
| `platform` | `provider` | 13 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.provider.application.ContributionPolicyService` |
| `platform` | `quota` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordBot.kt` | `com.discordassistant.central.quota.application.RateLimiter` |
| `platform` | `relay` | 8 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.relay.ConnectionRegistry` |
| `platform` | `requestlog` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/GuildAdminCommandHandler.kt` | `com.discordassistant.central.requestlog.application.UsageService` |
| `platform` | `routing` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt` | `com.discordassistant.central.routing.application.RequestOrchestrator` |
| `platform` | `shared` | 10 | `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt` | `com.discordassistant.central.shared.ModelBurden` |

Incoming imports to `com.discordassistant.central.platform.discord*`:

| Source | Target | Imports | Sample path | Sample import |
| --- | --- | ---: | --- | --- |
| `ainetwork` | `platform` | 3 | `central-server/src/main/kotlin/com/discordassistant/central/ainetwork/adapter/inbound/web/DashboardController.kt` | `com.discordassistant.central.platform.discord.BotChannelInfo` |
| `onboarding` | `platform` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildHistoryBackfillService.kt` | `com.discordassistant.central.platform.discord.DiscordBot` |
| `provider` | `platform` | 2 | `central-server/src/main/kotlin/com/discordassistant/central/provider/adapter/inbound/web/ProviderAdminController.kt` | `com.discordassistant.central.platform.discord.BotGuildLister` |

## Reproduce

```bash
python3 scripts/central-package-graph.py --check
```

`--check` regenerates this markdown in memory and fails if the committed snapshot drifts.
