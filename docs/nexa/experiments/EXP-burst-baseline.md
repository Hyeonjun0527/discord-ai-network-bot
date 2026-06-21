# EXP — baseline 버스트 정밀도·재현율 측정 (NEXA-P04-T023)

- 작업: NEXA-P04-T023 (`kind: experiment`, `human_gate: false`) · 상위: [conversation-context](../architecture/conversation-context.md)
- 근거: [burst 평가 데이터셋 형식](../conversation/burst-evaluation.md),
  [닉네임 golden fixture](../../../test-fixtures/nexa/bursts/nickname-fragments.yaml),
  [boundary 라벨](../../../test-fixtures/nexa/bursts/labels/nickname-fragments.labels.yaml)
- 스크립트: [`scripts/evaluate-burst-segmentation.py`](../../../scripts/evaluate-burst-segmentation.py)

## 범위·금지

- **실제 운영 데이터 금지**: 운영 DB·게이트웨이에 연결하지 않는다. 합성 golden fixture(`test-fixtures/nexa/bursts/`)
  와 사람이 라벨한 boundary(`labels/`)만 본다. 측정은 라벨된 boundary 에 대한 precision/recall 이지 운영 품질 보증이
  아니다 — 운영 전환 시 옵트인 실샘플로 보정한다(P04-T025 게이트).
- `ambiguous: true` boundary 는 strict 정밀도/재현율 분모에서 제외하고 별도 비율로 보고한다(annotator 불확실 신호).

## 방법

라벨 데이터에 대해 두 분할 규칙을 비교한다(라벨된 인접 fragment 쌍마다 boundary 예측 ↔ gold 비교):

- `fixed_gap`: **시간 gap 만** 보는 naive 기준선 — 인접 fragment 의 gap 이 임계값(기본 7s)을 넘으면 split.
- `dynamic_feature`: **화자 변경 + gap** 을 합친 동적 규칙 — 화자가 바뀌거나 gap 이 임계값을 넘으면 split
  (reply target·thread·typing 신호는 fixture 에 있으면 추가 split 신호로 합산; 닉네임 fixture 엔 없음).

지표: boundary **precision/recall/F1**, **over-split**(실제 join 인데 split 으로 예측한 헛 split = false positive),
**over-merge**(실제 split 인데 join 으로 예측해 놓친 boundary = false negative), 그리고 언어/채널별 분해.

실행:

```bash
python3 scripts/evaluate-burst-segmentation.py            # 표 출력
python3 scripts/evaluate-burst-segmentation.py --json     # 기계 판독(JSON)
python3 scripts/evaluate-burst-segmentation.py --gap-seconds 7
```

## 측정 결과 (gap=7000ms, 라벨된 boundary 10개 / fixture: nickname-fragments)

| strategy | precision | recall | F1 | over-split | over-merge | ambiguous excl |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `fixed_gap` | 1.000 | 0.000 | 0.000 | 0 | 4 | 0 |
| `dynamic_feature` | 1.000 | 1.000 | 1.000 | 0 | 0 | 0 |

언어별(ko)·채널별(text) 분해도 동일하다(단일 fixture라 한 segment).

| strategy | segment | precision | recall | F1 | over-split | over-merge |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `fixed_gap` | ko / text | 1.000 | 0.000 | 0.000 | 0 | 4 |
| `dynamic_feature` | ko / text | 1.000 | 1.000 | 1.000 | 0 | 0 |

## 해석 (acceptance: boundary F1·over-merge·over-split·언어/채널별 오류 보고)

- **fixed_gap 의 치명적 over-merge**: 닉네임 fixture 는 모든 fragment 가 1초 간격 연속이라 시간 gap(≤1s)이 임계값
  (7s)을 결코 넘지 않는다. 따라서 시간만 보는 기준선은 **화자 변경 4건(A→B, B→C, C→D, D→C)을 전부 놓쳐**
  recall=0, F1=0, over-merge=4 다. 모든 메시지를 하나의 거대 버스트로 합쳐버리는 최악의 병합이다.
- **dynamic_feature 의 완전 복원**: 화자 변경 신호를 더하면 4개 boundary 를 모두 맞혀(over-merge=0) F1=1.0,
  헛 split 도 없다(over-split=0). 짧은 연속 한국어 채팅에서 **화자 신호가 시간 gap 보다 1차 신호**임을 수치로 보인다.
- **결론**: 한국어 짧은 조각 채팅에서 순수 고정 gap 은 버스트 경계를 잡지 못한다(over-merge 지배적). 실제
  segmenter([FixedGapBurstSegmenter])가 시간 gap 에 더해 작성자 개입(T007)·context switch(T008/T009)·typing(T010)을
  1급 경계 신호로 쓰는 설계가 이 측정으로 정당화된다 — golden fixture 재생 테스트([NicknameBurstGoldenFixtureTest])
  가 그 segmenter 로 F1=1.0(5개 정답 버스트 정확 분할)을 보장한다.

## 보정 절차(운영 전환 시)

1. 옵트인 실샘플(P04-T025)을 같은 라벨 형식([burst-evaluation.md](../conversation/burst-evaluation.md))으로 라벨한다.
2. `test-fixtures/nexa/bursts/labels/` 에 추가하면 스크립트가 자동으로 포함해 언어/채널별 F1 을 재집계한다.
3. 합의 F1 기준·critical fixture 100% 충족 여부를 P04-T025 게이트에서 독립 검토한다.
