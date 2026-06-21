# Parquet 이벤트 시퀀스 schema (NEXA-P10-T003)

- 작업: NEXA-P10-T003 (`kind: implementation`, `human_gate: false`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)
- 근거: [data-categories.md](../../../specs/product-v2/nexa/data-categories.md),
  [observable-state-policy.md](../social-state/observable-state-policy.md)
- 계약(SSOT): [`ml/social-policy/contracts/event_sequence.schema.json`](../../../ml/social-policy/contracts/event_sequence.schema.json)
- 로더·검사: [`ml/social-policy/src/nexa_policy/data/schema.py`](../../../ml/social-policy/src/nexa_policy/data/schema.py)

## 목적

P03 정규화 이벤트를 participation 정책 **지도학습 입력**으로 쓰기 위한 columnar(Parquet) schema 를 정의한다.
한 row = 한 정규화 이벤트. event time·burst·scene·actor pseudonym·features·masks 를 컬럼으로 고정한다.

**운영 데이터 미접근**: 이 schema 는 입력 형식의 정의일 뿐이며, 빌더는 합성/익명 fixture 로만 동작한다.
운영 DB dump 는 export 보안 경계([T002](../../../ml/social-policy/src/nexa_policy/data/export/boundary.py))가 차단한다.

## 컬럼 (required)

| 컬럼 | 타입 | 의미 | 개인정보 |
| --- | --- | --- | --- |
| `schema_version` | int(const 1) | 스키마 버전 고정 | — |
| `guild_pseudonym` | string | 길드 guild-scope 가명(실제 snowflake 금지) | Medium(가명) |
| `channel_pseudonym` | string | 채널 가명 | Medium(가명) |
| `thread_pseudonym` | string?null | 스레드 가명 | Medium(가명) |
| `event_id` | string | 이벤트 가명 식별자(원본 message id 금지) | Medium(가명) |
| `event_time_ms` | int | 이벤트 시각(epoch millis) | Low |
| `burst_id` | string | P04 버스트 묶음 가명 | Low |
| `scene_id` | string | 장면 가명 | Low |
| `actor_pseudonym` | string | 작성자 guild-scope 가명(**실제 user id 절대 금지**) | Medium(가명) |
| `event_kind` | enum | message/reaction/mention/reply/join/leave/typing | Low |
| `features` | object | 관찰 가능한 행동 신호만(길이 버킷·질문 여부·멘션·reply·reaction code·gap) | Low/Medium |
| `masks` | object | `is_observable`·`consent_opt_in` | Low |
| `training_eligible` | bool | export 보안 경계 통과 표시 | Low |

## 원문 포함 여부 — 명시 (acceptance)

- 스키마 최상위 `contains_raw_content: false` 로 **원문(raw content) 미포함**을 명시한다.
- `additionalProperties: false` 로 알 수 없는 컬럼(원문/파생 텍스트가 몰래 끼는 것)을 거부한다.
- `features` 도 `additionalProperties: false` — 허용 신호([observable-state-policy](../social-state/observable-state-policy.md)
  의 허용 목록)만. 기분·성격·정치·종교 등 민감 추론 feature 는 정의하지 않는다.
- 로더([schema.py](../../../ml/social-policy/src/nexa_policy/data/schema.py))의 `conform()` 이 `content`/`raw`/
  `user_id`/`username`/`snowflake` 등 금지 컬럼명을 fail-closed 로 거부한다.

## schema version 고정 (acceptance)

`schema_version` 은 const 1 이며, row 의 version 이 불일치하면 `conform()` 이 거부한다. 스키마 진화는 version
증가 + 마이그레이션을 요구한다.

## Parquet 직렬화 (선택)

`pyarrow` 가 있으면 `write_parquet()` 이 conformance 통과 후 Parquet 로 쓴다(features/masks 는 JSON 컬럼).
`pyarrow` 가 없으면 in-memory 레코드/JSON 으로 동작하고 Parquet 쓰기만 명시적으로 거부한다(조용한 우회 금지).
