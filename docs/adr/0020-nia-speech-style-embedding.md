# ADR 0020: NIA Speech 말투 RAG의 단일 embedding 조회

- 상태(Status): 승인됨 (Accepted)
- 날짜(Date): 2026-08-04
- 결정자(Deciders): Hyeonjun0527
- 부분 대체: [ADR 0019](./0019-nia-two-paid-call-ceiling.md)의 외부 embedding 금지와 발화 2회 절대 상한
- 관련: [NIA Speech 말투 RAG runtime 계약](../nexa/human-dialogue-speech-rag-runtime.md), [운영 metric](../nexa/operations/metrics.md)

## 맥락 (Context)

사람 말투 RAG는 주제 지식이 아니라, 현재 장면에서 자연스러운 반응 순서·길이·말풍선 리듬을 찾는 용도다.
로컬 텍스트 점수만으로는 같은 의미를 다른 말로 한 장면을 안정적으로 찾기 어렵다. 사용자는 이 검색 품질을 위해
OpenAI embedding 호출 한 번을 허용했다.

## 결정 (Decision)

1. 사람 말투 RAG는 Judge와 완전히 분리한다. Judge의 `SPEAK/WAIT/REACT/IGNORE/CANCEL` 판단에는 카드·벡터·embedding을
   사용하지 않는다. `SPEAK`를 고른 뒤에는 Judge가 카드 내용을 보지 않는 작은 `styleMode` enum 하나만 내보내며,
   이 값은 Speech의 private 검색 축으로만 쓴다.
2. 현재 턴이 `SPEAK`, not-stale이고 `SPEECH_GENERATION` 및 `EXTERNAL_GLM_REQUEST` 동의 게이트를 모두 통과했으며 Judge의
   `styleMode`가 있을 때만 현재 장면을 embedding으로 **최대 한 번** 조회한다. enum이 없으면 RAG를 건너뛰며, `SPEAK`가 아닌 턴은 0회다.
3. 조회는 `text-embedding-3-small`을 기본으로 사용한다. 검색 실패·timeout·키 누락은 사람 카드 없이 기존 Speech 생성으로
   하강하며, embedding 재시도는 하지 않는다.
4. 발화 정상 경로의 외부 OpenAI HTTP 상한은 Judge 1회 + Speech-style embedding 1회 + Speech 1회, 즉 최대 3회다.
   Judge를 건너뛰는 이미-확정된 Speech 턴은 embedding 1회 + Speech 1회, 최대 2회다.
5. embedding 입력은 검색용 최소 표현(반응 enum, 최소 일반화 상황, 말투 신호, 앞 대화)만 사용한다. 선택 전의 실제 사람
   답변 말풍선은 embedding 요청에 넣지 않는다. 선택된 최대 2개 예시만 기존 Speech 외부 호출의 동적 입력에 넣고,
   trace·로그·metric에는 카드 원문을 넣지 않는다.
6. 색인 import는 별도의 명시적 운영 작업이다. 카드 수만큼 배치 embedding을 할 수 있으나, 일반 Discord 턴과 함께 자동으로
   실행하지 않는다.
7. `central_openai_requests_total{purpose="nia_speech_style_embedding"}`와 같은 목적별 집계로 실제 호출 수·입력량을
   확인한다. 원문, Discord ID, 카드 ID는 metric label로 사용하지 않는다.

## 결과 (Consequences)

- 생성 품질을 위해 발화 정상 경로에 외부 HTTP 1회를 추가한다. 그 대가로 Judge가 `SPEAK`하지 않은 대화에는 추가 전송·비용이
  생기지 않는다.
- 사람이 쓴 답변은 여전히 복사 금지다. 20자 이상 연속 일치한 생성 후보는 로컬에서 제거한다.
- Runtime DB에는 카드 payload와 vector를 암호화해 보관하며, `NEXA_FIELD_ENC_KEY`가 없으면 import·read 모두 fail-closed다.
- 배포, private 카드 import, Discord `LIVE` 발화는 기존 인간 승인 게이트를 그대로 따른다.
