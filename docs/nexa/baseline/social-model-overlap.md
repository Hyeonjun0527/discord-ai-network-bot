# NEXA social model overlap baseline (T024)

Status: `REVIEW` candidate; depends on T023, which is still dependency-gated `REVIEW` through the T017 human security gate.  
Audit date: 2026-06-20.  
Scope: current `ainetwork` and `channelai` data models that can conflict with a future `socialmemory` bounded context, especially Nia affinity, channel AI profile/behavior, routing policy, and auto-response configuration.

## Current verdict

- There is no `central-server/src/main/kotlin/com/discordassistant/central/socialmemory` package yet, so this is a pre-implementation ownership audit.
- Current `ainetwork` Nia affinity is a global per-user progress/read-model (`user_affinity.user_id` is unique without `guild_id`). Future `socialmemory` relationship state is planned as guild-scoped, observable, replayable state. Do not duplicate Nia affinity score/stage as a socialmemory entity field.
- Current `channelai` is the owner of channel profile and behavior configuration. Future `socialmemory` may read it as context, but it must not write channel personas, approval state, or profile audit logs.
- Current `channel_ai.auto_respond` is the main conflict: it is a boolean that makes the Discord message hot path decide whether to respond to every eligible message. Future NEXA participation should own timing/decision policy; `channelai` should retain configuration/mode only.

Decision labels used below:

- `REUSE`: keep the current owner and let future contexts consume through a narrow read port.
- `BRIDGE`: preserve current data but expose only an explicit mapped view into NEXA; no direct entity import or dual write.
- `MIGRATE`: move the responsibility or replace the field/trigger in a later task, with compatibility during transition.
- `DEPRECATE`: keep temporarily for compatibility but do not build new socialmemory behavior on top of it.

## Ownership and overlap matrix

| Current item | Current owner and evidence | Socialmemory overlap | Decision | Boundary rule for later tasks |
| --- | --- | --- | --- | --- |
| Nia affinity row: `user_affinity.user_id`, `score`, `stage`, `stage_ordinal`, `last_interaction_at` | `UserAffinityEntity` is in `ainetwork` and is globally keyed by unique `userId` (`AiNetworkEntities.kt:33-48`; migration `V47__user_affinity.sql:1-12`). | Looks like a relationship/familiarity signal, but it is global, gamified progress and not guild-scoped observed social state. | `BRIDGE` | Socialmemory may read a mapped `NiaAffinityView`/stage as one optional input, but must store its own guild-scoped observed interaction state instead of copying `score` or `stage`. |
| Nia affinity write trigger | `UsageService.recordSuccess` writes usage/contribution logs and best-effort calls `NiaAffinityService.awardInteraction(guildId, userId)` (`UsageService.kt:35-48`). `NiaAffinityService` increments score atomically and records stage-up events (`NiaAffinityService.kt:23-52`, `NiaAffinityService.kt:87-105`). | Future conversation/socialmemory projections will also observe interactions; a single user-visible interaction could otherwise increment affinity and relationship state through multiple unrelated triggers. | `MIGRATE` | Keep the existing award path until socialmemory exists, then introduce an explicit bridge/dedup event ID so one interaction produces one affinity side effect and one relationship projection update. |
| Nia affinity stage prompt line | Default Nia prompt reads `niaAffinity.view(ctx.userId).stage` and injects a relation sentence in `/ask` (`AskCommandHandler.kt:620-674`). | Future speech generation will also need relationship context, risking two relationship summaries in one prompt. | `MIGRATE` | Speech prompt assembly should own dynamic relationship wording. The old `/ask` line may remain for non-NEXA legacy ask, but NEXA speech must receive one composed relationship block from socialmemory plus an optional affinity bridge. |
| Nia identity kernel | Static Nia identity lives in shared `NexaIdentity` and global prompt set treats `nia` as builtin fallback (`NexaIdentity.kt:3-58`; `GlobalPromptSetService.kt:101-127`). | Identity can be confused with memory if dynamic social state is written into persona text. | `REUSE` | Keep identity static and reusable. Socialmemory must not mutate `NIA_DEFAULT_PERSONA`, global prompt-set rows, or client-visible identity previews. |
| Channel AI profile row: `channel_ai.guild_id`, `channel_id`, `display_name`, `avatar_url`, `active_behavior_version_id`, `source` | `ChannelAiEntity` is the authority for channel-level AI identity (`ChannelAiPersistence.kt:25-38`). Legacy `channel_ai_profile` was merged and dropped in V33 (`V33__merge_channel_ai_profile.sql:1-49`). | Profile identity is context for speech, not memory. | `REUSE` | Socialmemory may reference `(guildId, channelId, channelAiId)` but may not own display name, avatar, active behavior pointer, or profile source. |
| Channel behavior version: `purpose`, `tone`, `answer_length`, `constitution`, `custom_instruction`, `safety_level` | `AiBehaviorVersionEntity` stores versioned behavior; custom instruction is encrypted (`ChannelAiPersistence.kt:40-55`). Creation/rollback/custom-instruction writes append a new behavior version and proposal (`ChannelAiCustomizationService.kt:68-145`, `ChannelAiCustomizationService.kt:181-363`). | Behavior text can overlap with speech style and social response tone. | `REUSE` | Speech may consume a read model of active behavior, but socialmemory must not persist or infer behavior settings. Dynamic familiarity/timing must remain separate from static profile behavior. |
| Channel AI proposals and customization audit | `AiChangeProposalEntity` and `CustomizationAuditLogEntity` track approval/review/audit (`ChannelAiPersistence.kt:57-88`). Approval locks proposal and channel rows and applies routing snapshots (`ChannelAiCustomizationService.kt:366-441`). | Socialmemory will need privacy/audit of observed memories, but these rows audit configuration changes only. | `REUSE` | Keep channel configuration audit separate from memory provenance/audit. Do not reuse `customization_audit_log` as a socialmemory audit log. |
| Channel AI prompt preview/rendering | `ChannelAiPromptRenderer` builds safety, identity, custom instruction, behavior, optional RAG, and user question sections (`ChannelAiPromptRenderer.kt:22-108`). | Future speech prompts will combine identity, memory, scene, and policy. | `BRIDGE` | Use a read/compose port if speech needs active channel identity. Do not let socialmemory call persistence repositories directly or duplicate prompt-rendering rules. |
| Channel routing policy: `channel_ai_routing_policy` | The table belongs to `ainetwork`; `ChannelAiRoutingPolicyService` normalizes response mode/model/provider filters and resolves model choice (`AiNetworkEntities.kt:128-144`; `ChannelAiRoutingPolicyService.kt:26-100`). | Response mode can be mistaken for social talkativeness or NEXA participation mode. | `REUSE` | Keep provider/model routing in `ainetwork`/`routing`. Future NEXA talkativeness or OFF/SHADOW/CANARY/LIVE mode must not be stored in `responseMode`. |
| Routing snapshot on proposal | `ChannelAiRoutingSnapshot` serializes routing values into proposals and applies them to routing policy on approval (`ChannelAiRoutingSnapshot.kt:11-74`; `ChannelAiCustomizationService.kt:429-441`). | A future social mode snapshot could be tempting to append here. | `REUSE` | Keep snapshots limited to model routing unless a later ADR explicitly versions a new channel-social-mode snapshot. |
| Auto-response flag: `channel_ai.auto_respond` | V48 adds `auto_respond` to `channel_ai`; `AutoRespondChannelRegistry` says the flag is the SSOT and caches enabled channel IDs (`V48__channel_ai_auto_respond.sql:1-3`; `AutoRespondChannelRegistry.kt:10-20`, `AutoRespondChannelRegistry.kt:29-67`). | Directly overlaps with future participation policy because it decides if every non-dot message should trigger a response. | `MIGRATE` | Treat as legacy compatibility seed: `false` means mention/slash-only; `true` maps to an explicit NEXA channel mode after P01/P06 design. Final response timing must be decided by participation, not this boolean. |
| Auto-response cache and hot-path gate | `DiscordBot.Listener.onMessageReceived` checks `autoRespondChannels.isAutoRespond` before calling shared ask flow; registry cache is invalidated on flag/guild/channel changes (`DiscordBot.kt:870-923`; `AutoRespondChannelRegistry.kt:26-85`; cleanup `GuildRemovalCleanupService.kt:28-36`, `ProviderPoolReconciliationService.kt:39-47`). | A cache of channels is insufficient for scene-aware participation, cooldowns, burst context, opt-out, or social state. | `DEPRECATE` | Keep only until NEXA participation has its own indexed channel-mode query/cache. New socialmemory projections must not depend on this cache as authoritative state. |
| Nia setup auto-created channel profile + auto-response | The setup flow creates a Nia channel AI profile and turns on auto-response for the generated chat channel (`NiaChannelSetupHandler.kt:21-25`, `NiaChannelSetupHandler.kt:82-97`). | Migration must preserve user intent for existing generated AI chat channels. | `MIGRATE` | Migration should detect these channels and seed the future NEXA mode from the current flag/profile instead of recreating profiles or losing the chat-channel behavior. |
| AI feedback tied to channel AI | `AiFeedbackEntity` can store `channelAiId`; `AiQualityFeedbackService.submit` links feedback to the current channel AI row (`AiNetworkEntities.kt:93-111`; `AiQualityFeedbackService.kt:27-60`). | Feedback is a quality/moderation signal, not relationship memory. | `REUSE` | Socialmemory can reference request/feedback IDs for provenance if needed, but should not treat ratings/reports as member relationship facts. |
| Network overview channel AI count | `NetworkOverviewProjectionEntity.channelAiCount` and `AiNetworkFoundationService.refreshOverview` count channel AI rows for dashboard readiness (`AiNetworkEntities.kt:75-91`; `AiNetworkFoundationService.kt:170-215`). | Dashboard metrics can be confused with social-state metrics. | `REUSE` | Keep as operations/dashboard projection. Socialmemory should expose separate replayable projection metrics, not overload network overview. |

## Main conflict points

1. **Global affinity vs guild-scoped social relationship** — `user_affinity` has no `guild_id`; future `MemberInteractionState` tasks require a guild-scoped key. Copying `score/stage` into socialmemory would make a global gamified counter masquerade as observed per-guild relationship state.
2. **Static identity vs dynamic memory** — `NexaIdentity` and channel AI behavior are stable persona/config. Socialmemory should provide time-valid observed facts/relationship projections, not rewrite static persona rows.
3. **Auto-response timing** — current `auto_respond=true` means every eligible message in a channel reaches the `/ask` path. Future NEXA participation must decide IGNORE/WAIT/REACT/SPEAK/CANCEL from conversation state; `channelai` should not keep deciding timing by itself.
4. **Duplicate side effects** — current successful model requests award Nia affinity from `UsageService`. Future event-store projection may also process the same user interaction. Introduce request/event IDs before connecting both systems.
5. **Prompt double-injection** — legacy `/ask` injects an affinity relation line for default Nia. Future speech prompt assembly must ensure only one relationship block appears.

## Migration-safe target boundary

```text
channelai
  owns: channel profile, active behavior version, profile approval/audit, legacy autoRespond compatibility flag
  exposes: ChannelAiIdentityView / ChannelAiModeView read ports
  must not: decide final SPEAK timing after NEXA participation exists

ainetwork
  owns: Nia affinity progress, dashboard/network/readiness projections, channel routing policy, quality feedback
  exposes: NiaAffinityBridge / ChannelRoutingPolicyView read ports
  must not: write socialmemory relationship rows or store observable relationship projections

socialmemory
  owns: guild-scoped observed interactions, relationship/familiarity projections, time validity, source/provenance, deletion/replay behavior
  consumes: explicit bridge views from ainetwork/channelai
  must not: import ainetwork/channelai JPA entities or mutate their tables
```

## Current gaps for later tasks

- The planned channel modes `OFF`/`SHADOW`/`CANARY`/`LIVE` and talkativeness are not in the current `channelai` schema. Only `auto_respond` exists.
- No `socialmemory` package or persistence schema exists yet.
- No bridge interface currently prevents future code from importing `UserAffinityEntity` or `ChannelAiEntity` directly; P01/P06 tasks need package-level contracts or ArchUnit guards.
- No dedup key connects `ai_request`, `usage_log`, `user_affinity`, and future socialmemory projection events; P02/P03 event contracts should provide that key.

## Acceptance result

Every audited item is classified above as `REUSE`, `BRIDGE`, `MIGRATE`, or `DEPRECATE`. The practical boundary is:

- Reuse channel AI profile/behavior/routing/feedback where they already own configuration or operations data.
- Bridge Nia affinity into socialmemory only through explicit mapped views.
- Migrate auto-response timing and prompt relationship assembly into future participation/speech boundaries.
- Deprecate the current auto-response channel cache as the final decision surface once NEXA participation exists.

## Verification commands

- `./scripts/nexa-verify.sh docs` — validates the NEXA graph, package graph, conversation fixtures, documentation links, and diff-check guard.
- `./scripts/nexa-verify.sh central` — runs the central Gradle build/test/ktlint/Kover gate with JDK 21.

Because T024 depends on T023 and T023 is still dependency-gated `REVIEW`, this task should remain `REVIEW` after automated verification. It can move to `VERIFIED` only after upstream gates are approved or the task graph dependency is revised.
