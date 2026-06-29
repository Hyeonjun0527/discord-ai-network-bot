# NEXA 1개 내부 길드 canary 계획 (P18-T022)

NEXA 를 **1개 내부 길드**에서 처음으로 실제 발화시키는 canary 진입 계획의 SSOT. 이 문서는 **계획**이며, 실제
canary 배포는 운영(P18-T025 게이트 통과 후 staging 시연 → 운영)에서만 한다 — 이 작업에서 실제 canary 를 켜지
않는다.

## 대상 (target)

- **단 1개 내부 길드**: 팀이 소유·운영하는 내부 테스트 길드(외부 사용자 서버 아님). guild 가명만 운영 설정에
  기록한다(원문 snowflake 비저장).
- **채널**: 그 길드 안의 1개 AI 멤버 채널(MEMBER participation)로 한정. AI 질문 채널(ASSISTANT)은 canary 범위 밖.

## acceptance: 실제 사용자 동의와 AI 공개 상태 확인

canary 진입 **전** 반드시 확인한다(미충족 시 진입 금지):

1. **사용자 동의**: 그 길드의 참여 사용자가 NEXA 관찰·발화에 명시 동의한 상태여야 한다(온보딩 consent — `nexa_member_onboarding_consent` V66). 동의 철회자는 관찰·발화 대상에서 제외된다.
2. **AI 공개(disclosure)**: 채널에 NEXA 가 **AI 임을 명시**한 안내가 핀/공지로 게시돼 있어야 한다. 사람으로
   오인시키지 않는다(투명성 — privacy/AI 공개 계약). 닉네임·소개에 AI 표시.
3. 둘 다 운영 진입 체크리스트([release-checklist.md](release-checklist.md))에서 서명으로 확인한다.

## 파라미터

| 항목 | 값(계획) |
| --- | --- |
| 기간(duration) | 7일(1주). 매일 SLO·error budget 리뷰. |
| 시간대(time window) | 팀 근무 시간(예: 평일 10:00–18:00 KST)만 LIVE. 그 외에는 SHADOW 로 자동 강등(무인 발화 금지). |
| max share | 채널 burst 점유율 상한 35%([alerts.md](alerts.md) `SHARE_RATIO` warn) — 넘으면 자동 강등. |
| intervention proxy | [shadow-canary-gates.md](shadow-canary-gates.md)의 FIR/MIR 기준을 동시에 만족해야 승급 논의 가능. |
| 모델 | 현재 active LIVE 모델([model rollback](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/model/ModelRollbackService.kt) 의 active). 변경 시 재승인. |
| 단계 | [`ShadowMode.CANARY`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/shadow/ShadowMode.kt) — 제한적 실제 발화. |

## rollback

- **수동**: 운영자가 길드 kill switch([GuildKillSwitchService](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/GuildKillSwitchService.kt))
  또는 채널 mute([ChannelMuteService](../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/ChannelMuteService.kt))로 즉시 정지.
- **단계 강등**: CANARY→SHADOW_PREDICT→OFF. 정책 모델 문제면 [model rollback](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/model/ModelRollbackService.kt)
  으로 previous artifact 복귀. 설정 문제면 [설정 rollback](../../../central-server/src/main/kotlin/com/discordassistant/central/channelai/application/NexaSettingsRollbackService.kt).
- **자동**: [auto-halt 조건](canary-auto-halt.md) 충족 시 자동 강등(사람 개입 불요).

## 연락 책임자 (on-call)

- canary owner: 1명(의사결정·rollback 권한). 백업 1명.
- 모든 자동 중단·kill 은 owner 에게 알림([OperatorAlertPort](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/rollout/CanaryAutoHaltPorts.kt)).
- incident 절차는 [runbooks](runbooks/incident-response.md) 참조.

## 진입 게이트

이 계획은 P18-T025(canary 진입 게이트)에서 SLO·alert·kill switch·rollback·consent·자동 중단과
[intervention gate](shadow-canary-gates.md)를 **staging 에서 실제 시연**한 뒤에만 운영 canary 로 진행한다.
시연 전 운영 canary 금지.
