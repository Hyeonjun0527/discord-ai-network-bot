# nexa-social-policy — participation 정책 학습 데이터셋 빌더

NEXA participation 정책(IGNORE/WAIT/REACT/SPEAK)을 **지도학습**하기 위한 데이터셋 빌더다.
P03 정규화 이벤트 시퀀스 → 마스킹된 멤버 학습 예제 → 라벨(행동/target/delay/burst shape/social act/
negative window) → 대화 세션 경계 → 길드/시간 split.

- 작업: NEXA-P10-T001 ~ T013 (`docs/nexa/nexa_500_task_graph.yaml`)
- 상위: [ADR 0007](../../docs/adr/0007-nexa-social-member-context.md),
  [data-categories.md](../../specs/product-v2/nexa/data-categories.md),
  [observable-state-policy.md](../../docs/nexa/social-state/observable-state-policy.md)

## 절대 규칙 — 개인정보·운영 데이터 (P10 게이트)

1. **운영 DB·실제 사용자 데이터 미접근.** 이 패키지는 입력 스키마(Parquet 이벤트 시퀀스)를 정의하고
   **합성/익명 fixture 로만** 동작·테스트한다. 운영 export 코드를 포함하지 않는다.
2. **원문 미포함.** 어떤 레코드도 메시지 원문(raw content)을 담지 않는다 — 신호·카운트·시각만.
   스키마(`contracts/event_sequence.schema.json`)가 `contains_raw_content: false` 로 이를 고정하고,
   export 보안 경계(`export.boundary`)가 원문 컬럼을 fail-closed 로 거부한다.
3. **실제 user id 미포함.** 작성자는 guild-scope 가명(`actor_pseudonym`)으로만 표현한다(ADR 0010,
   cross-guild 식별 금지).
4. **학습은 옵트인 전용.** export 보안 경계가 `training_eligible == true` 인 행만 통과시킨다.

## 의존성

런타임 필수 의존 0개(stdlib 전용). Parquet 직렬화는 선택(`pip install -e .[parquet]`) —
`pyarrow` 가 없으면 in-memory 레코드/JSON 으로 동작하고 Parquet 쓰기만 명시적으로 거부한다.

## 구조

```
src/nexa_policy/
  data/
    schema.py            # T003 스키마 로더·레코드·conformance 검사
    masking.py           # T004 마스킹된 멤버 학습 예제 단위
    export/boundary.py   # T002 export 보안 경계(eligibility·원문/원본 id fail-closed)
    labels/
      action.py          # T005 IGNORE/WAIT/REACT/SPEAK (UNKNOWN mask)
      target.py          # T006 message/member/thread target (복수·none)
      delay.py           # T007 delay + right-censoring
      burst.py           # T008 burst shape
      social_act.py      # T009 약지도 social act (confidence·model version)
    windows/negative.py  # T010 negative opportunity window sampling
    sessionize.py        # T011 대화 세션 경계
    split.py             # T012 guild-level split + T013 시간 holdout
```

## 검증

```bash
# 저장소 루트에서
./scripts/nexa-verify.sh ml
# 또는 직접
cd ml/social-policy
../../.venv/bin/python -m pytest -q
../../.venv/bin/ruff check src tests
../../.venv/bin/mypy src
```
