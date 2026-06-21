# EXP — stale social-memory 평가 (NEXA-P07-T024)

- 작업: NEXA-P07-T024 (`kind: experiment`, `human_gate: false`) · 상위: [socialmemory-context](../architecture/socialmemory-context.md),
  [기억 유형 taxonomy](../memory/taxonomy.md), [observable-state-policy](../social-state/observable-state-policy.md)
- 근거 fixture: [stale-memory-cases](../../../test-fixtures/nexa/memory/stale-memory-cases.yaml),
  [nickname-change](../../../test-fixtures/nexa/memory/nickname-change.yaml),
  [joke-vs-fact](../../../test-fixtures/nexa/memory/joke-vs-fact.yaml)
- 스크립트: [`scripts/evaluate-social-memory.py`](../../../scripts/evaluate-social-memory.py)

## 범위·금지

- **실제 운영 데이터·외부 API·DB 금지**: 운영 DB·게이트웨이·z.ai 에 연결하지 않는다. 합성 golden fixture
  (`test-fixtures/nexa/memory/`)만 본다. GLM 추출 어댑터(T015)도 이 실험에 등장하지 않는다 — 측정은 라벨된
  정답에 대한 retrieval 규칙의 정확도이지 운영 품질 보증이 아니다(운영 전환은 P07-T025 게이트에서 옵트인
  실샘플로 보정).
- raw chain-of-thought 미저장: fixture·측정 모두 구조화 필드(상태·구간·스코프·modality·라벨)만 본다 — 원문/
  추론 과정 텍스트가 없다(data-categories.md, observable-state-policy).

## 방법 (stale usage rate)

다섯 축에서 "이 시점·이 스코프에서 이 기억을 retrieval 이 써도 되는가"의 정답(`shouldUse`)을 라벨하고,
baseline retrieval 규칙(socialmemory 도메인 `MemoryRetrievalRanking`·`CandidatePromotionRule` 와 동일 의미:
유효=status ACTIVE·valid-at 구간 안, 같은 guild scope, 단정 ASSERTED, 비민감일 때만 사용)을 적용한다.
규칙이 쓰기로 한 기억의 라벨이 `shouldUse=false` 면 **stale usage 1건**이다.

- **change(변경/supersession)**: 현재 조회에 옛 사실(SUPERSEDED·validTo 지남)을 쓰면 stale. 과거 회상(asOf 가
  유효 구간 안)은 옛 닉네임을 써도 정상.
- **delete(삭제/invalidation)**: 출처 redaction 으로 INVALIDATED 된 기억을 쓰면 stale.
- **conflict(모순)**: 근거 부족으로 CONFLICTED 보류된 기억을 임의로 쓰면 stale.
- **joke(농담/비단정·민감)**: modality≠ASSERTED 또는 sensitive 사실을 쓰면 stale(승격 단계 차단의 retrieval 안전망).
- **scope(누출)**: 다른 guild 기억을 이 guild prompt 에 쓰면 stale(cross-guild 금지, T011).

각 실패는 case id·axis·source(상태/구간/스코프/modality)·retrieval decision(asOf/요청 스코프)으로 재현 가능하게
출력한다(acceptance T024).

실행:

```bash
python3 scripts/evaluate-social-memory.py            # 표 출력
python3 scripts/evaluate-social-memory.py --json     # 기계 판독(JSON)
```

## 측정 결과 (fixture: stale-memory-cases)

| axis | cases | stale | stale usage rate |
| --- | ---: | ---: | ---: |
| change | 3 | 0 | 0.000 |
| conflict | 1 | 0 | 0.000 |
| delete | 1 | 0 | 0.000 |
| joke | 1 | 0 | 0.000 |
| scope | 2 | 0 | 0.000 |
| **overall** | 8 | 0 | 0.000 |

## 해석 (acceptance: 각 실패가 source 와 retrieval decision 으로 재현 가능)

- **change**: 현재 조회(asOf=now)는 SUPERSEDED·validTo 지난 옛 닉네임을 쓰지 않고(stale 0), 과거 회상(asOf 가
  유효 구간 안)은 옛 닉네임을 정상 사용한다 — [nickname-change](../../../test-fixtures/nexa/memory/nickname-change.yaml)
  fixture 가 같은 변경을 단위 테스트(`NicknameTemporalMemoryTest`)로도 증명한다.
- **delete/conflict**: INVALIDATED·CONFLICTED 는 status 필터에서 점수와 무관하게 제외된다(retrieval 우회 불가).
- **joke**: 농담/부정/인용/가정·민감 추론은 [joke-vs-fact](../../../test-fixtures/nexa/memory/joke-vs-fact.yaml)
  의 승격 규칙(`JokeFactMemoryTest`)에서 애초에 사실로 저장되지 않고, retrieval 측 안전망도 modality·sensitive 를
  거른다 — 이중 차단.
- **scope**: cross-guild 기억은 guild 가명 불일치로 제외되고, 같은 guild 기억만 사용된다.
- **결론**: baseline retrieval 규칙은 다섯 축 모두에서 stale usage rate 0.000 이다 — 변경·삭제·모순·농담·scope
  누출 어디서도 써선 안 되는 기억을 끌어오지 않는다. pgvector 보조 인덱스(T023)는 이 정형 필터 **뒤에만** 의미
  유사도를 보조로 쓰므로 validity 를 우회하지 않는다(`VectorSimilarityRerankerTest`).

## 보정 절차(운영 전환 시, P07-T025 게이트)

1. 옵트인 실샘플을 같은 라벨 형식(`memory/*.yaml`: source 상태/구간/스코프/modality + retrieval decision +
   shouldUse)으로 라벨한다 — 가명 토큰만, 실제 Discord ID 비저장.
2. `test-fixtures/nexa/memory/stale-memory-cases.yaml` 에 case 를 추가하면 스크립트가 자동 포함해 축별 stale
   usage rate 를 재집계한다.
3. critical stale-memory(현재성·삭제·scope·민감 추론) 부재와 raw chain-of-thought 미저장을 P07-T025 게이트에서
   독립 감사한다.
