# ADR 0018: NIA two-call LLM generation budget

- 상태(Status): 승인됨 (Accepted)
- 날짜(Date): 2026-07-25
- 결정자(Deciders): Hyeonjun0527
- 대체 범위: [ADR 0017](./0017-nia-closed-loop-social-policy.md)의 Cloud 완전 행동 평가와
  Judge/Speech 재호출 결정
- 관련: [ADR 0016](./0016-nia-raw-fewshot-judge.md),
  [speech context](../nexa/architecture/speech-context.md),
  [운영 metric](../nexa/operations/metrics.md)

## 맥락 (Context)

정상 발화에서 Judge, Speech, action evaluator를 각각 호출하고 형식 실패 때 repair/retry까지 허용하면 같은 턴의
유료 모델 요청 수가 입력과 무관하게 늘어난다. action evaluator는 비밀 노출, 동의, stale, Discord 실행 permit 같은
하드 안전검사를 대신하지 않으며, 이미 Judge가 결정한 `SPEAK`를 다시 모델로 평가해 비용과 실패 지점을 추가했다.

## 결정 (Decision)

1. `FINAL` 참여 판단은 Judge를 최대 한 번 호출한다. provider 실패나 출력 검증 실패 때 repair 호출을 만들지 않고
   기존의 제한된 로컬 하강 정책을 적용한다.
2. Judge가 `SPEAK`를 고른 경우에만 Speech를 최대 한 번 호출한다. 여러 문장 후보는 이 한 요청의 단일 응답에서
   함께 만들며 timeout, malformed 응답, provider 실패를 재호출하지 않는다.
3. 별도 Cloud action evaluator는 사용하지 않는다. 로컬 critic을 통과한 후보 중 모델이 반환한 uncertainty가 가장
   낮은 후보를 선택하고, 동률이면 생성 순서를 유지한다.
4. 생존 후보가 없으면 새 모델을 호출하지 않는다. `OPTIONAL`은 허용된 리액션으로, `REQUIRED`는 침묵으로 안전
   하강한다.
5. consent, high-risk, 비밀 노출, 버블 형식, 요청 행위 미수행, scene freshness, 채널 mode, 실행 permit 검사는
   모두 유지한다. 이 검사는 발화를 차단할 수만 있고 추가 모델 호출을 만들지 않는다.
6. 따라서 한 `FINAL` 턴의 생성 LLM 호출 상한은 비발화 1회(Judge), 발화 2회(Judge + Speech)다. Conversation
   RAG query embedding과 SearXNG 검색은 생성 LLM 호출이 아닌 별도 검색 경로이며 metric에서 따로 관측한다.
7. 이 상한은 feature flag가 아니라 코드 구조로 강제한다. 이전 evaluator 동작이 꼭 필요하면 이전 이미지 전체로
   롤백하고 비용·품질 근거를 다시 검토한다.

## 결과 (Consequences)

- 정상 발화가 추가 evaluator 비용을 만들지 않고, malformed/timeout도 같은 장면을 재과금하지 않는다.
- 후보 선택은 단순하고 재현 가능하며, 안전검사의 책임이 로컬 코드에 명확히 남는다.
- Speech 응답 실패 때 자동 복구 대신 침묵할 수 있다. 중복 과금보다 안전한 실패를 선택한 결과다.
- 모델 기반 후보 간 결과 예측은 사라진다. 품질은 실제 발화 결과와 사람 평가로 관측하고, 문제가 있어도 숨은
  모델 호출을 다시 추가하지 않는 방향으로 개선한다.
