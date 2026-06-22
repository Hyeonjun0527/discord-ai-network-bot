# NEXA 관리자 RBAC (admin permissions)

- 작업: NEXA-P17-T006 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [consent-model.md](./consent-model.md)(관리자 권한 증명),
  [guild-policy-boundary.md](../../../docs/nexa/architecture/guild-policy-boundary.md)(정책은 웹 대시보드 전용),
  [threat-model.md](../../../docs/nexa/security/threat-model.md)(STRIDE: 권한 상승)
- 구현: [`NexaAdminPermission`](../../../central-server/src/main/kotlin/com/discordassistant/central/global/security/NexaAdminPermission.kt),
  [`NexaAdminAuthorization`](../../../central-server/src/main/kotlin/com/discordassistant/central/global/security/NexaAdminAuthorization.kt)

## 목적

NEXA 관리 행위를 **권한별로 분리**한다. 조회·설정·live 전환·데이터 export·삭제·모델 승인은 서로
다른 권한이며, **하나의 Discord 관리자 종류에 모든 고위험 권한을 자동 부여하지 않는다**. 모든 NEXA
관리는 웹 대시보드 전용·durable-token 경로에서만 일어난다(디스코드 명령으로 변경 금지).

## 권한(permission) 정의

| 권한 | 위험 | 설명 |
| --- | --- | --- |
| `VIEW_SETTINGS` | low | NEXA 설정·상태 조회(읽기 전용) |
| `EDIT_SETTINGS` | medium | 페르소나·자유지침·채널 모드 등 설정 변경 |
| `TOGGLE_LIVE` | high | shadow ↔ live 전환(실제 발화 송출 on/off) |
| `EXPORT_DATA` | high | 사용자 데이터 export 실행 |
| `DELETE_DATA` | high | 삭제 요청 orchestration 실행 |
| `APPROVE_MODEL` | high | 학습 model 승인·배포 |

## 역할(role) ↔ 권한 매핑

| 역할 | 부여 권한 |
| --- | --- |
| `VIEWER` | `VIEW_SETTINGS` |
| `OPERATOR` | `VIEW_SETTINGS`, `EDIT_SETTINGS` |
| `LIVE_OPERATOR` | `OPERATOR` + `TOGGLE_LIVE` |
| `DATA_OFFICER` | `VIEW_SETTINGS`, `EXPORT_DATA`, `DELETE_DATA` |
| `MODEL_APPROVER` | `VIEW_SETTINGS`, `APPROVE_MODEL` |

핵심: **고위험 권한(`TOGGLE_LIVE`/`EXPORT_DATA`/`DELETE_DATA`/`APPROVE_MODEL`)은 어느 단일 역할에도
한꺼번에 모이지 않는다.** Discord `MANAGE_SERVER` 보유자라고 자동으로 모든 권한을 받지 않는다 —
기본 매핑은 `OPERATOR`(설정까지)이며, 고위험 권한은 명시적 역할 부여가 필요하다.

## acceptance 충족

- **Discord 관리자 한 종류에 모든 고위험 권한을 자동 부여하지 않는다**: `NexaAdminRole` 의 어떤 역할도
  4개 고위험 권한을 모두 갖지 않는다(`NexaAdminAuthorizationTest` 가 모든 역할에 대해 검증). Discord
  `MANAGE_SERVER` → 기본 `OPERATOR` 매핑은 고위험 권한 0개를 포함한다.
- **권한 분리**: 조회/설정/live/export/삭제/모델승인이 각각 독립 `NexaAdminPermission` 이며, 권한 검사는
  역할이 아니라 개별 permission 으로 한다(`requirePermission`).

## 불변식

1. 고위험 권한 부여는 명시적이다(Discord 관리자 권한에서 자동 상속 금지).
2. 어떤 단일 역할도 모든 고위험 권한을 갖지 않는다.
3. 권한 검사는 web/durable-token 경로에서만 적용된다(디스코드 명령 경로 없음).
