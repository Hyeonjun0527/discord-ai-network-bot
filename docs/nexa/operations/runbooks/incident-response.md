# Runbook — NEXA incident 대응 (P18-T024)

당직자(on-call)용. **코드 지식 없이** 사고를 분류·대응·복구하는 절차. 상위: [release-checklist](../release-checklist.md).
즉시 정지 동작은 [halt-and-recover](halt-and-recover.md).

## 0. 즉시 안전 (먼저 멈춘다)

사고가 의심되면 원인 분석 전에 영향 범위를 멈춘다:
- 한 채널 → channel mute(OBSERVE_AND_SPEECH).
- 한 길드 → 길드 kill switch engage.
- 전역 의심 → 영향 길드들을 순차 kill + 에스컬레이션.

## 1. 사고 분류

| 신호 | 분류 | 우선순위 |
| --- | --- | --- |
| 같은 말 두 번 전송(duplicate send) | 신뢰성(0-tolerance) | P1 |
| 동의/삭제 관련 오류(privacy error) | privacy(0-tolerance) | P1 |
| 묵은(stale) 메시지 폭주 | 신뢰성 | P2 |
| 정책 latency·GLM 가용성 SLO 위반 | 신뢰성(error budget) | P2 |
| 과다 발화·complaint | 품질/안전 | P2 |

## 2. P1 — duplicate send

1. 영향 길드 kill switch engage(추가 전송 차단).
2. action audit 에서 중복 transition 확인(대시보드 audit 뷰). 원인이 복원/재시작이면 [backup-restore](../backup-restore.md)
   의 복원 규칙 위반 여부 점검.
3. error budget([slo.md](../slo.md)) duplicate send 는 0-tolerance — incident 기록 필수.
4. 복구: 원인 해소 후 disengage. 같은 사고 재발 방지 확인 전 재진입 금지.

## 3. P1 — privacy error / 데이터 삭제 요청

1. **즉시 OFF**: 영향 길드는 자동 중단([canary-auto-halt](../canary-auto-halt.md))으로 이미 OFF 일 수 있다. 아니면
   수동 kill.
2. **동의 철회·삭제**: 동의 철회는 즉시 pending 취소 + 생성 content 제거(ConsentRevocation). 삭제 요청은
   deletion SLA ≤ 24h([slo.md](../slo.md)) 안에 처리.
3. **주의**: 철회된 동의는 설정 rollback 으로 되돌릴 수 없다(P18-T016) — 다시 켜려면 사용자 명시 재동의.
4. privacy incident 는 별도 기록·보고 절차를 따른다.

## 4. P2 — SLO 위반 / 과다 발화

1. error budget 잔량 확인([slo.md](../slo.md)). budget 100% 소진이면 자동 강등 대상.
2. 과다 발화면 max share 초과 — 자동 중단이 SHADOW 강등했는지 확인, 아니면 수동 강등.
3. 정책 serving 장애면 [EXP-policy-service-chaos](../../experiments/EXP-policy-service-chaos.md) 의 fallback-to-silent
   가 작동 중인지(IGNORE 로 안전) 확인 — 발화로 떨어지지 않았는지 audit 확인.

## 5. 사후 (post-incident)

- 타임라인·원인·영향(가명 단위)·error budget 소모 기록.
- 재발 방지 액션. 필요 시 SLO·alert 임계·자동 중단 조건 조정([slo.md](../slo.md), [alerts.md](../alerts.md), [canary-auto-halt](../canary-auto-halt.md)).
- canary 중 사고면 [canary-plan](../canary-plan.md) 진입 조건 재검토.
