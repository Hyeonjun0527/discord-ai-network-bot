# 실제 인간 대화 데이터의 NIA Speech RAG 적용 계획

상태: **역사적 초기 설계안 — 현재 runtime 계약은 [human-dialogue-speech-rag-runtime.md](human-dialogue-speech-rag-runtime.md)를 따른다**
작성일: 2026-08-01

> 2026-08-04 정정: Speech-only 단일 embedding 조회 구현은 허용됐지만, `USER_AUTHORIZED_CANDIDATE` preview
> 카드는 formal curation approval을 대체하지 못한다. runtime export/import는 최신 runtime 계약의
> `CURATION_APPROVED` gate를 통과한 카드만 허용한다. Judge/Conversation RAG에는 여전히 private 카드나 OpenAI
> embedding을 연결하지 않는다.

## 1. 결론

이 데이터의 첫 용도는 `Judge`가 아니라 **Speech가 짧고 자연스러운 답변 방식을 참고하는 것**으로 한정한다.

- Judge는 현재 Discord 장면을 직접 보고 `SPEAK/WAIT/REACT/IGNORE/CANCEL`을 판단한다.
- Judge가 `SPEAK`를 골랐을 때만, 검수 완료된 인간 대화 장면을 로컬에서 검색한다.
- 검색 결과는 최대 2개만 Speech의 참고 예시로 넣는다.
- 검색에 OpenAI embedding을 쓰지 않는다.
- Evaluator를 다시 만들지 않는다.
- 한 턴의 유료 OpenAI 요청은 `Judge 1회 + Speech 1회`, 최대 2회다.
- 사람 검수 전 장면은 절대 운영에 넣지 않는다.

인스타그램 2인 대화는 “상대가 이렇게 말했을 때 실제 사람이 어떻게 답했는가”는 보여 주지만, 제3자인 니아가
끼어들어야 하는지는 보여 주지 않는다. 따라서 원본만으로 Judge 정답을 만들면 안 된다.

## 2. 현재 비공개 가공본

가공 명령은 `scripts/prepare-nia-human-dialogue.py`다. 모든 결과는 Git에서 제외되는
`data/private/nia-human-dialogue/2026-08-01-v1/`에 있다.

| 항목 | 값 |
|---|---:|
| 원본 파일 | 16개 |
| 원본 합계 | 9,993,564 bytes |
| 정규화된 메시지 | 202,877개 |
| 반응 장면 후보 | 81,848개 |
| 정확히 같은 중복 | 57개 |
| 사람 검수 대기 | 81,791개 |
| 검수 배치 | 328개, 배치당 최대 250개 |
| 가공 중 네트워크/AI 요청 | 0회 |

폴더 구조는 다음과 같다.

```text
2026-08-01-v1/
├── sources/                 # 원본 byte와 같은 보존본, 중립 파일명, 0400
├── normalized/              # 메시지·화자·시각·원본 줄 위치 JSONL
├── candidates/
│   └── all-scenes.jsonl     # 전체 후보와 중복 표시
├── review/
│   └── pending-batches/     # 사람이 볼 250개 단위 JSONL
├── reports/
│   ├── source-manifest.json # 파일 hash·크기·건수만 저장
│   └── summary.json         # 집계값만 저장
└── README_PRIVATE.md
```

권한은 폴더 `0700`, 가공 파일 `0600`, 원본 보존본 `0400`이다. 원문은 저장소 문서, 테스트 fixture, 로그,
PR 본문에 복사하지 않는다.

## 3. “장면”을 자른 기준

장면은 라이브 Discord의 대기 시간을 결정하는 단위가 아니다. 오직 **인간의 실제 반응 예시를 검수하기 위한 단위**다.

1. 같은 사람이 연속으로 보낸 말풍선을 하나의 화자 턴으로 묶는다.
2. 화자가 바뀌면, 바뀌기 직전 최대 4개 화자 턴을 `context`로 둔다.
3. 새 화자의 연속 말풍선을 `response`로 둔다.
4. A가 말하고 B가 답한 장면과 B가 말하고 A가 답한 장면을 모두 만든다.
5. 분 단위 시각이 있으면 30분 넘는 공백에서 검수용 세션을 나눈다. 날짜·월이 바뀌어도 나눈다.
6. 시각이 없는 파일은 화자 교대만 사용하며 `timestamp_unknown`으로 표시한다.
7. 사진·릴스·반응 표식, 지나치게 짧거나 긴 답변, 여러 줄 메타데이터는 자동 승인하지 않고 flag만 붙인다.

`A-A-A-B` 모양이 비슷하다는 이유로 검색하거나 좋은 예시라고 판단하지 않는다. 같은 사람의 연속 말풍선 수는
답변 버블 형태를 참고하는 보조 정보일 뿐, 상황의 의미를 대신하지 않는다.

## 4. 사람 검수 계약

### 4.1 상태

모든 장면은 다음 상태 중 하나를 갖는다.

- `PENDING`: 아직 사람이 보지 않음
- `APPROVED_NIA`: 니아 답변 참고 예시로 직접 사용 가능
- `APPROVED_STYLE_ONLY`: 길이·맞장구·버블 모양만 참고 가능하고 내용은 참고 금지
- `HOLD`: 앞뒤 원문을 더 확인해야 함
- `REJECTED`: 운영에 사용 금지
- `DUPLICATE`: 앞의 같은 장면을 가리키며 별도 검수하지 않음

자동 처리는 `DUPLICATE` 표시까지만 한다. `APPROVED_*`는 사람만 지정할 수 있다.

### 4.2 장면마다 반드시 확인할 질문

1. 앞에 보이는 context만 읽어도 response가 이해되는가?
2. response가 이름, 학교, 직장, 주소, 계정, 일정처럼 특정 개인만의 사실에 의존하는가?
3. 연애 관계에서만 가능한 표현, 소유욕, 성적 표현, 과도한 의존 유도가 있는가?
4. 니아의 “친한 친구” 성격으로 말해도 자연스러운가?
5. 조롱, 압박, 집단 따돌림, 자해 조장, 의료·법률 단정처럼 그대로 모방하면 위험한가?
6. 사진이나 이전 사건이 없으면 이해할 수 없는 답인가?
7. 내용은 부적합하지만 짧은 맞장구나 말풍선 길이만 참고할 가치는 있는가?
8. 실제 답을 그대로 복사하기보다 반응 방식만 일반화할 수 있는가?

하나라도 애매하면 `HOLD` 또는 `REJECTED`다. 많이 넣는 것보다 틀린 예시를 넣지 않는 것이 중요하다.

### 4.3 승인할 때 붙일 최소 라벨

`situation_class`는 하나만 고른다.

```text
EMOTIONAL_DISCLOSURE   힘듦·기쁨·걱정 등을 털어놓음
CASUAL_UPDATE          일상 근황을 말함
FACTUAL_QUESTION       사실 답을 요구함
OPINION_REQUEST        생각·평가를 물음
ADVICE_REQUEST         어떻게 할지 조언을 구함
RECOMMENDATION_REQUEST 무엇을 볼지·살지·할지 추천을 구함
CELEBRATION            좋은 일을 함께 기뻐함
COMPLAINT              불편·짜증을 표현함
APOLOGY_REPAIR         사과하거나 관계를 풀려 함
PLANNING_COORDINATION  시간·장소·행동을 맞춤
PLAYFUL_BANTER         가벼운 장난·놀림
MEDIA_REACTION         사진·영상·링크에 반응함
OTHER                  위에 정확히 들어가지 않음
```

`response_move`는 최대 2개만 고른다.

```text
ACKNOWLEDGE, EMPATHIZE, ANSWER, ASK_FOLLOW_UP, SUGGEST,
REASSURE, CELEBRATE, TEASE_LIGHTLY, DECLINE, REACT_ONLY
```

추가로 `tone`(WARM/LIGHT/SERIOUS/EXCITED/BLUNT), `relationship_tone`(CLOSE_FRIEND만 우선 허용),
적정 답변 길이, 검수 메모를 기록한다. 자유 태그를 무한히 만들지 않는다. 태그가 많아지면 검색 기준이 흔들린다.

### 4.4 현실적인 검수량

81,791개를 장면당 20초만 봐도 약 454시간, 30초면 약 682시간이다. 따라서 “운영에 들어가는 것은 전부 사람
검수” 원칙은 지키되, 전체 검수 완료를 첫 출시 조건으로 두지 않는다.

권장 순서:

1. flag가 적고 문맥 길이가 적당한 후보 500개를 먼저 검수한다.
2. 라벨 기준이 흔들리지 않는지 같은 100개를 며칠 뒤 재검수한다.
3. 일치율이 90% 미만이면 라벨 정의부터 고친다.
4. 2,000개 승인 장면을 첫 검색 품질 평가 대상으로 삼는다.
5. 이후 250개 배치를 계속 처리해 결국 전체 후보를 확인한다.

## 5. 운영에 넣을 때의 데이터 모양

검수 파일을 그대로 운영 DB에 넣지 않는다. 승인 장면만 다음 형태로 다시 만든다.

```json
{
  "exampleId": "human-speech-example-v1-...",
  "contextBubbles": ["..."],
  "responseBubbles": ["..."],
  "situationClass": "EMOTIONAL_DISCLOSURE",
  "responseMoves": ["EMPATHIZE", "ASK_FOLLOW_UP"],
  "tone": "WARM",
  "relationshipTone": "CLOSE_FRIEND",
  "quality": "APPROVED_NIA",
  "sourceFingerprint": "sha256:...",
  "consentRevision": "2026-08-01",
  "reviewedBy": "...",
  "reviewedAt": "..."
}
```

운영본에는 원본 파일명, 날짜, 원본 줄 번호, 참여자 A/B의 지속 식별자를 넣지 않는다. 삭제·동의 철회를 처리하기 위한
단방향 source fingerprint와 consent revision만 보관한다. context와 response는 기존 NEXA 필드 암호화 경계를 사용해
암호화하고, 관리자 API도 원문 전체 목록을 기본 반환하지 않는다.

## 6. 런타임 검색 — 추가 AI 호출 없이 하는 방법

### 6.1 호출 순서

```text
Discord 메시지 수신
  -> 로컬 turn boundary와 안전 게이트
  -> Judge 유료 호출 1회
       IGNORE/WAIT/REACT/CANCEL -> 종료
       SPEAK -> 아래 계속
  -> 승인된 인간 Speech 예시를 로컬 검색 (HTTP/AI 호출 0회)
  -> 상위 예시 최대 2개, 합계 문자·토큰 상한 적용
  -> Speech 유료 호출 1회
  -> 기존 로컬 critic/selector
  -> Discord 전송
```

Judge RAG에 인간 2인 대화를 넣지 않는다. 검색은 Judge가 말하기로 결정한 뒤에만 실행하므로, 아무 말도 하지 않을
장면에서 검색·프롬프트 비용이 생기지 않는다.

### 6.2 Judge가 같은 호출에서 검색 힌트를 함께 냄

새 AI를 호출하지 않고 기존 Judge 출력의 `speechIntent`에 작은 enum 두 개만 추가한다.

```json
{
  "situationClass": "EMOTIONAL_DISCLOSURE",
  "desiredResponseMoves": ["EMPATHIZE", "ASK_FOLLOW_UP"]
}
```

Judge는 이미 상황과 필요한 반응을 해석해 `speechIntent`, `actHint`, `toneAxes`를 만들고 있다. 같은 판단 결과에
검색용 enum을 더하는 것이므로 요청 횟수는 그대로다. 긴 검색용 문장을 새로 생성하게 하지 않아 출력 토큰 증가도 작다.

### 6.3 1차 후보 선택

운영 검색은 별도 `HumanSpeechExampleService`가 맡는다. 기존 `ConversationRagService`와 섞지 않는다.

먼저 다음을 모두 만족하는 장면만 남긴다.

- `APPROVED_NIA` 또는 필요한 경우 `APPROVED_STYLE_ONLY`
- Judge의 `situationClass`와 같음
- `desiredResponseMoves` 중 하나 이상 겹침
- 니아의 현재 관계 톤과 호환됨
- 이미지가 없는 현재 장면에는 이미지 의존 예시 제외
- 동의가 철회되거나 비활성화된 source 제외

후보가 없으면 검색 결과 없이 Speech를 호출한다. 억지로 다른 상황의 예시를 넣지 않는다.

### 6.4 로컬 재정렬 점수

후보 안에서만 다음 고정 점수로 정렬한다.

```text
0.35  situationClass 정확히 일치
0.20  desiredResponseMoves 겹침
0.20  현재 원문 + 기존 Judge intent와 검수 장면 context의 로컬 텍스트 유사도
0.10  tone 일치
0.10  기대 답변 길이 일치
0.05  검수 품질/사용 후 긍정 결과
```

로컬 텍스트 유사도는 먼저 글자 n-gram과 단어 겹침으로 구현한다. 이 방법이 의미가 다른 표현을 충분히 찾지 못하면
품질 평가 결과를 보고 **별도 승인 후** 중앙 서버에 작은 로컬 encoder를 묶는 방안을 검토한다. 그 경우에도 OpenAI
embedding API는 사용하지 않는다. 검증 전에 무거운 로컬 모델 의존성을 먼저 추가하지 않는다.

상위 2개는 서로 다른 source를 우선한다. 한 대화 상대의 습관만 니아 전체 성격처럼 과대표현하지 않기 위해서다.

## 7. Speech 프롬프트에 넣는 방식

검색 예시는 다음 원칙으로만 넣는다.

- 최대 2개
- 두 예시를 합쳐 최대 700자 또는 토큰 추정 상한 중 먼저 닿는 값
- 원본 날짜·출처·참여자 관계 설명 제외
- “사실을 복사하지 말고 반응 방식·길이·말풍선 리듬만 참고”라고 명시
- 현재 질문에 필요한 사실은 현재 대화나 SearXNG 근거만 사용
- RAG 예시는 캐시 breakpoint 뒤의 동적 Speech 입력에 둠

예시가 길어서 일반 입력 토큰 비용이 늘 수 있으므로, 적용 전후 `nia_speech` 평균 input token을 비교한다. 자연스러움
향상이 없는 예시는 비용만 늘리므로 제거한다.

생성 결과가 source response와 20자 이상의 비정상적으로 긴 연속 문자열을 그대로 복사하면 로컬 필터가 차단한다.
짧은 일상 표현은 예외 목록으로 관리한다. 이 검사도 AI를 호출하지 않는다.

## 8. 검색이 실제로 잘 되는지 증명하는 방법

“그럴듯해 보임”으로 출시하지 않는다. 승인 장면 중 일부를 정답 평가 세트로 고정한다.

1. 장면 300개를 고른다.
2. 정답 response는 숨기고 context만 검색 질의로 사용한다.
3. 같은 source와 exact duplicate는 검색 후보에서 제외한다.
4. 사람이 상위 5개를 `적절/부분 적절/부적절`로 평가한다.
5. 아래 조건을 모두 만족해야 운영 프롬프트에 넣는다.

| 지표 | 첫 통과 기준 |
|---|---:|
| Top-3 안에 적절한 예시가 하나 이상 | 80% 이상 |
| 1위가 완전히 부적절 | 5% 이하 |
| 현재 상황과 반대 감정·반대 행동 | 1% 이하 |
| 개인 사실을 그대로 복사한 생성 | 0건 |
| RAG 적용 인간성 블라인드 선호 | 미적용보다 10%p 이상 높음 |
| Speech 평균 입력 증가 | 정한 700자/토큰 상한 이내 |

기준을 못 넘으면 프롬프트에 넣지 않는다. 라벨, 검색 점수, 장면 품질을 먼저 고친다. OpenAI embedding을 켜서 문제를
숨기지 않는다.

## 9. 배포 단계와 중단 조건

### 단계 A — 현재

- 비공개 원본 보존·정규화·장면 후보 생성
- 모든 후보 `PENDING`
- OpenAI embedding 제거
- Judge/Speech 자동 retry 제거
- 운영 인간 대화 RAG 주입 0건

### 단계 B — 검수 도구와 첫 500개

- 검수 화면은 로컬에서만 실행
- 원본 앞뒤 보기, 승인/보류/거절, 라벨 단축키, 진행률 제공
- 100개 재검수 일치율 측정

### 단계 C — 2,000개와 오프라인 검색 평가

- 승인본만 별도 암호화 import artifact 생성
- 300개 고정 평가 세트로 검색 품질 측정
- 기준 미달이면 운영 연결 금지

### 단계 D — shadow

- 실제 Discord `SPEAK` 장면에서 검색 ID와 점수만 기록
- Speech 프롬프트에는 아직 넣지 않음
- 원문이나 human response는 운영 로그에 기록하지 않음

### 단계 E — canary

- 제한 채널에서만 최대 2개 예시 주입
- `human-speech-rag-enabled` 한 스위치로 즉시 OFF
- 비용, 응답 지연, 무응답, 사람 선호, 복사 차단을 비교

다음 중 하나면 즉시 OFF한다.

- OpenAI 요청 수가 발화 턴당 2를 초과
- `nia_rag_embedding`, `nia_action_evaluator`, `nia_judge_repair` 요청 발생
- 개인 사실 복사 1건
- 부적절한 예시 1위 비율 5% 초과
- RAG 미적용보다 블라인드 선호가 좋아지지 않음
- 평균 Speech 입력이 상한을 반복 초과

## 10. 구현 순서

1. 이번 변경: 두 번의 유료 호출 상한과 로컬 Conversation RAG를 코드·테스트·운영 문서에 고정한다.
2. 사용자가 이 문서의 방향을 승인한다.
3. 별도 PR에서 로컬 검수 UI와 승인 artifact validator를 만든다.
4. 사람이 첫 500개를 검수하고 라벨 계약을 수정한다.
5. 별도 PR에서 `HumanSpeechExample` DB/암호화/삭제 lineage를 만든다.
6. Judge의 같은 응답에 enum 두 개를 추가하고 Structured Output 계약 테스트를 갱신한다.
7. 로컬 검색과 300개 평가 harness를 만든다.
8. 통과 후 shadow, 다시 승인 후 canary로 진행한다.

## 11. 사용자 검수에서 확정할 결정

다음 여덟 항목을 승인하거나 바꿔야 실제 운영 연결을 시작한다.

1. 인간 대화 데이터는 우선 Speech에만 사용한다.
2. `APPROVED_NIA`는 친한 친구 톤만 허용하고 연인 전용 표현은 제외한다.
3. 운영에 들어가는 모든 장면은 사람 승인을 필수로 한다.
4. 전체 81,791개를 장기적으로 검수하되 첫 목표는 500개, 첫 검색 corpus는 승인 2,000개로 한다.
5. 검색 힌트 enum 두 개는 기존 Judge 1회 출력에 포함한다.
6. 검색 결과는 최대 2개·합계 700자 상한으로 한다.
7. OpenAI embedding은 계속 0회로 유지하고, 로컬 encoder도 평가 실패 전에는 추가하지 않는다.
8. 실패 자동 재시도보다 한 턴 최대 2회 비용 상한을 우선한다.
