# EXP — speech 발화 품질·비용 shadow 평가 (NEXA-P14-T024)

- 작업: NEXA-P14-T024 (`kind: experiment`, `human_gate: true`) · 상위: [speech-context](../architecture/speech-context.md)
- 경계: [ADR 0006 central cloud LLM backend](../../adr/0006-central-cloud-llm-backend.md),
  [module-dag](../architecture/module-dag.md)
- 평가기: `central-server/.../speech/application/generation/SpeechShadowEvaluator.kt`
  (테스트: `.../speech/generation/SpeechShadowEvaluatorTest.kt`)
- 비평가: `.../speech/domain/service/critic/{AssistantStyleDetector,RepetitionDetector,MemoryConsistencyCritic,TargetAndSceneCritic}.kt`
- 호출 경계: `.../speech/application/generation/SpeechGenerationGate.kt`
  (테스트: `.../speech/generation/SpeechInvocationBoundaryTest.kt`)
- 사람다움 rubric eval(보강): [`scripts/nexa-human-likeness-eval.py`](../../../scripts/nexa-human-likeness-eval.py)

## 범위·금지

- **실제 Discord 전송 금지**: shadow 평가기는 전송 포트(`DiscordSendPort`)·actionruntime executor 를 **참조하지
  않는다**. 후보와 비평 결과만 보고 자동 metric 을 집계한다(전송 부수효과 0). OBSERVE_ONLY hard block(OutboundGuard)
  은 그대로 유지된다.
- **운영 미적용**: 이 작업은 코드 + 테스트까지다. 운영 배포·실제 GLM 호출은 P14-T025 게이트가 별도로 승인한다.
  human_gate=true 지만 사용자 일괄 위임으로 코드·테스트만 진행하고, 운영 임계 적용은 게이트로 미룬다.
- **가명화 장면만**: 평가 샘플은 minimizer(T005)가 부여한 가명 키("user_*"·"thread_*") 장면이다. 운영 DB·원문
  snowflake·실명을 넣지 않는다.

## 측정 지표 (자동 metric)

shadow 평가기는 가명화 장면 후보 집합에 대해 다음을 집계한다(전송 없이):

| 지표 | 정의 | 겨냥하는 약점(human-likeness gate) |
| --- | --- | --- |
| `assistantStyleRate` | AssistantStyleDetector(T017)에 탈락한 후보 비율 | AI 도우미 말투 |
| `repetitionRate` | RepetitionDetector(T018)에 탈락한 후보 비율 | 같은 유행어·반응 반복 |
| `contradictionRate` | MemoryConsistencyCritic(T019)에 탈락한 후보 비율 | 기억과 모순 |
| `targetSceneMismatchRate` | TargetAndSceneCritic(T020)에 탈락한 후보 비율 | cross-thread 끌어오기 |
| `rejectionRate` | 비평가 1개 이상에 탈락한 후보 비율 | 전체 통과율 |
| `avgLatencyMillis` | 샘플당 후보 생성 지연 평균 | 타이밍 |
| `avgCostTokens` | 샘플당 prompt+completion token 평균 | 비용 |
| `avgHumanScore` | blind human review 점수 평균(0~5, 선택) | 종합 자연스러움 |

## 방법

1. participation 결정이 SPEAK 인 가명화 장면에서만 후보를 모은다(`SpeechGenerationGate` 가 IGNORE/WAIT/REACT·
   stale·consent revoke 를 호출 0회로 막는다 — T023). GLM 은 mock CloudLlm 포트로 대체해 실제 호출하지 않는다.
2. 각 후보를 4개 비평가로 평가해 사유별 탈락을 집계한다(`SpeechShadowEvaluator.evaluate`).
3. 자동 metric 과 함께, 같은 후보에 대한 사람 blind review 점수(`ShadowSample.humanScore`)를 주입해 평균낸다.
4. 운영 전환 게이트(P14-T025)는 `assistantStyleRate`·`targetSceneMismatchRate` 가 임계 이하일 때만 end-to-end
   canary 후보로 본다.

## 호출 경계·비용 안전 (T023)

- IGNORE/WAIT/REACT, stale SPEAK, consent revoke 에서 generation 포트 호출 **0회** — quota/requestlog 에 generation
  요청 자체가 생기지 않는다. `SpeechInvocationBoundaryTest` 가 호출 카운팅 fake 포트로 입증한다.
- 빈 생성 결과·전부 탈락은 침묵(fallback T016)으로 하강한다 — canned 도우미 템플릿을 만들지 않는다.

## 결과 기록 (운영 전 placeholder)

실제 GLM·운영 데이터는 P14-T025 게이트에서 옵트인 실샘플로 채운다. 현재는 합성 가명 장면 단위 테스트로 평가기의
집계 정확성(사유별 rate·latency·cost·human 평균)과 호출 경계 0회만 검증한다. 자동 metric 표는 게이트 통과 시 실측치로
대체한다.

| 지표 | baseline(합성) | 운영 임계(P14-T025 결정) |
| --- | ---: | --- |
| `assistantStyleRate` | TBD | TBD |
| `contradictionRate` | TBD | TBD |
| `targetSceneMismatchRate` | TBD | TBD |
| `avgHumanScore` | TBD | TBD |
