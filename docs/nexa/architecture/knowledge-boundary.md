# 경계 계약: knowledge RAG 호출 순서

- 작업: NEXA-P01-T016 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 관련 계약: [participation-context.md](./participation-context.md),
  [speech-context.md](./speech-context.md), [socialmemory-context.md](./socialmemory-context.md)

## 목적

knowledge(문서 RAG·웹검색)를 **언제** 호출할지 순서를 고정해, 침묵 판단을 위해 매 메시지마다
무겁게 검색하지 않게 한다.

## 호출 순서 (acceptance)

```
1. conversation 장면 갱신            (검색 없음)
2. participation 행동 선택            (검색 없음 — IGNORE/WAIT/REACT/SPEAK/CANCEL)
3. SPEAK 인 경우에만:
   3a. speech 프롬프트 구성(정체성+기억+장면)
   3b. factual retrieval 필요성 판단  (질문성/사실 확인이 필요할 때만)
   3c. 필요할 때만 knowledge(RAG/웹검색) 호출
4. 발화 계획 생성
```

- **침묵 판단(IGNORE/WAIT)에는 knowledge/웹검색을 호출하지 않는다.**
- knowledge는 SPEAK가 정해지고 factual retrieval이 필요할 때만 호출된다(socialmemory 일화 기억과
  구분 — knowledge=외부 사실 근거, socialmemory=관찰된 관계/사건).

## 불변식

1. participation 결정 단계는 어떤 외부 검색도 호출하지 않는다(비용·지연 0).
2. knowledge 호출은 SPEAK + retrieval 필요 조건을 모두 만족할 때만 일어난다.
3. knowledge와 socialmemory는 별개 포트이며 서로 대체하지 않는다.
