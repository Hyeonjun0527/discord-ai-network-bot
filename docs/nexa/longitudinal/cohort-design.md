# 30일·90일 longitudinal cohort 설계 (NEXA-P19-T001)

- 작업: NEXA-P19-T001 (`kind: documentation`, `human_gate: true`, `risk: high`) · 선행: NEXA-P18-T025
- 상위 게이트: P19 종료 게이트 — "30/90일 데이터로 일관성·침묵·관계·기억·불쾌감·차단률을 평가하고,
  명시적 인간 승인 없이는 온라인 학습이나 다길드 LIVE 출시를 하지 않는다."
- 관련: [ADR 0007 NEXA social member context](../../adr/0007-nexa-social-member-context.md),
  [EXP-30day-simulation](../experiments/EXP-30day-simulation.md),
  identity consistency metric [`identity_consistency.py`](../../../ml/social-policy/src/nexa_policy/eval/identity_consistency.py),
  relationship consistency metric [`relationship_consistency.py`](../../../ml/social-policy/src/nexa_policy/eval/relationship_consistency.py)

## 목적

수주·수개월 사용에서 NEXA 가 **장기적으로 사람처럼 일관**되는지(정체성·관계·기억·침묵 품질) 측정하기 위한
관찰 코호트를 설계한다. 이 문서는 **설계**다 — 실제 운영 데이터 수집·온라인 학습·다길드 LIVE 출시는 별도의
인간 승인 게이트가 필요하다(이 문서가 그 승인을 대신하지 않는다).

## acceptance — 제품 사용자를 속이지 않고 AI 정체성과 연구 참여를 고지한다

코호트의 모든 측정은 다음 정직성 전제 위에서만 유효하다. 하나라도 충족 못하면 그 길드는 코호트에서 제외한다.

1. **AI 정체성 고지**: NEXA 가 사람이 아니라 AI 멤버임을 길드 관리자와 일반 사용자 모두에게 명시한다
   (봇 프로필·온보딩·privacy 안내). "사람인 척" 하지 않는다.
2. **연구 참여 고지·옵트인**: 길드는 "장기 품질 관찰 코호트"에 참여한다는 사실을 명시적으로 옵트인한다
   (관리자 동의 + 멤버 onboarding consent, V66 `nexa_member_onboarding_consent`). 옵트인하지 않은 길드는
   관찰 대상이 아니다(기본 미참여).
3. **언제든 철회**: 코호트 철회·mute(V68)·kill switch(V67)·memory reset·데이터 export/delete 가 NEXA 와
   대화하지 않고도 가능하다(통제가 숨겨지면 안 됨 — P19-T021 검증 대상).

## 옵트인 길드·비교군

| 구분 | 정의 | 비고 |
| --- | --- | --- |
| **treatment** | NEXA participation(MEMBER 모드)을 켠 옵트인 길드 | 사람처럼 참여(SPEAK/REACT/IGNORE) |
| **control(within-guild)** | 같은 길드의 ASSISTANT 채널(무조건 답변)만 쓰는 구간 | participation 미적용 비교 baseline |
| **holdout(unseen)** | 코호트에 포함하되 모델 적응 실험(P19-T004~T007)에 노출하지 않는 길드 | 일반화·과적응 점검(unseen guild) |

- 표본은 **소수 옵트인 길드**로 시작한다(다길드 LIVE 출시 금지 — 종료 게이트). 길드 규모/tempo/언어가
  다양하도록 의도적으로 분산해 부분군 붕괴(generalization.py)를 본다.
- 길드·사용자는 **가명(pseudonym)** 으로만 식별한다(guild-scoped key, cross-guild 연결 금지 — ADR 0007).

## 측정 기간

| 마일스톤 | 시점 | 핵심 측정 |
| --- | --- | --- |
| baseline | D0 | 초기 정체성·관계 0점, 설문 baseline |
| short-term | D30 | identity/relationship consistency, 침묵 적절성(FIR/MIR), 불쾌감·차단률 |
| long-term | D90 | 위 + 기억 노화·압축 품질(provenance·삭제 가능성·현재성), 관계 안정성 |

- 30일·90일 두 지점을 **고정**해 코호트 간 비교 가능성을 확보한다. 측정은 합성/실데이터 모두 동일 metric
  코드([identity_consistency.py], [relationship_consistency.py])로 한다.

## 중도 이탈(attrition) 처리

- **right-censoring**: 길드가 D90 전에 철회/이탈하면 마지막 관찰 시점까지만 집계하고 이후를 결측으로 둔다
  (이탈을 "나쁜 결과"로 단정하지 않음 — 이유 미상). P12 시간축 censoring 정신과 일관.
- **이탈 사유 코딩**: 자발 철회 / 관리자 제거 / 비활성 / complaint 로 닫힌 코드로만 기록(자유 텍스트 심리
  판정 금지, observable-state-policy). 이탈률 자체를 품질 지표로 보고한다(숨기지 않음).
- **생존 편향 가드**: D90 까지 남은 길드만으로 품질을 보고하면 과대평가된다 → 이탈 포함 분모로 함께 보고한다.

## privacy

- 운영 데이터 미접근 전제(이 P19 작업군은 합성 fixture 만 사용). 실제 코호트 운영 시에도 공개 artifact 에는
  **개인정보 없는 합성 fixture·metric 코드·contract** 만 포함한다(P19-T022).
- 메시지 원문·실제 user id 는 공개 산출물에 포함하지 않는다. 저장은 가명·집계 신호만(ADR 0007·0012).
- 삭제/철회 시 cascade redaction(socialmemory) 으로 관련 기억·관계 상태가 함께 지워진다.

## 설문(survey) 일정

| 시점 | 대상 | 내용(예시 축) |
| --- | --- | --- |
| D0 | 옵트인 관리자·참여 멤버 | 기대치, AI 인지(사람 아님 확인), 통제 위치 인지 |
| D30 | 참여 멤버 | 사람다움 체감, 불쾌감/끼어듦, 침묵 적절성, 통제 사용 경험 |
| D90 | 참여 멤버·관리자 | 장기 일관성 체감, 관계 자연스러움, 기억 정확성, 철회 의향 |

- 설문은 **사용자 심리를 정답으로 강요하지 않는다** — 체감 보고일 뿐, metric 의 ground truth 로 쓰지 않는다
  (relationship consistency metric 의 acceptance 와 일관). proxy reward validation(P19-T012)에서만
  블라인드 인간 평가를 상관 분석 입력으로 쓴다.

## 금지·경계

- 이 설계는 **온라인 학습·다길드 LIVE 출시를 승인하지 않는다**. 그것들은 별도 인간 승인 게이트(P19-T024 v1
  readiness, GO/NO-GO/EXTEND-CANARY)를 통과해야 한다.
- engagement(대화량·멘션 수) 극대화를 목표로 삼지 않는다(reward hacking 경계 — reward-contract.md).
- 코호트 측정값은 **추정**이며 운영 품질 보증이 아니다.
