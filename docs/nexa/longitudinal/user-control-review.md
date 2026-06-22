# 장기 사용자 통제·탈퇴 UX 검증 (NEXA-P19-T021)

- 작업: NEXA-P19-T021 (`kind: experiment`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T020
- 상위: [cohort-design](./cohort-design.md), [user-opt-out](../../../specs/product-v2/nexa/user-opt-out.md),
  [deletion-propagation](../../../specs/product-v2/nexa/deletion-propagation.md),
  [consent-model](../../../specs/product-v2/nexa/consent-model.md),
  [training-deletion](../security/training-deletion.md)

## 목적·범위

장기 사용자가 NEXA 와의 데이터·참여를 **스스로 통제하고 탈퇴**할 수 있는지 사용자 테스트한다(deliverable T021).
다섯 흐름을 본다: mute · opt-out · memory reset · 데이터 export/delete · complaint. 핵심 합격 기준(acceptance):
**통제가 숨겨져 있거나 "NEXA 와 대화해야만" 끌 수 있는 구조가 아니다** — 통제권은 대화 밖 명시 경로에 있다.

## 통제 흐름과 실제 경로

| 흐름 | 사용자 경로 | 뒷받침 코드/계약 |
| --- | --- | --- |
| **mute(즉시 정지)** | 웹 대시보드/관리 토글로 채널·길드 NEXA 정지. SPEECH_ONLY/OBSERVE_AND_SPEECH 2수준. | `nexa_channel_mute`(V68), `nexa_guild_kill_switch`(V67) |
| **opt-out** | 멤버가 관찰/학습/외부 GLM/live 를 **목적별로** 끈다(포괄 동의 금지). | `nexa_member_onboarding_consent`(V66), [consent-model](../../../specs/product-v2/nexa/consent-model.md) |
| **memory reset** | 그 멤버의 사회 상태·기억을 초기화한다. | `ForgetMemberSocialStateService` |
| **데이터 export/delete** | 출처(event id) 기반으로 파생 기억까지 연쇄 삭제·무효화. | `CascadeMemoryRedactionService`, `data/deletion.py`, [deletion-propagation](../../../specs/product-v2/nexa/deletion-propagation.md) |
| **complaint** | 신고/이의 제기 경로(운영 incident 연계). | [incident-response](../security/incident-response.md) |

## acceptance — 통제가 숨겨져 있거나 대화로만 끄는 구조가 아니다

검증 체크리스트(사용자 테스트 관점):

1. **대화 밖 명시 경로**: mute·opt-out·reset·export/delete 는 **웹 대시보드/명시 토글**로 도달한다 — NEXA 에게
   말을 걸거나 설득해야만 꺼지는 경로가 아니다(정책: 정책 설정은 웹 대시보드 전용).
2. **fail-closed 기본값**: 동의 컬럼은 기본 FALSE — 봇 추가·버튼 클릭만으로 어떤 목적도 켜지지 않는다(V66).
   즉 "끄기" 가 기본값이고 "켜기" 가 명시 행위다.
3. **즉시성**: mute 는 발화·예약·전송을 즉시 멈춘다(OBSERVE_AND_SPEECH 는 신규 관찰까지 차단). 끄는 데 대화·
   유예가 필요 없다.
4. **철회의 실효성**: opt-out·삭제는 파생물(기억·임베딩)까지 연쇄 전파된다(deletion-propagation) — 표면만 끄고
   뒤에 남는 구조가 아니다.
5. **숨김 금지**: 통제 항목이 대시보드 상단 명시 영역에 있고, 위험 동작(삭제)은 확인을 받되 **도달 자체가
   어렵지 않다**(button-soup 가 아니라 작업 카드 — 대시보드 UX 재설계 일관).

## 결과·후속

- 위 경로는 코드/계약으로 존재한다. 본 검증은 그 경로가 **숨겨지지 않고 대화 독립적**인지 UX 관점에서 확인한다.
- 통제 UX 의 실제 사용자 테스트(다양한 단말 스크린샷·실 흐름)는 운영 게이트(human_gate)에서 수행하며, 본 문서는
  통제권의 구조적 합격 기준을 고정한다.
