# legacy → NEXA 점진 migration·rollback runbook (NEXA-P15-T024)

- 작업: [NEXA-P15-T024](../nexa_500_task_graph.yaml) · 상위: P15 통합 단계
- 관련 계약: [participation-context.md](../architecture/participation-context.md),
  [guild-policy-boundary.md](../architecture/guild-policy-boundary.md),
  [module-dag.md](../architecture/module-dag.md)
- 관련 인벤토리: [channelai-inventory.md](./channelai-inventory.md)

## 목적

NEXA 사회적 참여를 **길드 단위로 점진 적용**하고, 문제가 생기면 **각 축(DB·설정·모델)을 독립적으로 rollback**
하는 절차를 SSOT 로 못 박는다. 핵심 원칙: **기본값은 LEGACY(=OFF)** 라 명시 승인 전에는 기존 channelai
자동응답만 동작한다(회귀 0). 운영 배포·실제 전송은 이 runbook 의 게이트를 통과해야만 켜진다.

## lane 모델 (단방향 승급, 역방향 자유)

운영 lane 은 4단계다(`ParticipationLane`, [participation-context.md](../architecture/participation-context.md)):

| lane | 의미 | 내부 ShadowMode | 실제 전송 |
| --- | --- | --- | --- |
| `LEGACY` | NEXA 끔 — 기존 channelai 자동응답만(기본) | `OFF` | 없음 |
| `SHADOW` | 정책 예측·기록만 | `SHADOW_PREDICT` | **0회(hard block)** |
| `CANARY` | 소수 채널 실제 발화 | `CANARY` | 제한적 |
| `LIVE` | 전면 실제 발화 | `LIVE` | 전면 |

- **승급(legacy→shadow→canary→live)** 은 한 단계씩, 길드 단위로만 한다. 건너뛰기 금지.
- **CANARY/LIVE 진입(실제 전송 활성화)** 은 별도 권한(`canEnableRealSend`)과 명시 확인을 요구한다
  (관리자 NEXA 설정 API `confirmLiveSend=true`, T018/T019). 무심코 켜지지 않는다.
- **하향(rollback)** 은 어느 단계에서든 즉시 허용된다(끄기는 안전 방향 — 권한 게이트만, 확인 불필요).

## 점진 migration 절차 (길드별)

1. **준비(LEGACY 유지)**: DB 마이그레이션(V50~V66)을 적용한다. 마이그레이션은 additive 라 기존 동작을
   바꾸지 않는다([NexaFlywayMigrationIntegrationTest] 가 V50~V66 success 를 검증). 이 시점 lane 은 여전히 LEGACY.
2. **온보딩 동의(NEXA 멤버 채널)**: 운영자가 "채널 자동만들기" 로 AI 질문채널(ASSISTANT)+AI 멤버채널(NEXA MEMBER)을
   만들 때, 멤버채널의 데이터 처리 동의를 **목적별로 따로** 받는다(관찰 범위 / 외부 GLM / shadow·live / 학습 —
   포괄 동의 금지, T014). 동의하지 않은 축은 꺼진 채로 둔다.
3. **SHADOW 승급**: 관리자 NEXA 설정(웹 대시보드 전용)에서 길드 lane 을 SHADOW 로 올린다. 정책이 관찰·예측·
   기록만 하고 **전송은 0회**다([NexaShadowEndToEndTest] 가 shadow 전송 0 을 증명). shadow 리포트로 발화율/침묵률을
   며칠 관찰한다.
4. **CANARY 승급**: shadow 지표가 [intervention gate](../operations/shadow-canary-gates.md) 기준 안에 있으면,
   소수 채널만 CANARY 로 올린다(`confirmLiveSend=true` 필요). 채널 단위 override 로 일부 채널만 실제
   발화시키고 나머지는 SHADOW 로 둔다.
5. **LIVE 승급**: canary 가 [intervention gate](../operations/shadow-canary-gates.md) 기준을 계속 만족하면
   길드 lane 을 LIVE 로 올린다(`confirmLiveSend=true`). 라이선스가 만료된 사용자가 켜면 LIVE 요청은 SHADOW 로
   자동 상한된다(legacy 로 되돌지 않음, T015).

## rollback 절차 (3축 독립)

각 축은 **서로 독립적으로** 되돌릴 수 있다. 한 축 rollback 이 다른 축을 건드리지 않는다.

### 1) 설정 rollback (lane 하향 — 가장 빠른 1차 대응)

- 관리자 NEXA 설정에서 길드 lane 을 한 단계(또는 LEGACY 로 즉시) 내린다. 권한 게이트만 적용되고 확인은
  불필요하다(끄기는 안전 방향). LEGACY 로 내리면 그 즉시 기존 channelai 자동응답만 동작한다(회귀 0).
- 특정 채널만 문제면 채널 **제외(kill switch)** 로 그 채널만 NEXA 에서 빼낸다(다른 채널·길드 영향 없음).
- 설정 변경은 audit 에 남는다(누가·언제·무엇에서·무엇으로·왜). DB 스키마·모델은 그대로 둔다.

### 2) 모델 rollback (정책 모델 교체 — 설정·DB 무변경)

- shadow/정책 모델만 문제면(예: 새 모델이 과발화), 모델 레지스트리에서 이전 모델 버전으로 되돌린다. lane·DB 는
  건드리지 않는다 — 같은 lane 에서 모델만 교체된다.
- 모델 rollback 후 SHADOW 로 잠시 내려 새(=구) 모델의 지표를 재확인한 뒤 다시 승급할 수 있다.

### 3) DB rollback (스키마 — 최후 수단)

- NEXA 마이그레이션(V50~V66)은 **additive**라 평상시 rollback 이 필요 없다(기존 테이블 무변경). 데이터만 비우려면
  설정 rollback(LEGACY) 후 NEXA 테이블의 데이터를 삭제 요청(`nexa_deletion_request`)으로 정리한다 — 스키마는
  유지해도 LEGACY 면 사용되지 않는다.
- 스키마 자체를 내려야 하면(드문 경우), Flyway `undo` 대신 **백업 복원**을 표준으로 한다(이 프로젝트는 forward-only
  마이그레이션). 복원 대상은 V49 baseline 스냅샷이다. 운영 DB 직접 DDL 은 금지.

## 안전 불변 (모든 단계에서 항상 보장)

- **safety/삭제/kill switch 는 라이선스·lane·DB 상태와 무관하게 항상 제공된다**(T015). 만료·정지여도 끄기·삭제·
  동의 철회·lane 하향은 막히지 않는다.
- **라이선스 만료가 legacy mention-always 로 바뀌지 않는다**: 실제 전송 권한이 없으면 LIVE/CANARY 요청은 SHADOW 로
  상한될 뿐, NEXA 가 꺼지거나 기존 mention-always 자동응답으로 *전환되지 않는다*(전환은 운영자 명시 행위).
- **shadow = 전송 0**: SHADOW 단계에서는 Discord outbound·GLM 발화 생성이 policy SPEAK 전까지 호출되지 않고,
  실제 전송은 hard block 으로 0회다([NexaShadowEndToEndTest]).

## 검증 게이트

- `./scripts/nexa-verify.sh central` — Flyway 통합(V50~V66)·channelai 호환·shadow 전송 0·ArchUnit 회귀.
- `./scripts/nexa-verify.sh contracts` — wire-contract 생성물 drift 없음(provider-agent 경계 회귀, T017).
- `./scripts/nexa-verify.sh docs` — package-graph 동기·아키텍처 SSOT·문서 링크.
