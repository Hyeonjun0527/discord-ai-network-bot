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
- **MEMBER 모드의 현재 직접 호명은 응답 계약이다**: 현재 턴의 @멘션·이름 호명·니아 발화 reply가 니아를 향하고
  그 뒤에 정정·철회·수신자 변경·중단 요청이 없으면 participation judge는 SPEAK를 선택한다. 반복 호명도 침묵 사유가
  아니며, 니아는 같은 인사를 다시 시작하지 않고 반복된 호출을 짧고 자연스럽게 받아준다. 단, 더 최신의 정정·철회·수신자
  변경·중단 요청은 이전 호명보다 우선한다. 예를 들어 니아를 불렀다가 다른 구성원에게 한 말이라고 바로잡은 장면에서는,
  니아가 아직 말하지 않았으면 침묵하고 이미 잘못 끼어들었으면 짧게 인정한 뒤 물러나는 판단이 자연스럽다.
- **real-send 롤아웃은 FINAL judge가 한 턴을 소유한다**: `IGNORE`/`WAIT`/`REACT`/`SPEAK`/`CANCEL`뿐 아니라
  rate limit·consent/safety 차단·judge 실패도 participation의 최종 결과다. Discord 어댑터는 의미적 침묵 뒤에
  legacy mention/name/auto-respond 경로로 폴백 발화하지 않는다. CANARY/LIVE에서 FINAL judge가 아닌 설정은 legacy로
  되돌아가지 않고 해당 턴을 fail-closed한다. `SHADOW_PREDICT`는 장면 평가·비교만 수행하고 기존 명시 호출·자동응답
  계약을 보존한다.
- **예약 행동은 판단 당시 rollout 권한을 보존한다**: SPEAK/REACT 예약은 생성 시점의 모드를 함께 저장하고, 실행 시에는
  그 권한과 현재 채널 모드의 더 좁은 교집합만 쓴다. 따라서 shadow에서 기록용으로 예약된 행동이나 이 변경 전의 예약은
  이후 채널을 CANARY/LIVE로 승격해도 실제 발화 권한을 얻지 않는다.
- **호명은 문자열 열거가 아니라 judge의 장면 이해로 판단한다**: 이름 표기 변형을 regex로 계속 추가하지 않는다.
  participation judge(LLM)가 원문 장면과 few-shot을 바탕으로 현재 발화가 니아를 향하는지, 이미 다른 사람에게 자연스럽게
  이어지는지, 니아가 물러나야 하는지를 함께 판단한다. Discord 어댑터의 `niaDirectAddressPrompt`는 participation이
  비활성인 기존 호출 경로의 fast-path일 뿐, 활성 participation의 의미 판단이나 발화 강제가 아니다.

## 불변식

1. 길드 마스터 스위치가 off거나 채널 모드가 OFF면 participation은 IGNORE만 선택한다.
2. 모드·talkativeness·롤아웃의 SSOT는 guild 정책 한 곳이며 channelai는 채널 프로필만 소유한다.
3. 정책 편집 입구는 웹 관리 대시보드가 유일하다(디스코드 명령으로 변경 금지).
4. participation은 합성된 단일 `EffectiveGuildPolicyView`만 읽는다(두 소스 직접 조회 금지).
5. MEMBER 모드에서 현재 직접 호명(멘션/이름 호명)·니아 발화 reply가 철회되지 않았으면 judge는 SPEAK를 선택한다.
   judge 출력 형식 오류나 공급자 실패가 발생해도 현재 직접 호명은 single judge 내부의 짧은 SPEAK로 축소하고, 중단 요청이면
   한 번만 인정한 뒤 물러난다. 더 최신의 정정·철회·수신자 변경과 안전 차단 뒤에는 legacy 폴백을 하지 않는다. SHADOW는
   이 불변식을 관찰하되 사용자 대화의 기존 응답 계약을 바꾸지 않는다.
