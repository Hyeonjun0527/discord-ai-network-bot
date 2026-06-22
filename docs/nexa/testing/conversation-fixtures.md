# NEXA Conversation Fixture Format v1

Status: baseline format for `test-fixtures/nexa/conversations/*.yaml` from `NEXA-P00-T016` onward. The format is intentionally small: it records Discord-observable events, deterministic replay time, actors, and optional expected projections. It does not define the final conversation domain model; later tasks can map this fixture into domain objects.

Related files:

- [JSON Schema](../../../test-fixtures/nexa/schemas/conversation-fixture.schema.json)
- [Nickname burst fixture](../../../test-fixtures/nexa/conversations/nickname-burst.v1.yaml)
- [Event-shape coverage fixture](../../../test-fixtures/nexa/conversations/event-shapes.v1.yaml)
- [Deterministic testing convention](./determinism.md)

## Top-level shape

```yaml
schemaVersion: nexa.conversation-fixture.v1
fixtureId: nickname-burst
title: Fragmented nickname-change chat from PROJECT_CONTEXT section 2.5
source:
  document: docs/nexa/PROJECT_CONTEXT.md
  lineRange: 228-242
  originalTimeKnown: false
time:
  baseInstant: "2026-01-01T11:15:01Z"
  zone: Asia/Seoul
  resolutionMs: 1000
guild:
  guildId: guild-nexa-fixture
  channelId: channel-general
actors: []
events: []
expected: {}
```

Rules:

- `schemaVersion` is fixed to `nexa.conversation-fixture.v1`.
- `fixtureId` is stable and lowercase kebab-case so test names and reports can refer to it.
- `time.baseInstant` plus each event's `atOffsetMs` is the only replay clock. Do not call system time while replaying a fixture.
- `events[].seq` is strictly increasing. `events[].atOffsetMs` is non-decreasing; equal offsets are ordered by `seq`.
- `actors[].actorId` is a pseudonymous fixture-local ID. Do not store real Discord user IDs in these fixtures unless a later privacy task explicitly approves it.
- `expected` is optional and may contain labels for later projection tests. It must never hide events needed for replay.

## Event types

| Type | Required payload | Meaning |
| --- | --- | --- |
| `message_create` | `messageId`, `authorId`, `content` | A Discord message became visible in the channel. |
| `message_update` | `messageId`, `editorId`, `content` | Message content changed. `previousContent` may be kept when the source has it. |
| `message_delete` | `messageId` | Message is no longer visible. `contentAvailable: false` means replay must not require original text. |
| `typing_start` | `actorId` | Typing signal, not a message and not an obligation for NEXA to respond. |
| `reaction_add` | `messageId`, `actorId`, `emoji` | Reaction was added to a target message. |
| `reaction_remove` | `messageId`, `actorId`, `emoji` | Reaction was removed from a target message. |

Common fields on every event:

- `seq`: deterministic event order.
- `eventId`: fixture-local event ID, unique inside the file.
- `type`: one of the event types above.
- `atOffsetMs`: offset from `time.baseInstant`.

## Nickname example preservation

The source transcript in `docs/nexa/PROJECT_CONTEXT.md` is:

```text
A: 닉네임
A: 바꿔
A: ㅃㄹ
A: 헷갈리니까
B: 웅
B: 니키
C: 음 그래그래
D: 그래
C: 아니
C: 코알라였음??????
C: 누군가 했네
```

`nickname-burst.v1.yaml` preserves this without collapsing messages:

| Source line | Fixture actor | Fixture message ID | Content |
| --- | --- | --- | --- |
| `A: 닉네임` | `actor-a` | `msg-nickname-001` | `닉네임` |
| `A: 바꿔` | `actor-a` | `msg-nickname-002` | `바꿔` |
| `A: ㅃㄹ` | `actor-a` | `msg-nickname-003` | `ㅃㄹ` |
| `A: 헷갈리니까` | `actor-a` | `msg-nickname-004` | `헷갈리니까` |
| `B: 웅` | `actor-b` | `msg-nickname-005` | `웅` |
| `B: 니키` | `actor-b` | `msg-nickname-006` | `니키` |
| `C: 음 그래그래` | `actor-c` | `msg-nickname-007` | `음 그래그래` |
| `D: 그래` | `actor-d` | `msg-nickname-008` | `그래` |
| `C: 아니` | `actor-c` | `msg-nickname-009` | `아니` |
| `C: 코알라였음??????` | `actor-c` | `msg-nickname-010` | `코알라였음??????` |
| `C: 누군가 했네` | `actor-c` | `msg-nickname-011` | `누군가 했네` |

The original transcript did not include timestamps, so the fixture explicitly marks `source.originalTimeKnown: false` and uses deterministic one-second replay offsets. That preserves the source order and text while still giving later replay tests stable timing.

## Validation

Run fixture validation through the docs scope:

```bash
./scripts/nexa-verify.sh docs
```

The docs scope now includes:

```bash
python3 scripts/validate-nexa-conversation-fixtures.py
```

The validator checks the practical invariants needed before domain code exists:

- fixture files exist under `test-fixtures/nexa/conversations/`,
- actor IDs and labels are unique,
- event IDs are unique,
- sequence numbers are strictly increasing,
- offsets are non-decreasing,
- event actor and message references point to previously declared actors/messages,
- `expected.sourceTranscript`, when present, exactly matches the `message_create` replay order and content.

## Extension rule

Add fields only when a later task needs them for replay correctness. If a new Discord event type is required, update the schema, validator, this document, and at least one fixture in the same change. Do not add unvalidated free-form blobs; they make replay non-deterministic and break the fixture's purpose.
