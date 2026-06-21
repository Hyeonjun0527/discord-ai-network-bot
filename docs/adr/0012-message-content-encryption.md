# ADR 0012: 원문 저장 암호화 전략 — 애플리케이션 레벨 봉투 암호화

- 상태(Status): 제안됨 (Proposed) — **인간 승인 게이트 대기** (NEXA-P02-T011, `human_gate: true`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007](./0007-nexa-social-member-context.md)
- 근거: [data-categories.md](../../specs/product-v2/nexa/data-categories.md),
  [retention-policy.md](../../specs/product-v2/nexa/retention-policy.md),
  [deletion-propagation.md](../../specs/product-v2/nexa/deletion-propagation.md)

## 맥락 (Context)

[data-categories.md](../../specs/product-v2/nexa/data-categories.md)에서 메시지 원문은 **High** 등급
이며 [retention-policy.md](../../specs/product-v2/nexa/retention-policy.md)는 원문을 **기본 비영속**
(장면 윈도우 한정)으로 둔다. 그러나 단기라도 원문이 저장되는 구간(장면 버퍼, 재시도 큐)이 존재할
수 있어, 그 저장의 암호화 방식을 정한다.

### 검토한 대안

| 방안 | 검색 | 삭제/키회전 | 백업복원 위험 | 평가 |
| --- | --- | --- | --- | --- |
| A. 평문 저장 | 쉬움 | — | **원문 노출** | **명시적 거절** — High 데이터 평문 금지 |
| B. DB 컬럼 암호화(TDE) | 가능 | 키가 DB에 근접, 회전 무거움 | 백업에 키 동반 위험 | 부분 — 키 분리 약함 |
| **C. 애플리케이션 레벨 봉투 암호화** | 제한(원문 검색은 임베딩이 담당) | 앱이 키 소유·회전, 삭제=행 또는 데이터키 폐기 | 백업에 평문 없음 | **채택** |
| D. 별도 blob 스토어 | 분리 | 추가 인프라 | 분산 | 과함(원문 단기엔 불필요) |

## 결정 (Decision)

**원문이 저장되는 경우 애플리케이션 레벨 봉투 암호화(envelope encryption)를 쓴다. 평문 저장은
거절한다(방안 C).**

- 원문은 데이터키(DEK)로 AES-GCM 암호화하고, DEK는 마스터키(KEK, 환경/KMS 분리)로 감싼다.
- 원문 검색은 하지 않는다 — 의미 검색은 임베딩(별도, 원문 비포함)이 담당하므로 암호문 검색 불요.
- **삭제**: 행 삭제 또는 DEK 폐기로 비가역 무효화([deletion-propagation.md](../../specs/product-v2/nexa/deletion-propagation.md)).
- **키 회전**: KEK 회전 시 DEK 재암호화(원문 재암호화 불요). 회전 주기는 운영 정책으로.
- 키는 백업에 평문으로 동반되지 않는다(KEK는 별도 보관).

### 비-목표

- 원문 전문 검색 — 하지 않는다(임베딩 검색으로 충분).
- 구체 KMS 제품 선택 — 운영 환경 결정(env 기반 KEK로 시작, KMS 연동은 후속).

## 결과 (Consequences)

**장점**: 평문 미저장, 키-데이터 분리, 삭제·키회전이 가벼움, 백업에 평문 없음.
**단점**: 앱이 암복호 책임(성능·키 관리 비용), 원문 검색 불가(설계상 의도).

## 인간 승인 상태 (Approval)

- `NEXA-P02-T011`, `human_gate: true`, `risk` 높음(보안).
- acceptance("검색 요구와 삭제·키 회전·백업 복원 위험이 비교되고 평문 저장은 명시적 거절 또는
  승인된다") 충족 — 대안 표에서 4축 비교, **평문 저장 명시적 거절**.

## 미해결 질문

- KEK 보관처(env vs KMS)와 회전 주기 운영 기준.
- 장면 버퍼/재시도 큐의 원문 보존 시간 상한(retention-policy와 정합).
