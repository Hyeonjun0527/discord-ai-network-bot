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
- **MEMBER 모드 직접 호명 = 반드시 응답**: MEMBER 채널은 평소 장면 기반으로 눈치껏 끼어들지만(IGNORE/WAIT/REACT/SPEAK),
  **사용자가 니아를 직접 호명(@멘션 · 이름 호명) 하거나 니아 발화에 reply 하면 반드시 응답한다**. judge/policy 가
  그 턴을 IGNORE 로 판단하더라도 무응답으로 끝내지 않는다 — participation 이 SPEAK 로 소유하거나, participation 이
  침묵하면 discord 어댑터가 legacy 직접응답 경로(mention/name-ask)로 **폴백**해 답한다. "관찰만 하다 부르면 반드시
  답하는 사람다움"이 MEMBER 모드의 계약이다.
- **호명 판정은 문자열 매칭이 아니라 judge 의 의미 이해로 한다**: 이름 호명은 표기 변형이 무한하다("니아야", "니아",
  로마자 "nia" · "nia야" · "nia ya", 오타·띄어쓰기·자모 분리). regex 로 이를 열거하는 접근은 항상 뒤처지므로,
  **1차 판정은 participation judge(LLM)** 가 원문 trigger 를 읽고 "지금 이 사람이 니아를 부르는가"를 의도로 판단한다
  (판정 규칙·few-shot 은 `NexaIdentity`/`CloudRawParticipationJudge` 프롬프트에 SSOT 로 둔다). discord 어댑터의
  regex(`niaDirectAddressPrompt`)는 judge 가 꺼졌거나 실패했을 때만 쓰는 값싼 폴백 fast-path 이지 유일한 감지원이
  아니다. 3인칭 언급("니아는 원래…")은 호명이 아니다.

## 불변식

1. 길드 마스터 스위치가 off거나 채널 모드가 OFF면 participation은 IGNORE만 선택한다.
2. 모드·talkativeness·롤아웃의 SSOT는 guild 정책 한 곳이며 channelai는 채널 프로필만 소유한다.
3. 정책 편집 입구는 웹 관리 대시보드가 유일하다(디스코드 명령으로 변경 금지).
4. participation은 합성된 단일 `EffectiveGuildPolicyView`만 읽는다(두 소스 직접 조회 금지).
5. MEMBER 모드에서 **직접 호명(멘션/이름 호명)·니아 발화 reply 는 반드시 응답**한다(judge IGNORE 여도 폴백으로 답한다).
   OFF/마스터 off 만 무응답이 허용된다(불변식 1).
