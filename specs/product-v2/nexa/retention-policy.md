# 보존 등급과 TTL

- 작업: NEXA-P02-T008 (`human_gate: true`, decision) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [data-categories.md](./data-categories.md), [socialmemory-context.md](../../../docs/nexa/architecture/socialmemory-context.md),
  [deletion-propagation.md](./deletion-propagation.md)

## 결정

데이터 유형별 **TTL(보존 기간)**과 **영구 보존 금지 항목**을 정한다. TTL 종료 시 파생 데이터까지
함께 삭제·무효화하는 책임자를 명시한다.

### 보존 표

| 유형 | TTL | 영구 보존 | TTL 종료 책임자 |
| --- | --- | --- | --- |
| 원본 이벤트(원문) | 단기(장면 윈도우) | **금지** | conversation — 윈도우 밖 파기 |
| projection(장면·버스트) | 파생, 원본 TTL 종속 | 금지 | conversation |
| 기억(socialmemory) | 시간 유효(decay), 유형별 TTL | 금지 | socialmemory — 만료 스윕 |
| 로그(requestlog/decision) | 운영 보존 한도 | 금지(원문 없음) | requestlog/participation |
| 모델 산출물(학습) | 동의 유효 기간 | 금지 | training 파이프라인 |

## acceptance 충족

- **TTL 종료 시 파생 데이터까지 삭제/무효화되는 책임자가 명시된다**: 각 유형의 TTL 종료 처리
  책임자(소유 컨텍스트)가 표에 지정되고, 원본 만료 시 projection·임베딩 등 파생도 연쇄 제거된다
  (삭제 전파 [deletion-propagation.md](./deletion-propagation.md) 연계).

## 불변식

1. 어떤 유형도 영구 보존하지 않는다(원문은 특히 단기).
2. 원본 만료는 파생(projection·임베딩)의 만료를 강제한다.
3. 각 TTL 종료 처리에는 명시된 소유 컨텍스트 책임자가 있다.
