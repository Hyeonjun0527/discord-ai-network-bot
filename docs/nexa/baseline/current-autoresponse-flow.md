# NEXA current auto-response flow baseline (T022)

Status: `REVIEW` candidate; depends on T021, which is still dependency-gated `REVIEW` through the T017 human security gate.  
Audit date: 2026-06-20.  
Scope: Discord slash/modal/context-message ask entry points, mention ask, channel auto-response, channel AI prompt/profile influence, routing/provider transmission, Discord response rendering, and hidden side effects.

## Entry point matrix

| Entry point | Discord event | Input source | Public/ephemeral decision | Shared ask path |
| --- | --- | --- | --- | --- |
| `/ask` slash command | `SlashCommandInteractionEvent` | `prompt`, optional `model`, `mode`, `web` slash options | Public unless a channel AI profile is active; active profile defers ephemerally, then posts the public answer through webhook/fallback. | `DiscordBot.dispatch` -> `CommandService.ask` -> `AskCommandHandler.ask`. |
| Long ask modal | `ModalInteractionEvent` with `ask-long-modal` | Modal `prompt` field | `deferReply(useWebhookProfile)`, same profile-vs-normal renderer split as `/ask`. | `CommandService.ask` -> `AskCommandHandler.ask`. |
| Message context ask | `MessageContextInteractionEvent` named `AI에게 질문` | Target message `contentRaw` | `deferReply(useWebhookProfile)`, same profile-vs-normal renderer split. | `CommandService.ask` -> `AskCommandHandler.ask`. |
| Mention ask | `MessageReceivedEvent` | Message content after removing `<@bot>` / `<@!bot>` tokens | Normal message reply or channel AI webhook if a channel profile exists. | `respondInChannel` -> `CommandService.ask` -> `AskCommandHandler.ask`. |
| Auto-response channel | `MessageReceivedEvent` without bot mention | Full trimmed message content | Normal message reply or channel AI webhook if a channel profile exists. | `respondInChannel` -> `CommandService.ask` -> `AskCommandHandler.ask`. |

The mention and auto-response paths require Message Content Intent. `DiscordBot.launchJda` passes `mentionAskEnabled = messageContentIntent` into the listener (`DiscordBot.kt:206-229`). If Discord rejects privileged intents with gateway 4014, `handleDisallowedIntents` marks the gateway down and can restart without message-content intent, explicitly disabling mention/auto-response while keeping slash commands available (`DiscordBot.kt:243-259`).

## Slash, modal, and context-command sequence

```text
Discord slash/modal/context event
  -> DiscordBot.Listener builds CommandContext
  -> deferReply(...) before slow work
  -> CommandService.ask(...)
  -> AskCommandHandler.ask(...)
  -> RequestOrchestrator.handle(...)
  -> Cloud direct or provider pool sendInfer(...)
  -> DiscordAnswerRenderer edits original interaction or sends profile webhook/fallback
```

Evidence:

- `onSlashCommandInteraction` records command metrics, rejects guild-only commands in DM, builds `CommandContext`, and handles quick interactive commands before the shared defer path (`central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordBot.kt:310-370`).
- Slash `/ask` uses `useWebhookProfile = event.name == "ask" && channelProfiles.get(ctx.guildId, ctx.channelId) != null`; all non-interactive commands are deferred before execution (`DiscordBot.kt:371-384`).
- Slow commands run outside the JDA gateway thread on `slowCommandExecutor`; `/ask` reaches `dispatch`, then `commands.ask(...)`, and results are rendered through `DiscordAnswerRenderer` (`DiscordBot.kt:385-431`, `DiscordBot.kt:1046-1058`).
- Long ask modal and message context menu call `commands.ask(...)` directly and use the same profile webhook vs interaction edit renderer split (`DiscordBot.kt:838-862`).
- `CommandService.ask` is only a thin facade into `AskCommandHandler.ask`; JDA event handling and ask business logic are intentionally separated (`central-server/src/main/kotlin/com/discordassistant/central/platform/discord/CommandService.kt:59-105`).

## Mention ask sequence

```text
MessageReceivedEvent
  -> guard: content intent enabled, guild message, non-bot author
  -> if the message mentions the bot user
  -> strip bot mention tokens from contentRaw
  -> blank prompt: usage reply only
  -> metrics.record("mention-ask")
  -> respondInChannel(event, prompt)
  -> shared ask/routing/render path
```

Evidence:

- `onMessageReceived` is the single message-event entry. It exits early when content intent is disabled, the event is not from a guild, or the author is a bot; it then checks the JDA mention list for the bot's own user ID (`DiscordBot.kt:872-883`).
- `handleMentionAsk` strips mention tokens through `mentionPrompt`, sends a usage reply for blank prompts, records `mention-ask`, then calls `respondInChannel` (`DiscordBot.kt:885-900`, `DiscordBot.kt:997-1004`).
- `respondInChannel` builds context from guild/member/channel/user IDs, sends a typing indicator, calls `commands.ask`, then either posts through the channel AI webhook and reacts with `✅`, or replies to the source message with pseudo-stream support (`DiscordBot.kt:925-959`).

## Auto-response channel sequence

```text
MessageReceivedEvent without bot mention
  -> handleAutoRespond(event)
  -> O(1) guild cache check: channel_ai.auto_respond contains channelId?
  -> skip blank or dot-prefixed messages
  -> channel-level rate-limit key autorespond:{guildId}:{channelId}
  -> throttled: metrics + ⏳ reaction, no LLM call
  -> accepted: metrics.record("auto-respond")
  -> respondInChannel(event, trimmed content)
  -> shared ask/routing/render path
```

Evidence:

- `handleAutoRespond` checks the auto-response registry, applies `AutoRespondChannelRegistry.shouldRespond`, rate-limits on `autorespond:$guildId:$channelId`, records either `auto-respond-throttled` or `auto-respond`, and calls `respondInChannel` only after all gates pass (`DiscordBot.kt:902-923`).
- The registry is explicitly designed for the message hot path: `channel_ai.auto_respond` is the SSOT, and guild-level channel ID sets are lazy-loaded into an in-memory cache so `isAutoRespond` is O(1) after load (`central-server/src/main/kotlin/com/discordassistant/central/channelai/application/AutoRespondChannelRegistry.kt:10-33`, `AutoRespondChannelRegistry.kt:82-94`).
- Enabling auto-response for a channel with no existing profile creates a default `니아` channel AI profile first, sets `autoRespond`, saves it, and invalidates the guild cache (`AutoRespondChannelRegistry.kt:35-67`).
- Profile creation writes the `channel_ai` row, a behavior version, an approved proposal, and an audit log entry, so turning on auto-response is not just a boolean write (`central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiProfileService.kt:59-123`).

## Shared ask and prompt-composition sequence

```text
CommandService.ask
  -> AskCommandHandler.ask
  -> per-user ask cooldown unless admin
  -> guild default model + effective channel routing policy
  -> model-choice resolution from available provider models
  -> local-first attempt when a local provider exists and selected model is not cloud
  -> free cloud fallback through glm-5.1 when needed
  -> runOrchestrator(ctx, prompt, model, responseMode, webSearch, maxCandidates)
```

Evidence:

- `AskCommandHandler.ask` enforces `ask:{guildId}:{userId}` cooldown for non-admin users, reads guild default model, resolves effective channel routing policy and model choice, and returns a user-facing warning when policy requires an available model but none is selectable (`central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt:117-147`).
- The current policy is local-first only when a non-cloud selected model and a local provider are present; otherwise it uses or falls back to the free cloud model `glm-5.1` after `FreeAskRateLimiter` passes (`AskCommandHandler.kt:149-175`, `AskCommandHandler.kt:178-184`).
- `runOrchestrator` creates the execution prompt, starts optional multi-response runtime observation, and sends `AiRequestInput` to the routing orchestrator. Burden weighting uses the original user prompt length, not the injected system prompt length (`AskCommandHandler.kt:186-227`).
- Prompt composition first tries channel knowledge/RAG, then active channel AI customization, then a channel AI profile or guild default persona with NEXA safety guardrails. Channel AI prompt preview can suppress RAG when the user question appears sensitive (`AskCommandHandler.kt:533-674`, `central-server/src/main/kotlin/com/discordassistant/central/channelai/application/ChannelAiPromptRenderer.kt:22-96`).
- Effective channel routing policy falls back to guild default model and defaults to `balanced`, standard quality, one candidate, provider-safe cost guard (`central-server/src/main/kotlin/com/discordassistant/central/ainetwork/application/ChannelAiRoutingPolicyService.kt:56-72`). Model choice reads current provider capabilities and selects the requested/preferred model only if available (`ChannelAiRoutingPolicyService.kt:80-100`).

## Routing and provider transmission sequence

```text
RequestOrchestrator.handle(AiRequestInput)
  -> idempotency duplicate guard
  -> blocklist, quota, channel policy
  -> request weight and role/burden cap
  -> optional web-search augmentation
  -> if glm-* and central CloudLlm enabled: direct cloud call
  -> else build provider candidates from live sessions + provider profiles
  -> ProviderFilterPipeline drops ineligible providers
  -> ProviderRouter chooses immediate dispatch or rejects/queues
  -> reserve provider capacity
  -> ProviderSession.sendInfer(prompt, model, responseModeOptions)
  -> record attempt, usage, contribution, request state, failure/cooldown stats
```

Evidence:

- The orchestrator's documented order is policy -> weight -> candidate -> filter -> select -> send, with one fallback attempt and usage/contribution recording (`central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt:55-58`).
- `handle` applies an idempotency guard, records duplicate rejections, routes the request, then records the final request state (`RequestOrchestrator.kt:88-100`).
- `route` applies blocklist, quota, channel policy, request weighing, role burden caps, and web-search augmentation before provider selection (`RequestOrchestrator.kt:171-229`).
- For `glm-*` requests with central CloudLlm enabled, the orchestrator bypasses provider sessions, calls `cloudLlm.generate`, records synthetic cloud provider success, and returns sources from augmentation (`RequestOrchestrator.kt:230-250`).
- Provider-pool routing builds candidates from `ConnectionRegistry`, filters them, records audit decisions, asks `ProviderRouter` for the selected provider, reserves capacity, and calls `session.sendInfer(prompt = effectivePrompt, model = input.preferredModel, options = responseModeOptions(...)).get()` (`RequestOrchestrator.kt:253-408`).
- `ProviderFilterPipeline` can drop candidates for model burden, restricted request, blocked provider, circuit/heartbeat/cooldown/offline state, role/channel mismatch, model unavailability, quota, concurrency, context/prompt size, quality, privacy, streaming/tools/json support, and failure rate (`central-server/src/main/kotlin/com/discordassistant/central/routing/domain/service/ProviderFilterPipeline.kt:115-240`).
- `ProviderRouter` scores candidates, dispatches immediately only for a non-negative selectable score, otherwise queues once or rejects after retry budget (`central-server/src/main/kotlin/com/discordassistant/central/routing/domain/service/ProviderRouter.kt:57-100`).
- `ProviderSession.sendInfer` creates a wire request ID, registers the pending future, increments in-flight count, decrements finite daily quota, marks the provider busy, sends an `InferRequest` frame over the agent connection, applies timeout/cancel handling, and marks repeated failures as unhealthy (`central-server/src/main/kotlin/com/discordassistant/central/relay/ProviderSession.kt:148-190`).
- Usage recording persists `usage_log`, `contribution_log`, `ai_request`, provider health failure counters, and a best-effort Nia affinity award (`central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt:35-85`).

## Discord response rendering sequence

```text
Reply from AskCommandHandler
  -> completedAskReply prefixes ☁️ or 🖥️, truncates safely, optionally creates pseudo-stream snapshots, attaches feedback requestId
  -> slash/modal/context without profile: edit original interaction, then scheduled pseudo-stream edits if needed
  -> slash/modal/context with profile: try channel AI webhook; fallback to bot channel message; fallback to original interaction edit
  -> mention/auto-response with profile: try channel AI webhook and add ✅ reaction; fallback to source-message reply
  -> mention/auto-response without profile: source-message reply with pseudo-stream edits and feedback buttons
```

Evidence:

- `completedAskReply` adds cloud/local source icon, appends fallback notices only for local provider answers, creates pseudo-stream snapshots for long answers, enforces Discord length safety, and attaches feedback metadata from the routing request ID (`AskCommandHandler.kt:383-420`).
- Interaction rendering with a channel profile first tries `sendAnswerWebhook`, then a normal bot channel message, then original-interaction pseudo-stream fallback (`central-server/src/main/kotlin/com/discordassistant/central/platform/discord/DiscordAnswerRenderer.kt:23-46`).
- Non-profile interaction rendering edits the original interaction, schedules pseudo-stream edits when available, and attaches feedback buttons at the final edit (`DiscordAnswerRenderer.kt:66-122`, `DiscordAnswerRenderer.kt:205-225`).
- Message replies use `source.reply(...).mentionRepliedUser(false)`, schedule message edits for pseudo-streaming, and attach feedback buttons when the final message is reached (`DiscordAnswerRenderer.kt:124-166`).
- Channel AI webhook rendering retrieves or creates a webhook named `discord-ai-channel-profile`, applies the channel profile display name/avatar, and posts the answer; failures return `false` for fallback (`DiscordAnswerRenderer.kt:174-203`).

## Hidden side effects and operational risks

| Side effect | Code evidence | Impact for NEXA work |
| --- | --- | --- |
| Mention/auto-response disappears when Message Content Intent is disabled or rejected. | `DiscordBot.kt:206-259`, `DiscordUxTest.kt:48-57`. | New speech/agent entry points must not assume message events are always available; slash commands remain the safer fallback. |
| Slash and message paths record metrics with different names. | Slash: `DiscordBot.kt:310-312`; mention: `DiscordBot.kt:898`; auto-response: `DiscordBot.kt:916-922`; reactions: `DiscordBot.kt:962-968`. | Dashboards/alerts can distinguish slash, mention, throttled auto-response, and feedback reactions. |
| Auto-response has a channel-level cost cap that admins cannot bypass. | Runtime: `DiscordBot.kt:916-919`; focused test: `AutoRespondChannelCapTest.kt:9-46`. | Any auto speech mode should reuse or explicitly replace this cap, not rely only on per-user `/ask` cooldowns. |
| Auto-response enablement creates default channel AI identity and audit/proposal records. | `AutoRespondChannelRegistry.kt:35-67`; `ChannelAiProfileService.kt:59-123`. | Migration scripts must preserve `channel_ai.auto_respond`, behavior versions, proposals, and audit history together. |
| Message-event path adds Discord reactions. | Throttle `⏳`: `DiscordBot.kt:916-919`; webhook success `✅`: `DiscordBot.kt:940-944`. | Reactions are user-visible behavior and should be tested/kept if replacing the renderer. |
| Request logs and contribution logs are written inside the routing path, not by Discord renderers. | `RequestOrchestrator.kt:88-100`, `UsageService.kt:35-85`. | A future transport that bypasses `RequestOrchestrator` will silently lose usage/contribution/accounting. |
| Multi-response runtime observation and pseudo-stream planning can add DB/runtime work to plain ask. | Runtime observation: `AskCommandHandler.kt:186-227`; pseudo-stream reply: `AskCommandHandler.kt:383-420`. | Latency investigations must include post-LLM answer processing, not just provider inference time. |
| RAG, web-search, and channel AI prompt preview can alter the transmitted prompt. | RAG/channel AI: `AskCommandHandler.kt:533-593`; web augmentation: `RequestOrchestrator.kt:222-229`; sensitive RAG suppression: `ChannelAiPromptRenderer.kt:39-56`. | Tests that assert provider prompt text must include these augmenters or explicitly disable them. |
| Provider session state mutates around each transmission. | `ProviderSession.kt:148-190`. | Failures can change provider state to `UNHEALTHY`, affecting later unrelated requests. |
| Guild/channel lifecycle events clean related state. | Guild leave/member remove/channel delete: `DiscordBot.kt:781-796`; registry invalidation: `AutoRespondChannelRegistry.kt:69-80`. | Channel deletion or bot removal can change future auto-response routing without a direct user ask event. |
| Feedback buttons write quality feedback through `CommandService`. | Button handling: `DiscordBot.kt:981-994`; renderer buttons: `DiscordAnswerRenderer.kt:215-225`; quality feedback dependency: `AskCommandHandler.kt:51-53`. | Response rendering includes future feedback side effects via component IDs; do not treat it as display-only. |

## Current automated coverage

| Behavior | Current guard |
| --- | --- |
| Slash catalog and dispatch drift | `SlashCommandCatalogTest` builds command definitions and asserts `/ask` exists; `CommandRegistrationDriftTest` compares registered slash commands with `DiscordBot.dispatch` branches. |
| Message Content Intent toggle | `DiscordUxTest` verifies `GatewayIntentPolicy` includes or excludes `MESSAGE_CONTENT` while keeping `GUILD_MESSAGES`. |
| Auto-response skip predicate | `AutoRespondJudgmentTest` covers nonblank, blank, and dot-prefixed message content. |
| Auto-response channel cap | `AutoRespondChannelCapTest` verifies the `autorespond:{guild}:{channel}` key is channel-scoped and user/admin-agnostic. |
| Auto-response registry persistence/cache behavior | `AutoRespondChannelRegistryTest` covers default profile creation, on/off, cache invalidation, guild isolation, and no-op off. |
| Full runtime Discord message event | No live JDA end-to-end test currently exists for `MessageReceivedEvent` -> provider -> Discord message. Central build coverage is unit/slice based. |

## Verification commands

- `./scripts/nexa-verify.sh docs` — validates the NEXA graph, central package graph, conversation fixtures, relative documentation links, and diff-check guard.
- `./scripts/nexa-verify.sh central` — runs the central Gradle build/test/ktlint/Kover gate with JDK 21.

Because T022 depends on T021 and T021 is still dependency-gated `REVIEW`, this task should remain `REVIEW` after automated verification. It can move to `VERIFIED` only after upstream gates are approved or the task graph dependency is revised.
