# NEXA 위협 모델 (STRIDE/LINDDUN)

- 작업: NEXA-P17-T001 (`human_gate: true`, security) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [logging-baseline.md](./logging-baseline.md), [redaction-contract.md](./redaction-contract.md),
  [event-store-redaction.md](./event-store-redaction.md),
  [external-model-data.md](../../../specs/product-v2/nexa/external-model-data.md),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md),
  [data-lineage.md](../../../specs/product-v2/nexa/data-lineage.md),
  [deletion-propagation.md](../../../specs/product-v2/nexa/deletion-propagation.md)

NEXA(사람처럼 참여하는 AI 멤버) 시스템의 자산·신뢰 경계·공격자·데이터 흐름을 정리하고,
STRIDE/LINDDUN 관점 위험과 완화책을 매핑한다. P17 보안 하드닝 task(T002~T013)는 이 문서가
식별한 위험을 코드/테스트로 닫는다.

## 1. 자산 (asset)

| 자산 | 분류 | 비고 |
| --- | --- | --- |
| Discord 메시지 원문 | 최고 민감 | event store 에 무저장(redaction). 처리 중 메모리에만 존재 |
| 가명 매핑(snowflake ↔ pseudonym) | 민감 | scoped pseudonymizer salt 로 일방향. 역연결 시 PII 노출 |
| 사회 기억(socialmemory: 관계·일화) | 민감 | 가명 기반 파생물. 삭제 전파 대상 |
| 학습 dataset / ML artifact | 민감 | 재가명화·redaction 후 export. 삭제 시 tombstone |
| system prompt·전역 프롬프트·identity kernel | 비밀 | 응답·로그·payload 에 노출 금지(T003) |
| Z.AI(GLM) API key·durable token | 비밀 | env/헤더로만. 로그·payload 비저장(T013) |
| 메시지 at-rest 암호화 키(`NEXA_FIELD_ENC_KEY`) | 비밀 | 회전 가능해야 함(T005) |
| admin dashboard 세션·durable-token | 비밀 | RBAC 게이트(T006). 고위험 권한 분리 |
| consent 스냅샷 | 민감 | 철회 시 즉시 처리 차단(T010) |

## 2. 신뢰 경계 (trust boundary)

```
[Discord 사용자] --(메시지)--> [Discord/JDA 경계 ①] --> [central 처리 ②]
                                                          |
   [admin 웹 대시보드 ③] --(durable-token/OAuth)----------+
                                                          |
   [central DB ④] <--(at-rest 암호화)---------------------+
                                                          |
   [Z.AI / GLM 외부 모델 ⑤] <--(allowlist payload)---------+
                                                          |
   [ML artifact / dataset ⑥] <--(재가명화·redaction)-------+
```

| # | 경계 | 신뢰 전이 | 주요 위험 |
| --- | --- | --- | --- |
| ① | Discord/JDA → central | 비신뢰 입력(content) → 처리 | prompt injection, snowflake 누출 |
| ② | central 내부 처리 | 가명·최소 맥락 | 비밀 노출, 과도 수집 |
| ③ | admin 대시보드 → central | 인증된 관리자 | 권한 상승, stale 덮어쓰기 |
| ④ | central ↔ DB | at-rest 암호화 | 키 유출 시 일괄 복호 |
| ⑤ | central → Z.AI | 외부 전송 | 원문/PII/비밀 외부 누출 |
| ⑥ | central → ML artifact | 파생 데이터 | 가명 역연결, 삭제 미전파 |

## 3. 공격자 (actor)

- **악성 Discord 멤버**: 메시지 content 로 system prompt 를 덮어쓰거나(injection) 비밀을 끌어내려 시도.
- **호기심 많은 관리자**: 자기 권한 범위를 넘어 다른 사용자 content·내부 비밀을 export.
- **외부 모델 측(Z.AI) 또는 중간자**: 전송 payload 에서 PII·snowflake·키를 수집.
- **내부 운영자 실수**: 로그·예외 메시지에 원문/키를 흘림.
- **데이터 주체(사용자)**: 삭제·동의 철회 후에도 처리가 계속되는지 검증(정당한 권리 행사).

## 4. 데이터 흐름 (data flow)

1. 메시지 수신(①) → 가명화·관찰 가능성 판정 → event store(원문 미저장, ④).
2. participation 결정(SPEAK) → speech 가 **최소 맥락 패킷**(상한 강제) 구성.
3. payload allowlist serializer(T004) → minimizer(마지막 방어선) → Z.AI 전송(⑤).
4. 응답 critic(T003) 비밀 노출 검사 → Discord 전송(①).
5. 비동기: 관찰 이벤트 → feature → dataset export(재가명화·redaction, ⑥).
6. 삭제/철회: event redaction → projection 무효화 → memory 삭제 → dataset tombstone(T009/T011).

## 5. STRIDE 위험 ↔ 완화 매핑

| STRIDE | 위험 | 완화 (task) |
| --- | --- | --- |
| **S**poofing | 위조 관리자 요청 | OAuth/durable-token + RBAC 권한 분리(T006) |
| **T**ampering | stale 대시보드가 최신 설정 덮어씀 | optimistic lock + before/after audit(T007) |
| **R**epudiation | 외부 전송 사후 부인 | canonical payload hash 감사(T012), audit hash 체인 |
| **I**nformation disclosure | system prompt·키·snowflake 누출 | content 지침 격리(T002), 비밀 비노출 critic(T003), allowlist serializer(T004), 로그 redaction(T013) |
| **D**enial of service | 무제한 맥락·반복 외부 호출 | 맥락 패킷 상한(P14-T004), cloud budget(기존) |
| **E**levation of privilege | injection 으로 정책 재정의 | content=quoted scene data(T002), 단일 관리자에 전권 금지(T006) |

## 6. LINDDUN(프라이버시) 위험 ↔ 완화 매핑

| LINDDUN | 위험 | 완화 (task) |
| --- | --- | --- |
| **L**inkability | 운영 가명 ↔ 학습 가명 연결 | 용도별 salt 재가명화(P10-T014) |
| **I**dentifiability | snowflake/원문 외부 누출 | minimizer + allowlist(T004), redaction(T013) |
| **N**on-repudiation(프라이버시) | 동의 없는 처리의 추적성 | consent 스냅샷 기록(T012) |
| **D**etectability | 처리 여부 관측 | observable-state-policy(기존) |
| **D**isclosure of information | 외부 모델로 과도 전송 | allowlist deny-by-default(T004) |
| **U**nawareness | 사용자가 데이터 범위 모름 | export 유스케이스(T008) |
| **N**on-compliance | 삭제·철회 미이행 | 삭제 orchestration(T009), 즉시 차단(T010), tombstone(T011) |

## 7. 잔여 위험 (residual risk)

- ML 모델 가중치에서 **개별 샘플 제거 불가** — 재학습 기준으로만 대응(T011, [training-deletion.md](./training-deletion.md)).
- at-rest 키 유출 시 회전 전 ciphertext 는 복호 가능 — 회전·폐기 절차로 노출 창을 줄인다(T005, [key-rotation.md](./key-rotation.md)).
- 본 문서는 `human_gate: true` — 자동 검증 통과 후에도 사람 보안 리뷰 수용 전까지 `REVIEW` 로 유지한다.
