# NEXA Burst Evaluation Dataset Format (NEXA-P04-T022)

Status: labeling format for `test-fixtures/nexa/bursts/labels/*.yaml` from `NEXA-P04-T022` onward.
This document defines the human-labeled fragment→burst boundary format and the annotator guidance used
to measure burst-segmentation precision/recall (NEXA-P04-T023).

Related files:

- [Nickname golden fixture](../../../test-fixtures/nexa/bursts/nickname-fragments.yaml)
- [Nickname boundary labels](../../../test-fixtures/nexa/bursts/labels/nickname-fragments.labels.yaml)
- [Conversation fixture format](../testing/conversation-fixtures.md)
- [Baseline experiment](../experiments/EXP-burst-baseline.md)

## Why a separate labeling format

A burst golden fixture (`bursts/*.yaml`) carries one author's intended ground-truth grouping. An evaluation
label file separates two distinct judgments so a measurement can treat them differently:

1. **Boundary truth** — between two adjacent fragments (in chronology order) the annotator marks whether a
   burst boundary exists (`split`) or not (`join`).
2. **Ambiguity** — whether a human annotator found that boundary genuinely unclear. Ambiguous boundaries are
   recorded but excluded from the strict precision/recall denominator so a model is not penalized (or rewarded)
   for a case humans could not agree on.

Keeping these separate means precision/recall is computed only over *confident* boundaries, while ambiguous
cases are reported as a separate rate (annotator agreement signal), not silently merged into the score.

## Top-level shape

```yaml
schemaVersion: nexa.burst-labels.v1
fixtureId: nickname-fragments          # must match a fixture in test-fixtures/nexa/bursts/
fixtureRef: test-fixtures/nexa/bursts/nickname-fragments.yaml
language: ko                           # for per-language error reporting (T023)
channelKind: text                      # for per-channel error reporting (T023)
annotator: synthetic-gold              # who labeled (synthetic gold for fixtures; real annotator id otherwise)
# One row per *adjacent fragment pair* in chronology order. There are N-1 boundaries for N fragments.
boundaries:
  - afterMessageId: msg-nickname-004   # boundary sits *after* this fragment, *before* the next one
    beforeMessageId: msg-nickname-005
    label: split                       # split | join
    ambiguous: false                   # true => excluded from strict precision/recall
    note: speaker change A -> B
```

Rules:

- `schemaVersion` is fixed to `nexa.burst-labels.v1`.
- `fixtureId` must match the referenced fixture's `fixtureId`; `fixtureRef` is the repo-relative path.
- `boundaries` lists every adjacent fragment pair in the fixture's chronology order, exactly `N-1` rows for
  `N` fragments. No pair may be skipped — a missing boundary is an incomplete label, not an implicit `join`.
- `label` is `split` (a burst boundary exists between the two fragments) or `join` (same burst continues).
- `ambiguous: true` marks a boundary a human found genuinely unclear. It is reported separately and excluded
  from the strict precision/recall denominator (T023).
- Do not store real Discord user IDs; fragment IDs are fixture-local (same rule as conversation fixtures).

## Annotator guidance

1. Read the fixture transcript in source order. For each adjacent pair, decide whether the second fragment
   continues the same person's contiguous utterance (`join`) or starts a new burst (`split`).
2. Mark `split` when any of these hold: the speaker changes, the reply target changes, the location
   (channel/thread) changes, or the time gap is clearly long enough that the second message reads as a new turn.
3. Mark `join` when the same speaker continues a contiguous thought with short gaps and no context switch.
4. Mark `ambiguous: true` only when you genuinely cannot decide (e.g., a same-speaker message after a medium
   gap that could read either way). Do not use `ambiguous` to avoid making a call on clear cases.
5. A speaker who is interrupted and then resumes produces two `split` boundaries around the interruption
   (one closing the interrupted burst, one opening the resumed burst) — label both.

## Validation

These label files are not yet machine-validated by a dedicated CI script (the evaluation script in T023 fails
loudly on malformed labels when run). Keep one label file per burst fixture, with `fixtureId`/`fixtureRef`
pointing at an existing fixture. The boundary count must equal `fragments - 1`.
