# Runbook — NEXA 중단·복구 (P18-T024)

당직자(on-call)용. **코드 지식 없이** NEXA 를 멈추고 되돌리는 절차. 모든 동작은 운영 대시보드/관리 API 로
수행하며, 발동·해제는 audit 에 남는다. 상위: [release-checklist](../release-checklist.md).

> 원칙: 의심되면 **먼저 멈추고**(안전), 그 다음 원인을 본다. "한 번 덜 말함" 이 "잘못 말함" 보다 안전하다.

## 1. NEXA 가 너무 많이 말한다 / 길드 전체를 멈춰야 한다

**길드 kill switch** — 그 길드의 신규 결정·예약·전송을 즉시 차단하고 이미 생성된 pending 까지 취소한다.

1. 대시보드 → 해당 길드 → "Kill switch: engage". 사유 코드 입력(예: `over_talk`).
2. 즉시 발효(다음 호출부터 BLOCK). 이미 만들어진 버블도 취소됨.
3. 복구: 원인 해소 후 "disengage". (pending 은 복구되지 않음 — 안전 우선.)

근거: [GuildKillSwitchService](../../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/GuildKillSwitchService.kt).

## 2. 특정 채널만 끈다 (길드보다 세밀)

**channel mute** — 두 수준 중 선택.

- **발화만 끄기(SPEECH_ONLY)**: 그 채널 발화만 멈추고 계속 듣는다(맥락 보존). 잠깐 조용히 시킬 때.
- **관찰·저장까지 끄기(OBSERVE_AND_SPEECH)**: 발화 + 신규 event 적재까지 막는다(완전히 손 뗌).

1. 대시보드 → 채널 → "Mute" → 수준 선택 → 사유.
2. 즉시 발효. 발화 차단 시 그 채널 pending 도 취소됨.
3. 해제: "Unmute".

근거: [ChannelMuteService](../../../../central-server/src/main/kotlin/com/discordassistant/central/actionruntime/application/ChannelMuteService.kt).
(과거 적재 데이터는 mute 로 지워지지 않음 — 삭제는 [incident-response](incident-response.md) 의 동의 철회 경로.)

## 3. 정책 모델이 이상하다 → 이전 모델로 되돌린다

**model rollback** — active → previous signed artifact 로 원자적 전환.

1. 대시보드 → "Policy model" → "Roll back to previous".
2. 전환은 원자적. **진행 중이던 결정**은 시작 시 본 모델 버전이 바뀌었으면 자동 취소된다(혼합 추론 방지).
3. previous 가 없으면(되돌릴 대상 없음) 실패 — 대신 단계 강등(SHADOW/OFF)으로 막는다.

근거: [ModelRollbackService](../../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/model/ModelRollbackService.kt).

## 4. 설정을 잘못 바꿨다 → 이전 버전으로 되돌린다

**settings rollback** — version history 에서 이전 값으로 새 version 을 만든다(전방 복구, 과거 보존).

1. 대시보드 → 길드 설정 → "History" → 되돌릴 version 선택 → "Roll back".
2. **주의**: 동의가 철회된 상태면 rollback 이 거부된다 — 동의 철회는 설정 시간여행으로 되살릴 수 없다(명시
   재동의만). 이 경우 [incident-response](incident-response.md) 참조.

근거: [NexaSettingsRollbackService](../../../../central-server/src/main/kotlin/com/discordassistant/central/channelai/application/NexaSettingsRollbackService.kt).

## 5. 자동 중단이 발동했다

자동 중단([canary-auto-halt](../canary-auto-halt.md))이 길드를 SHADOW/OFF 로 강등하면 알림이 온다. 알림의 사유
코드를 보고:

- `OVER_TALK`/`COMPLAINT`/`STALE_SEND` → SHADOW 강등됨. 원인 확인 후 재진입 결정.
- `PRIVACY_ERROR`/`MODEL_MISMATCH` → OFF 강등됨(가장 강함). [incident-response](incident-response.md) 로.

자동 중단은 pending 도 취소하고 알림도 보낸 상태다 — 추가 정지는 불필요.
