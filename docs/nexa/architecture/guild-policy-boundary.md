# 경계 계약: guild 정책 소유권

- 작업: NEXA-P01-T017 (`human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0009 channelai 책임 재정의](../../adr/0009-channelai-responsibility.md)
- 근거 기준선: [social-model-overlap.md](../baseline/social-model-overlap.md),
  [admin-dashboard.md](../baseline/admin-dashboard.md)
- 관련 계약: [participation-context.md](./participation-context.md),
  [onboarding-boundary.md](./onboarding-boundary.md)

## 목적

길드 레벨 사회행동 정책(활성화, 채널 모드, 말 많음 배수, 제외 채널)의 **단일 소유자와 읽기
우선순위**를 확정하고, **편집은 웹 관리 대시보드에서만** 하도록 고정한다(중복 설정 테이블 금지).

## 편집 입구 (사용자 결정 — 웹 전용)

- 길드 정책의 **유일한 편집 UI는 웹 관리 대시보드**다. 디스코드 슬래시 명령으로 길드 정책 값을
  바꾸지 않는다(관리 전면 웹 이관 방침과 일치, [admin-dashboard.md](../baseline/admin-dashboard.md)).
- 디스코드 측은 온보딩의 "AI 채널 자동 만들기" 버튼([onboarding-boundary.md](./onboarding-boundary.md))과
  채널 자체만 제공한다. 세부 값(모드·말 많음·제외)은 웹에서만 조정한다.

## 소유권 (acceptance — 중복 테이블 금지, 읽기 우선순위 하나)

| 설정 | 소유 | 비고 |
| --- | --- | --- |
| 길드 NEXA 활성화(on/off) | **guild 정책** | 서버 단위 마스터 스위치 |
| 채널 모드(ASSISTANT / MEMBER / OFF) | **guild 정책** | ASSISTANT=무조건 답변(질문 채널), MEMBER=사람처럼 participation, OFF=비활성 |
| 채널 제외 목록 | **guild 정책** | 길드 스코프 deny-list |
| 말 많음 배수(talkativeness) | **guild 정책** | MEMBER 채널의 사회행동 빈도 계수. `channel_ai_routing_policy.responseMode`에 저장 금지(ADR 0009) |
| 운영 롤아웃(shadow/canary/live) | **guild 정책** | 모드 전환의 점진 배포 단계(채널 종류와 별개 축) |
| 채널별 AI 프로필·행동 버전 | channelai | 정체성/설정 SSOT(ADR 0009 REUSE) |

- **채널 모드**(ASSISTANT/MEMBER/OFF)는 "무엇처럼 동작하는가", **롤아웃**(shadow/canary/live)은
  "얼마나 넓게 켜는가"로 축이 다르다. 예: MEMBER 채널을 shadow로 시작해 관찰만 하다 live 전환.

## 읽기 우선순위 (단일 체인)

```
participation 이 유효 정책을 조회할 때:
  1. guild 정책: 활성화 / 채널 모드 / talkativeness / 제외 / 롤아웃 단계
  2. channelai: 채널 AI 프로필·행동(있으면)
결과: 하나의 EffectiveGuildPolicyView 로 합성 — participation 은 이 단일 뷰만 본다
```

- 중복 금지: 동일 의미(활성화/모드/talkativeness)를 guild와 channelai 양쪽에 저장하지 않는다.
- ASSISTANT 모드 채널은 participation 관점에서 항상 SPEAK로 평가된다(무조건 답변).

## 불변식

1. 길드 마스터 스위치가 off거나 채널 모드가 OFF면 participation은 IGNORE만 선택한다.
2. 모드·talkativeness·롤아웃의 SSOT는 guild 정책 한 곳이며 channelai는 채널 프로필만 소유한다.
3. 정책 편집 입구는 웹 관리 대시보드가 유일하다(디스코드 명령으로 변경 금지).
4. participation은 합성된 단일 `EffectiveGuildPolicyView`만 읽는다(두 소스 직접 조회 금지).
