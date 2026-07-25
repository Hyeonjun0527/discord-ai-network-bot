# NEXA 운영 metric taxonomy (P18-T001)

NEXA 관측 metric 의 SSOT. 아래 metric 중 **runtime 연결 완료**로 표시한 항목만 실제 요청 경로에서 증가한다. 단순히
Micrometer 컴포넌트가 존재하거나 0 gauge가 노출되는 것을 "수집 중"으로 간주하지 않는다. 구현은
`central-server/.../global/observability/**`(집계 metric)·도메인 인접 metric 컴포넌트가 채운다.

## 현재 runtime 연결 상태

- **연결 완료(비용 검증 기준)**: Discord ingress/admission, NIA turn outcome/supersession, OpenAI 목적별 요청·payload·token·cache policy.
- **정의만 있고 runtime 호출자 없음**: burst, policy/action, share, latency, legacy GLM cost, FIR/MIR, memory health, tracer. 이 항목은
  운영 대시보드에 0이 보여도 수집 증거가 아니며, 비용 해결 완료 판정에 사용하지 않는다.
- 모든 Micrometer 값은 프로세스 재시작 시 초기화된다. 운영 Prometheus scrape와 retention이 실제로 올라온 뒤에만 기간 비교가 가능하다.

## label 카디널리티 규칙 (불변식)

- **금지 label**: 원본 user ID·channel ID·guild snowflake 원문·message ID·메시지/프롬프트/응답 **원문**. 즉 고카디널리티 식별자와 PII 는 metric label 에 **절대** 쓰지 않는다(P09-T018 일관, logging-boundary.md).
- **허용 label**: 고정 enum 집합(action kind, mode, purpose, reason, window 등 저카디널리티)·**길드 가명(pseudonym)**(비용/점유율 운영 분해용, 원문 snowflake 아님)·모델 버전 라벨.
- 개별 식별이 필요한 값(fragment 수·gap·latency 등)은 **label 없는 분포(summary)/카운터**로 집계한다 — label 폭발과 원문 유출을 동시에 막는다.
- metric 은 **집계만** 노출한다. 개별 사용자 심리·개별 memory object·개별 예측 레코드는 metric 으로 내보내지 않는다.

## metric 군

### 1. ingestion (수집)
- `nexa_event_ingested_total{source}` — 관찰 event 유입 수(source=message/edit/delete 등 저카디널리티).
- `nexa_discord_event_admission_total{event,outcome}` — raw-context 우선 작업과 평가 작업의 큐 admission 결과. `rejected`가
  0인지 확인해 burst 원문/평가 유실을 찾는다.
- `nexa_turn_outcome_total{outcome,stage,addressing}` — NIA turn 종결과 supersession 단계. `addressing`은
  `unclassified|ambient|explicit|continuation|reevaluation` 고정값이라, 비용 절감용 stale 생략이 직접 호출을 삼키는지
  구분한다. `unclassified`는 signal 조립 전 실패에만 사용한다.
  `stage=before_judge`는 provider 호출 전 생략한 stale 턴이고, `stage=after_judge`는 호출 도중 새 메시지가 온 경합 비용이다.

### 2. burst (버스트 분할) — `BurstSegmentationMetrics`(P04-T024, 기존)
- `nexa_burst_finalized_total{reason}` / `nexa_burst_fragment_count`(summary) / `nexa_burst_gap_millis`(summary) / `nexa_burst_corrected_total`.

### 3. scene (장면)
- `nexa_scene_opened_total` / `nexa_scene_closed_total{reason}` — 장면 생애(원문 없이).

### 4. policy / action (정책 결정 분포) — `PolicyActionMetrics`(P18-T004)
- `nexa_policy_action_total{kind, stage}` — stage=`raw`(제약 전)·`final`(제약 후), kind=ignore/wait/react/speak/cancel_pending.
- `nexa_policy_constraint_overridden_total` — 하드 제약이 raw action 을 바꾼 횟수. constraint override 비율 = 이 카운터 / `nexa_policy_action_total{stage=raw}` 합.

### 5. share (NEXA 채팅 점유율) — `NexaChatShareMetrics`(P18-T005)
- `nexa_chat_share_burst_ratio{window}` / `nexa_chat_share_token_ratio{window}`(gauge, window=`5m`/`1h`) — 최근 창에서 human burst 대비 NEXA burst·token/char share. **조각 수가 아닌 burst 수**와 token/char share 를 함께 본다.

### 6. latency (정책·생성 지연) — `NexaLatencyMetrics`(P18-T006)
- `nexa_latency_policy_inference_millis` / `nexa_latency_schedule_wait_millis` / `nexa_latency_generation_millis` / `nexa_latency_first_bubble_millis` / `nexa_latency_last_bubble_millis`(summary).
- `nexa_latency_cancelled_millis` — **취소된 action 도** 취소까지 걸린 시간을 기록한다(별도 분포).

### 7. cost (GLM 비용·token) — `GlmCostMetrics`(P18-T007)
- `nexa_glm_tokens_total{guild, model, purpose, direction}` — direction=prompt/completion. guild=가명, **개별 user ID label 금지**.
- `nexa_glm_cost_micros_total{guild, model, purpose}` — 추정 비용(micro-USD 정수 누적, 부동소수 누적 오차 회피).

### 7-1. OpenAI 직접 호출 비용·token — `OpenAiTokenUsageMetrics`
- `central_openai_requests_total{model,purpose}` — 실제 provider HTTP 요청 횟수. timeout·실패도 호출 직전에 증가하므로 숨은 재시도를 포함한다.
- `central_openai_request_payload_chars{model,purpose}`(summary) — 직렬화된 요청 payload 문자 수. 원문을 저장하지 않고 목적별 평균 입력 크기와 비정상 팽창을 찾는다.
- `central_openai_cache_policy_requests_total{model,purpose,policy}` — 실제 요청에 적용된 정책. policy는 `disabled` 또는 `explicit_prefix`다.
- `central_openai_tokens_total{model,purpose,category}` — API usage 기반 누적 token. category는 `input_total`, `uncached_input`, `cached_input`, `cache_write`, `output`이다. `input_total`은 provider 원본 합계이고, 가격 성격별 비교에는 겹치지 않는 나머지인 `uncached_input`을 사용한다.
- purpose는 `nia_judge`, `nia_judge_repair`, `nia_speech`, `nia_action_evaluator`, `nia_rag_embedding` 등 고정 enum이다. 원문·길드·채널·사용자 식별자는 넣지 않는다.
- 이 값은 **central-server 프로세스 안의 누적 카운터**다. 운영 compose의 Prometheus가 bearer token으로 15초마다 scrape하고 named volume에 365일 동안 보존한다. 365일 전에 삭제될 수 있는 용량 상한은 두지 않으므로 운영 호스트의 디스크 사용량을 함께 감시한다. 배포 검증은 collector ready뿐 아니라 `central-server` target의 `health=up`까지 확인한다.
- 수집 경계는 central-server의 `/responses`·`/embeddings` HTTP 어댑터다. 별도 프로세스인
  `scripts/nexa-human-likeness-eval.py`와 `rag/build_index.py --with-vector`의 수동 유료 호출은 이 metric에 포함되지 않는다.
  전자는 `--confirm-paid-openai`, 후자는 `--with-vector`를 명시해야만 실행된다.

정상적인 `FINAL` 턴의 요청 형태는 다음과 같다.

- 연속 social message는 turn boundary가 닫힐 때 최신 signal 하나로 합쳐진다. 메시지마다 raw context와
  generation은 즉시 갱신되므로 중간 원문을 버리는 것이 아니라 유료 Judge 시작 횟수만 줄인다.
- 비발화: `nia_judge` 1회. strict Structured Outputs envelope가 `refusal`/`incomplete`이면 자동 재호출
  없이 fail-closed한다. completed 응답의 동적 의미 검증이 실패한 경우에만 `nia_judge_repair`가 최대
  1회 추가될 수 있다.
- 발화: `nia_judge` 1회 + `nia_speech` 1회 + `nia_action_evaluator` 1회가 기본이다. 세 요청은 같은 답을 세 번
  생성하는 것이 아니라 각각 참여 여부 판단, 서로 다른 실제 문구 후보 생성, SEND/REACT/IGNORE 최종 선택을 맡는다.
- evaluator는 생존 행동 후보가 하나뿐이면 호출하지 않는다. 완전히 같은 speech 후보도 먼저 하나로 줄이므로 REQUIRED 응답의
  실질 후보가 하나면 `nia_action_evaluator`는 0회다.
- 운영 fast path가 켜져 있으면 local critics를 통과한 `REQUIRED` 응답 중 Judge confidence가 임계값 이상인
  경우에도 evaluator를 호출하지 않고 최소 uncertainty SEND 후보를 선택한다. OPTIONAL과 낮은 confidence는
  계속 evaluator를 호출하므로, 절감률은 `nia_action_evaluator / nia_speech` 요청 비율로 확인한다.
- stale 턴은 `stage=before_judge`면 OpenAI 요청 0회, `stage=after_judge`면 이미 시작한 judge 1회만 비용에 남을 수 있다.
- RAG 항목에 비교 가능한 vector가 있을 때만 `nia_rag_embedding`이 발생하며, 완전히 같은 장면 query는 프로세스 내 해시 캐시를
  재사용한다.

### 8. fir_mir (FIR/MIR proxy 운영 경보) — `FirMirProxyMetrics`(P18-T008)
- `nexa_fir_proxy_total` / `nexa_mir_proxy_total` / `nexa_proxy_outcome_total{outcome}` — shadow/canary outcome 에서 지연 집계한 **false interruption(과반응)·missed intervention(과침묵) proxy**. **proxy** 이며 사용자 심리를 사실로 표시하지 않는다(dashboard 에 proxy 명시).

### 9. memory (stale memory·conflict) — `SocialMemoryHealthMetrics`(P18-T009)
- `nexa_memory_invalid_retrieval_blocked_total` — 무효(만료/철회) memory 검색 차단 수.
- `nexa_memory_conflict_total` / `nexa_memory_retrieval_total` — conflict rate 유도.
- `nexa_memory_deletion_backlog`(gauge) — 삭제 대기 backlog 크기. **실제 memory object 는 노출하지 않는다**.

### 10. privacy
- `nexa_consent_revocation_purged_total` — 동의 철회로 즉시 취소된 pending 수.

## correlation
- 모든 단계는 단일 correlation ID 로 엮인다(P18-T002, `NexaCorrelationContext`). 로그·metric 어디에도 원문은 없다.
