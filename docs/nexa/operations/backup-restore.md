# NEXA DB backup·restore 검증 (P18-T017)

NEXA 데이터의 백업·복원 절차와 복원 훈련(restore drill)의 SSOT. **메커니즘+문서**만 다룬다 — 실제 운영 DB 에
접근하지 않는다(운영 복원은 별도 staging 한정). 복원 훈련 스크립트: [`nexa-backup-restore-drill.py`](../../../scripts/nexa-backup-restore-drill.py).

## 백업 범위 (event store·action queue·memory·decision log 포함)

NEXA 의 모든 상태/감사 데이터는 한 번에 일관되게 백업한다(deliverable T017):

| 데이터셋 | 표(Flyway) | 역할 |
| --- | --- | --- |
| event store | `nexa_event_store` (V51) | 정규화 관찰 이벤트(append-only) |
| scheduled actions | `nexa_scheduled_actions` (V63) | 예약 사회 행동 큐 |
| action audit | `nexa_action_audit` (V64) | 행동 생애 감사(append-only) |
| social memory | `nexa_social_memory` (V56) | 사회 기억 |
| memory vector | `nexa_memory_vector` (V57) | 기억 임베딩 |
| policy decisions | `nexa_policy_decisions` (V58) | 결정 로그 |
| kill switch / channel mute | `nexa_guild_kill_switch*` (V67), `nexa_channel_mute*` (V68) | 정지 상태·감사 |

## 백업 절차 (운영 — 실행은 배포 환경에서만)

1. **일관 스냅샷**: Postgres `pg_dump`(논리) 또는 PITR(물리·WAL)로 위 표를 **하나의 트랜잭션 시점**으로 잡는다.
   event store 와 action queue 가 서로 다른 시점이면 복원 후 정합성이 깨지므로 동시점 필수.
2. **암호화·보관**: 가명·해시만 담겨도 백업은 암호화 저장한다(privacy-by-design — boundary 문서 준수).
3. **보존**: 일 1회 전체 + WAL 연속. deletion SLA([slo.md](slo.md))를 지키도록 만료 백업은 자동 폐기.

## 복원 절차

1. Flyway 마이그레이션은 **건드리지 않는다**(V1~V68 그대로) — 복원은 데이터만 되돌린다.
2. 복원 후 scheduler 를 시작하기 **전에** [`RestartRecoveryService`](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/recovery/RestartRecoveryService.kt)(P13-T010)
   가 in-flight lease 를 회수한다.
3. kill switch / channel mute 활성 상태가 복원되므로, 복원 직후에도 정지된 길드/채널은 정지 상태를 유지한다.

## acceptance: 복원 후 duplicate send 없이 scheduler 가 시작된다

핵심 위협은 **중복 전송**이다 — 백업 시점에 in-flight 였던 행동을 복원 후 scheduler 가 다시 집어 같은 말을 두
번 하는 것. 안전 규칙(RestartRecoveryService 와 동일):

| 복원 시점 phase | 처리 | 이유 |
| --- | --- | --- |
| `PARTIALLY_SENT` / `COMPLETED` | **재전송 안 함**, 종결만 | 이미 일부/전부 보냄 — "한 번 덜" 이 안전 |
| `REEVALUATING` / `TYPING` / `SCHEDULED` | 재예약 → 1회 전송 | 본문 미전송 — 다시 처리해도 중복 없음 |

복원 훈련(`python3 scripts/nexa-backup-restore-drill.py`)이 이 시퀀스를 합성으로 돌려 **각 action 이 정확히 1회**
전송됨(중복 0)을 검증한다. 같은 안전 규칙의 단위 검증은 [EXP-actionruntime-chaos](../experiments/EXP-actionruntime-chaos.md)
와 `ActionRuntimeChaosTest` 가 담당한다(재시작=복원과 동형).

## 운영 경계

이 문서는 절차+합성 검증이다. 실제 운영 DB dump/restore 는 staging 에서만 수행하며, 운영 배포·실 데이터 접근은
이 작업 범위 밖이다.
