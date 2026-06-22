# NEXA current CloudLlm and GLM flow baseline (T023)

Status: `REVIEW` candidate; depends on T022, which is still dependency-gated `REVIEW` through the T017 human security gate.  
Audit date: 2026-06-20.  
Scope: central-server text LLM routing, central CloudLlm direct z.ai calls, provider-agent GLM fallback calls, quota/requestlog integration, adjacent image safety/translation GLM usage, and removal/deprecation boundaries.

## Current verdict

- NEXA speech/text requests should enter the central `RequestOrchestrator`, not call `provider_agent.glm` directly. The central route is where blocklist, daily quota, channel policy, burden caps, optional web augmentation, request logging, usage logging, and contribution logging are consistently applied.
- The repo does not currently make all GLM calls central-only. Two GLM routes exist today: central direct `CloudLlm` when the central z.ai key is configured, and provider-agent GLM when an agent advertises `glm-*` or when image fallback work still needs local GLM safety/translation.
- Provider-agent GLM is therefore a removal candidate for a later migration, not removal-safe in T023. Removing it now would also require replacing desktop `/api/cloud` key storage/live advertisement and image fallback review/translation behavior.

## LLM route matrix

| Route | Trigger | External caller | Central policy/log coverage | Provider-agent involvement | Current disposition |
| --- | --- | --- | --- | --- | --- |
| Central direct CloudLlm | Preferred model starts with `glm` and `cloudLlm.isEnabled()` is true. | central-server `ZaiCloudLlm` posts to z.ai. | Full `RequestOrchestrator` gates before call; `recordSuccess` uses synthetic provider ID `-1`; `ai_request.providerId` is `null`. | None; `ProviderSession.sendInfer` is skipped. | Primary free-cloud path when central `ZAI_API_KEY` exists. |
| Provider-agent GLM fallback | Model starts with `glm`, central CloudLlm is disabled, and a connected provider advertises `glm-*`. | provider-agent `GlmClient` posts to z.ai from the provider PC. | Central still gates/logs because request reaches the agent through `ProviderSession.sendInfer`; success is recorded against the selected provider ID. | Required; key stays on provider PC. | Compatibility/rollback path. |
| Local non-GLM provider | Selected model is not cloud and a local provider can serve it. | provider-agent local model runtime, usually Ollama. | Full central route; selected provider ID is recorded. | Required. | Existing provider-pool path. |
| Image GLM safety/translation | `/imagine` with central cloud image, image provider, or legacy agent image path. | central-server CloudLlm or provider-agent GlmClient depending on central key and route. | Discord command path enforces central command gates; image-generation transport differs from text routing. | Still required for legacy image fallback when central GLM is absent. | Adjacent dependency that blocks simple GLM deletion. |

## Central direct CloudLlm sequence

```text
Discord /ask or message ask
  -> AskCommandHandler.ask
  -> optional local-first attempt
  -> FreeAskRateLimiter.check(userId)
  -> runOrchestrator(..., model = glm-5.1, dedup = false for fallback)
  -> RequestOrchestrator.handle(AiRequestInput)
  -> duplicate guard when enabled
  -> blocklist, quota, channel policy, burden caps
  -> optional web-search augmentation
  -> if glm-* and CloudLlm enabled: ZaiCloudLlm.generate(effectivePrompt, model)
  -> UsageRecorder.recordSuccess(guild, user, CLOUD_PROVIDER_ID = -1, requestId)
  -> UsageRecorder.recordRequest(..., providerId = null, state = COMPLETED)
  -> Discord reply renderer prefixes the answer as cloud output
```

Evidence:

- `/ask` is local-first only when a non-cloud selected model and a local provider are available; otherwise it uses or falls back to free cloud model `glm-5.1` after `FreeAskRateLimiter.check(ctx.userId)` (`central-server/src/main/kotlin/com/discordassistant/central/platform/discord/command/AskCommandHandler.kt:149-175`).
- `runOrchestrator` composes the execution prompt, preserves the user prompt length for burden weighing, and passes `AiRequestInput` into `RequestOrchestrator.handle` (`AskCommandHandler.kt:186-227`).
- `RequestOrchestrator` applies blocklist, user quota, channel policy, request weighing, role burden caps, and web-search augmentation before the cloud branch (`central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt:171-229`).
- The cloud branch is only taken for `glm-*` requests when `cloudLlm.isEnabled()` is true. It calls `cloudLlm.generate`, records success with `CLOUD_PROVIDER_ID`, returns `providerId = null`, and does not call `ProviderSession.sendInfer` (`RequestOrchestrator.kt:230-250`).
- The sentinel is explicitly `CLOUD_PROVIDER_ID = -1L` so central direct usage is distinguishable from real positive provider IDs (`RequestOrchestrator.kt:411-414`).
- `ZaiCloudLlm` is enabled by `central.cloud.zai-api-key`, posts OpenAI-compatible chat requests to `{baseUrl}/chat/completions`, uses a bearer token only in central, and defaults the model to `glm-5.1` (`central-server/src/main/kotlin/com/discordassistant/central/routing/application/CloudLlm.kt:200-283`).
- The default configuration keeps the central z.ai key empty and documents that an empty key falls back to the existing provider-agent `glm-*` route (`central-server/src/main/resources/application.yml:91-96`).

## Provider-agent GLM fallback sequence

```text
provider-agent starts or saves a GLM key
  -> AgentConfig.glm_api_key from ZAI_API_KEY or saved config
  -> ProviderAgent creates GlmClient and advertises glm-5.1 in hello.models
  -> central routes a glm-* InferRequest through ProviderSession.sendInfer
  -> ProviderAgent.handle_infer applies server pause, daily limit, resource, and concurrency gates
  -> _run_infer sees glm-* and dispatches _run_glm
  -> GlmClient.generate posts to z.ai from the provider PC
  -> InferResult returns over the relay
  -> central records success/request against the selected provider ID
```

Evidence:

- `AgentConfig` stores `glm_api_key` and `glm_models`; comments state the key stays on the provider PC and is not uploaded to central (`provider-agent/src/provider_agent/config.py:18-38`).
- Config loading reads `.env` and saved config, maps `ZAI_API_KEY` or saved `glm_api_key`, and defaults the GLM model list to `glm-5.1` when a key exists (`config.py:71-102`, `config.py:141-178`, `config.py:227-260`).
- `ProviderAgent.__init__` creates `GlmClient` when `cfg.glm_api_key` is present and merges `_glm_models` into the advertised model list (`provider-agent/src/provider_agent/agent.py:330-369`).
- The hello capability advertises the effective model list and remaining daily request capacity to central (`agent.py:391-407`).
- `handle_infer` applies provider-side pause, daily limit, resource, and concurrency gates before executing the request (`agent.py:435-490`).
- `_run_infer` routes `glm-*` to `_run_glm`; if the central request chooses a GLM model but the agent has no GLM key, the agent returns a clear `InferError` and does not silently fall back to Ollama (`agent.py:500-534`).
- `_run_glm` calls `self._glm.generate`, supports chunked streaming, maps GLM usage into `InferResult`, and reports `GlmError` as `InferError` (`agent.py:555-573`).
- `GlmClient` posts OpenAI-compatible chat completions to z.ai, extracts `choices[0].message.content` and usage, generalizes upstream errors, supports prompt translation and image-prompt review, and has a `/models` health check (`provider-agent/src/provider_agent/glm.py:1-205`).
- Desktop web UI cloud settings persist the provider-side `glm_api_key` via legacy wire names `geminiApiKey`/`geminiConfigured`, and live apply the key through `agent.set_glm_key` when the GUI agent is running (`provider-agent/src/provider_agent/webui.py:1239-1358`).
- `ProviderAgent.set_glm_key` can add or remove the GLM key without restart, health-check the key, recompute advertised models, and re-advertise to central (`agent.py:1267-1285`).

## Quota and requestlog integration

| Concern | Central direct CloudLlm | Provider-agent GLM fallback |
| --- | --- | --- |
| Free cloud anti-abuse limit | `FreeAskRateLimiter.check(ctx.userId)` runs before the free-cloud fallback call (`AskCommandHandler.kt:167-169`; limiter implementation `central-server/src/main/kotlin/com/discordassistant/central/quota/application/FreeAskRateLimiter.kt:6-35`). | Same when reached through `/ask` free-cloud fallback; direct model-selection routes still pass central quota inside `RequestOrchestrator`. |
| Blocklist and user quota | `RequestOrchestrator.route` checks `blocklist.isBlocked` and `quota.exceededQuota` before cloud direct call (`RequestOrchestrator.kt:171-180`). | Same central checks before provider selection. |
| Channel policy and burden cap | Central channel policy and `RequestWeigher` run before cloud direct call (`RequestOrchestrator.kt:181-220`). | Same central checks before provider selection. |
| Request log | `handle` records final state with `providerId = null` for the cloud-direct `OrchestrationResult` (`RequestOrchestrator.kt:88-100`, `UsageService.kt:57-77`). | `recordRequest` stores the selected provider ID returned by provider-pool routing. |
| Usage and contribution logs | `recordSuccess` writes `usage_log` and `contribution_log` using synthetic provider ID `-1` (`UsageService.kt:35-48`). | `recordSuccess` writes the real provider ID selected by `ProviderRouter` (`RequestOrchestrator.kt:366-373`). |
| Provider local quota | Not touched; no provider session is selected. | Touched twice: central candidate filtering uses advertised remaining daily capacity, and provider-agent decrements its own finite `remaining_daily_requests` around `handle_infer`. |

## Removal and deprecation boundary

Current code supports central-first CloudLlm but intentionally preserves provider-agent GLM fallback:

- The application config comment states that an empty central z.ai key should preserve provider-agent `glm-*` fallback (`central-server/src/main/resources/application.yml:91-96`).
- The central route comment says the central direct branch skips pool selection only when the admin key exists; otherwise existing agent-mediated `glm-*` fallback remains for compatibility and rollback safety (`RequestOrchestrator.kt:230-234`).
- Central tests cover direct-cloud vs provider-agent fallback behavior, including `glm` with key using CloudLlm directly, no key falling back to `sendInfer`, non-GLM never using CloudLlm, policy gates rejecting before cloud, and cloud failure returning a generalized error (`central-server/src/test/kotlin/com/discordassistant/central/routing/application/RequestOrchestratorTest.kt:484-605`).
- Provider-agent tests cover GLM request payload/auth/usage parsing, default model behavior, generalized upstream failures, advertised GLM config, `glm-*` routing, stream chunks, no accidental Ollama fallback without a key, and live `set_glm_key` advertisement (`provider-agent/tests/test_glm.py:135-420`).

Removal-safe split for later tasks:

1. Keep central `CloudLlm` and make NEXA speech/text enter central `RequestOrchestrator`.
2. Treat provider-agent GLM text serving as legacy/rollback until a migration explicitly removes desktop cloud-key UX, config fields, hello model advertisement, `glm-*` agent dispatch, and related tests.
3. Do not remove provider-agent GLM image review/translation helpers until `/imagine` fallback semantics are migrated to central-only safety/translation or a replacement image-policy path.
4. If provider-agent GLM is removed, preserve user-facing compatibility for the legacy `geminiApiKey`/`geminiConfigured` desktop contract or deliberately version the desktop contract in the same change.

## NEXA speech implication

The acceptance premise is supported with one caveat:

- Supported: NEXA speech should route through central because only central currently owns cross-guild policy, user quota, blocklist, channel policy, request weighing, web augmentation, request logs, usage logs, and provider contribution logs.
- Caveat: routing through central does not necessarily mean central itself must be the external z.ai caller for every `glm-*` request today. If central `ZAI_API_KEY` is absent, the current central route can still select a provider-agent GLM capability, and accounting remains central because the agent receives the work through `ProviderSession.sendInfer`.
- Rejected path: a new speech feature should not call `provider_agent.glm.GlmClient` directly from desktop/provider-agent UI for user-facing answer generation, because that would bypass the central `RequestOrchestrator` accounting and safety gates recorded above.

## Current automated coverage and gaps

| Behavior | Current guard |
| --- | --- |
| Central direct CloudLlm response parsing | `CloudLlmResponseParserTest` covers content extraction, usage extraction, upstream error generalization, malformed/empty responses, and image-review fail-closed parsing. |
| Direct cloud vs provider fallback | `RequestOrchestratorTest` covers direct `glm` cloud when key exists, `sendInfer` fallback when key is absent, non-GLM exclusion from cloud direct, cloud-policy gates, and cloud failure handling. |
| Discord free cloud selection | `CommandServiceTest` covers cloud-only provider/free-cloud behavior and local-failure fallback to cloud. |
| Provider-agent GLM behavior | `provider-agent/tests/test_glm.py` covers request shape, auth, usage, failures, config advertisement, runtime dispatch, streaming, missing-key errors, and live key changes. |
| Desktop cloud setting persistence | `provider-agent/tests/test_webui.py` covers saving `glm_api_key` through the legacy `/api/cloud` desktop contract. |
| Live external z.ai E2E | No live z.ai integration test is part of the default gates; automated coverage uses mocked HTTP clients and routing fakes. |
| Discord-to-cloud live E2E | No live Discord `/ask` -> central CloudLlm -> z.ai -> Discord response test is part of the default gates. |

## Verification commands

- `./scripts/nexa-verify.sh docs` — validates the NEXA graph, central package graph, conversation fixtures, relative documentation links, and diff-check guard.
- `./scripts/nexa-verify.sh central` — runs the central Gradle build/test/ktlint/Kover gate with JDK 21.
- `./scripts/nexa-verify.sh agent` — runs provider-agent pytest with coverage, ruff, and mypy.

Because T023 depends on T022 and T022 is still dependency-gated `REVIEW`, this task should remain `REVIEW` after automated verification. It can move to `VERIFIED` only after upstream gates are approved or the task graph dependency is revised.
