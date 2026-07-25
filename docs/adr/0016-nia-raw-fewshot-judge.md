# ADR 0016: NIA raw-window + few-shot constitution + single judge

- 상태(Status): 부분 대체됨 (Partially superseded by [ADR 0017](./0017-nia-closed-loop-social-policy.md),
  current call budget restored by [ADR 0018](./0018-nia-two-call-llm-budget.md))
- 날짜(Date): 2026-06-30
- 최신 개정(Amended): 2026-07-25
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007 사회적 행위자 모델](./0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork · socialmemory 경계](./0010-ainetwork-socialmemory-boundary.md),
  [participation-context.md](../nexa/architecture/participation-context.md),
  [conversation-context.md](../nexa/architecture/conversation-context.md),
  [speech-context.md](../nexa/architecture/speech-context.md)
- 실행 계획: [nia-humanlike-root-improvement-v2.md](../nexa/exec-plans/nia-humanlike-root-improvement-v2.md)

## 맥락 (Context)

NIA participation의 현재 실패 모드는 개별 문장이나 감정 상태를 더 많이 분류해서 해결할 수 없다.
`외로움`, `위로 요청`, `답장 요구`, `대화 공백` 같은 상태를 enum이나 규칙으로 계속 늘리면
결정 로직이 전문가시스템으로 굳어지고, 실제 Discord 원문 흐름과 다르게 동작한다.

사용자가 요구한 핵심은 다음과 같다.

- 원문은 NIA의 현재 장면 판단에서 뇌에 가까운 1차 근거다.
- few-shot은 NIA의 판단 감각을 형성하는 헌법에 가깝다.
- 행동 종류는 작게 유지해야 하며, 감정/상황 enum을 계속 늘리면 안 된다.
- 말할지, 기다릴지, 반응만 할지는 단일 judge가 원문과 few-shot을 함께 보고 판단해야 한다.
- `SPEAK`가 선택된 뒤 실제 발화가 안 되면 durable log로 이유가 남아야 한다.

## 결정 (Decision)

NIA의 humanlike participation v2는 아래 구조를 따른다.

```text
raw conversation window
+ active admin-managed few-shot set
+ socialmemory support with source refs
+ consent and channel metadata
-> single participation judge
-> exactly one action: IGNORE / WAIT / REACT / SPEAK / CANCEL
-> speech only after SPEAK
-> actionruntime schedule/send
-> durable trace for every missing link
```

구체 결정:

1. `conversation`은 consent가 허용한 Discord 원문 메시지의 rolling raw window를 소유한다.
2. raw window는 현재 장면의 1차 근거이며, 요약이나 feature가 이를 대체할 수 없다.
3. few-shot은 DB에 버전 관리되는 운영 SSOT다. 코드 상수에만 박아두지 않는다.
4. admin은 few-shot draft, preview, eval, publish, rollback을 제공해야 한다.
5. participation judge는 raw window와 active few-shot version을 입력으로 받아 정확히 하나의 action을 반환한다.
6. 허용 action은 `IGNORE`, `WAIT`, `REACT`, `SPEAK`, `CANCEL`만이다.
7. `EMOTIONAL_SUPPORT` 같은 감정/상황 enum은 action-selection driver로 추가하지 않는다.
8. speech는 `SPEAK` 이후 문장 후보를 한 번 만들고 로컬 안전검사를 적용한다. 별도 Cloud evaluator는 호출하지
   않으며, 전송할 수 있는 후보가 없을 때만 침묵/리액션으로 안전 하강한다([ADR 0018](./0018-nia-two-call-llm-budget.md)).
9. safety/consent는 차단·하강만 할 수 있고, NIA를 말하게 만들 수 없다.
10. 모든 non-IGNORE 결정은 raw message refs, few-shot version, judge prompt version, reason을 남긴다.
11. judge는 `SPEAK`와 함께 `bubbleCount=1..4`를 정한다. 일상 대화는 한 bubble, 이야기·농담처럼 전개가 필요한
    응답은 여러 bubble로 생성하고 actionruntime이 각 bubble을 별도 Discord 메시지로 전송한다.
12. Judge 입력의 raw scene은 메시지마다 같은 JSON key를 반복하지 않는다. `rawMessageFields`가 선언한 고정폭
    row로 직렬화하되 ref, 화자 label, 이전 메시지와의 간격, reply ref, text, unavailable reason의 값과
    순서를 하나도 버리지 않는다. 입력 schema와 prompt version을 올려 이전 cache namespace와 분리한다.
13. CANARY/LIVE social message는 Judge 호출 전에 channel/thread별 turn boundary를 지난다. quiet window는
    최근 메시지 간격의 median을 2~7초로 제한해 적응시키고, 열린 boundary의 inbound typing은 최대 4초
    연장할 수 있다. 최초 메시지 기준 30초 hard deadline은 계속되는 메시지나 typing이 판단을 무한히
    미루지 못하게 한다. 모든 메시지는 즉시 raw context와 generation에 반영하며, boundary가 닫힐 때
    최신 signal 하나만 Judge로 보낸다.

## 비-목표 (Non-Goals)

- 사람처럼 보이는 모든 상황을 enum으로 모델링하지 않는다.
- few-shot을 규칙 편집기로 만들지 않는다.
- admin UI에 "문장 포함 -> action" 같은 rule-builder를 제공하지 않는다.
- production raw user text를 문서, 테스트 fixture, 로그, 채팅에 복사하지 않는다.
- 본 ADR만으로 production deploy나 Discord LIVE 발화를 승인하지 않는다.

## 근거 (Rationale)

전문가시스템식 개선은 빠르게 보이지만 실제로는 실패 사례가 하나씩 늘 때마다 조건문이 추가된다. 반면
raw-window + few-shot + single judge 구조는 판단 복잡성을 코드 분기에서 모델 입력과 평가 가능한 운영 자산으로
옮긴다. 이는 KISS/YAGNI 측면에서도 더 낫다. 행동 enum은 작게 유지하고, 복잡한 판단은 원문 근거와 few-shot
사례로 다룬다.

few-shot을 admin-managed SSOT로 두는 이유는 NIA의 말투와 판단 감각이 코드 deploy보다 자주 바뀌는 제품
자산이기 때문이다. draft/eval/publish/rollback이 없으면 prompt 상수 수정이 곧 운영 변경이 되어 회귀를
추적하기 어렵다.

## 결과 (Consequences)

장점:

- 전문가시스템식 rule explosion을 막는다.
- 사용자가 본 Discord 원문 흐름과 judge 판단 근거가 연결된다.
- no-reply-after-SPEAK 같은 운영 실패를 추적할 수 있다.
- few-shot 품질 개선이 코드 배포와 분리된다.

단점:

- few-shot admin, eval gate, raw-window storage, durable trace를 함께 만들어야 하므로 초기 구현량이 크다.
- 원문 저장/삭제/동의 경계가 더 중요해진다.
- judge prompt와 few-shot 버전 관리가 운영 절차가 된다.

## 되돌림 (Rollback)

- judge mode를 `shadow` 또는 `off`로 낮춘다.
- `NEXA_PARTICIPATION_TURN_BOUNDARY_ENABLED=false`로 메시지별 기존 Judge 진입 경로를 복구한다.
- `NEXA_PARTICIPATION_JUDGE_STRUCTURED_OUTPUT_ENABLED=false`로 prompt-only JSON 응답 계약을 복구한다.
- active few-shot version을 이전 버전으로 rollback한다.
- raw-window 저장은 retention/redaction 정책에 따라 유지하되, judge 입력 사용을 feature flag로 끈다.
- production Discord LIVE 범위는 target guild/channel 단위로 축소한다.

## 승인 게이트 (Approval Gates)

다음은 별도 인간 승인이 필요하다.

- production deploy
- production DB repair or manual consent mutation
- Discord LIVE 발화 활성화
- judge final mode를 target guild/channel 밖으로 확장
