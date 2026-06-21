# EXP — event store 부하·보존 크기 측정 (NEXA-P03-T024)

- 작업: NEXA-P03-T024 (`kind: experiment`, `human_gate: false`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [V51 nexa_event_store 스키마](../../../central-server/src/main/resources/db/migration/V51__nexa_event_store.sql),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md)(High 원문 기본 비영속),
  [retention-policy.md](../../../specs/product-v2/nexa/retention-policy.md)
- 스크립트: [`scripts/nexa-event-store-capacity.py`](../../../scripts/nexa-event-store-capacity.py)

## 범위·금지

- **실제 운영 부하 테스트 금지**: 운영 DB·게이트웨이에 연결하지 않는다. 현실적 채팅률 **가정**으로 24시간 이벤트량,
  DB 크기, index 비용, 처리 지연을 **추정**한다(±50% 여유). 소규모 in-test 측정은 통합 테스트(T023)가 이미 커버한다.
- 측정이 아니라 추정이므로, 운영 전환 시 실데이터로 보정한다(아래 보정 절차).

## 방법

1. **이벤트량 모델**: 메시지 1건이 만드는 정규화 이벤트 = `create 1 + reaction×r + typing×t + edit/delete×e`.
   채널 수 = 길드 × 길드당 채널. 분당 이벤트 = 채널수 × 채널당 분당 메시지 × 배수.
2. **행/인덱스 바이트**: V51 DDL 컬럼 타입에서 보수적 평균 바이트 추정(부록). 인덱스 5개(channel_order/occurred/
   received/source_event/uk_event_id) 엔트리 합산. 원문(High)은 기본 비영속이라 `content_cipher` 채움 비율 0% 기본.
3. **TTL/파티셔닝 시점**: 일일 증가량으로 예산(기본 1 GiB) 도달 일수를 계산해 권고를 낸다.

실행:

```bash
python3 scripts/nexa-event-store-capacity.py            # 기본 가정(표 출력)
python3 scripts/nexa-event-store-capacity.py --json     # 기계 판독(JSON)
python3 scripts/nexa-event-store-capacity.py --guilds 50 --msgs-per-channel-per-min 5
```

## 추정 결과 (기본 가정: 길드 10 · 길드당 채널 5 · 채널당 분당 메시지 2)

이벤트 배수: reaction×0.8, typing×1.2, edit/delete×0.15.

| 지표 | 값(24h) |
| --- | --- |
| 시간당 이벤트 | 18,900 |
| 총 이벤트 | 453,600 |
| 쓰기 처리율 | ~5.25 events/s |
| 행 데이터 | 64.02 MiB |
| 인덱스(5개) | 77.87 MiB |
| 암호 payload | 0.00 MiB (High 비영속) |
| **총 크기** | **141.89 MiB** |
| 일일 증가(환산) | 141.89 MiB/day |

> 처리 지연: 쓰기 ~5 events/s 는 단일 게이트웨이 이벤트 스레드(jda-ingestion.md, 비샤딩)와 단일 트랜잭션 append
> (event + outbox 동반)에서 여유롭다 — append 가 ms 단위면 수십 events/s 까지 큐잉 없이 흡수한다. 병목은 쓰기 TPS 가
> 아니라 **인덱스가 행보다 큰 누적 디스크**다(메타-only 행이라 인덱스 5개 합이 행 데이터를 초과).

## 용량 추정·TTL/파티셔닝 필요 시점 (acceptance)

- **인덱스 우위**: 원문 비영속이라 행이 작아 인덱스 5개 합(77.9 MiB)이 행 데이터(64.0 MiB)를 넘는다. 보존 정책은
  **인덱스 비용**을 1급으로 본다 — 불필요한 인덱스(예: source_event_id 가 거의 NULL 이면)는 운영 데이터로 재검토.
- **TTL 시점**: 기본 가정에서 1 GiB 예산까지 **약 7.2일**. 권고 = **1개월 이내 TTL 도입**. 큰 가정(길드 50·분당 5)
  에서는 하루도 안 돼 예산 초과 → **즉시 TTL/파티셔닝 필요**.
- **파티셔닝 기준**: `received_at`(이미 인덱스 존재) 기반 시간 파티션이 보존 만료(드롭 파티션)와 정렬된다. 채널
  파티션은 streamByChannel 지역성에 유리하나 보존 만료에는 시간 파티션이 단순하다 — **시간 파티션 우선**.

## 보정 절차(운영 전환 시)

1. 운영(또는 shadow, P03-T025)에서 24시간 실수집 후 `nexa_event_store` 실제 행수·`pg_total_relation_size` 측정.
2. 스크립트의 `BYTES_PER_ROW_*`·`BYTES_PER_ROW_INDEXES` 를 실측치로 교체(부록 상수).
3. 재계산해 TTL/파티셔닝 시점을 확정하고 retention-policy.md 에 반영.

## 부록 — 바이트 상수 근거(보수적 추정)

| 상수 | 값 | 근거 |
| --- | --- | --- |
| `BYTES_PER_ROW_META` | 120 | event_id(≤200자 중 평균 30)·type·guild/channel/seq(8B×)·occurred/received(8B×)·privacy·redacted 합. |
| `BYTES_PER_ROW_TUPLE_OVERHEAD` | 28 | Postgres heap tuple header + alignment. |
| `BYTES_PER_ROW_INDEXES` | 180 | 인덱스 5개 엔트리(키+ctid) 합 보수치. |
| `BYTES_PER_CIPHER` | 256 | enc1: ciphertext 평균(짧은 메시지). content_cipher 채움 시에만. |

이 상수는 **추정**이며 운영 실측으로 보정한다(위 절차). 측정 정확도가 아니라 **TTL/파티셔닝 의사결정 시점**을
수치로 잡는 것이 목적이다(acceptance: 용량 추정과 TTL/파티셔닝 필요 시점이 수치로 기록).
