# NEXA 운영 metric taxonomy (P18-T001)

NEXA 관측 metric 의 SSOT. 모든 metric 은 Micrometer 로 `/actuator/prometheus` 에 노출되며, 아래 **label 카디널리티 규칙**을 어기지 않는다. 구현은 `central-server/.../global/observability/**`(집계 metric)·도메인 인접 metric 컴포넌트가 채운다.

## label 카디널리티 규칙 (불변식)

- **금지 label**: 원본 user ID·channel ID·guild snowflake 원문·message ID·메시지/프롬프트/응답 **원문**. 즉 고카디널리티 식별자와 PII 는 metric label 에 **절대** 쓰지 않는다(P09-T018 일관, logging-boundary.md).
- **허용 label**: 고정 enum 집합(action kind, mode, purpose, reason, window 등 저카디널리티)·**길드 가명(pseudonym)**(비용/점유율 운영 분해용, 원문 snowflake 아님)·모델 버전 라벨.
- 개별 식별이 필요한 값(fragment 수·gap·latency 등)은 **label 없는 분포(summary)/카운터**로 집계한다 — label 폭발과 원문 유출을 동시에 막는다.
- metric 은 **집계만** 노출한다. 개별 사용자 심리·개별 memory object·개별 예측 레코드는 metric 으로 내보내지 않는다.

## metric 군

### 1. ingestion (수집)
- `nexa_event_ingested_total{source}` — 관찰 event 유입 수(source=message/edit/delete 등 저카디널리티).

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
