# NEXA 보안 incident runbook

- 작업: NEXA-P17-T023 (`human_gate: true`, documentation) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [threat-model.md](./threat-model.md), [key-rotation.md](./key-rotation.md),
  [redaction-contract.md](./redaction-contract.md), [red-team-report.md](./red-team-report.md)

NEXA(사람처럼 참여하는 AI 멤버) 운영 중 발생할 수 있는 보안 사고별 **즉시 조치·증거 보존·복구·통지** 절차를
정의한다. 모든 사고는 (1) 봉쇄(contain) → (2) 증거 보존(preserve) → (3) 복구(recover) → (4) 통지(notify) →
(5) 사후(postmortem) 순으로 처리한다.

## 0. 공통 통제 (책임자 명시 — acceptance)

| 통제 | 수단 | 책임자 |
| --- | --- | --- |
| **kill switch** | NEXA 참여 즉시 중단(killSwitchEngaged → 모든 비-침묵 행동 차단, `PolicySafetyConstraint`) | 운영 on-call |
| **model rollback** | LIVE 모델을 직전 APPROVED 로 되돌림(`ShadowModelRegistry`, 변조 시 ACTIVE 박탈) | ML 책임자 |
| **consent freeze** | 동의 처리 동결 — 신규 관찰/기억/학습 중단(동의 철회와 동일 경로) | 개인정보 책임자 |
| **user 통지** | 영향 사용자·길드 관리자 통지 | 보안 책임자(법무 협의) |

위 4개 통제는 사고 종류와 무관하게 즉시 가용해야 한다. on-call 은 봉쇄 단계에서 필요한 통제를 즉시 발동한다.

## 1. 데이터 누출 (PII·가명 역연결·비밀)

- **봉쇄**: 누출 경로 차단(해당 엔드포인트/통합 비활성), 노출 비밀이면 [key-rotation.md] 로 즉시 회전, kill switch 검토.
- **증거 보존**: 누출 시각·범위·경로를 audit hash 체인과 함께 스냅샷(원문 비저장 원칙 유지 — 메타만).
- **복구**: 회전된 키 배포, 영향 레코드 tombstone, redaction 회귀 점검.
- **통지**: 개인정보 책임자 판단으로 영향 사용자·관리자 통지(법적 의무 시 기한 준수).

## 2. 과다 발화 (도배·점유율 폭주)

- **봉쇄**: kill switch 또는 해당 길드 채널 mute → REACT/SPEAK 차단. share cap 강제 확인.
- **증거 보존**: 발화 빈도·점유율·decision log(raw vs override) 보존.
- **복구**: talkativeness/share cap 재조정, 필요 시 model rollback.
- **통지**: 영향 길드 관리자에 상황·완화 통지.

## 3. prompt leak (시스템 지침·정체성 커널 노출)

- **봉쇄**: 누출 후보 경로 차단, `SecretDisclosureCritic` 게이트 강화 검토.
- **증거 보존**: 누출 트리거(injection 패턴) 보존(원문 비밀은 enum/메타만 — 누출 원문 재기록 금지).
- **복구**: 가드레일/critic 패턴 보강, 필요 시 model rollback.
- **통지**: 보안 책임자 내부 보고. 비밀 노출이면 §1 키 회전 병행.

## 4. 잘못된 기억 (오염·모순·삭제 누락)

- **봉쇄**: 해당 사용자/길드 기억 사용 동결(consent freeze 경로), 오염 의심 기억 격리.
- **증거 보존**: 기억 provenance·생성 경로·관련 학습 레코드 보존.
- **복구**: 오염 기억 tombstone·삭제 전파, poisoning 탐지(T019) 재실행으로 학습 입력 재격리.
- **통지**: 영향 사용자에 정정·삭제 사실 통지.

## 5. 모델 변조 (artifact tamper)

- **봉쇄**: 즉시 **model rollback**(직전 APPROVED), 변조 artifact REJECTED 로 영구 차단.
- **증거 보존**: artifact 서명·hash 검증 로그(`ArtifactIntegrityVerifier` 실패 기록), 변조 manifest 보존.
- **복구**: 서명키 회전([key-rotation.md]) 검토, 무결성 재검증 후 재배포.
- **통지**: ML·보안 책임자 보고. 변조 영향 발화가 있었으면 §1/§2 병행.

## 6. 사후 (postmortem)

모든 사고는 24~72시간 내 무비난 postmortem: 타임라인, 근본 원인, 영향, 항구 조치, 잔여 위험을 기록하고
[red-team-report.md] 의 finding 과 교차 점검한다. critical 은 닫힐 때까지 추적한다(P17-T025 승인 게이트 입력).
