# EXP — baseline thread·addressee 장면 측정 (NEXA-P05-T023)

- 작업: NEXA-P05-T023 (`kind: experiment`, `human_gate: false`) · 상위: [conversation-context](../architecture/conversation-context.md)
- 근거: [addressee 라벨 fixture](../../../test-fixtures/nexa/scenes/addressee-labels.yaml),
  [동시 대화 scene fixture](../../../test-fixtures/nexa/scenes/concurrent-threads.yaml),
  [burst baseline 실험](EXP-burst-baseline.md)
- 스크립트: [`scripts/evaluate-conversation-scene.py`](../../../scripts/evaluate-conversation-scene.py)

## 범위·금지

- **실제 운영 데이터 금지**: 운영 DB·게이트웨이에 연결하지 않는다. 합성 golden fixture
  (`test-fixtures/nexa/scenes/`)만 본다. 측정은 라벨된 장면 정답에 대한 정확도이지 운영 품질 보증이
  아니다 — 운영 전환 시 옵트인 실샘플로 보정한다(P05-T025 게이트).
- `ambiguous: true` target 라벨(닉네임 호출·self-talk·무표식 잡담 같은 약한 신호)은 strict target
  accuracy 분모에서 제외하고 별도 비율(ambiguous excl)로 보고한다(annotator 불확실 신호).

## 방법

두 합성 fixture 에 baseline 규칙을 적용해 네 지표를 측정한다:

- **edge F1**: thread reply-edge baseline(명시된 `replyTo` 가 있으면 edge)이 fixture 의 reply edge
  (= 같은 thread 로 묶이는 강한 신호) 정답을 맞히는 precision/recall/F1. gold edge 는 "같은 thread 의
  reply" 로 정의해 baseline reply edge 가 thread 정답과 교차 일치하는지 본다.
- **target top-1 accuracy**: addressee baseline 이 각 burst 의 target(none/group/특정인)을 맞히는 비율.
  baseline 규칙: `replyTo`→specific, `groupForm`→group, 그 외(nickname/self/무표식)→none(약한 신호 미사용).
- **thread clustering score**: reply 그래프로 burst 를 union-find 병합한 클러스터가 thread 정답 분할과
  일치하는 pairwise accuracy(같은/다른 thread 쌍을 맞히는 비율).
- **correction rate**: confident 라벨인데 baseline target 예측이 어긋나 사후 교정이 필요한 비율.

상황 분해는 acceptance 요구대로 **reply / mention(nickname) / 무표식** 셋으로 나눠 target 오류를 본다.

실행:

```bash
python3 scripts/evaluate-conversation-scene.py            # 표 출력
python3 scripts/evaluate-conversation-scene.py --json     # 기계 판독(JSON)
```

## 측정 결과 (fixture: addressee-labels, concurrent-threads)

### edge F1 · thread clustering

| 지표 | 값 |
| --- | ---: |
| edge precision | 1.000 |
| edge recall | 1.000 |
| edge F1 | 1.000 (tp=3, fp=0, fn=0) |
| thread clustering accuracy | 1.000 (10/10 pairs) |

`concurrent-threads` fixture 의 두 동시 대화(thread-x: A↔C 등산, thread-y: B↔D 빌드)는 교차 reply 가
없어 reply-only baseline 이 5개 burst 를 정확히 두 클러스터로 분리한다 — 모든 쌍(10쌍)의 같은/다른
thread 판정이 정답과 일치한다.

### target top-1 accuracy / correction rate / 상황별 분해

| situation | top1 | correctionRate | confident | ambiguous excl |
| --- | ---: | ---: | ---: | ---: |
| 전체 | 1.000 | 0.000 | 2 | 3 |
| reply | 1.000 | 0.000 | 1 | 1 |
| mention | 1.000 | 0.000 | 0 | 1 |
| unmarked | 1.000 | 0.000 | 1 | 1 |

## 해석 (acceptance: 전체 평균뿐 아니라 reply/mention/무표식 상황별 오류 보고)

- **reply 상황은 confident**: Discord reply 가 있는 burst 는 target=specific 을 결정론적으로 맞힌다
  (correctionRate=0). reply 는 가장 강한 addressee 신호이므로 baseline 도 정답이다.
- **mention(nickname) 상황은 전부 ambiguous**: 닉네임 문자열 호출(`니키 이거 봤어?`)은 약한 신호라
  golden 라벨이 `ambiguous: true` 다. baseline 은 약한 신호를 쓰지 않아 none 으로 두고, strict 분모에서
  제외된다 — 정확도가 아니라 **불확실로 보고**되는 게 정답이다(P05-T006 NICKNAME_STRING 단독 확정 금지와 일치).
- **무표식(unmarked) 상황 분해**: 그룹 질문(`다들 점심 뭐 먹음?`)은 confident group 정답으로 맞히고,
  자유 잡담·self-talk 는 ambiguous none 으로 제외된다. 즉 "특정 대상 없음" 을 오류가 아니라 정상값으로
  표현한다([AddresseeDistribution.none], [ConversationFocus.idle] 의 정상-idle 의미와 일치).
- **결론**: reply 와 그룹 신호는 baseline 으로도 confident 하게 target 을 잡고(top1=1.0, correctionRate=0),
  닉네임/self/무표식 같은 약한 신호는 ambiguous 로 분리돼 strict 정확도를 오염시키지 않는다. 동시 대화의
  thread 분리도 reply 그래프만으로 완전 복원된다(clustering=1.0) — 정책 context 가 대상 thread 만 선택할 수
  있다는 P05-T010 acceptance 를 수치로 재확인한다. 결정론은 [ConversationSceneReplayTest] 가 canonical
  event stream → byte-equivalent snapshot 으로 별도 보장한다(T024).

## 보정 절차(운영 전환 시)

1. 옵트인 실샘플(P05-T025)을 같은 라벨 형식(`scenes/addressee-labels.yaml`·`scenes/concurrent-threads.yaml`)
   으로 라벨한다 — fragment/burst ID 는 fixture-local, 실제 Discord ID 비저장.
2. `test-fixtures/nexa/scenes/` 에 추가하면 스크립트가 자동으로 포함해 edge F1·clustering·상황별 target
   오류를 재집계한다(`addressee-*.yaml` = addressee 라벨, 그 외 = scene fixture).
3. 합의 target/thread 신뢰도 기준·치명적 cross-thread 오답 부재를 P05-T025 게이트에서 독립 검토한다.
