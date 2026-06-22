# 기억 유형 taxonomy

- 작업: NEXA-P07-T001 (`human_gate: true`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0010 ainetwork·socialmemory 경계](../../adr/0010-ainetwork-socialmemory-boundary.md)
- 근거: [socialmemory-context.md](../architecture/socialmemory-context.md),
  [knowledge-boundary.md](../architecture/knowledge-boundary.md),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md)

## 목적

socialmemory가 소유하는 기억을 5개 유형으로 구분하고, **knowledge RAG(외부 문서 사실)와 소유권이
겹치지 않음**을 명시한다.

## 5개 기억 유형 (socialmemory 소유)

| 유형 | 정의 | 시간성 | 예 |
| --- | --- | --- | --- |
| **EpisodicMemory** | "언제 누구와 무슨 일이 있었다"는 시점 있는 사건 | 일화적, valid-at + decay | "어제 민수와 도커 문제를 같이 풀었다" |
| **TemporalFact** | 관찰/언급된 안정 속성(변할 수 있음) | valid-from/until | "지현은 파이썬을 쓴다(라고 말했다)" |
| **RelationshipMemory** | guild 스코프 관찰 가능한 관계 상태 | decay | familiarity·reciprocity 집계(관찰 신호, [observable-state-policy](../social-state/observable-state-policy.md)) |
| **PendingIntent** | "나중에 하기로 한" 미완 의도 | 만료/해결 | "보라에게 자료 찾아주기로 함" |

## 정체성 커널 (IdentityKernel) — socialmemory 비소유, 읽기만

| 유형 | 소유 | socialmemory 관계 |
| --- | --- | --- |
| **IdentityKernel** | ainetwork/globalpromptset(정적, ADR 0010 REUSE) | socialmemory는 **읽기 브리지**로만 참조(복제·변경 금지). 니아 정체성은 기억이 아니라 정적 페르소나 |

## knowledge RAG와의 소유권 경계 (acceptance)

| 축 | socialmemory | knowledge RAG |
| --- | --- | --- |
| 대상 | 사람·관계·사건(일화적, 관찰됨) | 문서·FAQ·서버 지식(의미적) |
| 출처 | NEXA가 관찰한 상호작용 | 색인된 외부/서버 문서 |
| 쓰임 | speech가 "이 사람과의 맥락" 반영 | speech가 "사실 근거" 인용([knowledge-boundary](../architecture/knowledge-boundary.md)) |
| 소유권 | 겹치지 않음 — 일화적 기억 ≠ 문서 사실 | |

## 불변식

1. socialmemory는 4개 기억 유형(Episodic/TemporalFact/Relationship/PendingIntent)을 소유한다.
2. IdentityKernel은 socialmemory가 소유하지 않고 읽기로만 참조한다(정적 정체성, ADR 0010).
3. knowledge RAG(외부 문서 사실)와 사회적 기억(관찰된 일화·관계)은 소유권이 겹치지 않는다.
4. 모든 기억은 시간 유효성(valid/decay/만료)을 가지며 원문 전체를 복제하지 않는다(provenance ID).
