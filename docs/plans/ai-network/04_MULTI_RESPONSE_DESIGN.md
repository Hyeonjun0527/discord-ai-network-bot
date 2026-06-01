# AI Network 다중 응답/비교/합성 설계

> 상태: Draft  
> 작성일: 2026-06-01  
> 목적: 여러 Provider/모델을 동시에 쓰는 “AI 네트워크다운” 고품질 기능을 설계하되, 기본값은 안전·절약·Provider 보호를 우선한다.

## 1. 결론

다중 응답은 일반 질문의 기본 동작이 아니다.

1차 원칙:

- 기본 off
- 채널/관리자/사용자가 명시적으로 켠 경우에만 실행
- 초기 fan-out 최대 2개
- 고난도/깊은 답변 모드에서만 허용
- Provider opt-in 과 가용량 보호를 반드시 통과
- 민감정보/위험 주제/과부하 상황에서는 자동 비활성화
- 실패해도 단일 응답으로 graceful fallback

목표는 “무조건 많이 돌리기”가 아니라, **필요할 때만 여러 관점을 받아 더 좋은 답변을 만들기**다.

## 2. 사용자 경험

### 2.1 Discord 질문 옵션

예시:

- `/질문 질문:... 모드:깊게`
- `/질문 질문:... 비교:켜기`
- 버튼: `[빠른 답변] [깊은 답변] [비교해서 답변]`

응답 방식:

1. 즉시 접수 메시지
2. 1차 진행 상태 표시
3. Provider 후보 응답 수신
4. 최종 답변 선택 또는 합성
5. “참고: 여러 Provider 응답을 비교해 정리했습니다” 정도의 짧은 표시

Provider 이름/유저 ID 는 기본 노출하지 않는다. 운영/감사용 내부 ID 만 저장한다.

### 2.2 웹 대시보드

대시보드에서는 아래를 볼 수 있다.

- 다중 응답이 켜진 채널
- 최근 다중 응답 사용량
- 평균 지연시간
- 실패/timeout 비율
- Provider 과부하 회피 횟수
- 사용자 품질 피드백

## 3. Domain Model

### 3.1 MultiResponsePolicy

채널 AI 또는 guild 정책에 붙는 설정.

필드:

- `id`
- `guildId`
- `channelAiId`
- `enabled`
- `allowedModes` = balanced/deep/compare
- `maxFanout` = 1~3, 초기 기본 2
- `timeoutMs`
- `providerOptInRequired`
- `minProviderHealth`
- `minModelTier`
- `sensitivePromptBehavior` = disable/fallback/reject
- `dailyBudget`
- `createdAt`
- `updatedAt`

Invariant:

- `maxFanout` 은 전역 상한을 넘을 수 없다.
- guild 정책보다 channel 정책이 느슨해질 수 없다.
- 민감 질문에서는 `disable` 또는 `reject` 만 허용한다.

### 3.2 MultiResponseRun

사용자 질문 하나에 대한 다중 응답 실행 기록.

필드:

- `id`
- `guildId`
- `channelId`
- `channelAiId`
- `requestId`
- `mode`
- `requestedFanout`
- `actualFanout`
- `status` = planned/running/synthesized/fallback/failed/cancelled
- `reason`
- `startedAt`
- `completedAt`

### 3.3 CandidateAnswer

Provider/모델별 후보 답변.

필드:

- `id`
- `multiResponseRunId`
- `providerId`
- `modelName`
- `modelTier`
- `status` = pending/succeeded/timeout/failed/rejected
- `latencyMs`
- `tokenEstimate`
- `qualitySignals`
- `answerHash`
- `redactedPreview`
- `createdAt`

저장 원칙:

- 답변 원문 전체 저장은 기본 금지.
- 감사/디버그가 필요하면 짧은 redacted preview 와 hash 만 저장한다.
- 사용자가 명시적으로 품질 피드백을 남긴 경우에도 민감정보 redaction 을 먼저 수행한다.

### 3.4 SynthesisResult

최종 선택/합성 결과.

필드:

- `id`
- `multiResponseRunId`
- `strategy` = fastest/bestByHeuristic/compareThenSynthesize/manualReview
- `selectedCandidateId`
- `synthesizerProviderId`
- `qualitySummary`
- `safetySummary`
- `createdAt`

## 4. 실행 파이프라인

1. 질문 수신
2. 민감정보/위험 주제 검사
3. 채널 AI 정책 확인
4. 다중 응답 허용 여부 결정
5. Provider health/readiness 확인
6. 비용/토큰/일일 예산 확인
7. 후보 Provider 선택
8. fan-out 요청 발송
9. timeout 전까지 후보 수집
10. 후보 안전 필터링
11. 선택 또는 합성
12. Discord pseudo-streaming 정책에 맞춰 응답
13. 사용량/기여/피드백 기록

## 5. 선택/합성 전략

초기 전략:

- `fastest`: 가장 먼저 성공한 답변 사용. 다중 응답 의미가 약하므로 실험용.
- `bestByHeuristic`: 길이, 오류 문구, 근거 포함, 모델 등급, Provider health 기반 점수.
- `compareThenSynthesize`: 후보 2개를 하나의 최종 답변으로 합성. 가장 위험하므로 제한.

금지:

- 후보 답변을 그대로 모두 공개해서 채널을 도배하는 UX
- 무제한 debate/self-play
- Provider 를 judge 로 계속 재호출하는 반복 루프
- 고비용 모델에 자동 fan-out

## 6. Provider 보호

필수 제한:

- Provider별 동시 처리 상한
- Provider별 일일/시간당 요청 상한
- 다중 응답 요청은 단일 응답보다 더 높은 비용 가중치
- Provider opt-out 즉시 반영
- 고온/저전력/사용자 작업 중 상태에서는 제외
- timeout 짧게, retry 최소화
- overload 알림과 kill switch 제공

다중 응답은 “품질 기능”이지만, 잘못 만들면 다른 사람 PC 에 대한 DDoS 처럼 보일 수 있다. 따라서 Provider 보호 정책이 라우팅보다 우선한다.

## 7. RAG 와의 결합

RAG 검색은 fan-out 전 한 번 수행한다.

권장:

- 동일한 RAG context 를 후보 Provider 들에게 전달
- Provider 별로 서로 다른 지식 검색을 하지 않음
- RAG context token budget 을 먼저 제한
- 후보 합성 시 source citation 은 중복 제거

금지:

- fan-out Provider 수만큼 RAG 검색을 반복해 Qdrant/임베딩 비용을 증폭시키기
- guild/channel scope filter 없이 검색하기
- 삭제된 지식이 후보 답변에 남는 것

## 8. Dashboard projection

대시보드는 원본 실행 테이블을 직접 조인하지 않고 projection 을 본다.

초기 projection:

- `multi_response_daily_stats`
- `provider_fanout_load_summary`
- `channel_multi_response_quality_summary`
- `multi_response_failure_summary`

표시 항목:

- 활성 채널 수
- 다중 응답 요청 수
- 평균 actual fanout
- 평균/상위 지연시간
- fallback 비율
- timeout 비율
- Provider 보호로 차단된 횟수
- 좋아요/싫어요 피드백

## 9. Feature flag / kill switch

필수 flag:

- `MULTI_RESPONSE_ENABLED`
- `MULTI_RESPONSE_SYNTHESIS_ENABLED`
- `MULTI_RESPONSE_DASHBOARD_ENABLED`
- `MULTI_RESPONSE_RAG_ENABLED`
- `MULTI_RESPONSE_MAX_FANOUT`

Kill switch:

- 전체 다중 응답 중지
- 합성만 중지
- 특정 guild/channel 중지
- 특정 Provider fan-out 제외
- RAG 결합만 중지

## 10. 테스트 체크리스트

- [ ] 기본 off 상태에서는 어떤 질문도 fan-out 하지 않는다.
- [ ] channel policy 가 켜져도 guild policy 가 금지하면 실행되지 않는다.
- [ ] 민감정보 포함 질문에서는 다중 응답이 비활성화된다.
- [ ] Provider opt-out 상태인 Provider 는 후보에서 제외된다.
- [ ] Provider concurrency 초과 시 후보에서 제외된다.
- [ ] maxFanout 전역 상한을 넘기지 않는다.
- [ ] 한 후보가 timeout 되어도 성공 후보가 있으면 fallback 응답한다.
- [ ] 모든 후보가 실패해도 사용자에게 명확한 실패 메시지를 보낸다.
- [ ] 후보 원문 전체가 DB 에 저장되지 않는다.
- [ ] dashboard projection 장애가 질문 처리 장애로 번지지 않는다.
- [ ] RAG scope filter 누락 시 검색이 실패한다.
- [ ] 삭제된 KnowledgeSource 가 후보 context 에 포함되지 않는다.
- [ ] pseudo-streaming edit throttle 과 충돌하지 않는다.
- [ ] 사용자 피드백이 특정 Provider 공개 망신으로 이어지지 않는다.
- [ ] kill switch 로 즉시 기능을 끌 수 있다.

## 11. 구현 순서

1. 정책/도메인 모델만 추가
2. 라우팅 후보 산정에 dry-run 모드 추가
3. dashboard projection 에 “실행하지 않은 추천 fanout” 관측값 추가
4. 내부 dev guild 에서 실제 fan-out 최대 2 실험
5. 합성 없이 best candidate 선택만 실험
6. 안전한 질문 유형에서만 합성 실험
7. RAG 결합 실험
8. 사용자 피드백/품질 지표 연결
9. guild admin 설정 UI 제공
10. 일반 채널 opt-in 공개

## 12. 보류 결정

아직 하지 않는다:

- fan-out 3개 이상
- 자동 debate
- 모든 질문 기본 다중 응답
- Provider 간 직접 통신
- 중앙 서버가 사용자 질문 원문/후보 답변 원문을 장기 저장
- 민감 질문 다중 응답

이 기능의 성공 기준은 “똑똑해 보임”이 아니라, **품질이 좋아지면서도 Provider 가 불안해하지 않는 것**이다.
