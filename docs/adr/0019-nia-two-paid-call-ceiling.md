# ADR 0019: NIA two paid-call ceiling

- 상태(Status): 부분 대체됨 (Partially superseded by [ADR 0020](./0020-nia-speech-style-embedding.md))
- 날짜(Date): 2026-08-01
- 결정자(Deciders): Hyeonjun0527
- 대체: [ADR 0018](./0018-nia-bounded-llm-retry-budget.md)
- 관련: [NIA pipeline](../nexa/NIA_PIPELINE.md), [운영 metric](../nexa/operations/metrics.md)

## 맥락 (Context)

Judge와 Speech가 각각 한 번 재시도하면 정상 발화는 2회지만 실패 경로는 최대 4회의 유료
모델 요청을 만든다. Conversation RAG가 OpenAI embedding을 사용하면 정상 발화에도 세 번째 유료
HTTP 요청이 추가된다. NIA의 단일 턴 비용은 정상·실패 여부와 관계없이 명확해야 한다.

## 결정 (Decision)

1. `FINAL` Judge는 최대 1회만 호출한다. provider 오류나 출력 형식 오류는 추가 호출 없이 제한된 로컬
   하강 정책으로 처리한다.
2. Judge가 `SPEAK`를 고른 경우에만 Speech를 최대 1회 호출한다. timeout·provider 오류·파싱 오류는
   재시도하지 않고 안전하게 무발화한다.
3. provider 내부 retry도 Judge·Speech 모두 0으로 고정한다.
4. NIA Conversation RAG는 외부 embedding API를 호출하지 않고 로컬 텍스트 점수만 사용한다. 기존 DB
   vector가 있어도 런타임 검색에서 사용하지 않는다.
5. Cloud action evaluator와 Judge repair 호출은 없다. Speech 한 요청이 여러 문구 후보를 반환하고, 로컬
   critic과 결정론적 selector가 하나를 고른다.
6. 따라서 유료 OpenAI 요청 상한은 비발화 1회, 발화 2회(Judge 1 + Speech 1)다. 이미 니아를 향한
   것으로 로컬에서 확정된 이미지 턴은 Judge를 건너뛰고 Speech 1회다.
7. SearXNG 검색은 자체 검색 서비스의 비-LLM 요청이며 OpenAI 유료 요청으로 세지 않는다. 검색 결과는
   같은 Speech 1회의 입력에 넣는다.

## 결과 (Consequences)

- 비용 상한은 간단하고 검증 가능하다. `nia_judge` 또는 `nia_speech` 요청 비율이 턴 수보다 커지면
  회귀다.
- 일시적 provider 장애에서 답변 누락 가능성은 늘어난다. 재시도 대신 짧은 timeout, 로컬 하강, 다음 사용자
  턴에서의 자연스러운 복구로 다룬다.
- 실패 시 재시도와 2회 절대 상한은 동시에 보장할 수 없다. 본 결정은 최신 제품 요구인 비용 상한을
  우선한다.
