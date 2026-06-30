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
19. For M5-M11, section 13 is mandatory. Resume from the first unchecked `R###`
    item, do not skip ahead, and split the item in this document first if it is
    still too broad to implement without interpretation.

### 4.1 Resume Protocol

Use this sequence whenever a new session, compacted context, or different agent
continues the work:

1. Read sections 1-7, then the latest entry in section 12.
2. Run `git status --short --branch`.
3. Confirm the branch name equals the branch for the active milestone.
4. Confirm all modified files are owned by the active milestone or already
   justified by a plan amendment.
5. Re-run the last failed or pending validation command before adding new code.
6. Identify the first incomplete task number in the active milestone. For
   M5-M11, use the first unchecked `R###` item in section 13 as the current
   task.
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

### 4.4 Concrete Work-Order Lock

Section 14 is mandatory for every remaining unchecked item in section 13. A
remaining `R###` item may be checked only after its section 14 work order has:

- all listed inputs inspected or an explicit section 12 note explaining why an
  input is absent;
- changes limited to the listed file envelope or a plan amendment made before
  editing new files;
- the named close evidence recorded in section 12 or in the exact test output;
- every stop/amend condition either absent or resolved by a plan amendment.

Do not treat "it compiles" as sufficient evidence for a work order unless the
work order explicitly names build success as the close evidence. Do not mark a
task done from chat memory, screenshots, or model confidence.

### 4.5 Context Reset Packet

Every resume handoff must start with this compact packet before further code:

```text
ExecPlan: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Branch: <current branch from git status>
Latest section 12 entry: <timestamp + milestone + next task>
First unchecked section 13 task: <R###>
Section 14 work order loaded: yes/no
Dirty files: <git status --short paths>
Last validation command/result: <command + pass/fail or not run>
Stop condition active: <none or exact condition>
```

If this packet cannot be filled from files and commands, pause and inspect; do
not continue from the previous assistant's prose summary.

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
  "reasonCode": "optional stable machine code",
  "evidenceRefs": ["msg_..."],
  "reactionCode": "only when action=REACT; stable code, not free-form emoji text",
  "speechIntent": {
    "intentSummary": "only when action=SPEAK; not final response text",
    "sceneDirection": "only when action=SPEAK; instruction for speech pipeline",
    "actHint": "optional stable hint"
  },
  "toneAxes": {
    "warmth": 0.5,
    "playfulness": 0.0,
    "directness": 0.5,
    "emotionalIntensity": 0.0
  },
  "confidence": 0.0,
  "riskFlags": [],
  "reevaluateAfterMs": 0
}
```

Validation rules:

- exactly one action,
- action must be one of the five allowed actions,
- non-IGNORE must include at least one evidence ref,
- REACT output must include `reactionCode`,
- SPEAK output may include `speechIntent` because existing
  `SingleJudgeDecision` requires an intent-only speech plan, but it must not
  contain final response text,
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

- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleParticipationJudgePort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleJudgeDecisionGuard.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleJudgeSceneSnapshotBuilder.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudge.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssembler.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParser.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/ShadowPredictionStorePort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/judge/*`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaShadowPredictionStore.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/shadow/*`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/application/shadow/`
- `docs/nexa/baseline/central-package-graph.md` only when regenerated by
  `python3 scripts/central-package-graph.py` after docs gate reports drift.

Discovery amendment:

- Existing base already has `SingleParticipationJudgePort`,
  `SingleJudgeDecisionRequest`, `SingleJudgeDecision`,
  `SingleJudgeDecisionGuard`, and `SingleJudgeSceneSnapshotBuilder`. M5 must
  extend these contracts; it must not create a parallel judge request/decision
  hierarchy.
- Existing base already has `ShadowPredictionStorePort`,
  `JpaShadowPredictionStore`, `ShadowStatusService`, and shadow tests. M5 must
  reuse that storage path and persist no raw text. If the current shadow schema
  cannot store a needed comparison field, add only application-level derived
  fields in M5 and defer DB schema expansion to M6 unless the plan is amended.
- Runtime domain uses `SocialActionKind.CANCEL_PENDING`, while the judge output
  contract exposes `CANCEL`. M5 maps output `CANCEL` to domain
  `CANCEL_PENDING` and must not introduce a sixth judge action.
- `SingleJudgeSceneSnapshotBuilder` may expose numeric/text-signal features to
  the judge, but M5 must not add or rely on a feature rule that forces SPEAK.
  The final action still comes from raw scene + few-shot + single judge.
- `SingleJudgeDecisionRequest` currently lacks active few-shot set/version
  data. M5 must add a compact few-shot reference/example payload to the request
  and `NiaJudgeContextAssembler` input before prompt assembly.

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
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/BanterSafetyDecisionService.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/ParticipationDecisionLogPort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaParticipationDecisionLog.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/persistence/JpaSpeechDecisionLog.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/application/port/out/SpeechDecisionLogPort.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaSpeechEmitService.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/speech/`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/`
- `scripts/diagnose-nia-decision.sh`
- `docs/nexa/baseline/central-package-graph.md` only when regenerated by
  `python3 scripts/central-package-graph.py` after docs gate reports drift.

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
- optional `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/ConsentPolicyRepositories.kt`
  only if query separation reduces complexity; otherwise keep the query layer
  private inside `DbConsentPolicyAdapter.kt`.
- optional `central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyHealthIndicator.kt`
  only if health/status code is split from the adapter file.
- `central-server/src/test/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyAdapterTest.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/PolicyBackedConsentGateIntegrationTest.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/conversation/IngestDiscordEventServiceTest.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaSpeechEmitServiceTest.kt`
- `central-server/src/test/kotlin/com/discordassistant/central/global/privacy/ConsentRevocationEndToEndTest.kt`

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
- optional `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaJudgeModeProperties.kt`
  or equivalent local config type if no existing config object can own the mode.
- optional `central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaJudgeRuntimeConfig.kt`
  or equivalent local runtime wiring config if existing M5 judge classes need
  Spring bean registration.
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/policy/baseline/CooldownHeuristicPolicy.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/domain/service/CoreInterventionRules.kt`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/`
- tests under `central-server/src/test/kotlin/com/discordassistant/central/participation/domain/service/`

Discovery amendment:

- Current final SPEAK paths are not yet judge-owned. `NexaParticipationEmitBridge`
  first runs `CoreInterventionRules`; `Verdict.Speak` bypasses policy and calls
  `emitSpeak` with `RULE_FORCED_MODEL_VERSION`. If core returns `Candidate`,
  bridge asks `ParticipationPolicyPort`; any response whose `mostLikelyAction`
  is `SPEAK` also reaches `emitSpeak`.
- `CoreInterventionRules` currently forces SPEAK for Discord mention, direct
  NIA name/vocative markers, reply-to-NIA, and continuation token overlap. It
  also intentionally lets direct-address plus burst/duplicate override WAIT or
  SILENT as repeated-call SPEAK.
- `CooldownHeuristicPolicy` currently forces SPEAK when mentioned, or when the
  recent NIA burst count is below cooldown. M8 must keep this only as baseline
  comparison in `final`, not as final action source.
- Existing M5 contracts already provide `SingleParticipationJudgePort`,
  `NiaParticipationJudge`, `NiaJudgeContextAssembler`, and
  `NiaJudgeShadowService`, but no production Spring bean wiring for the judge or
  shadow service was found. M8 may add local runtime config above; if no
  `NiaJudgeLlmPort` exists, `final` mode must not silently fall back to baseline
  SPEAK.
- Existing few-shot admin/service contracts provide `NiaFewShotService.activeFor`
  and scope fallback, but no mapper from active few-shot domain models to
  `JudgeFewShotSetPayload` was found. M8 bridge wiring must pass active
  few-shot examples into the single judge when the service is available; it may
  degrade to an empty payload only when the service or active set is absent.

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
- `central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/inbound/web/NiaFewShotAdminController.kt`
- `central-server/src/main/kotlin/com/discordassistant/central/participation/application/fewshot/*`
- `central-server/src/test/kotlin/com/discordassistant/central/participation/**/fewshot/*`
- `admin-console/src/api.ts`
- `admin-console/src/App.tsx`

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

```text
2026-06-30 19:27 KST - M5 - planning lock completed
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R001-M5.R002
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: docs gate passed; git diff --check passed.
Blocked by: none
Next: R003 add few-shot request payload types to the existing single-judge contract.
```

```text
2026-06-30 19:30 KST - M5 - request contract extended
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R003-M5.R004
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleParticipationJudgePort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssembler.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/SingleParticipationJudgeContractTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssemblerTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused SingleParticipationJudgeContractTest and NiaJudgeContextAssemblerTest passed; git diff --check passed.
Blocked by: none
Next: R005 add NiaJudgeLlmPort with provider-neutral request/response DTOs.
```

```text
2026-06-30 19:32 KST - M5 - LLM port contract added
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R005
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPort.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPortTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused NiaJudgeLlmPortTest, SingleParticipationJudgeContractTest, and NiaJudgeContextAssemblerTest passed;
  git diff --check passed.
Blocked by: none
Next: R006 add prompt-assembler tests for raw scene, few-shot, memory, constraints, and schema.
```

```text
2026-06-30 19:34 KST - M5 - prompt assembler added
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R006-M5.R007
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssembler.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssemblerTest.kt
Validation: focused NiaJudgePromptAssemblerTest, NiaJudgeLlmPortTest, SingleParticipationJudgeContractTest,
  and NiaJudgeContextAssemblerTest passed; git diff --check passed.
Blocked by: none
Next: R008 add parser tests for the five output actions and strict validation.
```

```text
2026-06-30 19:37 KST - M5 - strict judge output parser added
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R008-M5.R011
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParser.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParserTest.kt
Validation: focused NiaJudgeOutputParserTest, NiaJudgePromptAssemblerTest, NiaJudgeLlmPortTest,
  SingleParticipationJudgeContractTest, and NiaJudgeContextAssemblerTest passed; git diff --check passed.
Blocked by: none
Next: R012 add NiaParticipationJudge tests for success, one repair retry, and WAIT/IGNORE degrade.
```

```text
2026-06-30 19:39 KST - M5 - single judge service added
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R012-M5.R013
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudge.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudgeTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused NiaParticipationJudgeTest, NiaJudgeOutputParserTest, NiaJudgePromptAssemblerTest,
  NiaJudgeLlmPortTest, SingleParticipationJudgeContractTest, and NiaJudgeContextAssemblerTest passed;
  git diff --check passed.
Blocked by: none
Next: R014 add shadow-service tests proving judge result is persisted without behavior changes.
```

```text
2026-06-30 19:41 KST - M5 - shadow persistence service added
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R014-M5.R016
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/application/shadow/NiaJudgeShadowService.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/shadow/NiaJudgeShadowServiceTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused *NiaJudge* and *Shadow* tests passed; git diff --check passed.
Blocked by: none
Next: R017 run make central-build with JDK 21.
```

```text
2026-06-30 19:49 KST - M5 - completed and validated
Branch: feat/nia-single-judge-shadow
Commit/PR: pending
Task IDs: M5.R017-M5.R018
Changed files: central-server/src/main/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssembler.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/SingleParticipationJudgePort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParser.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssembler.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudge.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/shadow/NiaJudgeShadowService.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/context/NiaJudgeContextAssemblerTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/SingleParticipationJudgeContractTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgeOutputParserTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaJudgePromptAssemblerTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/judge/NiaParticipationJudgeTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/port/out/NiaJudgeLlmPortTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/application/shadow/NiaJudgeShadowServiceTest.kt,
  docs/nexa/baseline/central-package-graph.md,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation: focused *NiaJudge* and *Shadow* tests passed; make central-build passed with JDK 21;
  docs gate passed after central-package-graph regeneration; git diff --check passed.
Blocked by: none
Next: commit M5, then start M6 at R019 on `feat/nia-durable-tracing`.
```

```text
2026-06-30 20:02 KST - M6 - tracing discovery locked
Branch: feat/nia-durable-tracing
Commit/PR: pending
Task IDs: M6.R019-M6.R020
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Decision log path: extend existing `DecisionLogRecord` and `JpaParticipationDecisionLog`; do not replace the
  existing `nexa_policy_decision_log` table.
Speech path: extend `SpeechDecisionLogPort` and add a DB-backed `JpaSpeechDecisionLog`; production Noop remains
  only as `@ConditionalOnMissingBean` fallback.
Pipeline link: pass the participation correlation id from `NexaSpeechEmitService` into `NexaSpeechPipelineService`;
  pipeline-only tests may use an unlinked trace context.
Migration numbers: V75 for participation trace columns, V76 for speech decision log table. Tracked migrations end at
  V74 and no untracked migration exists in this worktree.
Validation: discovery only; implementation/tests start at R021.
Blocked by: none
Next: R021 add participation trace fields without raw text.
```

```text
2026-06-30 20:12 KST - M6 - durable tracing completed and validated
Branch: feat/nia-durable-tracing
Commit/PR: pending
Task IDs: M6.R021-M6.R032
Changed files: central-server/src/main/resources/db/migration/V75__nexa_judge_trace_fields.sql,
  central-server/src/main/resources/db/migration/V76__nexa_speech_decision_log.sql,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/BanterSafetyDecisionService.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/application/port/out/ParticipationDecisionLogPort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaParticipationDecisionLog.kt,
  central-server/src/main/kotlin/com/discordassistant/central/speech/adapter/outbound/persistence/JpaSpeechDecisionLog.kt,
  central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt,
  central-server/src/main/kotlin/com/discordassistant/central/speech/application/port/out/SpeechDecisionLogPort.kt,
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaSpeechEmitService.kt,
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt,
  central-server/src/test/kotlin/com/discordassistant/central/participation/adapter/outbound/persistence/JpaParticipationDecisionLogTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/speech/adapter/outbound/persistence/JpaSpeechDecisionLogTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaSpeechEmitServiceTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt,
  docs/nexa/baseline/central-package-graph.md,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md,
  scripts/diagnose-nia-decision.sh
Validation: focused `*DecisionLog*`, `*SpeechDecisionLog*`, `*NexaSpeechPipelineServiceTest`,
  `*NexaSpeechEmitServiceTest`, and `*NexaParticipationEmitBridgeTest` passed; `make central-build`
  passed with JDK 21; docs gate passed with the repo venv Python; `git diff --check` passed.
Blocked by: none
Next: commit M6, then start M7 at R033 on `feat/nia-db-consent-adapter`.
```

```text
2026-06-30 21:04 KST - M7 - consent discovery locked
Branch: feat/nia-db-consent-adapter
Commit/PR: pending
Task IDs: M7.R033-M7.R034
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Real tables and fields: V50 defines `nexa_guild_consent.guild_id/enabled`,
  `nexa_channel_scope.guild_id/channel_id/observe_allowed/speak_allowed`, and
  `nexa_user_opt_out.guild_id/user_id/opted_out`; V65 defines
  `nexa_participation_channel_flag.guild_pseudonym/channel_id/excluded`.
Consent call sites: `IngestDiscordEventService` checks `ConsentPolicyPort` before append;
  `PolicyBackedConsentGate` checks the same port for `SPEECH_GENERATION` and
  `EXTERNAL_GLM_REQUEST`; `NexaSpeechEmitConfig` wires that gate as the default
  `ConsentGate`; `FailClosedConsentPolicyConfig` remains the fallback
  `@ConditionalOnMissingBean(ConsentPolicyPort::class)`.
Plan amendment: M7 owned test path corrected from
  `central-server/src/test/kotlin/com/discordassistant/central/conversation/application/IngestDiscordEventServiceTest.kt`
  to `central-server/src/test/kotlin/com/discordassistant/central/conversation/IngestDiscordEventServiceTest.kt`.
  External GLM/request-boundary enforcement lives in `NexaSpeechPipelineService`, so that service and its
  focused test were added to the M7 owned file list before changing the consent-check order.
Validation: discovery only; implementation/tests continue at R035.
Blocked by: none
Next: R035 add repository/query layer coverage for guild consent, channel scope, user opt-out, and channel flags.
```

```text
2026-06-30 20:37 KST - M7 - DB consent adapter complete
Branch: feat/nia-db-consent-adapter
Commit/PR: pending
Task IDs: M7.R035-M7.R046
Changed files:
  central-server/src/main/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyAdapter.kt,
  central-server/src/main/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineService.kt,
  central-server/src/test/kotlin/com/discordassistant/central/conversation/IngestDiscordEventServiceTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/conversation/adapter/outbound/persistence/DbConsentPolicyAdapterTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/PolicyBackedConsentGateIntegrationTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaSpeechEmitServiceTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/speech/application/NexaSpeechPipelineServiceTest.kt,
  docs/nexa/baseline/central-package-graph.md,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Implementation: `DbConsentPolicyAdapter` now synthesizes DB-backed consent from guild enabled state,
  channel observe/speak scope, user opt-out, and participation channel exclusion. The production
  `ConsentPolicyPort` is DB-backed when present; `FailClosedConsentPolicyConfig` remains fallback-only.
  `NexaConsentPolicyHealthIndicator` reports `dbBackedConsentPolicyActive`, `failClosedOnly`, and
  `devEnabled` without raw text or ids.
Consent boundary fix: `NexaSpeechPipelineService` now checks `EXTERNAL_GLM_REQUEST` before calling the
  speech generation port, so `OBSERVE_ONLY` cannot reach external generation and then be blocked too late.
Validation:
  `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '*Consent*' --tests '*PolicyBackedConsentGate*'` passed.
  `env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build` passed.
  `python3 scripts/central-package-graph.py` regenerated `docs/nexa/baseline/central-package-graph.md`.
  `PATH=/Users/osuma/coding_stuffs/discord-assitant/.venv/bin:$PATH ./scripts/nexa-verify.sh docs` passed.
  First docs-gate attempt with this worktree PATH failed only because this worktree has no `.venv`
  and `/usr/bin/python3` lacks PyYAML; the repo venv rerun passed.
Blocked by: none
Next: commit M7, then start M8 at R047 on `feat/nia-judge-final-decision`.
```

```text
2026-06-30 20:42 KST - M8 - final decision discovery locked
Branch: feat/nia-judge-final-decision
Commit/PR: pending
Task IDs: M8.R047-M8.R048
Changed files: docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Current final SPEAK branches:
  1. `NexaParticipationEmitBridge.evaluateAndEmit` calls `CoreInterventionRules.evaluate` before policy.
     `Verdict.Speak` bypasses policy and calls `emitSpeak` with `RULE_FORCED_MODEL_VERSION`.
  2. `CoreInterventionRules` returns SPEAK for Discord mention, direct NIA markers/vocatives,
     reply-to-NIA, continuation overlap, and direct-address plus burst/duplicate repeated-call cases.
  3. Candidate flow asks `ParticipationPolicyPort`; any `PolicyDecisionResponse.mostLikelyAction == SPEAK`
     reaches `emitSpeak`.
  4. `CooldownHeuristicPolicy` returns SPEAK for mention or below-cooldown recent burst count.
Current trace sources: bridge records `RULE_CORE` for rule-forced decisions and `POLICY_ARGMAX` for policy
  decisions. `baseline-cooldown-heuristic-1` can still be the production policy model version.
Existing judge contracts: `SingleParticipationJudgePort`, `NiaParticipationJudge`, `NiaJudgeContextAssembler`,
  and `NiaJudgeShadowService` exist, but production Spring bean wiring for `SingleParticipationJudgePort` or
  `NiaJudgeShadowService` was not found; no `NiaJudgeLlmPort` adapter was found.
Plan amendment: added optional M8 owned runtime config file for judge mode/runtime wiring. `final` mode must not
  silently fall back to baseline SPEAK when judge wiring is absent.
Validation: discovery only; implementation/tests start at R049.
Blocked by: none
Next: R049 add `CENTRAL_NEXA_JUDGE_MODE=shadow|final|off` parsing with invalid values failing fast.
```

```text
2026-06-30 20:47 KST - M8 - judge off mode locked
Branch: feat/nia-judge-final-decision
Commit/PR: pending
Task IDs: M8.R049-M8.R050
Changed files:
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaJudgeMode.kt,
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaJudgeModeTest.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Implementation: `central.nexa.judge.mode` now parses `off`, `shadow`, and `final`; blank defaults to `off`;
  invalid values fail fast. `NexaParticipationEmitBridge` parses the property at bean construction.
Off-mode behavior: pre-M8 core/baseline action path is preserved, but decision logs now record
  `JUDGE_OFF_RULE_CORE` or `JUDGE_OFF_POLICY_ARGMAX` as `finalDecisionSource`, so traces explain that
  no single judge was called.
Validation: `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '*NexaJudgeModeTest' --tests '*NexaParticipationEmitBridgeTest'` passed.
Blocked by: none
Next: R051 run the single judge in `shadow` mode and persist comparison without changing runtime action.
```

```text
2026-06-30 21:06 KST - M8 - judge shadow wiring locked
Branch: feat/nia-judge-final-decision
Commit/PR: pending
Task IDs: M8.R051
Changed files:
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaJudgeRuntimeConfig.kt,
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Implementation: `shadow` mode now assembles the same raw-context-backed single-judge request used by M5,
  includes the active admin-managed few-shot set when present, records the judge prediction through
  `NiaJudgeShadowService`, and leaves the existing runtime action path unchanged. Runtime config conditionally
  registers `SingleParticipationJudgePort` and `NiaJudgeShadowService` only when a concrete judge LLM port exists,
  so default deployments stay off.
Validation: `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '*NexaJudgeModeTest' --tests '*NexaParticipationEmitBridgeTest'` passed.
Blocked by: none
Next: R052 use the single judge output as the final participation action when mode is `final`.
```

```text
2026-06-30 21:36 KST - M8 - final judge routing locked
Branch: feat/nia-judge-final-decision
Commit/PR: pending
Task IDs: M8.R052-M8.R062
Changed files:
  central-server/src/main/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridge.kt,
  central-server/src/test/kotlin/com/discordassistant/central/platform/discord/nexa/NexaParticipationEmitBridgeTest.kt,
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Implementation: `final` mode now treats the single judge as the only final participation action source. Baseline
  cooldown policy is still evaluated only as `shadowBaselineAction` comparison metadata. Core rules can still block
  SILENT/WAIT guard cases, but core direct-mention/reply SPEAK no longer bypasses the judge in final mode. Judge
  WAIT/IGNORE never call speech generation; judge REACT routes only through actionruntime; judge CANCEL_PENDING routes
  cancellation without speech; judge SPEAK enters the existing speech seam with `finalDecisionSource=SINGLE_JUDGE`.
Validation: `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '*NexaJudgeModeTest' --tests '*NexaParticipationEmitBridgeTest'` passed. Tests cover baseline-ignore + judge-SPEAK, direct mention + judge-IGNORE, judge WAIT/REACT no-speech, and judge CANCEL_PENDING no-speech cancellation.
Blocked by: none
Next: run M8 broad validation, append validation evidence, and commit `feat/nia-judge-final-decision`.
```

```text
2026-06-30 21:50 KST - M8 - validation passed
Branch: feat/nia-judge-final-decision
Commit/PR: pending
Task IDs: M8.R063-M8.R064
Changed files:
  docs/nexa/exec-plans/nia-humanlike-root-improvement-v2.md
Validation:
  1. `central-server/gradlew -p central-server test --no-daemon --console=plain --tests '*NexaParticipationEmitBridge*' --tests '*CoreInterventionRules*' --tests '*CooldownHeuristic*'` passed.
  2. `make central-build` passed after ktlint formatting of the M8-owned source/test files.
  3. `PATH=/Users/osuma/coding_stuffs/discord-assitant/.venv/bin:$PATH ./scripts/nexa-verify.sh docs` passed
     after regenerating `docs/nexa/baseline/central-package-graph.md` with `python3 scripts/central-package-graph.py`.
Blocked by: none
Next: inspect diff/status, stage only M8-owned files, and commit `feat/nia-judge-final-decision`.
```

## 13. Remaining Atomic Checklist (M5-M11)

This checklist is the execution ledger for the remaining work. Start at the
first unchecked item. Mark an item only after its code/docs/tests are complete
or after a progress-log entry records why it is deferred.

### M5 Atomic Checklist - Single Judge Shadow

- [x] R001 Inspect `SingleParticipationJudgePort.kt`,
  `SingleJudgeDecisionGuard.kt`, `SingleJudgeSceneSnapshotBuilder.kt`,
  `ShadowPredictionStorePort.kt`, `JpaShadowPredictionStore.kt`, and their
  tests; record whether each is adopted or extended.
- [x] R002 If R001 finds an unplanned contract or owned-file mismatch, amend
  this ExecPlan before writing production code.
- [x] R003 Add few-shot request payload types to the existing single-judge
  contract: active set id, version, examples, expected action, reason,
  evidence refs, bad alternative, and privacy class.
- [x] R004 Extend `NiaJudgeContextInput` and `NiaJudgeContextAssembler` so the
  judge request receives the active few-shot payload from M1/M2 contracts.
- [x] R005 Add `NiaJudgeLlmPort` with provider-neutral request/response DTOs;
  no Spring, HTTP, provider SDK, Discord, or raw logging in the port.
- [x] R006 Add prompt-assembler tests proving the prompt includes raw scene
  text, few-shot examples, secondary memory refs, constraints, and schema.
- [x] R007 Implement `NiaJudgePromptAssembler` using structured serialization;
  prompt text must state SPEAK returns intent only, not final response text.
- [x] R008 Add parser tests for exactly five output actions:
  `IGNORE`, `WAIT`, `REACT`, `SPEAK`, `CANCEL`.
- [x] R009 Implement strict JSON parsing for
  `nia.participation-judge-output.v1`, rejecting malformed or unknown actions.
- [x] R010 Reject parser output when SPEAK contains final text/message/content
  fields, and map wire `CANCEL` to domain `CANCEL_PENDING`.
- [x] R011 Enforce evidence refs for every non-IGNORE output in parser tests
  and implementation.
- [x] R012 Add `NiaParticipationJudge` tests for first-call success,
  one repair retry, and retry exhaustion degrading to WAIT or IGNORE.
- [x] R013 Implement `NiaParticipationJudge` behind
  `SingleParticipationJudgePort`; apply `SingleJudgeDecisionGuard` after parse.
- [x] R014 Add shadow-service tests proving the judge result is persisted as
  shadow comparison and cannot change runtime behavior in M5.
- [x] R015 Implement the M5 shadow service using existing
  `ShadowPredictionStorePort`; store hashes/refs/actions only, never raw text.
- [x] R016 Run focused `*NiaJudge*` and `*Shadow*` tests and fix only M5-owned
  files.
- [x] R017 Run `make central-build` with JDK 21 and fix only M5-owned files.
- [x] R018 Append the M5 progress entry, stage only M5-owned files, and commit
  `feat/nia-single-judge-shadow`.

### M6 Atomic Checklist - Durable Decision And Speech Tracing

- [x] R019 Inspect existing participation decision log, speech pipeline, and
  `SpeechDecisionLogPort`; record adopt/extend/replace decisions.
- [x] R020 Choose Flyway migration numbers after checking tracked and
  untracked migrations.
- [x] R021 Add participation trace fields for judge model, prompt, few-shot
  set/version, raw window hash, refs, evidence refs, reason, baseline action,
  and final decision source.
- [x] R022 Add speech decision log table or fields for decision id,
  correlation id, outcome, blocked stage/reason, generation count, critic
  reasons, selected content ref, and created time.
- [x] R023 Extend application ports and domain DTOs so trace data is carried
  without raw text.
- [x] R024 Implement or extend `JpaParticipationDecisionLog` to persist the
  new trace fields.
- [x] R025 Implement `JpaSpeechDecisionLog` and replace production Noop wiring
  where the real DB sink is available.
- [x] R026 Log `BLOCKED` before every speech-pipeline return that prevents
  generation, selection, scheduling, or send.
- [x] R027 Log when speech generation was never invoked and why.
- [x] R028 Link scheduled action id or durable block reason back to the
  participation decision id.
- [x] R029 Add `scripts/diagnose-nia-decision.sh` that prints correlation
  trace without raw text, snowflakes, or author ids.
- [x] R030 Add tests for consent-blocked SPEAK, successful scheduled SPEAK,
  and no-generation blocked paths.
- [x] R031 Run focused `*DecisionLog*` and `*SpeechDecisionLog*` tests.
- [x] R032 Run `make central-build`, append M6 progress, and commit
  `feat/nia-durable-tracing`.

### M7 Atomic Checklist - DB Consent Adapter

- [x] R033 Inspect consent tables, current fail-closed config,
  `PolicyBackedConsentGate`, ingestion, speech, and external GLM call sites.
- [x] R034 Amend this plan if real table/field names differ from section 7 M7.
- [x] R035 Add repository/query layer for guild consent, channel scope,
  user opt-out, and participation channel flags.
- [x] R036 Implement `DbConsentPolicyAdapter` synthesis exactly as section 7
  M7 states: disabled guild, user opt-out, observe denied, speak denied,
  else observe-and-speak.
- [x] R037 Register DB-backed `ConsentPolicyPort` as production bean and keep
  fail-closed only as `@ConditionalOnMissingBean`.
- [x] R038 Add startup health/status evidence when production has only the
  fail-closed consent bean.
- [x] R039 Add ingestion tests proving DENIED stores no raw context.
- [x] R040 Add speech tests proving OBSERVE_ONLY blocks generation and logs
  the durable reason.
- [x] R041 Add external GLM/request-boundary tests proving the same consent
  decision is reused.
- [x] R042 Add empty-table tests proving fail-closed behavior.
- [x] R043 Add enabled guild/channel tests proving OBSERVE_AND_SPEAK reaches
  generation/scheduling.
- [x] R044 Add opt-out revocation tests before scheduled send.
- [x] R045 Run focused `*Consent*` and `*PolicyBackedConsentGate*` tests.
- [x] R046 Run `make central-build`, append M7 progress, and commit
  `feat/nia-db-consent-adapter`.

### M8 Atomic Checklist - Judge Final Decision

- [x] R047 Inspect `NexaParticipationEmitBridge`, baseline policy config,
  `CooldownHeuristicPolicy`, and `CoreInterventionRules` for any final SPEAK
  forcing branches.
- [x] R048 Amend this plan if final decision wiring lives outside the M8-owned
  files.
- [x] R049 Add `CENTRAL_NEXA_JUDGE_MODE=shadow|final|off` config parsing with
  invalid values failing fast.
- [x] R050 In `off`, preserve the pre-M8 production path and still write a
  durable reason for no judge call when tracing is present.
- [x] R051 In `shadow`, run the single judge and persist comparison while
  preserving the current runtime action.
- [x] R052 In `final`, use the single judge output as the participation action.
- [x] R053 Keep baseline cooldown heuristic only as shadow comparison in final
  mode.
- [x] R054 Change `CoreInterventionRules` so it can block unsafe/stale actions
  but cannot force SPEAK as the final decision in final mode.
- [x] R055 Remove or bypass direct-mention/reply-to-NIA branches that return
  SPEAK without judge evidence.
- [x] R056 Ensure WAIT, IGNORE, REACT, and CANCEL never call speech generation.
- [x] R057 Ensure SPEAK reaches speech and scheduler unless consent, guard, or
  runtime blocks it with a durable reason.
- [x] R058 Map domain `CANCEL_PENDING` to actionruntime cancellation without
  speech generation.
- [x] R059 Persist `final_decision_source=single_judge` in final mode and
  `final_decision_source=baseline` in shadow/off modes.
- [x] R060 Add bridge tests for off, shadow, and final modes.
- [x] R061 Add regression tests proving direct-address cases are handled by
  judge evidence, not deterministic SPEAK rules.
- [x] R062 Add tests proving no action other than SPEAK enters speech.
- [x] R063 Run focused `*NexaParticipationEmitBridge*`,
  `*CoreInterventionRules*`, and `*CooldownHeuristic*` tests.
- [x] R064 Run `make central-build`, append M8 progress, and commit
  `feat/nia-judge-final-decision`.

### M9 Atomic Checklist - Evaluation Gates And Few-Shot Seed

- [ ] R065 Inspect existing fixture schema and eval scripts before creating
  the seed dataset.
- [ ] R066 Add `test-fixtures/nexa/quality/nia-fewshot-seed.yaml` schema with
  explicit ids, actions, evidence refs, and privacy class.
- [ ] R067 Add exactly 40 synthetic/anonymized seed examples with the section
  7 M9 composition.
- [ ] R068 Add a validation check that fails if fixture text is marked or
  shaped as production raw text.
- [ ] R069 Implement `scripts/nia-judge-eval.py` action-correctness scoring.
- [ ] R070 Add over-talk risk scoring.
- [ ] R071 Add under-talk/missed-direct-request risk scoring.
- [ ] R072 Add stale-memory override risk scoring where current raw text beats
  old memory.
- [ ] R073 Add ambiguous contrast-case scoring for cases where a nearby action
  is tempting but wrong.
- [ ] R074 Connect the M2 admin eval endpoint to the local eval service/script
  without requiring production data.
- [ ] R075 Fail few-shot publish when required seed eval thresholds fail.
- [ ] R076 Write `docs/nexa/quality/human-likeness-rubric.md`.
- [ ] R077 Generate `docs/nexa/quality/nia-judge-report.md` with failed ids,
  not production raw text.
- [ ] R078 Run `scripts/nia-judge-eval.py` on the seed fixture.
- [ ] R079 Run `scripts/validate-nexa-eval-report.py`.
- [ ] R080 Run docs gate and `make central-build`.
- [ ] R081 Append M9 progress and commit `feat/nia-judge-eval-gates`.

### M10 Atomic Checklist - Deploy And Operations Protection

- [ ] R082 Inspect central deploy and CI workflows before editing YAML.
- [ ] R083 Make intended central code commits unable to be hidden behind a
  generated RAG skip path.
- [ ] R084 Add deploy summary fields for app SHA, image SHA, migration
  version, judge mode, few-shot set/version, and DB consent adapter status.
- [ ] R085 Extend `scripts/diagnose-central-ops.sh` or
  `scripts/diagnose-nia-decision.sh` for read-only production verification.
- [ ] R086 Add manual deploy runbook steps with GitHub API polling limits.
- [ ] R087 Add rollback runbook for judge mode and few-shot active version.
- [ ] R088 Add docs for interpreting skipped, blocked, and successful deploys.
- [ ] R089 Run docs gate.
- [ ] R090 Run `actionlint`; if unavailable, record the local tool gap and rely
  on GitHub CI.
- [ ] R091 Append M10 progress and commit
  `fix/nia-central-deploy-traceability`.

### M11 Atomic Checklist - Staged Rollout

- [ ] R092 Verify M1-M10 are merged, CI is green on `main`, and no release or
  production approval gate is missing.
- [ ] R093 Obtain explicit human approval before production deploy, DB
  migration, Discord LIVE behavior, or target guild/channel enablement.
- [ ] R094 Deploy with `CENTRAL_NEXA_JUDGE_MODE=shadow`.
- [ ] R095 Verify app SHA, image SHA, migration version, active few-shot
  set/version, and DB-backed consent adapter status.
- [ ] R096 Run read-only diagnostics on known recent messages without exposing
  raw text.
- [ ] R097 Enable final judge for one approved target guild/channel only.
- [ ] R098 Monitor no-reply-after-SPEAK, consent-blocked, malformed judge
  output, timeout fallback, over-talk, and rollback events.
- [ ] R099 If silent SPEAK drop or over-talk spike occurs, execute the planned
  rollback path and keep diagnostic evidence.
- [ ] R100 Record final rollout evidence that the screenshot-like regression no
  longer produces unexplained silence and every no-reply case has a durable
  reason.

## 14. Remaining Work Orders

Use this section as the execution spec for section 13. The first unchecked
`R###` is the only active work order unless section 12 explicitly says otherwise.

### M7 Work Orders - DB Consent Adapter

| ID | Inputs to inspect first | Allowed change | Close evidence | Stop/amend condition |
| --- | --- | --- | --- | --- |
| R033 | `git status --short --branch`; migrations defining `nexa_guild_consent`, `nexa_channel_scope`, `nexa_user_opt_out`, `nexa_participation_channel_flag`; `ConsentPolicyPort`; `FailClosedConsentPolicyConfig`; `PolicyBackedConsentGate`; ingest/speech call sites. | Section 12 discovery note only. | Section 12 lists real table names, field names, active branch, dirty files, and call sites. | Any table/field/call site differs from section 7 M7. |
| R034 | R033 discovery note. | This ExecPlan only. | Either "no amendment needed" in section 12, or updated table/file/contract rows before code. | Discovered mismatch not reflected in section 7, section 13, or this section. |
| R035 | Real SQL columns from R033 and existing persistence style in `Jpa*Store` adapters. | Query layer in `DbConsentPolicyAdapter.kt`, or optional `ConsentPolicyRepositories.kt` if it reduces complexity. | Tests or focused assertions cover guild enabled, channel scope, user opt-out, and channel flag queries. | Query requires raw text, Discord names, production ids in logs, or new schema. |
| R036 | `ConsentDecision` semantics and section 7 M7 synthesis. | `DbConsentPolicyAdapter` synthesis only. | Empty/missing data returns `DENIED`; enabled + observe + speak returns `OBSERVE_AND_SPEAK`; enabled + observe + no speak returns `OBSERVE_ONLY`. | Any new consent enum/state or phrase-based behavior rule appears. |
| R037 | Spring bean registration and `@ConditionalOnMissingBean` fallback. | DB adapter bean wiring; fallback config only if needed to preserve fail-closed. | A Spring context or slice test proves the DB adapter is the production `ConsentPolicyPort` when present and fallback remains only when missing. | More than one unqualified `ConsentPolicyPort` bean exists. |
| R038 | Actuator health/status conventions already used in central server. | Consent health/status indicator in the M7 owned package. | Health/status output includes `dbBackedConsentPolicyActive`, `failClosedOnly`, and dev/prod distinction. | The health path prints raw text, snowflakes, tokens, or user ids. |
| R039 | `IngestDiscordEventService` tests/fakes and raw-context append path. | Ingestion test only, plus M7-owned fix if it fails. | DENIED returns the rejected consent result and the fake/store receives zero raw context appends. | Ingest stores raw before consent, or fixing requires non-M7 owned architecture. |
| R040 | `NexaSpeechPipelineService`/`NexaSpeechEmitService` consent-block tests. | Speech test and M7-owned consent/logging fix. | `OBSERVE_ONLY` passes observation but blocks `EXTERNAL_GLM_REQUEST`; speech decision log records blocked reason. | Speech generation is reached without a consent gate. |
| R041 | `PolicyBackedConsentGate.pseudonymOf` and external GLM boundary. | Gate integration test or speech-pipeline test. | The same `ConsentPolicyPort` decision is used at `SPEECH_GENERATION` and `EXTERNAL_GLM_REQUEST`. | A second in-memory consent source is introduced. |
| R042 | Empty DB state from migrations. | DB adapter test. | Empty `nexa_*consent*` tables return `DENIED` for observation and speech. | Empty DB defaults to observe or speak. |
| R043 | Existing SPEAK scheduling tests in `NexaSpeechEmitServiceTest`. | Test wiring DB adapter or policy-backed gate into a fixture SPEAK path. | Enabled guild/channel consent schedules exactly one SPEAK or reaches the existing scheduling fake. | The test bypasses `PolicyBackedConsentGate`. |
| R044 | `ConsentRevocationEndToEndTest`, actionruntime revocation listener, and scheduled action path. | Revocation test and minimal adapter/listener connection if absent. | Opt-out before send cancels/blocks scheduled SPEAK before Discord execution/export. | Revocation waits for scheduler tick or requires production DB mutation. |
| R045 | All M7 focused tests. | Test fixes inside M7-owned files only. | `central-server/gradlew ... --tests '*Consent*' --tests '*PolicyBackedConsentGate*'` passes. | Same failure repeats twice or points outside M7 ownership. |
| R046 | M7 diff, docs gate status, central build status. | Section 12 progress entry and commit only. | `make central-build` passes; docs gate passes or package graph is regenerated by its script; commit includes only M7-owned files. | Dirty unowned files are staged or validation evidence is missing. |

### M8 Work Orders - Final Judge Promotion

| ID | Inputs to inspect first | Allowed change | Close evidence | Stop/amend condition |
| --- | --- | --- | --- | --- |
| R047 | `NexaParticipationEmitBridge`, `CooldownHeuristicPolicy`, `CoreInterventionRules`, final SPEAK branches, decision log fields. | Section 12 discovery note only. | Section 12 lists every branch that can currently force SPEAK. | A final-decision branch lives outside M8 owned files. |
| R048 | R047 discovery. | This ExecPlan only. | M8 owned files and work orders match real wiring before code. | New config/route/log file is required but not listed. |
| R049 | Existing config binding style. | Judge mode config type and invalid-value fail-fast. | Unit/context test covers `off`, `shadow`, `final`, and invalid mode. | Default mode changes production behavior without explicit config. |
| R050 | M6 trace fields and current baseline path. | Off-mode trace reason only. | Off mode preserves baseline action but logs no-judge/final-source reason. | Off mode calls the LLM judge. |
| R051 | M5 shadow service and M6 decision log. | Shadow-mode bridge wiring. | Shadow mode persists judge comparison and leaves runtime action unchanged. | Shadow mode changes scheduled action behavior. |
| R052 | `NiaParticipationJudge` output contract and bridge action mapping. | Final-mode action selection. | Final mode maps only judge output to final participation action. | Baseline policy can override final judge. |
| R053 | Baseline policy usage sites. | Rename/wire baseline as comparison-only in final mode. | Final-mode logs never report `baseline-cooldown-heuristic-1` as final source. | Baseline remains in final action path. |
| R054 | `CoreInterventionRules` verdict model. | Convert hard SPEAK rules to guard/candidate behavior in final mode while preserving unsafe/stale blocks. | Tests prove rules may block/wait/drop but cannot force SPEAK in final mode. | A deterministic direct-address rule still returns final SPEAK. |
| R055 | Direct mention/reply-to-NIA tests and bridge branches. | Remove/bypass final SPEAK shortcuts. | Regression test shows direct-address cases need judge evidence. | Fix adds a phrase list that forces SPEAK. |
| R056 | Speech emit entry points. | Routing guard tests. | WAIT/IGNORE/REACT/CANCEL never enter speech generation. | Non-SPEAK action touches speech service. |
| R057 | SPEAK path and durable block reasons. | Routing/logging fix for SPEAK. | SPEAK either reaches speech+scheduler or stores a durable block reason. | Silent return after SPEAK exists. |
| R058 | `CANCEL_PENDING` mapping and actionruntime cancel path. | Bridge/runtime mapping only. | CANCEL cancels pending action without speech generation. | CANCEL creates a new speech action. |
| R059 | `final_decision_source` persistence. | Decision log wiring. | Final mode writes `single_judge`; shadow/off write `baseline` or explicit non-final source. | Source field is null for a completed decision. |
| R060 | Bridge tests. | Tests for off/shadow/final modes. | Three mode tests pass and assert final action source. | Mode tests rely on production external LLM. |
| R061 | Screenshot-like synthetic fixture and direct-address contrast cases. | Regression tests only unless failure is M8-owned. | Direct-address behavior is explained by raw scene + few-shot + judge evidence. | Test asserts a deterministic phrase rule. |
| R062 | Speech-entry tests. | Tests only unless M8-owned fix needed. | No action except SPEAK can call speech. | The test can pass while speech is mocked away from all branches. |
| R063 | Focused M8 test command. | M8-owned test/code fixes only. | `*NexaParticipationEmitBridge*`, `*CoreInterventionRules*`, `*CooldownHeuristic*` pass. | Same failure repeats twice or points outside M8 ownership. |
| R064 | M8 full diff and validation. | Section 12 progress entry and commit only. | `make central-build` passes; commit includes only M8-owned files. | Unowned files are required without plan amendment. |

### M9 Work Orders - Eval Gates And Few-Shot Seed

| ID | Inputs to inspect first | Allowed change | Close evidence | Stop/amend condition |
| --- | --- | --- | --- | --- |
| R065 | Existing fixture schemas, `scripts/validate-nexa-eval-report.py`, admin eval endpoint, `scripts/nexa-human-likeness-eval.py`. | Section 12 discovery note only. | Section 12 names the schema to extend/reuse and the admin eval integration point. | Existing schema conflicts with M9 composition. |
| R066 | Chosen fixture schema from R065. | `nia-fewshot-seed.yaml` schema/header. | Schema contains ids, action, raw message refs, evidence refs, bad alternatives, and privacy class. | Schema requires production raw text. |
| R067 | Section 7 M9 composition. | Exactly 40 synthetic/anonymized examples. | Counts are SPEAK 8, WAIT 8, REACT 5, IGNORE 8, CANCEL 4, hard ambiguous 7. | Any example is copied from production text or real Discord ids. |
| R068 | Fixture privacy class and validator style. | Validation script/rule. | Validator fails if an example is marked or shaped as production raw text. | Validator needs access to production logs. |
| R069 | Judge output contract and fixture expected actions. | Eval scoring for action correctness. | Script exits non-zero when action accuracy threshold fails and reports ids only. | Report prints raw production text. |
| R070 | Over-talk failure definition. | Over-talk metric in eval script/report. | False SPEAK/over-talk risk is scored and thresholded. | Metric becomes a phrase blacklist. |
| R071 | Under-talk/missed-direct scenarios. | Under-talk metric. | Missed direct reply/request cases are scored and thresholded. | Metric forces SPEAK by deterministic rule. |
| R072 | Stale memory scenario category. | Stale-memory override metric. | Current raw scene beats contradictory old memory in scoring. | Socialmemory becomes primary current-scene source. |
| R073 | Ambiguous contrast cases. | Ambiguity metric/category. | Nearby-but-wrong action failures are counted by example id. | Ambiguity is solved by adding new enums. |
| R074 | M2 admin eval endpoint and service boundaries. | Admin eval service/script bridge. | Admin eval runs local seed evaluation without production data. | Endpoint sends production raw text to docs/scripts. |
| R075 | Publish workflow in `NiaFewShotService`. | Publish gate. | Publish fails with actionable failed ids when seed thresholds fail. | Failed publish can be overridden silently. |
| R076 | Existing quality docs. | `human-likeness-rubric.md`. | Rubric defines action quality, over-talk, under-talk, stale-memory, ambiguity, and privacy. | Rubric introduces deterministic comfort/emotion states. |
| R077 | Eval output format. | Generated report. | `nia-judge-report.md` includes thresholds, pass/fail, failed ids, no production raw text. | Report includes raw production text or Discord ids. |
| R078 | Eval command. | Script/data fixes only. | `python3 scripts/nia-judge-eval.py --fixtures test-fixtures/nexa/quality/nia-fewshot-seed.yaml` passes. | Script requires network or production DB. |
| R079 | Report validator. | Validator/report fixes only. | `python3 scripts/validate-nexa-eval-report.py` passes. | Validator accepts missing thresholds. |
| R080 | Docs gate and central build. | M9-owned fixes only. | Docs gate and `make central-build` pass. | Central build failure points outside M9 ownership. |
| R081 | M9 diff and validation. | Section 12 progress entry and commit only. | Commit includes seed, scripts, admin eval wiring, report/rubric, and validation evidence. | Commit includes production raw text. |

### M10 Work Orders - Deploy And Operations Protection

| ID | Inputs to inspect first | Allowed change | Close evidence | Stop/amend condition |
| --- | --- | --- | --- | --- |
| R082 | Central deploy workflow, central CI workflow, existing skip/path filters, deploy summaries. | Section 12 discovery note only. | Section 12 lists the exact skip condition and deploy trigger path. | Workflow ownership differs from section 7 M10. |
| R083 | R082 skip condition. | Workflow guard/filter fix. | A central code commit cannot be hidden by generated-RAG skip logic. | Fix broadens deploy to unrelated paths without reason. |
| R084 | Existing GitHub Actions summary format. | Deploy summary fields. | Summary prints app SHA, image SHA, migration version, judge mode, few-shot version, DB consent status. | Summary prints secrets/tokens/raw text. |
| R085 | Existing diagnostic scripts and M6 decision trace script. | Read-only diagnostic extension. | Diagnostic can verify app/migration/judge/few-shot/consent status without raw text. | Diagnostic mutates production or requires LIVE send. |
| R086 | Project GitHub API polling rule. | Manual deploy runbook. | Runbook states no more than one PR/check/run poll every 8 minutes unless user asks tighter. | Runbook encourages tight polling loops. |
| R087 | Judge mode and few-shot rollback controls. | Rollback runbook. | Runbook can roll back by judge mode or few-shot version without code revert. | Rollback requires DB deletion or irreversible migration. |
| R088 | Skipped/blocked/successful deploy states. | Ops docs. | Docs distinguish skipped, blocked, failed, deployed, and verified production states. | Docs call a skipped deploy successful. |
| R089 | Docs gate. | Docs/script fixes only. | `./scripts/nexa-verify.sh docs` passes. | Docs gate failure points outside M10 ownership. |
| R090 | `actionlint` availability. | Workflow lint fixes or recorded local gap. | `actionlint` passes, or section 12 records tool absence and GitHub CI reliance. | Workflow syntax is unvalidated and no gap is recorded. |
| R091 | M10 diff and validation. | Section 12 progress entry and commit only. | Commit includes only M10-owned files and validation evidence. | Human approval gate is bypassed. |

### M11 Work Orders - Staged Rollout

| ID | Inputs to inspect first | Allowed change | Close evidence | Stop/amend condition |
| --- | --- | --- | --- | --- |
| R092 | `main` branch state, PR/CI status, release gates, M1-M10 progress entries. | No code; readiness note only. | Readiness note says M1-M10 merged, CI green, no missing release/prod gate. | Any gate missing or ambiguous. |
| R093 | Human approval record for deploy, DB migration, Discord LIVE, target guild/channel. | No code; approval capture only. | Explicit approval is recorded before prod/LIVE action. | Approval is absent, indirect, or ambiguous. |
| R094 | Approved deploy path and M10 runbook. | Deployment action only after R093. | Production starts in `CENTRAL_NEXA_JUDGE_MODE=shadow`. | Mode is `final` before shadow verification. |
| R095 | Deployed app SHA, image SHA, Flyway/migration status, active few-shot, DB consent health. | Verification only. | Evidence records exact SHA/version/status values. | Any deployed value differs from intended. |
| R096 | Read-only diagnostic command and known message/correlation ids. | Diagnostics only; no raw text in chat/docs. | Diagnostic output proves explainability without exposing raw text. | Diagnostic requires copying production raw content. |
| R097 | Approved target guild/channel and final-mode toggle path. | Enable final judge for one approved scope only. | One target guild/channel is in final mode; all others remain shadow/off. | Scope is broader than approval. |
| R098 | Metrics/log queries for silent SPEAK drops, consent blocks, malformed judge output, timeout fallback, over-talk, rollback events. | Monitoring only. | Monitoring window evidence records counts and examples by id/hash only. | Monitoring cannot distinguish silence cause. |
| R099 | M10 rollback runbook and R098 evidence. | Rollback only if triggered. | Rollback evidence records trigger, action, and post-rollback health. | Rollback requires code revert or data deletion. |
| R100 | Original screenshot-like synthetic regression, decision trace, speech trace, rollout evidence. | Final evidence note only. | The regression has no unexplained silence; every no-reply path has durable reason. | Any no-reply path lacks traceable reason. |
