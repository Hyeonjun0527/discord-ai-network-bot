# ADR 0009: channelai 책임 재정의 — 설정 SSOT 존치, 자동응답 타이밍 이관

- 상태(Status): 승인됨 (Accepted) — 인간 결정자 승인 2026-06-21 (NEXA-P01-T008, `human_gate: true`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007 사회적 행위자 모델](./0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](./0010-ainetwork-socialmemory-boundary.md)
- 근거 기준선: [social-model-overlap.md](../nexa/baseline/social-model-overlap.md),
  [current-autoresponse-flow.md](../nexa/baseline/current-autoresponse-flow.md)
- 계약: [participation-context.md](../nexa/architecture/participation-context.md)

## 맥락 (Context)

ADR 0007이 "말할지 여부"의 결정을 `participation`에 두기로 했다. 그러나 현재 그 결정은
`channelai`의 `channel_ai.auto_respond` boolean과 `AutoRespondChannelRegistry` 핫패스 캐시가
사실상 내리고 있다([social-model-overlap.md](../nexa/baseline/social-model-overlap.md) 항목
`auto_respond`/`auto-response cache`):

- `auto_respond=true`면 채널의 비-dot 메시지가 전부 `/ask` 경로로 들어가 응답을 유발한다
  (`DiscordBot.Listener.onMessageReceived` → `autoRespondChannels.isAutoRespond`).
- 이는 장면 인식·쿨다운·버스트 맥락·opt-out·사회 상태가 없는 단순 채널 ON/OFF 게이트다.

동시에 `channelai`는 채널 AI **정체성·행동 버전·승인/감사·라우팅 정책**의 정당한 SSOT다
(`channel_ai`, `ai_behavior_version`, proposals/audit). 이 설정 책임까지 옮기면 안 된다.

## 결정 (Decision)

**channelai는 "채널별 AI 프로필·설정·모드의 SSOT"로 존치하고, 자동응답 타이밍 결정만 단계적으로
participation으로 이관한다.**

### 존치(REUSE) — channelai가 계속 소유

- `channel_ai` 프로필(display_name, avatar_url, active_behavior_version, source)
- `ai_behavior_version`(purpose/tone/answer_length/constitution/custom_instruction/safety_level)
- 변경 제안·승인·커스터마이즈 감사(`ai_change_proposal`, `customization_audit_log`)
- 프롬프트 렌더링 규칙(`ChannelAiPromptRenderer`) — speech는 읽기/compose 포트로만 소비

이들은 [social-model-overlap.md](../nexa/baseline/social-model-overlap.md)에서 REUSE로 분류됐다.
speech는 활성 채널 정체성·행동의 **읽기 모델**(`ChannelAiIdentityView`/`ChannelAiModeView`)만
소비하고, socialmemory는 이 행에 쓰지 않는다.

### 이관(MIGRATE) — participation으로 단계적 이동

`auto_respond` 플래그와 핫패스 캐시는 다음 순서로 옮긴다. **각 단계는 이전 단계와 호환된다.**

| 단계 | 내용 | 호환/제거 |
| --- | --- | --- |
| 0 (현재) | participation 미존재. `auto_respond` + 핫패스 캐시 현행 유지 | ArchUnit 가드만 추가: 새 코드가 `ChannelAiEntity`/`AutoRespondChannelRegistry`를 participation 밖에서 신규 의존하지 못하게 함 |
| 1 (P06 participation 도입) | `auto_respond`를 NEXA 채널 모드(OFF/SHADOW/CANARY/LIVE)로 **매핑**. participation이 IGNORE/WAIT/REACT/SPEAK/CANCEL 타이밍을 결정 | `auto_respond` 컬럼은 **읽기 호환 유지**: `false`→mention/slash-only, `true`→LIVE 모드 seed. 기존 동작 무손상 |
| 2 (participation 안정화) | 핫패스 게이트(`onMessageReceived`의 `isAutoRespond`)를 participation의 channel-mode query로 교체 | `AutoRespondChannelRegistry` 캐시는 **DEPRECATE**(participation 자체 인덱스 대체 후 제거) |
| 3 (후속 마이그레이션 ADR) | `auto_respond` 컬럼 최종 제거 여부 결정 | 별도 Flyway 마이그레이션 + ADR. 본 ADR은 컬럼을 **삭제하지 않는다** |

### Nia 셋업 자동생성 채널(MIGRATE)

`NiaChannelSetupHandler`가 만든 AI 채팅 채널의 의도를 보존한다 — 마이그레이션은 이 채널을 감지해
현재 플래그/프로필로 NEXA 모드를 seed하고, 프로필을 재생성하거나 채팅 채널 동작을 잃지 않는다.

## 비-목표

- participation·NEXA 채널 모드 스키마의 실제 구현 — P06 범위. 본 ADR은 책임 경계와 이관 순서만 정한다.
- `responseMode`(라우팅 응답 모드)를 사회적 talkativeness로 재해석 — 금지. 라우팅은 ainetwork/routing 소유.
- `auto_respond` 컬럼 즉시 제거 — 단계 3의 별도 ADR/마이그레이션으로만.

## 위험과 되돌림 가능성

| 위험 | 영향 | 완화 / 되돌림 |
| --- | --- | --- |
| 이관 중 자동응답 동작 회귀 | 채널이 답을 멈추거나 과다 응답 | 단계 1에서 `true`→LIVE seed로 기존 동작 보존. 단계별 SHADOW/CANARY로 점진 검증 |
| 두 시스템(플래그+participation)이 동시에 타이밍 결정 | 이중 응답 | 단계 2에서 핫패스 게이트를 participation으로 단일화하기 전까지 participation은 SHADOW(미발화)로만 동작 |
| ArchUnit 가드가 기존 코드 깨뜨림 | 빌드 실패 | 가드는 신규 의존만 차단하고 기존 위치는 baseline separation rule로 허용 |
| **되돌림** | — | 단계 0~1은 데이터 비파괴(읽기 호환). REJECTED/롤백 시 `auto_respond` 현행 경로로 즉시 복귀. 컬럼 삭제(단계 3)만 비가역이며 별도 게이트 |

## 결과 (Consequences)

**장점**: 설정 SSOT(channelai)와 타이밍 결정(participation)이 분리되어 장면 인식·쿨다운·버스트
기반의 사회적 응답이 가능해진다. 기존 설정 자산·API는 보존된다.

**단점**: 이관 기간 동안 `auto_respond`와 participation 모드가 공존해 매핑 규칙을 유지해야 한다.

## 인간 승인 상태 (Approval)

- `NEXA-P01-T008`, `human_gate: true`, `risk: high`.
- acceptance("기존 API 호환 기간과 제거 순서 명시") 충족 — 단계 0~3 표에 호환 유지 범위와 제거
  순서, 비가역 지점(단계 3 컬럼 삭제)을 명시했다.
- 인간 결정자(Hyeonjun0527)가 2026-06-21에 ACCEPTED로 승인했다. 후속 P06 이관 작업은 본 단계
  순서(0→3)와 호환 규칙을 따른다.

## 미해결 질문

- NEXA 채널 모드 enum(OFF/SHADOW/CANARY/LIVE)의 최종 정의와 저장 위치(channelai vs participation).
- 단계 2에서 핫패스 게이트를 교체할 때 participation channel-mode query의 캐시·무효화 전략.
