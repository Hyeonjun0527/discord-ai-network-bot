# 니아 사람같은 participation 전환 TODO 68

- 상태: DRAFT
- 작성일: 2026-06-29
- 범위: `니아수다`에서 니아가 사람처럼 `말할지 / 기다릴지 / 반응만 할지 / 침묵할지`를 판단하는 전체 전환 작업

## 목적

현재 구현은 participation baseline, hard rule, feature 일부, shadow/live gate, speech pipeline 일부가 흩어져 있다. 하지만 실제
목표는 전문가시스템처럼 상황 enum을 늘리는 것이 아니라, 단일 judge가 대화 원문과 구조화 신호를 함께 보고 사회적으로 자연스러운
행동 하나를 고르는 것이다.

이 문서는 그 상태로 가기 위해 빠지면 안 되는 TODO를 68개로 고정한다. `EMOTIONAL_SUPPORT` 같은 세부 상태 enum을 늘리지
않고, 원문 context window와 연속 축 기반 판단으로 처리한다.

## 핵심 결정

- 단일 judge만 사용한다. 3표결, ensemble judge, 다수결은 사용하지 않는다.
- 원문은 feature가 아니라 judge의 evidence/source transcript다.
- 원문은 일반 event store, request log, decision log에 저장하지 않는다.
- 원문은 별도 권한, 암호화, 보존 한도, 삭제 전파를 가진 RawContextStore에서만 관리한다.
- judge는 `SPEAK / WAIT / REACT / IGNORE / CANCEL_PENDING` 중 하나를 고른다.
- 위로, 농담, 장난, 질문, 정정 같은 세부 의도는 enum 폭증으로 만들지 않고 tone/intent 축과 scene direction으로 표현한다.

## A. 방향과 SSOT 정리

- [ ] 1. `니아수다`의 목표를 "자동응답 채널"이 아니라 "사람처럼 참여하는 멤버 채널"로 문서화한다.
- [ ] 2. `ai채팅`의 무조건 답변 경로와 `니아수다`의 자율 참여 경로를 SSOT에서 명시적으로 분리한다.
- [ ] 3. `말할지 / 기다릴지 / 반응만 할지 / 침묵할지`를 participation의 유일한 최종 행동 선택으로 고정한다.
- [ ] 4. speech는 말 내용만 만들고, 말할지 여부를 다시 판단하지 못하도록 계약을 점검한다.
- [ ] 5. actionruntime은 예약, 취소, 전송만 맡고 사회적 판단을 포함하지 않도록 경계를 점검한다.
- [ ] 6. 기존 baseline, heuristic, demo, dormant wiring이 실제 product path와 섞이지 않도록 이름과 문서를 정리한다.

## B. 현재 막힘 지점 제거

- [ ] 7. `니아수다` 메시지가 실제 participation path에 들어오는지 Discord inbound 경로를 끝까지 추적한다.
- [ ] 8. active flag, lane, shadow mode, channel mode 중 어느 gate에서 멈추는지 outcome별로 기록한다.
- [ ] 9. `ConsentPolicyPort`가 fail-closed deny만 반환하는 환경에서는 왜 `ConsentBlocked`가 나는지 운영자가 볼 수 있게 한다.
- [ ] 10. `니아수다` 채널 생성 시 autoRespond가 아니라 participation live/canary/shadow 대상이 되는지 검증한다.
- [ ] 11. bot/self/webhook/system 메시지가 사람 메시지와 섞여 judge에 들어가지 않도록 source type 필터를 확정한다.
- [ ] 12. blank, dot-prefix, command-like 메시지 처리 정책을 autoRespond와 participation에서 각각 분리한다.
- [ ] 13. early return 결과인 `Inactive`, `ConsentBlocked`, `RuleSilent`, `RuleWait`, `AttentionDeferred`, `NotSpeaking`을 모두 추적 가능하게 한다.
- [ ] 14. 사용자가 "왜 답장 안 해?"라고 물었을 때 운영자가 원문 없이도 어느 gate에서 막혔는지 볼 수 있는 디버그 view를 만든다.

## C. 원문 뇌 컨텍스트 저장소

- [x] 15. `RawContextStore` 포트를 새로 정의하고 일반 event store, request log, decision log와 분리한다.
- [x] 16. RawContextStore scope를 `guild + channel + thread` 기준으로 정의하고, 필요 시 author pseudonym view를 추가한다.
- [x] 17. 원문 저장 항목에 `messageId`, `authorPseudonym`, `occurredAt`, `replyTo`, `sourceType`, `content`, `contentLength`를 포함한다.
- [x] 18. 원문은 at-rest 암호화로 저장하고, 평문 DB column, 평문 로그, exception message 유출을 금지한다.
- [x] 19. scope별 최대 보존량을 설정한다. 기본값은 수십만자 범위로 두고 config로 조정 가능하게 한다.
- [x] 20. append 후 보존량을 초과하면 가장 오래된 원문부터 FIFO로 제거하는 bounded ring buffer를 구현한다.
- [ ] 21. 오래된 원문 제거 시 순서와 존재 증거가 필요한 경우 raw 없이 tombstone metadata만 남기는 정책을 정한다.
- [ ] 22. Discord message delete/edit, 사용자 동의 철회, opt-out, guild/channel disable 시 raw context redaction을 전파한다.
- [ ] 23. 원문이 삭제된 메시지는 context window에서 즉시 빠지고, 파생 memory와 dataset export에서도 무효화되게 한다.
- [x] 24. `ContextWindowBuilder`를 만들어 judge 입력용 최근 원문을 token/char 예산 안에서 자른다.
- [x] 25. judge prompt에서 원문은 quoted scene data로만 넣고, 원문 속 지시가 system/developer/policy를 덮어쓰지 못하게 한다.
- [x] 26. 원문이 unavailable인 경우 빈 문자열로 처리하지 않고 `content_unavailable` 근거를 judge 입력에 명시한다.

## D. 대화 흐름과 타이머 런타임

- [ ] 27. `discord-assistant-core`의 event loop, idle polling, gated pipeline 개념 중 3표결을 제외한 부분만 이식 범위로 다시 정한다.
- [ ] 28. 현재 per-message synchronous bridge만으로는 "대화 공백 후 끼어들기"가 안 되는 지점을 코드로 확정한다.
- [ ] 29. message event 외에 idle timer, delayed reevaluation, pending action wake-up을 participation runtime에 추가한다.
- [ ] 30. 사용자가 반복적으로 부르는 상황을 "반복 요구 regex"가 아니라 thread state와 direct address pressure로 모델링한다.
- [ ] 31. 사람들끼리 대화 중이면 끼어들지 않고, 니아를 향한 발화나 공백이면 개입할 수 있도록 turn-taking state를 만든다.
- [ ] 32. reply chain, mention, direct address, nickname call, previous ignored request를 하나의 scene snapshot으로 묶는다.
- [ ] 33. pending action이 실행되기 직전 최신 대화로 재평가해서 이미 늦었거나 부적절하면 취소한다.
- [ ] 34. rate limit과 anti-spam은 hard block이 아니라 judge 입력과 actionruntime guard 양쪽에서 일관되게 적용한다.

## E. feature와 scene snapshot

- [ ] 35. 현재 bridge가 mention과 recent burst count만 넣는 feature vector를 전수 조사한다.
- [ ] 36. `FeatureCatalog`의 burst, thread, tempo, relationship, memory, agent feature 중 실제 judge에 줄 항목을 선별한다.
- [ ] 37. 원문에서 파생한 `is_question`, direct address, reply target, emotional intensity, call pressure를 scene snapshot에 추가한다.
- [ ] 38. feature missing과 feature zero를 엄격히 구분한다.
- [ ] 39. relationship/socialmemory confidence가 낮으면 judge가 과하게 친한 척하지 않도록 confidence를 함께 전달한다.
- [ ] 40. 니아의 최근 발화 비율, 마지막 발화 시각, 연속 발화 수를 scene snapshot에 포함한다.
- [ ] 41. 사람이 답하려는 흐름인지, 아무도 안 답하는 공백인지, 이미 해결된 대화인지 구분하는 conversation state를 만든다.
- [ ] 42. snapshot 생성 결과를 deterministic fixture로 고정해서 같은 원문이면 같은 judge input이 만들어지게 한다.

## F. 단일 judge 정책

- [ ] 43. 3표결, ensemble judge, majority vote 호출 경로를 production participation path에서 제거한다.
- [ ] 44. 단일 judge 입력 schema를 정의한다. 입력은 raw context window, scene snapshot, feature vector, memory refs, constraints로 나눈다.
- [ ] 45. 단일 judge 출력 schema를 정의한다. 출력은 action, confidence, delay, reaction candidate, speech intent, tone axes, reason code로 나눈다.
- [ ] 46. judge가 `SPEAK`를 고를 때도 speech text를 직접 만들지 않게 하고, speech intent와 scene direction만 넘긴다.
- [ ] 47. `WAIT`는 무기한 침묵이 아니라 wake-up 조건과 만료 시간을 가진 pending decision으로 만든다.
- [ ] 48. `REACT`는 emoji/reaction-only 후보와 실패 시 fallback을 포함하되 텍스트 발화와 분리한다.
- [ ] 49. `IGNORE`는 로그 가능한 판단이어야 하며, gate early return과 구분한다.
- [ ] 50. judge confidence가 낮으면 기본 행동은 `WAIT` 또는 `IGNORE`로 두고, 억지 SPEAK를 금지한다.
- [ ] 51. "위로", "농담", "장난", "사과", "정정" 같은 세부 상황은 enum 추가가 아니라 tone axes와 natural language intent로 표현한다.

## G. 발화 생성과 톤

- [ ] 52. participation의 speech intent를 `SpeechScenePacket`에 반영해서 항상 `ACKNOWLEDGE`로 고정되는 현재 경로를 제거한다.
- [ ] 53. speech prompt에 raw context window 중 필요한 부분과 judge의 intent를 함께 넣는다.
- [ ] 54. speech는 짧게 말할 상황, 반응만 할 상황, 말하지 않을 상황을 다시 뒤집지 않도록 입력 계약을 점검한다.
- [ ] 55. 장문 위로, 설명충식 답변, 과한 친밀감, 사용자 대신 감정 단정하기를 critic으로 막는다.
- [ ] 56. 대화 상황에 따라 한 문장, 짧은 두 문장, reaction-only, silence를 자연스럽게 선택하는 burst profile을 적용한다.
- [ ] 57. "야 이럴땐 위로해줘야지 / 위로하라고" 같은 직접 요구 fixture에서 짧고 자연스러운 개입이 생성되는지 고정한다.

## H. 관측성, 평가, 운영

- [ ] 58. 모든 participation decision에 correlation id를 부여하고 requestlog와 decision log를 원문 없이 연결한다.
- [ ] 59. raw context ref는 decision log에 직접 원문으로 남기지 않고, 접근 권한이 필요한 evidence reference로만 남긴다.
- [ ] 60. "왜 안 말했는지"를 action, gate, judge confidence, missing input, last wake-up reason으로 설명하는 admin/debug view를 만든다.
- [ ] 61. `missed_intervention` eval set을 만든다. 위로 요구, 반복 호출, 대화 공백, 질문 무시 상황을 포함한다.
- [ ] 62. `false_interruption` eval set을 만든다. 사람끼리 대화 중 끼어들면 안 되는 상황을 포함한다.
- [x] 63. raw context retention 테스트를 만든다. 수십만자 초과 시 가장 오래된 원문이 삭제되는지 검증한다.
- [ ] 64. privacy 테스트를 만든다. 원문이 request log, decision log, exception, metric label, dataset export에 새지 않는지 검증한다.
- [ ] 65. consent 테스트를 만든다. 동의 철회 직후 raw context read, judge call, speech generation, pending action이 모두 멈추는지 검증한다.
- [ ] 66. prompt injection 테스트를 만든다. 원문 안의 "이전 지시 무시"가 judge/system policy를 덮어쓰지 못해야 한다.
- [ ] 67. shadow/canary 운영 기준을 만든다. missed intervention과 false interruption 비율이 기준 안에 들어와야 LIVE를 논의한다.
- [ ] 68. 최종 검증 스크립트를 추가한다. 문서 링크, task graph, unit/integration/eval fixture, no-raw-log scan을 한 번에 실행한다.

## 완료 기준

- `니아수다`에서 단일 judge가 raw context window를 포함한 입력으로 action을 고른다.
- 원문은 bounded, encrypted, consent-gated RawContextStore에서만 관리된다.
- 수십만자 한도를 넘으면 가장 오래된 원문부터 제거된다.
- 일반 로그와 dataset export에는 원문이 남지 않는다.
- 사용자가 직접 반응을 요구하고 대화 공백이 생긴 fixture에서 `SPEAK` 또는 적절한 `REACT`가 선택된다.
- 사람끼리 대화 중인 fixture에서는 불필요하게 끼어들지 않는다.
- 3표결/ensemble이 production path에 남아 있지 않다.
