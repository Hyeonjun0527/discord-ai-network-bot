# 정책 feature 카탈로그 (SSOT)

- 작업: NEXA-P08-T009 · 상위 계약: [participation-context.md](../architecture/participation-context.md)
- 윤리 기준: [observable-state-policy.md](../social-state/observable-state-policy.md),
  quota 경계: [quota-boundary.md](../architecture/quota-boundary.md)
- 기계 판독 스키마: `contracts/policy/feature-vector.schema.json`
- 코드 미러(단일 출처): `central-server/.../participation/application/feature/FeatureCatalog.kt`

## 목적

participation 결정 엔진 입력 feature 의 **이름·type·범위·missing semantics·provenance·privacy class** 를
한곳에 고정한다. 이로써 **코드와 ML 데이터셋이 같은 feature ID/version** 을 쓴다(acceptance T009). feature 키는
자유 텍스트가 아니라 아래 안정 ID 다.

## 버전

- `version = 1` (`FeatureCatalog.VERSION` 과 일치해야 한다). feature 추가/의미 변경 시 함께 올린다.

## missing semantics (공통 규칙)

- 각 값은 `{ value, missing }` 다. `missing = true` 면 `value` 는 무의미(관례상 0)다.
- **content unavailable**(본문 미보존/암호화·삭제)·**표본 부족**에서 본문/관계 파생 feature 는 0 으로 뭉개지
  않고 `missing` 으로 둔다. 정책은 `missing` 을 0 과 구분해 "모름" 으로 다룬다(보수적).

## privacy class

- `OBSERVABLE`: 단일 관찰 행동에서 곧장 나오는 값(허용 — observable-state-policy).
- `AGGREGATE`: 여러 관찰의 빈도·최근성 집계(성격·감정 추론 아님). 특정 member ID 는 feature 가 아니다.

## feature 표

| feature ID | type | 범위 | provenance(빌더) | privacy | 비고 |
| --- | --- | --- | --- | --- | --- |
| `burst.fragment_count` | COUNT | ≥0 | BurstFeatures (T010) | OBSERVABLE | 메타(본문 불필요) |
| `burst.total_length` | COUNT | ≥0 | BurstFeatures (T010) | OBSERVABLE | 본문 파생 → unavailable 면 missing |
| `burst.gap_seconds` | DURATION | ≥0 | BurstFeatures (T010) | OBSERVABLE | 직전 burst 와의 gap |
| `burst.is_question` | BOOLEAN | {0,1} | BurstFeatures (T010) | OBSERVABLE | 본문 파생 → unavailable 면 missing |
| `burst.has_mention` | BOOLEAN | {0,1} | BurstFeatures (T010) | OBSERVABLE | 메타 |
| `burst.is_reply` | BOOLEAN | {0,1} | BurstFeatures (T010) | OBSERVABLE | 메타 |
| `burst.source_type` | CATEGORICAL | {0,1,2} | BurstFeatures (T010) | OBSERVABLE | human/nexa/other_bot |
| `thread.focus_present` | BOOLEAN | {0,1} | ThreadFeatures (T011) | OBSERVABLE | 활성 focus thread 존재 |
| `thread.target_entropy` | NORMALIZED | [0,1] | ThreadFeatures (T011) | OBSERVABLE | addressee 분산도(member ID 미사용) |
| `thread.active_speakers` | COUNT | ≥0 | ThreadFeatures (T011) | OBSERVABLE | 활성 화자 수 |
| `thread.topic_age_seconds` | DURATION | ≥0 | ThreadFeatures (T011) | OBSERVABLE | 화제 경과 |
| `tempo.human_burst_rate` | RATE | ≥0 | TempoFeatures (T012) | OBSERVABLE | 봇/옵트아웃 제외(P06 동일) |
| `tempo.median_gap_seconds` | DURATION | ≥0 | TempoFeatures (T012) | OBSERVABLE | human ≤1 burst 면 missing |
| `tempo.overlap_ratio` | NORMALIZED | [0,1] | TempoFeatures (T012) | OBSERVABLE | human 동시발화 비율 |
| `tempo.nexa_share` | NORMALIZED | [0,1] | TempoFeatures (T012) | OBSERVABLE | NEXA 발화 점유율 |
| `relationship.familiarity` | NORMALIZED | [0,1] | RelationshipFeatures (T013) | AGGREGATE | P06 집계, 미관측 면 missing |
| `relationship.reciprocity` | NORMALIZED | [0,1] | RelationshipFeatures (T013) | AGGREGATE | P06 집계 |
| `relationship.banter_acceptance` | NORMALIZED | [0,1] | RelationshipFeatures (T013) | AGGREGATE | 관찰 비율(성격 라벨 아님) |
| `relationship.sample_confidence` | NORMALIZED | [0,1] | RelationshipFeatures (T013) | AGGREGATE | 표본 confidence(별도 feature) |
| `memory.relevant_present` | BOOLEAN | {0,1} | MemoryFeatures (T014) | AGGREGATE | 관련 기억 존재(원문 비포함) |
| `memory.relevant_confidence` | NORMALIZED | [0,1] | MemoryFeatures (T014) | AGGREGATE | 최상 관련 기억 confidence, 미관측 면 missing |
| `memory.relevant_age_seconds` | DURATION | ≥0 | MemoryFeatures (T014) | AGGREGATE | 최신 관련 기억 age, 미관측 면 missing |
| `memory.pending_intent_active` | BOOLEAN | {0,1} | MemoryFeatures (T014) | AGGREGATE | 활성 pending intent 존재 |
| `agent.recent_burst_count` | COUNT | ≥0 | AgentStateFeatures (T015) | OBSERVABLE | NEXA 최근 burst 수(메시지 수 아님) |
| `agent.share` | NORMALIZED | [0,1] | AgentStateFeatures (T015) | OBSERVABLE | NEXA burst 점유율 |
| `agent.last_spoke_age_seconds` | DURATION | ≥0 | AgentStateFeatures (T015) | OBSERVABLE | 마지막 발화 경과, 발화 전이면 missing |
| `agent.pending_action_count` | COUNT | ≥0 | AgentStateFeatures (T015) | OBSERVABLE | 미해결 pending action 수 |

> eligibility mask(관찰/반응/발화/외부 전송 허용 여부, T016)는 feature 벡터가 아니라 **별도 차원**이다(모델 입력
> 신호가 아니라 후처리 하드 게이트) — 위 표에 싣지 않는다(EligibilityMask.kt). 모델 확률이 높아도 mask 가 막으면
> 후처리(PolicySafetyConstraint, T021)가 제거한다.

## 불변식

1. feature 키는 위 표의 안정 ID 만 쓴다(자유 텍스트 금지) — 코드·데이터셋 공유.
2. 본문/관계 파생 feature 는 관측 불가 시 `missing` 으로 보존한다(0 으로 뭉개지 않음).
3. 특정 member ID 는 feature 가 아니다 — entropy·share 같은 집계로만 변환한다(T011).
4. `version` 변경은 코드(`FeatureCatalog.VERSION`)·스키마·이 문서를 함께 올린다.
