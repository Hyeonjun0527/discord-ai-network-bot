# 길드 관리자 동의 모델

- 작업: NEXA-P02-T002 (`human_gate: true`, decision) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [onboarding-boundary.md](../../../docs/nexa/architecture/onboarding-boundary.md),
  [guild-policy-boundary.md](../../../docs/nexa/architecture/guild-policy-boundary.md),
  [data-categories.md](./data-categories.md)

## 결정

NEXA는 **길드 관리자의 명시적 동의 전에는 어떤 관찰도 시작하지 않는다**(shadow 관찰조차 금지).

### 동의 단위

| 범위 | 동의 주체 | 비고 |
| --- | --- | --- |
| 서버 단위 활성화(on/off) | 길드 관리자 | 마스터 스위치. off가 기본값(onboarding-boundary) |
| 채널 단위 범위 | 길드 관리자 | 채널 모드(ASSISTANT/MEMBER/OFF)·제외([channel-scope.md](./channel-scope.md)) |

### 관리자 권한 증명

- 관리자 = Discord `MANAGE_SERVER` 또는 `MANAGE_CHANNELS` 권한 보유자(기존 `NiaChannelSetup` 권한 모델 계승).
- 동의 설정·변경은 **웹 관리 대시보드에서만**(guild-policy-boundary: 디스코드 명령으로 정책 변경 금지).
  디스코드 측은 온보딩의 "AI 채널 자동 만들기" 버튼(=관리자 명시 활성화)만 제공.

## acceptance 충족

- **동의 전 shadow 관찰조차 시작하지 않는다**: 서버 활성화 off(기본)면 conversation 관찰·이벤트
  수집이 일어나지 않는다. 버튼 클릭 또는 웹 활성화가 동의의 시작점이다.

## 불변식

1. 기본값은 off — 봇 초대만으로는 관찰·발화·기억이 시작되지 않는다.
2. 동의는 길드 관리자만 줄 수 있고 웹 대시보드가 유일한 편집 입구다.
3. 동의 철회 시 신규 관찰이 즉시 중단되고 기존 데이터는 삭제 경로(T009)로 제거 가능하다.
