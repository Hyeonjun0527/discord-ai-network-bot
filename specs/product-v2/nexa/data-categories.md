# NEXA 데이터 범주표 (개인정보 등급 분류)

- 작업: NEXA-P02-T001 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [logging-baseline.md](../../../docs/nexa/security/logging-baseline.md),
  [social-model-overlap.md](../../../docs/nexa/baseline/social-model-overlap.md),
  [domain-events.md](../../../docs/nexa/architecture/domain-events.md),
  [onboarding-boundary.md](../../../docs/nexa/architecture/onboarding-boundary.md),
  [logging-boundary.md](../../../docs/nexa/architecture/logging-boundary.md)

## 목적

NEXA가 다루는 모든 데이터를 **개인정보 등급별로 분류**하고, 각 범주마다 수집 목적·보존 기간·외부
전송 여부·삭제 방법을 하나씩 못 박는다. 이후 동의 모델(T002~), 보존 TTL(T008), 삭제 전파(T009),
가명화(T010), redaction(T012)이 이 표를 SSOT로 삼는다.

## 개인정보 등급

- **High**: 사용자를 직접 식별하거나 원문 내용을 담는다. 최소 수집·최단 보존·강한 접근통제·삭제 보장.
- **Medium**: 원문에서 파생됐거나 안정 식별자다. 가명화·보존 한도·외부 전송 제한.
- **Low**: 결정·상태·상관 ID만. 원문/파생 텍스트 없음. 운영 보존 가능.

## 범주표

| 범주 | 등급 | 수집 목적 | 보존 기간 | 외부 전송 | 삭제 방법 |
| --- | --- | --- | --- | --- | --- |
| 메시지 원문(raw content) | **High** | conversation 관찰·장면 구성 | 최단(장면 윈도우 한정, 영속 기본 금지) | routing CloudLlm 발화 생성 시에만(동의·정책 통과 후). 로그 저장 금지 | 원본 이벤트 삭제 시 즉시 파기, 삭제 전파(T009) |
| 임베딩(embeddings) | Medium | knowledge RAG·기억 검색 | 중기(재색인 주기 내) | 외부 전송 안 함(로컬 인덱스) | 원문 삭제 시 해당 벡터 제거·재색인 제외 |
| 작성자 식별자(Discord snowflake) | Medium | 관계·발화 귀속, 쿼터 | 가명으로만 보존 | **외부 전송 금지** — 로깅 경계에서 scoped 가명/해시(T010) | 가명 매핑 폐기로 비식별 |
| 관계 상태(socialmemory) | Medium | participation·speech 맥락 | 시간 유효(decay·TTL, T008) | 외부 전송 안 함 | 만료 또는 사용자 삭제 요청 시 제거(T009) |
| 정책 결정(participation decision log) | Low | 관측·디버그·감사 | 운영 보존 | 외부 전송 안 함 | correlation ID 기준 정리(원문 없음) |
| 생성 응답(speech 출력) | Medium | 전송·품질 피드백 | 단기 | 생성 과정에서 모델 경유(저장은 최소) | 채널/요청 삭제 시 제거 |
| 학습 산출물(training artifact) | **High** | 품질 개선(옵트인 시에만) | 동의 유효 기간 | 외부 전송 안 함 | 동의 철회 시 산출물·소스 제거 |

## 경계 규칙

1. **High는 기본 비영속**: 메시지 원문은 장면 윈도우를 벗어나면 보관하지 않는다. 영속이 필요하면
   별도 동의(T002/T003)와 보존 등급(T008)을 거친다.
2. **외부 전송 단일 경로**: 어떤 범주도 routing CloudLlm(ADR 0006) 외의 외부로 나가지 않는다.
   provider-agent·임의 URL 직접 전송 금지.
3. **로그에 원문·식별자 금지**: 모든 범주는 [logging-boundary.md](../../../docs/nexa/architecture/logging-boundary.md)의
   redaction 계약을 따른다(원문·프롬프트·응답·키·snowflake 원문 저장 금지).
4. **삭제 가능성 기본값**: 모든 High/Medium 범주는 원본 삭제·동의 철회 시 함께 제거 가능해야 한다(T009 전파).
5. **학습은 옵트인 전용**: training artifact는 명시 동의 없이는 생성·보관하지 않는다([onboarding-boundary.md](../../../docs/nexa/architecture/onboarding-boundary.md)).

## acceptance 충족

각 범주(7종)에 수집 목적·보존 기간·외부 전송 여부·삭제 방법이 표에 하나씩 연결되어 있으며,
개인정보 등급(High/Medium/Low)으로 분류되었다.
