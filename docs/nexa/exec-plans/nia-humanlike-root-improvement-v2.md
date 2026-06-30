# ExecPlan: NIA Humanlike Root Improvement v2

Status: execution-locked. Section 12 progress log is the current-state SSOT.
Created: 2026-06-30
Primary scope: `central-server`, `admin-console`, `docs/nexa`, `scripts`, `test-fixtures`

## 1. Target Outcome

NIA must stop behaving like an expert system whose behavior is patched by more
rules. The target architecture is:

```text
raw Discord text window
+ admin-managed few-shot constitution
+ socialmemory as secondary support
+ consent/policy metadata
-> one participation judge
-> exactly one action: IGNORE / WAIT / REACT / SPEAK / CANCEL
-> speech only after SPEAK
-> scheduler / Discord execution
-> durable reason for every branch
```

This plan is intentionally concrete. Each milestone defines owned files,
expected DB/API contracts, tests, validation commands, and stop conditions. If a
future session loses context, resume from the first incomplete milestone and do
not invent a new plan.

Execution rule: this document is the implementation contract. If execution needs
a design decision, file ownership change, behavior contract change, or validation
gate that is not written here, update this ExecPlan first and only then edit
code. The only exception is a mechanical compiler/test fix inside files already
owned by the active milestone.

## 2. Non-Goals

- Do not create new behavior enums such as `EMOTIONAL_SUPPORT`,
  `NEEDS_COMFORT`, or `ASKED_FOR_REPLY`.
- Do not add deterministic rules such as "if lonely text and no reply, speak".
- Do not let speech decide whether to speak.
- Do not store production raw user text in docs, fixtures, or chat.
- Do not deploy, mutate production DB, or enable Discord LIVE behavior without
  explicit human approval.

## 3. Current Baseline To Preserve As Evidence

- `docs/nexa/architecture/participation-context.md` says participation owns only
  one action choice: `IGNORE / WAIT / REACT / SPEAK / CANCEL`.
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/FailClosedConsentPolicyConfig.kt`
  currently provides a fail-closed `ConsentPolicyPort` fallback.
- `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/PolicyBackedConsentGate.kt`
  blocks speech generation and external GLM calls when consent is denied.
- `central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt`
  records decisions through `SpeechDecisionLogPort`, but production must not rely
  on `Noop`.
- Current local workspace has many unrelated modified files. Any implementation
  branch must start from a clean, freshly fetched base or explicitly isolate
  ownership before editing.

## 4. Anti-Context-Pollution Rules

1. One milestone equals one branch and one PR-sized unit.
2. Branch names must follow the milestone table below.
3. Start every implementation branch from current `origin/main`, not from a
   dirty feature branch.
4. Before each milestone, run `git status --short --branch` and record whether
   unrelated changes exist.
5. Stage only files listed in that milestone's "owned files" section.
6. If an unlisted file must change, update this ExecPlan first with the reason.
7. Do not proceed to the next milestone until all milestone acceptance checks
   pass.
8. After each milestone, append a progress entry in section 12.
9. Do not mix admin UI, DB schema, judge prompt, consent, and deploy fixes in one
   PR unless the milestone explicitly says so.
10. If validation fails twice for the same reason, stop and record the blocker
    instead of piling on unrelated fixes.
11. Every progress entry must name the exact milestone task numbers completed
    using `M?.?` notation. Existing numbered tasks under a milestone map directly:
    task `3` under M4 is `M4.3`.
12. If the active context is lost or compacted, do not infer from chat. Follow
    section 4.1 and section 12.
13. If an existing implementation is discovered, write whether the milestone will
    adopt, extend, or replace it before editing that code.
14. No hidden "temporary" rule may be added to make the screenshot-like case pass.
    The case must pass through raw scene, few-shot examples, and the single judge.
15. Prompt, parser, or action-routing changes require at least one fixture or test
    that proves the behavior without production raw text.
16. A test may use synthetic Korean raw text, but production Discord text must not
    be copied into docs, fixtures, commit messages, or chat.
17. A validation failure may be fixed only in files owned by the active milestone.
    If the fix needs another area, amend this plan first.
18. Generated files may change only through their generator/check command, and the
    progress log must name that command.

### 4.1 Resume Protocol

Use this sequence whenever a new session, compacted context, or different agent
continues the work:

1. Read sections 1-7, then the latest entry in section 12.
2. Run `git status --short --branch`.
3. Confirm the branch name equals the branch for the active milestone.
4. Confirm all modified files are owned by the active milestone or already
   justified by a plan amendment.
5. Re-run the last failed or pending validation command before adding new code.
6. Identify the first incomplete task number in the active milestone.
7. Implement only that task and its directly required tests.
8. If a missing file, new DB field, or unexpected existing implementation changes
   the design, update this plan before editing code.
9. After validation, append a section 12 progress entry with changed files,
   task numbers, commands, and next task.
10. Only then commit or move to the next milestone.

Stop immediately if branch, modified files, or validation state cannot be
reconciled with section 12. The correct recovery is to inspect and amend the
plan, not to continue from intuition.

### 4.2 Milestone Execution Template

Each milestone must be executed in this order:

1. **Discovery:** inspect current files, migrations, tests, and package graph for
   only the owned area.
2. **Plan amendment:** record discovered mismatches in this document before code
   changes.
3. **Contract first:** define or update domain/API/prompt contracts before
   adapters.
4. **Tests/fixtures:** add the narrow test or fixture proving the contract.
5. **Implementation:** make the smallest production change satisfying the test.
6. **Validation:** run focused tests, then the milestone build/check commands.
7. **Progress log:** record evidence, blocker, and exact next step.
8. **Commit:** stage only owned files.

### 4.3 What Counts As Improvisation

These are not allowed unless this ExecPlan is amended first:

- adding a new behavior state such as `EMOTIONAL_SUPPORT`;
- changing final action values beyond `IGNORE / WAIT / REACT / SPEAK / CANCEL`;
- adding "contains phrase -> SPEAK" or "lonely -> SPEAK" logic;
- letting the speech pipeline decide whether NIA should participate;
- changing consent semantics to get a test to pass;
- replacing few-shot examples with deterministic admin rules;
- introducing a second/third judge or vote because a case is ambiguous;
- using socialmemory as the primary current-scene source instead of raw text;
- broad refactors outside the active milestone's owned files.

Allowed micro-decisions without a plan amendment: private helper names, DTO field
ordering, test fixture ids, local variable names, and compiler-only fixes inside
owned files that do not change a contract.

## 5. Core Contracts

### 5.1 Few-Shot Example Contract

Few-shot is NIA's judgment constitution. It teaches judgment style by examples,
not by deterministic conditions.

```json
{
  "id": "fs_example_...",
  "title": "direct reply request after ignored NIA turn",
  "status": "draft",
  "scope": {"type": "global", "guildId": null, "channelId": null, "persona": "nia"},
  "rawMessages": [
    {"ref": "m1", "authorRole": "member", "offsetMs": -180000, "text": "..."},
    {"ref": "m2", "authorRole": "nia", "offsetMs": -120000, "text": "..."},
    {"ref": "m3", "authorRole": "member", "offsetMs": 0, "text": "..."}
  ],
  "expectedAction": "SPEAK",
  "reason": "The user is continuing a direct exchange with NIA and silence reads as ignoring.",
  "evidenceRefs": ["m2", "m3"],
  "badAlternative": {
    "action": "WAIT",
    "whyBad": "Waiting longer makes NIA appear to ignore a direct request."
  },
  "tags": ["direct-address", "missed-reply-risk"],
  "priority": 100,
  "privacyClass": "synthetic",
  "evalStatus": "not_run"
}
```

Allowed `expectedAction` values are exactly:

```text
IGNORE, WAIT, REACT, SPEAK, CANCEL
```

### 5.2 Judge Input Contract

```json
{
  "schema": "nia.participation-judge-input.v1",
  "guildId": "redacted-or-numeric-runtime-only",
  "channelId": "redacted-or-numeric-runtime-only",
  "sceneWindow": {
    "maxChars": 200000,
    "messages": [
      {
        "ref": "msg_...",
        "authorRole": "member|nia|bot|system",
        "createdAt": "ISO-8601",
        "replyToRef": "msg_...|null",
        "text": "raw text"
      }
    ]
  },
  "fewShotSet": {
    "setId": "fs_set_...",
    "version": 3,
    "examples": []
  },
  "socialMemory": {
    "items": [
      {"type": "episode|fact|relationship|pending_intent", "text": "...", "sourceRefs": ["msg_..."]}
    ]
  },
  "metadata": {
    "consent": "OBSERVE_AND_SPEAK|OBSERVE_ONLY|DENIED",
    "recentNiaActions": [],
    "channelMode": "participation",
    "runtimeFlags": {}
  }
}
```

The raw scene text is primary. Social memory and metadata are secondary.

### 5.3 Judge Output Contract

```json
{
  "schema": "nia.participation-judge-output.v1",
  "action": "IGNORE|WAIT|REACT|SPEAK|CANCEL",
  "reason": "short natural-language explanation",
  "evidenceRefs": ["msg_..."],
  "confidence": 0.0,
  "riskFlags": [],
  "reevaluateAfterMs": 0
}
```

Validation rules:

- exactly one action,
- action must be one of the five allowed actions,
- non-IGNORE must include at least one evidence ref,
- SPEAK output must not contain final response text,
- malformed output degrades to WAIT or IGNORE, never forced SPEAK.

### 5.4 Durable Trace Contract

Every runtime case must be answerable by correlation id:

```text
ingest -> raw_context_window -> few_shot_version -> judge_decision
-> speech_pipeline_result -> scheduled_action -> action_audit -> discord_send
```

If a link is absent, the previous link must contain the durable reason.

## 6. Milestone Dependency Graph

```text
M0 plan/ADR
  -> M1 few-shot backend schema/service
  -> M2 few-shot admin API/UI
  -> M3 raw conversation brain
  -> M4 scene window assembler
  -> M5 single judge shadow mode
  -> M6 durable decision/speech tracing
  -> M7 DB consent adapter and speech block visibility
  -> M8 final judge promotion and heuristic removal
  -> M9 eval gates and seed examples
  -> M10 deploy/ops protection
  -> M11 staged rollout
```

Do not start M5 before M1-M4 are accepted. A judge without raw text and
few-shot versioning will regress into prompt slop.

## 7. Milestone Details

### M0 - Plan And Architecture Lock

Branch: `docs/nia-humanlike-v2-execplan`

Owned files:

- `docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md`
- optional ADR: `docs/adr/00XX-nia-raw-fewshot-judge.md`
- optional architecture updates:
  - `docs/nexa/architecture/participation-context.md`
  - `docs/nexa/architecture/conversation-context.md`
  - `docs/nexa/architecture/socialmemory-context.md`
  - `ai-context/domain.json`

Tasks:

1. Add this ExecPlan.
2. Add ADR if implementation starts in the same planning batch.
3. State that few-shot is an operational SSOT.
4. State that raw text windows are the current-scene source of truth.
5. State that new emotional behavior enums are prohibited.

Acceptance:

- Plan is self-contained.
- Docs mention approval gate for production deploy/DB repair/LIVE Discord.
- No code behavior changes.

Validation:

```bash
./scripts/nexa-verify.sh docs
```

Stop condition:

- Stop if link checks fail on unrelated pre-existing docs; record the failure
  rather than editing unrelated docs.

### M1 - Few-Shot Backend Schema And Service

Branch: `feat/nia-fewshot-backend`

Owned files:

- `central-server/src/main/resources/db/migration/V_NEXT__nexa_fewshot_sets.sql`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/fewshot/NiaFewShotModels.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/fewshot/NiaFewShotService.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaFewShotStorePort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaNiaFewShotStore.kt`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/...`
- `central-server/build.gradle.kts` only if `make central-build` fails from
  test JVM heap exhaustion after the new package is added.

Migration tables:

- `nexa_fewshot_set`
  - `id`
  - `scope_type`
  - `guild_id`
  - `channel_id`
  - `persona`
  - `active_version`
  - `created_at`
  - `updated_at`
- `nexa_fewshot_version`
  - `id`
  - `set_id`
  - `version`
  - `status`
  - `created_by`
  - `reviewed_by`
  - `published_at`
  - `rollback_of_version`
- `nexa_fewshot_example`
  - `id`
  - `version_id`
  - `title`
  - `raw_messages_json`
  - `expected_action`
  - `reason`
  - `evidence_refs_json`
  - `bad_alternative_json`
  - `tags_json`
  - `priority`
  - `privacy_class`
  - `eval_status`

Tasks:

1. Choose `V_NEXT` after checking current tracked and untracked migrations.
2. Implement immutable published versions.
3. Implement draft mutation only for draft versions.
4. Implement active-version lookup by global/guild/channel/persona precedence.
5. Validate that active versions cannot contain invalid actions.
6. Validate that active examples include reason, evidence refs, and bad
   alternative.
7. Store raw few-shot text only in DB, not in logs.
8. Add unit tests for version activation and rollback.
9. Add persistence tests for JSON fields and indexes.

Acceptance:

- Runtime can ask for the active few-shot set without knowing admin draft state.
- Publishing a new version does not mutate old versions.
- Rollback changes only the active pointer.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*NiaFewShot*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

Stop condition:

- Stop if migration numbering conflicts with existing local V71 work. Rebase or
  renumber before adding more migrations.

### M2 - Few-Shot Admin API And UI

Branch: `feat/nia-fewshot-admin`

Owned files:

- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/inbound/web/NiaFewShotAdminController.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/participation/adapter/inbound/web/NiaFewShotAdminControllerTest.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/global/security/AiNetworkApiSecurityFilter.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/web/AiNetworkApiSecurityFilterTest.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/fewshot/NiaFewShotService.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaFewShotStorePort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaNiaFewShotStore.kt`
- `admin-console/src/api.ts`
- `admin-console/src/App.tsx`
- `admin-console/src/styles.css`
- optional new UI files under `admin-console/src/components/`

API endpoints:

```text
GET    /api/admin/nia/few-shot/sets?guildId=&channelId=&persona=
POST   /api/admin/nia/few-shot/sets
GET    /api/admin/nia/few-shot/sets/{setId}/versions/{version}
POST   /api/admin/nia/few-shot/sets/{setId}/drafts
PUT    /api/admin/nia/few-shot/sets/{setId}/drafts/{version}
POST   /api/admin/nia/few-shot/sets/{setId}/drafts/{version}/preview
POST   /api/admin/nia/few-shot/sets/{setId}/drafts/{version}/eval
POST   /api/admin/nia/few-shot/sets/{setId}/versions/{version}/publish
POST   /api/admin/nia/few-shot/sets/{setId}/versions/{version}/rollback
POST   /api/admin/nia/few-shot/sets/{setId}/versions/{version}/archive
```

Tasks:

1. Reuse existing admin auth convention (`X-Dashboard-Admin-Token`) unless a
   stricter project rule exists.
2. Add request/response DTOs with no raw production ids in error messages.
3. Add preview endpoint that returns the exact judge prompt shape with raw text
   redacted only when requested by admin UI.
4. Add eval endpoint stub that runs local fixtures first; model-backed eval can
   be added in M9.
5. Add admin navigation item "NIA Few-Shot".
6. Add editor for raw message examples.
7. Add action selector limited to the five action values.
8. Add bad-alternative editor.
9. Add active/draft/archive badges.
10. Add publish/rollback buttons gated by eval status.

Acceptance:

- Admin can create a draft few-shot set, preview prompt, publish it, and roll
  back without code deploy.
- Admin UI never presents rule-builder controls such as "contains word -> action".

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
cd admin-console && pnpm build
```

Stop condition:

- Stop if UI starts becoming a deterministic rule editor. Revert that UI shape
  and keep examples as raw scenes plus expected judgment.

### M3 - Raw Conversation Brain

Branch: `feat/nia-raw-context-brain`

Owned files:

- existing tracked `central-server/src/main/resources/db/migration/V71__nexa_raw_context_store.sql`
- existing tracked `central-server/src/main/resources/db/migration/V73__nexa_raw_context_tombstones.sql`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/model/rawcontext/*`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/service/rawcontext/*`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/application/port/out/RawContextStorePort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/JpaRawContextStore.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/conversation/...`
- `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt`
  only when `RawContextStorePort` changes and test fakes must implement the same
  contract.

Important existing risk:

- M3 discovery found `V71__nexa_raw_context_store.sql` and
  `V73__nexa_raw_context_tombstones.sql` already tracked on the current base.
  Adopt and extend the existing raw-context implementation. Do not create a
  duplicate raw-context migration unless a verified schema gap appears, and if a
  schema gap appears, amend this plan before adding a new migration.

Table: `nexa_raw_context_message`

- `id`
- `guild_id`
- `channel_id`
- `message_id`
- `author_id_hash`
- `author_role`
- `created_at`
- `reply_to_message_id`
- `content`
- `content_char_count`
- `redaction_state`
- `ingest_correlation_id`
- indexes:
  - `(guild_id, channel_id, created_at)`
  - `(guild_id, channel_id, message_id)`
  - `(guild_id, channel_id, redaction_state)`

Tasks:

1. Verify existing ingest path stores raw text only after consent allows
   observation; if not, fix the ingest bridge, not the judge.
2. Verify or add rolling retention by channel using max character budget.
3. Set default budget to 200,000 characters per guild/channel unless config
   overrides.
4. Verify or add oldest-first pruning while preserving message boundaries.
5. Verify redaction removes text from future judge windows.
6. Verify query windows are returned in chronological order.
7. Verify reply references are retained when available.
8. Add diagnostics returning counts/hashes, not raw text.
9. Update test fakes to match any new raw-context port methods.
10. Record whether each existing behavior was adopted unchanged or changed.

Acceptance:

- Given 250,000 chars in one channel and 200,000 budget, oldest messages are
  pruned until the stored total is <= 200,000.
- A redacted message never appears in judge input.
- Raw text is never printed in app logs or test assertion error messages.
- Raw-context diagnostics expose only counts, timestamps, and fingerprints; no
  raw text, Discord snowflakes, or author ids.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*RawContext*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M4 - Scene Window Assembler

Branch: `feat/nia-scene-window`

Owned files:

- `central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/model/scene/NiaSceneWindow.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/service/scene/NiaSceneWindowBuilder.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/JudgeContextWindow.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssembler.kt`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/application/context/`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/`
- `test-fixtures/nexa/scenes/*.yaml`
- `scripts/validate-nexa-conversation-fixtures.py` if schema changes are needed

Discovery amendment:

- Existing base already has `JudgeContextWindowBuilder` and single-judge
  contract tests. Do not create a competing raw-window builder in participation.
  Add conversation-owned `NiaSceneWindow`/`NiaSceneWindowBuilder`, then make the
  existing participation builder consume that contract.
- Existing raw entries do not carry a direct `nia` author role. M4 must support
  `niaAuthorPseudonyms` for entries that are already known to be NIA, but must
  not add deterministic reply behavior or force SPEAK from that role.
- If runtime ingestion of NIA's own outgoing messages is missing, record it as a
  later bridge/integration gap unless M4 tests prove the window contract itself
  cannot represent NIA messages.

Tasks:

1. Assemble raw chronological window.
2. Attach reply chain refs.
3. Attach NIA's previous messages in the same channel.
4. Attach author role only, not raw user names, to model-facing payload unless
   runtime requires display names.
5. Attach socialmemory as secondary evidence with source refs.
6. Attach consent/channel metadata as metadata only.
7. Make output deterministic for the same message set.
8. Add anonymized fixture for the screenshot-like case.
9. Add contrast fixtures where WAIT/IGNORE/REACT/CANCEL are correct.
10. Ensure model-facing window refs do not expose raw Discord snowflakes,
    guild/channel ids, or author pseudonyms.

Acceptance:

- The screenshot-like fixture contains raw message sequence and expected SPEAK,
  but no deterministic "lonely -> speak" rule.
- Scene builder output diff is stable across repeated runs.

Validation:

```bash
python3 scripts/validate-nexa-conversation-fixtures.py
python3 scripts/evaluate-conversation-scene.py
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*Scene*' --tests '*JudgeContext*'
```

### M5 - Single Judge In Shadow Mode

Branch: `feat/nia-single-judge-shadow`

Owned files:

- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudge.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssembler.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParser.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/judge/*`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/shadow/*`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/`

Tasks:

1. Build prompt from raw scene, active few-shot version, socialmemory, and
   metadata.
2. Keep model access behind `NiaJudgeLlmPort`.
3. Parse strict JSON output.
4. Reject unknown action values.
5. Reject SPEAK output that includes final speech text.
6. Enforce evidence refs on non-IGNORE.
7. On malformed output, retry once, then degrade to WAIT or IGNORE.
8. Run in shadow beside current production path.
9. Persist shadow comparison without changing runtime behavior.

Acceptance:

- Shadow judge can produce all five actions from fixtures.
- Heuristic result is not allowed to override the shadow judge in logs.
- No production behavior changes yet.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*NiaJudge*' --tests '*Shadow*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M6 - Durable Decision And Speech Tracing

Branch: `feat/nia-durable-tracing`

Owned files:

- `central-server/src/main/resources/db/migration/V_NEXT__nexa_judge_trace_fields.sql`
- `central-server/src/main/resources/db/migration/V_NEXT__nexa_speech_decision_log.sql`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaParticipationDecisionLog.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/persistence/JpaSpeechDecisionLog.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/application/port/out/SpeechDecisionLogPort.kt`
- `scripts/diagnose-nia-decision.sh`

Decision log fields to add or represent:

- `judge_model_version`
- `judge_prompt_version`
- `fewshot_set_id`
- `fewshot_version`
- `raw_window_hash`
- `raw_window_message_refs_json`
- `evidence_refs_json`
- `judge_reason`
- `shadow_baseline_action`
- `final_decision_source`

Speech log table fields:

- `id`
- `decision_id`
- `correlation_id`
- `outcome`
- `blocked_stage`
- `blocked_reason`
- `generated_candidate_count`
- `critic_reasons_json`
- `selected_content_ref`
- `created_at`

Tasks:

1. Make every non-IGNORE decision traceable to few-shot version and raw refs.
2. Replace production `SpeechDecisionLogPort.Noop` with a DB sink.
3. Log `BLOCKED` before returning from speech pipeline.
4. Log when generation was never invoked.
5. Add read-only diagnostic script that accepts correlation id/message id and
   prints the trace without raw text.

Acceptance:

- A SPEAK that is consent-blocked has a speech decision row.
- A SPEAK that schedules action has linked decision id and action id.
- `diagnose-nia-decision.sh` explains the missing link without raw text.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*DecisionLog*' --tests '*SpeechDecisionLog*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M7 - DB Consent Adapter And Block Visibility

Branch: `feat/nia-db-consent-adapter`

Owned files:

- `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyAdapter.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/ConsentPolicyRepositories.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyAdapterTest.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/PolicyBackedConsentGateIntegrationTest.kt`

Inputs:

- `nexa_guild_consent`
- `nexa_channel_scope`
- `nexa_user_opt_out`
- `nexa_participation_channel_flag`

Decision synthesis:

```text
if guild disabled -> DENIED
else if user opted out -> DENIED
else if channel observe_allowed=false -> DENIED
else if channel speak_allowed=false -> OBSERVE_ONLY
else -> OBSERVE_AND_SPEAK
```

Tasks:

1. Register DB-backed `ConsentPolicyPort` as the production bean.
2. Keep fail-closed only as `@ConditionalOnMissingBean` fallback.
3. Add startup health indicator if only fail-closed bean is active in production.
4. Prove ingestion, speech generation, and external GLM request all use the same
   consent decision.
5. Add tests for empty consent tables.
6. Add tests for active guild/channel consent.
7. Add tests for user opt-out revocation before scheduled send.

Acceptance:

- With consent enabled, a fixture SPEAK can reach generation/scheduling.
- With consent denied, SPEAK is blocked and durable reason is stored.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*Consent*' --tests '*PolicyBackedConsentGate*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M8 - Promote Judge To Final Decision Maker

Branch: `feat/nia-judge-final-decision`

Owned files:

- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/*`
- `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/policy/baseline/CooldownHeuristicPolicy.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/domain/service/CoreInterventionRules.kt`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/`

Tasks:

1. Add feature flag: `CENTRAL_NEXA_JUDGE_MODE=shadow|final|off`.
2. In `shadow`, current behavior remains but judge logs decisions.
3. In `final`, judge output is the participation action.
4. Baseline cooldown heuristic may remain only as shadow comparison.
5. Core intervention rules may block unsafe/stale actions but cannot force
   SPEAK.
6. Remove any final-decision branch that returns SPEAK from direct mention rules.
7. Ensure WAIT/IGNORE/REACT never call speech generation.
8. Ensure SPEAK flows to speech and scheduler unless blocked with durable reason.

Acceptance:

- Production final decision source can be identified as `single_judge`.
- Logs no longer show `baseline-cooldown-heuristic-1` as final decision source
  in final mode.
- Direct-address cases are handled by judge evidence, not hard-coded SPEAK.

Validation:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server test --no-daemon --console=plain \
  --tests '*NexaParticipationEmitBridge*' --tests '*CoreInterventionRules*' --tests '*CooldownHeuristic*'
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M9 - Evaluation Gates And Initial Few-Shot Seed

Branch: `feat/nia-judge-eval-gates`

Owned files:

- `test-fixtures/nexa/quality/nia-fewshot-seed.yaml`
- `test-fixtures/nexa/scenes/*.yaml`
- `scripts/nia-judge-eval.py`
- `scripts/nexa-human-likeness-eval.py`
- `scripts/validate-nexa-eval-report.py`
- `docs/nexa/quality/human-likeness-rubric.md`
- `docs/nexa/quality/nia-judge-report.md`

Seed composition:

```text
SPEAK: 8
WAIT: 8
REACT: 5
IGNORE: 8
CANCEL: 4
hard ambiguous contrast cases: 7
total: 40
```

Required scenario categories:

- direct reply request after NIA participated,
- user asks for comfort but another human may still answer,
- NIA is mentioned only as a topic, not an addressee,
- reaction-only naturalness,
- stale scheduled response after another human answered,
- current raw text contradicts old memory,
- "not speaking would feel like ignoring" case.

Tasks:

1. Add synthetic or anonymized seed examples.
2. Add eval script that can run without production data.
3. Score action correctness.
4. Score over-talk risk.
5. Score under-talk/missed-direct-request risk.
6. Score stale-memory override risk.
7. Fail publish gate if seed eval fails.
8. Connect admin eval endpoint from M2 to this script/service.

Acceptance:

- Few-shot publish can be blocked by failed eval.
- Report names failed examples by id, not raw production text.

Validation:

```bash
python3 scripts/nia-judge-eval.py --fixtures test-fixtures/nexa/quality/nia-fewshot-seed.yaml
./scripts/nexa-verify.sh docs
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
```

### M10 - Deploy And Operations Protection

Branch: `fix/nia-central-deploy-traceability`

Owned files:

- `.github/workflows/central-deploy.yml`
- `.github/workflows/central-server-ci.yml`
- `scripts/diagnose-central-ops.sh`
- `scripts/diagnose-nia-decision.sh`
- `docs/nexa/operations/release-checklist.md`
- `docs/nexa/operations/github-actions-polling.md`

Tasks:

1. Make generated RAG commits unable to silently skip central deploy for intended
   code commits.
2. Add deploy summary fields:
   - app SHA,
   - image SHA,
   - migration version,
   - judge mode,
   - active few-shot set/version,
   - DB-backed consent adapter status.
3. Add read-only production diagnostic command for a message/correlation id.
4. Add manual deploy runbook with GitHub API polling limits.
5. Add rollback runbook for judge mode and few-shot active version.

Acceptance:

- A skipped deploy is visible as blocked/skipped with reason, not mistaken for a
  successful code deploy.
- Operators can verify whether production is using the intended judge/few-shot
  version without reading raw text.

Validation:

```bash
./scripts/nexa-verify.sh docs
actionlint
```

If `actionlint` is unavailable locally, record that gap and rely on GitHub CI.

### M11 - Staged Rollout

Branch: no code branch unless fixes are found.

Preconditions:

- M1-M10 merged.
- CI green on `main`.
- Production deploy approval received.
- DB migration backup/rollback plan recorded.
- Discord LIVE activation approval received for target guild/channel.

Rollout steps:

1. Deploy with `CENTRAL_NEXA_JUDGE_MODE=shadow`.
2. Verify app SHA, image SHA, migration version, active few-shot version.
3. Run read-only diagnostic on known recent messages.
4. Observe shadow for fixed window.
5. Enable final judge for one target guild/channel.
6. Verify SPEAK creates speech log plus scheduled action or durable block reason.
7. Monitor:
   - no-reply-after-SPEAK,
   - consent-blocked,
   - malformed judge output,
   - timeout fallback,
   - over-talk,
   - rollback events.
8. If silent SPEAK drop occurs, rollback judge mode to shadow and keep few-shot
   version unchanged for diagnosis.
9. If over-talk spike occurs, rollback few-shot version first, then judge mode if
   needed.
10. Expand scope only after the monitoring window passes.

Acceptance:

- The original screenshot-like case no longer results in unexplained silence.
- Every no-reply case has a durable reason.
- Production can be rolled back without code revert by judge mode or few-shot
  version.

## 8. Validation Matrix

| Area | Command |
| --- | --- |
| Docs | `./scripts/nexa-verify.sh docs` |
| Central build | `env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build` |
| Focused central tests | `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '<pattern>'` |
| Admin console | `cd admin-console && pnpm build` |
| Provider agent unaffected check | not required unless provider-agent files change |
| Full gate before merge batch | `./scripts/nexa-verify.sh docs central` |

## 9. Files That Must Not Change Without Updating This Plan

- `provider-agent/**`
- `games/**`
- `site/**`
- `protocol/wire-contract.json`
- generated RAG files under `rag/**`
- generated docs/HTML under `docs/ssot-viewer/**`

If one of these changes is genuinely required, add a new milestone or extend an
existing milestone before editing it.

## 10. First Implementation Order

Use this exact order:

1. M0 planning and ADR.
2. M1 few-shot backend.
3. M2 admin API/UI.
4. M3 raw conversation brain.
5. M4 scene window assembler.
6. M5 single judge shadow.
7. M6 durable tracing.
8. M7 DB consent adapter.
9. M8 judge final mode.
10. M9 eval gates.
11. M10 deploy/ops.
12. M11 rollout.

Do not jump to M8 before M1-M7. That would recreate the current failure mode:
the judge would exist but raw memory, few-shot governance, consent, and durable
logs would still be missing.

## 11. Definition Of Done

The whole project is done only when all are true:

- Admin has at least one active few-shot version.
- Runtime decision logs include active few-shot version.
- Runtime judge input includes deterministic raw scene window.
- Judge final mode is enabled for the target guild/channel.
- Baseline heuristic is not the final decision source.
- DB-backed consent adapter is active.
- Speech pipeline writes durable logs for blocked and successful paths.
- A SPEAK decision cannot disappear without `ai_request`, scheduled action, or
  durable block reason.
- The screenshot-like regression fixture passes.
- Production diagnostic can explain a real message without exposing raw text.

## 12. Progress Log

Append entries in this format:

```text
YYYY-MM-DD HH:MM KST - M? - status
Branch:
Commit/PR:
Task IDs:
Changed files:
Validation:
Blocked by:
Next:
```

Initial entry:

```text
2026-06-30 18:25 KST - M0 - completed
Branch: docs/nia-humanlike-v2-execplan
Commit/PR: 625e4a1514eeb167f011e8ab9313bbfeb798d2ce
Task IDs: M0.1-M0.5
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md,
  docs/adr/0016-nia-raw-fewshot-judge.md,
  docs/nexa/architecture/conversation-context.md,
  docs/nexa/architecture/participation-context.md,
  docs/nexa/architecture/socialmemory-context.md,
  docs/nexa/architecture/speech-context.md,
  ai-context/domain.json,
  docs/nexa/baseline/central-package-graph.md (generated by scripts/central-package-graph.py after docs gate drift)
Validation: docs gate passed after generated baseline refresh.
Blocked by: none
Next: M1 on feat/nia-fewshot-backend.
```

```text
2026-06-30 18:45 KST - M1 - completed
Branch: feat/nia-fewshot-backend
Commit/PR: 83b3ebb3d2a750c9efa7e414ec935c9d13261232
Task IDs: M1.1-M1.9
Changed files: central-server/src/main/resources/db/migration/V74__nexa_fewshot_sets.sql,
  central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/fewshot/NiaFewShotModels.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/fewshot/NiaFewShotService.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaFewShotStorePort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaNiaFewShotStore.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/domain/model/fewshot/NiaFewShotModelsTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaNiaFewShotStoreTest.kt,
  central-server/build.gradle.kts,
  docs/nexa/baseline/central-package-graph.md
Validation: focused *NiaFewShot* tests passed; make central-build passed after raising default test JVM heap
  from 1280m to 1536m; docs gate passed after regenerating central package graph; git diff --check passed.
Blocked by: none
Next: M2 on feat/nia-fewshot-admin.
```

```text
2026-06-30 19:00 KST - M2 - completed
Branch: feat/nia-fewshot-admin
Commit/PR: 7d3c8ff267ed7348b2633167135df98f6fbf0937
Task IDs: M2.1-M2.10
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/inbound/web/NiaFewShotAdminController.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/adapter/inbound/web/NiaFewShotAdminControllerTest.kt,
  central-server/src/main/kotlin/com/discordassistant/central/global/security/AiNetworkApiSecurityFilter.kt,
  central-server/src/test/kotlin/com/discordassistant/central/web/AiNetworkApiSecurityFilterTest.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/fewshot/NiaFewShotService.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaFewShotStorePort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaNiaFewShotStore.kt,
  admin-console/src/api.ts,
  admin-console/src/App.tsx,
  admin-console/src/styles.css,
  docs/nexa/baseline/central-package-graph.md,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused *NiaFewShot* and AiNetworkApiSecurityFilter tests passed; make central-build passed;
  cd admin-console && pnpm build passed; docs gate passed after regenerating central package graph.
Blocked by: none
Next: M3 raw conversation brain on feat/nia-raw-context-brain.
```

```text
2026-06-30 19:05 KST - M3 - plan amendment before continuing implementation
Branch: feat/nia-raw-context-brain
Commit/PR: pending
Task IDs: M3.1-M3.10 planning lock only; implementation validation still pending
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: docs gate passed; git diff --check passed.
Blocked by: none
Next: update raw-context test fakes for the diagnostics port method, then rerun focused *RawContext* tests.
```

```text
2026-06-30 19:10 KST - M3 - completed
Branch: feat/nia-raw-context-brain
Commit/PR: M3 commit on this branch; exact hash is the branch HEAD before M4 starts.
Task IDs: M3.1-M3.10
Changed files: central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/model/rawcontext/RawContextModels.kt,
  central-server/src/main/kotlin/com/discordassistant/central/conversation/application/port/out/RawContextStorePort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/JpaRawContextStore.kt,
  central-server/src/test/kotlin/com/discordassistant/central/conversation/domain/service/rawcontext/RawContextRingBufferTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/JpaRawContextStoreTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused *RawContext* tests passed; make central-build passed; git diff --check passed;
  default raw-context config scan shows nexa.raw-context.max-raw-chars-per-scope default is 200000.
Blocked by: none
Next: commit M3, then start M4 scene window assembler on feat/nia-scene-window.
```

```text
2026-06-30 19:23 KST - M4 - completed
Branch: feat/nia-scene-window
Commit/PR: pending
Task IDs: M4.1-M4.10
Changed files: central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/model/scene/NiaSceneWindow.kt,
  central-server/src/main/kotlin/com/discordassistant/central/conversation/domain/service/scene/NiaSceneWindowBuilder.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/JudgeContextWindow.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssembler.kt,
  central-server/src/test/kotlin/com/discordassistant/central/conversation/domain/service/scene/NiaSceneWindowBuilderTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/context/JudgeContextWindowBuilderTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssemblerTest.kt,
  test-fixtures/nexa/scenes/nia-window-direct-and-contrast.yaml,
  docs/nexa/baseline/central-package-graph.md,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused *Scene* and *JudgeContext* tests passed; python3 scripts/validate-nexa-conversation-fixtures.py passed;
  python3 scripts/evaluate-conversation-scene.py passed; make central-build passed after ktlint import/line-wrap fixes;
  docs gate passed after regenerating central package graph; git diff --check passed.
Blocked by: none
Next: commit M4, then start M5 single judge shadow mode on feat/nia-single-judge-shadow.
```
