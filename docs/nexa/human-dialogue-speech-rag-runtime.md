# NIA 사람 말투 RAG runtime 계약

상태: **구현·실제 private corpus 통합 테스트 완료, 승인된 운영 one-shot import 대기**
결정: [ADR 0020](../adr/0020-nia-speech-style-embedding.md)

## 목적과 경계

이 RAG는 “무슨 사실을 말할지”를 찾는 지식 검색이 아니다. 실제 사람이 보인 짧은 반응의 순서·길이·말풍선 리듬을
Speech가 참고하도록 하는 private 말투 검색이다.

- Judge에는 카드, embedding, 검색 결과를 절대 주입하지 않는다.
- `SPEAK`가 확정되고 동의·stale·고위험 게이트를 통과했으며 Judge의 `styleMode`가 있을 때만 현재 장면을 한 번 embedding 조회한다. enum이 비면 잘못된 예시를 고르지 않도록 RAG를 건너뛴다.
- 상위 2개만 기존 Speech 입력에 붙인다. 니아는 인물·사건·문구를 복사하지 않고 반응 방식만 참고해야 한다.
- 사람 답변과 20자 이상 연속 일치한 생성 후보는 전송 전에 제거한다.
- 검색 실패는 기존 Speech 생성 실패가 아니다. 예시 없이 생성하고, embedding 재시도는 하지 않는다.
- 런타임 export는 원본 카드의 parser 반응·첨부 메타데이터를 제거하고 기계식 `A`/`B` 화자 표기를 자연스러운 가명으로
  바꾼다. 정리 뒤 앞 대화나 실제 답변이 비어 버린 비대화 카드는 제외하며, 그 수는 private manifest에 남긴다.

## 데이터 흐름

```text
private human-review cards
  -> scripts/export-human-speech-style-rag.py
  -> ignored JSONL (원본 경로·ID·일시·trace 제외)
  -> 명시적 import job
  -> 암호화된 nia_human_speech_style_example

Discord turn
  -> Judge (사람 말투 RAG 미사용, Speech-only styleMode만 선택)
  -> SPEAK + 동의 + not stale
  -> 현재 장면 embedding 1회
  -> top-2 style examples
  -> Speech 1회
```

색인 embedding에는 `response_mode`, 최소 일반화 `situation`, `style_signals`, `context_bubbles`만 보낸다.
`response_bubbles`는 색인 대상이 아니다. 선택된 카드의 말풍선은 같은 Speech provider 요청에만 들어가며, 실행 trace와
metric에는 생략한다.

## 현재 import artifact

로컬 private artifact는 Git ignore 및 권한 보호 상태여야 한다. runtime export는 `USER_AUTHORIZED_CANDIDATE` 카드만
수용하고, 아직 정식 `APPROVED`라고 표기하지 않는다. export는 카드의 원본 파일 경로, 메시지 ID, 날짜, 원문 line trace를
runtime JSONL에 넣지 않는다.

실제 import는 다음 조건이 모두 갖춰진 **운영 DB 연결 환경**에서만 한다.

1. `DB_URL`이 영속 Postgres를 가리킨다.
2. 기존 `NEXA_FIELD_ENC_KEY`가 설정돼 있다.
3. migration `V91__nia_human_speech_style_rag.sql`이 적용된다.
4. private import JSONL은 배포 가능한 보호 경로에만 있고 Git/로그에 없다.
5. 한 번만 켜는 import process에 `NEXA_SPEECH_STYLE_RAG_IMPORT_ON_STARTUP=true`와
   `NEXA_SPEECH_STYLE_RAG_IMPORT_FILE`을 설정한다. 일반 server boot에서는 import flag를 계속 `false`로 둔다.

이 작업은 private 데이터를 외부 embedding provider에 전송하고 운영 DB를 바꾸므로, 실행 전 배포/운영 승인과 보안 경로
확인이 필요하다. 일반 local H2 메모리 DB에 실행하면 프로세스 종료와 함께 사라지므로 실제 적재가 아니다.

### 승인된 운영 import 절차

1. V91과 Speech-style RAG 코드가 먼저 `main` 배포되어 있어야 한다. import workflow는 V91이 없으면 즉시 실패한다.
2. JSONL과 같은 디렉터리의 `manifest.json`을 운영 호스트의 `~/deploy/central-server/private/`에 0700/0600으로 복사한다.
   GitHub, Git, 이미지, 로그에는 넣지 않는다.
3. GitHub Actions의 **central Speech-style RAG import**를 수동 실행한다. 이 workflow는 `production` Environment 승인 뒤,
   private JSONL의 digest·16 source·7 enum·중복 ID를 확인하고 별도 one-shot 컨테이너에서 import한다.
4. one-shot 컨테이너는 web server, Discord, autonomous send, web demo를 모두 끄고 import 성공 후 종료한다. 기존 live
   `central-server` 컨테이너를 재시작하거나 Discord에 발화하지 않는다.
5. workflow는 DB의 카드 수·source 수·enum 수와 `payload_json`/`embedding_json` 암호화 접두어만 확인한다. 카드 문구와
   raw source는 출력하지 않는다.

## 관측과 즉시 중지

- `NEXA_SPEECH_STYLE_RAG_ENABLED=false`면 현재 장면을 embedding으로 보내지 않고 예시 없이 Speech가 작동한다.
- `central_openai_requests_total{purpose="nia_speech_style_embedding"}`은 runtime `SPEAK`당 최대 1회여야 한다.
- import job의 batch 호출은 일반 runtime 호출과 별도로 시간 창을 분리해 확인한다.
- `nia_rag_embedding`은 계속 0이어야 한다. 그것은 금지된 Judge/Conversation RAG 경로다.

## 검증 범위

- runtime JSONL exporter: 16개 입력 source의 1,540장, JSONL digest와 private file mode 검증.
- 실제 OpenAI embedding + H2 암호화 저장 + query + top-2 selection + prompt trace 비노출 통합 테스트.
- unit/JPA tests: `SPEAK` 전 경로에 RAG 호출 없음, 최대 2개 selection, 외부 embedding 입력에 사람 답변 제외,
  DB payload/vector 암호화, 20자 복사 차단.

이 계약은 과거의 [초기 설계안](human-dialogue-speech-rag-plan.md)을 대체한다.
