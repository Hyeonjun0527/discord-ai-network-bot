# 경계 계약: onboarding 동의·설정

- 작업: NEXA-P01-T019 (`human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0009 channelai 책임 재정의](../../adr/0009-channelai-responsibility.md)
- 관련 계약: [guild-policy-boundary.md](./guild-policy-boundary.md),
  [licensing-boundary.md](./licensing-boundary.md), [socialmemory-context.md](./socialmemory-context.md)
- 기존 구현 계승: `central/platform/discord/NiaChannelSetup.kt`(버튼 `setup:nia-channels`),
  `channelai/application/AutoRespondChannelRegistry.kt`

## 목적

봇을 서버에 들이면 **"AI 채널 자동 만들기" 버튼 한 번으로 2종 AI 채널을 생성**해 바로 쓰게 한다.
세부 정책·설정은 **웹 관리 대시보드에서만** 한다(디스코드 명령으로 정책을 만지지 않는다).

## 온보딩 흐름 (카미봇식, 기존 NiaChannelSetup 확장)

```
1. 봇 초대 → systemChannel 에 환영 메시지 + [AI 채널 자동 만들기] 버튼 (setup:nia-channels)
2. 길드 관리자가 버튼 클릭 = 명시적 활성화/동의
3. 자동 생성(멱등 — 이미 있으면 재생성 안 함):
   - AI 질문 채널 (ASSISTANT 모드)  : 채널 내 메시지에 "무조건 답변" (질문-답변)
   - AI 멤버 채널  (MEMBER 모드)    : "완전 사람처럼" participation 판단으로 자연스럽게 참여(니아)
   - (기존 ai그림 채널·음성 카테고리는 현행 유지)
4. 생성된 채널은 즉시 활성 — 질문 채널은 바로 답하고, 멤버 채널은 바로 사람처럼 동작
```

- **AI 질문 채널(ASSISTANT)**: 기존 `auto_respond`/`AutoRespondChannelRegistry` 기능 계승.
  채널의 모든 적격 메시지에 답한다(participation 관점에서 항상 SPEAK인 특수 채널 모드).
- **AI 멤버 채널(MEMBER)**: NEXA participation이 IGNORE/WAIT/REACT/SPEAK/CANCEL을 장면 기반으로
  판단해 사람처럼 끼어든다(ADR 0007). 니아 채널 AI 프로필을 부여한다.

## 설정·정책의 위치 (사용자 결정)

- 길드 활성화·채널 모드(ASSISTANT/MEMBER/OFF)·말 많음·제외 채널·데이터/학습 동의 등 **모든 정책
  편집은 웹 관리 대시보드에서만** 한다([guild-policy-boundary.md](./guild-policy-boundary.md)).
- 디스코드 측은 채널 자동 생성 버튼과 채널 자체만 제공하고, 정책 값을 디스코드 슬래시 명령으로
  변경하지 않는다(관리 전면 웹 이관 방침과 일치).

## 동의·되돌림 (acceptance — 명시 활성화, 되돌림 가능)

| 항목 | 동작 |
| --- | --- |
| 봇 초대만 한 상태 | 채널 미생성 → 봇은 아무 채널에서도 말하지 않는다(조용) |
| "AI 채널 자동 만들기" 클릭 | 관리자의 명시적 활성화 → 2종 AI 채널 생성·활성 |
| 외부 모델 전송 | 채널 활성화가 곧 routing CloudLlm 사용 동의. 어떤 데이터가 가는지 웹에 고지 |
| 사회 기억 학습(socialmemory 쓰기) | 웹 대시보드에서 on/off (MEMBER 채널의 자연스러움을 위해 권장하되 끌 수 있음) |
| 비활성화/철회 | 웹에서 채널 모드 OFF 또는 채널 삭제 → 즉시 중단. 기억은 삭제 경로(P03)로 제거 가능 |

핵심: **자동 발화는 관리자가 채널을 만들 때 시작된다(버튼=명시 동의).** 버튼을 누르기 전에는 봇이
스스로 말하거나 기억을 쌓지 않는다. 모든 활성/비활성은 웹에서 되돌릴 수 있다.

## 불변식

1. 봇 초대만으로는 어떤 채널에서도 자동 발화하지 않는다 — "AI 채널 자동 만들기" 클릭이 활성화 트리거다.
2. 정책·설정 편집은 웹 관리 대시보드가 유일한 입구다(디스코드 명령으로 정책 변경 금지).
3. AI 질문 채널(무조건 답변)과 AI 멤버 채널(사람처럼)은 명확히 구분된 채널 모드다.
4. 모든 활성화·동의는 웹에서 개별적으로 끄거나 철회할 수 있고 즉시 반영된다.
