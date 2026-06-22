# NEXA logging exposure baseline (T017)

Status: `REVIEW` candidate, human security gate pending.  
Audit date: 2026-06-20.  
Scope: source-only scan of `central-server/src/**` and `provider-agent/src/**` plus existing privacy tests. Local `.env*`, generated build output, and runtime log files were intentionally not read.

## Classification rule

| Severity | Meaning |
| --- | --- |
| Critical | A normal current path is confirmed to write a live token/API key or raw Discord prompt to application logs. |
| High | An uncontrolled upstream body, exception, or UI log sink can expose raw Discord text, prompt fragments, or secret-like values. |
| Medium | Stable identifiers, model/provider details, or sanitized-but-linkable data are logged without pseudonymization or a written boundary. |
| Low | Existing guardrail or non-finding; keep as regression evidence, not an immediate exposure. |

## Findings

| ID | Exposure class | Location | Severity | Evidence | Follow-up task |
| --- | --- | --- | --- | --- | --- |
| LOG-001 | Discord prompt / upstream body | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/CloudLlm.kt:218-276` | High | `generate`, image prompt review, and translation send user content in `messages`; non-2xx responses log `resp.body().take(500)`. API key is sent only as an Authorization header and was not found in log arguments. | `NEXA-P02-T012`, `NEXA-P02-T006` |
| LOG-002 | Image prompt / upstream body | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/CloudImageBackend.kt:127-190`, `:233-285`, `:365-380` | High | Stability and RunPod requests include `prompt` / `negative_prompt`; error bodies are logged with `String(body).take(500)` or `body.take(500)`. | `NEXA-P02-T012`, `NEXA-P02-T006` |
| LOG-003 | Discord prompt / upstream body | `provider-agent/src/provider_agent/glm.py:121-149` | High | GLM requests send user text in chat `messages`; HTTP errors log `body[:500]`, and upstream error messages are logged. Provider-side keys remain local, but error bodies can echo request content. | `NEXA-P02-T012`, `NEXA-P02-T006` |
| LOG-004 | Secret-like values in UI log sink | `provider-agent/src/provider_agent/webui.py:118-145` with `provider-agent/src/provider_agent/logging_setup.py:33-45` | High | Console/file handlers attach `RedactingFilter`, but the WebUI ring handler formats the same `provider_agent` records without adding that filter. If any future log record includes `token=...`, `api_key=...`, or `Authorization ...`, the in-app log pane can bypass the second-line redactor. | `NEXA-P02-T012` |
| LOG-005 | Provider/model exception text persisted in audit/fail reason | `central-server/src/main/kotlin/com/discordassistant/central/routing/application/RequestOrchestrator.kt:375-379`, `central-server/src/main/kotlin/com/discordassistant/central/routing/domain/service/RoutingAuditLogger.kt:81-84`, `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt:65-75` | Medium | Provider exceptions become `lastReason`, then routing audit `fallbackReason` and request `failReason` (truncated to 500 chars). Existing prompt privacy tests do not cover every provider/cloud exception string. | `NEXA-P01-T012`, `NEXA-P02-T012` |
| LOG-006 | User/guild/channel/provider identifiers | `central-server/src/main/kotlin/com/discordassistant/central/requestlog/application/UsageService.kt:42-47`, `central-server/src/main/kotlin/com/discordassistant/central/quota/application/RateLimitStore.kt:71-82`, `central-server/src/main/kotlin/com/discordassistant/central/global/audit/AuditLog.kt:23-30`, callers such as `AskCommandHandler.kt:125`, `:168`, `:245`, `DiscordBot.kt:916` | Medium | Logs and audit records can include raw Discord snowflakes or Redis keys containing `guildId`, `userId`, or `channelId`. This is useful for operations but must move to scoped pseudonyms/correlation IDs for NEXA. | `NEXA-P02-T010`, `NEXA-P02-T012`, `NEXA-P01-T012` |
| LOG-007 | Provider local identifiers / generated safety reason | `provider-agent/src/provider_agent/agent.py:609-630`, `:751-756`, `:795-803`, `:1070-1081`, `:1138-1145`, `:1251-1256`, `:1343-1354` | Medium | Provider logs can include GLM exception text, image safety review reason, guild ID, local backend target URL, model list, or fixed self-test output. No current line logs `req.prompt` directly, but review reasons and upstream/local exception messages need the same boundary as central logs. | `NEXA-P02-T012`, `NEXA-P02-T010`, `NEXA-P02-T006` |

## Existing controls and non-findings

| ID | Guard | Location | Result | Follow-up |
| --- | --- | --- | --- | --- |
| SAFE-001 | Central prompt privacy regression tests | `central-server/src/test/kotlin/com/discordassistant/central/relay/PromptPrivacyTest.kt:17-20`, `:54-77` | Normal relay prompt flow is covered by a marker test, and `AiRequestEntity` has no prompt/question/content field. | Expand forbidden-field tests in `NEXA-P02-T012`. |
| SAFE-002 | Provider prompt/secret privacy tests | `provider-agent/tests/test_privacy.py:17-64` | `RedactingFilter` masks secret patterns, and normal/cancelled text inference does not log the marker prompt. | Add WebUI ring-handler regression in `NEXA-P02-T012`. |
| SAFE-003 | Token storage / frame string masking | `central-server/src/main/kotlin/com/discordassistant/central/provider/application/TokenService.kt:15-17`, `:37-55`; `central-server/src/main/kotlin/com/discordassistant/central/relay/protocol/Frame.kt:50-60`; `provider-agent/src/provider_agent/protocol.py:84-117`; `provider-agent/src/provider_agent/config_file.py:1-5`, `:54-69` | Pairing tokens are stored hashed on central, auth-frame `toString`/`repr` masks tokens, and provider config is written with best-effort `0600`. | Keep as regression requirements in `NEXA-P02-T012`. |
| SAFE-004 | Backfill RAG sanitization | `central-server/src/main/kotlin/com/discordassistant/central/onboarding/application/GuildHistoryBackfillService.kt:8-20`, `:74-109`, `:148-166`, `:173-176` | Historical Discord content is scrubbed for sensitive/risky lines, author IDs are hashed, and mentions are masked before indexing. | Event-store deletion/redaction remains `NEXA-P03-T022`. |

## Required remediation shape

1. Define a shared redaction contract before fixing individual log calls: forbidden fields are raw message content, prompt, model response, API key, bearer token, Discord snowflake, and full upstream HTTP body.
2. Replace body/error logs with bounded structured codes: `status`, `provider`, `requestId`, `correlationId`, and a sanitized error class. Do not log upstream response body unless it has passed an allow-list sanitizer.
3. Pseudonymize raw Discord IDs at the logging boundary. Keep correlation possible via scoped hash, not by writing snowflakes into every log sink.
4. Attach the same redactor to every handler, including WebUI in-memory capture, and add regression tests for each sink.
5. Keep local secret files out of audit scope unless a human explicitly requests a live-machine secret handling review.

## Verification plan for this baseline

- `./scripts/nexa-verify.sh docs`
- `./scripts/nexa-verify.sh central`
- `./scripts/nexa-verify.sh agent`

This document is not a fix. Because T017 has `human_gate: true`, the task should remain in `REVIEW` after automated verification until a human security reviewer accepts the classifications and follow-up mappings.
