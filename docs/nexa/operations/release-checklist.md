# NEXA release checklist·runbook (P18-T024)

NEXA 배포 전후 검증·운영 절차의 SSOT. **당직자(on-call)가 코드 지식 없이** 핵심 중단·복구를 수행할 수 있도록
runbook 으로 연결한다(acceptance T024). 상세 절차는 [runbooks/](runbooks/) 참조.

## 배포 전 (pre-deploy)

- [ ] central build 그린: `./scripts/nexa-verify.sh central` (ArchUnit self-test 0 실패 포함).
- [ ] docs 그린: `./scripts/nexa-verify.sh docs` (task graph·링크·fixture).
- [ ] Flyway 마이그레이션 additive only(V1~V68 무변경, 신규는 V69+). 기존 마이그레이션 git diff 0.
- [ ] SLO 정의 최신([slo.md](slo.md)) — error budget·임계가 alert·자동 중단과 일치.
- [ ] canary 진입이면 [canary-plan](canary-plan.md) 의 **사용자 동의·AI 공개** 확인 서명.
- [ ] kill switch·channel mute·rollback 경로가 staging 에서 동작 시연됨(P18-T025 게이트).

## 배포 후 (post-deploy)

- [ ] dashboard 확인: 관측성 metric·correlation 정상([metrics.md](metrics.md)).
- [ ] alert 무발화(정상) 또는 의도된 것만([alerts.md](alerts.md)).
- [ ] 새 길드는 기본 OFF([ShadowMode](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/domain/model/shadow/ShadowMode.kt) DEFAULT) — 명시 승인 없이 발화 0 확인.
- [ ] 백업 정상 작동([backup-restore.md](backup-restore.md)).

## 핵심 운영 동작 (코드 지식 불요 — runbook 으로)

| 상황 | 절차 |
| --- | --- |
| NEXA 가 너무 많이 말함 | [halt-and-recover](runbooks/halt-and-recover.md) — 채널 mute 또는 길드 kill |
| 특정 채널만 끄기 | [halt-and-recover](runbooks/halt-and-recover.md) — channel mute(발화만/관찰까지) |
| 정책 모델 이상 | [halt-and-recover](runbooks/halt-and-recover.md) — model rollback(previous) |
| 설정 잘못 변경 | [halt-and-recover](runbooks/halt-and-recover.md) — settings rollback |
| 사고 발생(privacy/중복 전송) | [incident-response](runbooks/incident-response.md) |
| 데이터 삭제 요청 | [incident-response](runbooks/incident-response.md) — deletion SLA |
| DB 복원 | [backup-restore.md](backup-restore.md) |

## rollback·데이터 삭제·incident

- **rollback**: 단계 강등(LIVE→SHADOW→OFF), 모델 rollback, 설정 rollback. 자동 중단은 [canary-auto-halt](canary-auto-halt.md).
- **데이터 삭제**: 동의 철회는 즉시 pending 취소+content 제거(ConsentRevocation). 삭제 SLA ≤ 24h([slo.md](slo.md)).
  단순 설정 rollback 으로 철회를 되돌릴 수 없음(P18-T016).
- **incident**: [incident-response](runbooks/incident-response.md) 의 분류·대응·사후.
