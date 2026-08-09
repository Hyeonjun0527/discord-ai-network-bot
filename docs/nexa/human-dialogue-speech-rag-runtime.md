# NIA 사람 말투 RAG runtime 계약

상태: **v28 closed-metadata source-coverage candidate/seal은 fresh actual retrieval·독립 70-case blind quality·comparative RAG-value 검수를 다시 받아야 한다. 이 문서는 v28 `PASS`, production one-shot import, live Discord 동작을 주장하지 않는다.**
결정: [ADR 0020](../adr/0020-nia-speech-style-embedding.md)

## 목적과 경계

이 RAG는 “무슨 사실을 말할지”를 찾는 지식 검색이 아니다. 실제 사람이 보인 짧은 반응의 순서·길이·말풍선 리듬을
Speech가 참고하도록 하는 private 말투 검색이다.

- Judge에는 카드, embedding, 검색 결과를 절대 주입하지 않는다.
- `SPEAK`가 확정되고 동의·stale·고위험 게이트를 통과했으며 Judge의 `styleMode`가 있을 때만 현재 장면을 한 번 embedding 조회한다. enum이 비거나 그 enum의 승인 카드가 없으면 잘못된 예시를 고르지 않도록 RAG를 건너뛴다.
- 상위 2개만 기존 Speech 입력에 붙인다. 니아는 인물·사건·문구를 복사하지 않고 반응 방식만 참고해야 한다.
- 사람 답변과 20자 이상 연속 일치한 생성 후보는 전송 전에 제거한다.
- 검색 실패는 기존 Speech 생성 실패가 아니다. 예시 없이 생성하고, embedding 재시도는 하지 않는다.
- 런타임 export는 원본 카드의 parser 반응·첨부 메타데이터를 제거하고 기계식 `A`/`B` 화자 표기를 자연스러운 가명으로
  바꾼다. 정리 뒤 앞 대화나 실제 답변이 비어 버린 비대화 카드는 제외하며, 그 수는 private manifest에 남긴다.

## 데이터 흐름

```text
private cards released through an explicit allowed quality
  -> scripts/export-human-speech-style-rag.py
  -> ignored JSONL (원본 경로·ID·일시·trace 제외)
  -> materialize closed response mode + scene trait + response-move provenance/form/rhythm + single primary provider-style cue
  -> 명시적 import job
  -> 암호화된 nia_human_speech_style_example

Discord turn
  -> Judge (사람 말투 RAG 미사용, Speech-only styleMode만 선택)
  -> SPEAK + 동의 + not stale
  -> 현재 장면 embedding 1회
  -> top-2 closed style patterns (원문 카드 미전달)
  -> Speech 1회
```

색인 embedding에는 닫힌 enum인 `response_mode`, 카드 앞 장면에서만 뽑은 `scene_traits`,
관찰·검수 provenance가 붙은 `response_move`, `response_form`, `response_rhythm`, 단일 `provider_style_cue`만 보낸다.
`situation`, `style_signals`, 원문 앞 대화(`context_bubbles`), 실제 답변(`response_bubbles`), 가명, 사건 설명 같은 자유 텍스트는 embedding 대상이 아니다. `styleMode`는 후보를 같은 enum으로
먼저 제한하고, 그 안에서만 현재 장면·발화 방향과 카드의 일반화 metadata embedding을 cosine으로 비교한다. 현재 마지막 발화에서
단 하나의 `scene_trait`이 확인되고 카드에도 같은 trait가 있을 때는 그 카드를 minimum score 뒤 우선 검토해 카드별 말풍선 형식·호흡을
참고한다. trait가 없거나 여러 개면 enum 기본 말투와 길이·호흡만 남긴다. 더 좁은 `response_move`는 현재 마지막 발화가 단 하나의 move를
명시하고 선택 카드의 관찰 move도 같을 때만 provider 지시로 올린다. Speech provider에는 `STYLE_PATTERN`만 전달한다. 이것은 선택된
카드의 닫힌 `response_mode`·`scene_trait`·조건부 `response_move`·`response_form`·`response_rhythm`에서 코드가 만든 비식별
반응 순서·호흡 규칙이다. 카드의 원문 대화, 실제 답변, 인물, 사건, 가명, `situation`, `style_signals`은 provider prompt와 실행
trace에 넣지 않는다. 과거 `CONTEXT_RESPONSE_PAIR`·`RESPONSE_ONLY`는 암호화 audit payload를 읽기 위한 legacy 값일 뿐,
runtime 검색·renderer·신규 import가 모두 fail-closed로 거부한다.

## 현재 import artifact

로컬 private artifact는 Git ignore 및 권한 보호 상태여야 한다. import artifact에는 서로 섞이지 않는 두 quality 경로가 있다.

- `CURATION_APPROVED`: formal exporter가 `curation/approved/`의 v2 카드, `APPROVED` 상태, `USE|STYLE_ONLY` decision,
  source manifest hash, 최신 fresh verifier A~F `PASS`를 모두 fail-closed로 확인한 경로다.
- `USER_RELEASED_REVIEW`: 사용자가 human-review 카드 묶음의 RAG 사용을 명시적으로 허가한 경로다. artifact의 모든 카드가
  이 quality여야 하고 `all_cards_user_released=true`여야 하며, manual workflow에서도 같은 quality를 명시해야 한다. 이것을
  formal curation approval로 표기하지 않는다.

두 경로 모두 preview/month-plan만으로는 import할 수 없고, export/import JSONL에 원본 파일 경로, 메시지 ID, 날짜,
원문 line trace를 넣지 않는다. audit용으로 보관되는 카드와 실제 Speech style-pattern 후보는 다를 수 있다. v18의 1,536 audit
record·540 raw-surface 후보 수는 실패한 과거 구조 실험의 수치이며 신규 import 후보가 아니다. v24는 원문이 아니라 닫힌 관찰
metadata로 만든 `STYLE_PATTERN`만 검색·provider 후보로 허용한다. 이 수는 구조·안전 후보 수일 뿐 사람 품질 검수 통과를 뜻하지 않는다.
어떤 quality 경로든 one-shot import artifact를 만들려면 같은 JSONL SHA-256에 묶인 세 독립 증거가 모두 `PASS`여야 한다.
첫째는 실제 OpenAI `text-embedding-3-small`로 실행한 aggregate-only `retrieval_audit`, 둘째는 그 audit SHA-256까지 다시 묶은
aggregate-only `blind_quality_review`, 셋째는 같은 audit SHA-256까지 묶어 fixed mode baseline과 비교한 fresh
`rag_value_review`다. 마지막 review는 전체 top-1 60%·top-2 75%, 각 response mode top-1 50%·top-2 70%, baseline보다 나쁨·근거 없는
구체화·unsafe provider surface 0건을 모두 충족해야 한다. 세 증거에는 원문·질의·카드 문구를 넣지 않는다. v28 candidate는
`nia-human-speech-style-retrieval-audit.v4`에 정확히
`closed_metadata_response_mode_then_semantic_tie_primary_style_cue_contrast_rerank_v11`인 `retrieval_policy`를 포함해야 한다.
이 값은 candidate manifest와 import manifest의 같은 policy와도 일치해야 한다. 따라서 raw-live-text query semantics를 나타낸 v10
audit은 JSONL digest가 같아도 v28 seal로 수용되지 않는다. v26의 v10 evidence는 역사적 기록일 뿐 v28 quality gate의 증거가 아니며,
새 v28 audit digest에 다시 묶인 blind-quality와 RAG-value review가 `PASS`하기 전에는 sealed import artifact를 만들거나 quality pass를
주장할 수 없다.

v28부터는 `central-server/src/main/resources/human-speech-style-source-coverage.json`이 16개 허용 source의
fingerprint 집합 digest와 source 수를 고정한다. exporter, materializer, import-artifact preparer, Kotlin artifact verifier,
운영 one-shot script는 모두 이 정책을 읽어 actual card set과 `expected_source_*` manifest binding을 fail-closed로 대조한다.
따라서 self-consistent한 일부 source artifact나 source 수만 맞춘 다른 집합은 import할 수 없다. 정책 파일에는 source 경로나
원문이 없고 count와 집합 digest만 있다.

실제 import는 다음 조건이 모두 갖춰진 **운영 DB 연결 환경**에서만 한다.

1. `DB_URL`이 영속 Postgres를 가리킨다.
2. 기존 `NEXA_FIELD_ENC_KEY`가 설정돼 있다.
3. migration `V91__nia_human_speech_style_rag.sql`이 적용된다.
4. 선택한 quality의 gate, JSONL digest에 묶인 v4 closed-metadata retrieval-audit `PASS`, 그 audit digest까지 묶은 blind-quality
   `PASS`, 그리고 fresh comparative RAG-value `PASS`를 모두 통과한 private import **directory**는 배포 가능한 보호 경로에만 있고
   Git/로그에 없다. directory에는 JSONL, candidate manifest, retrieval audit, blind review, RAG-value review, import manifest가 함께 있어야 한다.
5. 한 번만 켜는 import process에 `NEXA_SPEECH_STYLE_RAG_IMPORT_ON_STARTUP=true`와
   `NEXA_SPEECH_STYLE_RAG_IMPORT_ARTIFACT_DIR`을 설정한다. runner는 JSONL 단독 경로를 받지 않고 여섯 파일의 schema·digest·v11
   closed-metadata query-policy·PASS evidence binding과 trusted 16-source fingerprint-set binding을 다시 확인한다. 일반 server boot에서는 import flag를 계속 `false`로 둔다.

이 작업은 private 데이터를 외부 embedding provider에 전송하고 운영 DB를 바꾸므로, 실행 전 배포/운영 승인과 보안 경로
확인이 필요하다. v26은 실제 OpenAI `text-embedding-3-small`과 ephemeral H2에서만 검증했다. 일반 local H2 메모리 DB에 실행하면
프로세스 종료와 함께 사라지므로 실제 적재가 아니며, production import는 아직 실행하지 않았다.

### 승인된 운영 import 절차

1. V91과 Speech-style RAG 코드가 먼저 `main` 배포되어 있어야 한다. import workflow는 V91이 없으면 즉시 실패한다.
2. JSONL, `candidate-manifest.json`, `retrieval-audit.json`, `blind-quality-review.json`, `rag-value-review.json`, `manifest.json`으로
   구성된 sealed import
   directory 전체를 운영 호스트의 `~/deploy/central-server/private/`에 directory 0700/file 0600으로 복사한다. GitHub, Git,
   이미지, 로그에는 넣지 않는다.
3. GitHub Actions의 **central Speech-style RAG import**를 수동 실행하고 manifest의 quality와 같은 `import_quality`를 선택한다.
   이 workflow와 one-shot runner는 `production` Environment 승인 뒤 private JSONL의 digest·정확한 16-source fingerprint 집합·enum·중복 ID·검색 가능
   카드 수, 같은 directory에 별도 저장된 candidate manifest·v4 retrieval-audit·blind-quality review의 hash/content binding, 세 문서에
   같은 v11 closed-metadata query policy가 있는지, 해당 JSONL에 묶인 actual retrieval-audit `PASS`, audit digest까지 묶인 blind-quality
   `PASS`, 같은 audit에 묶인 fresh comparative RAG-value `PASS`를 확인하고 별도 one-shot 컨테이너에서 import한다.
4. one-shot 컨테이너는 web server, Discord, autonomous send, web demo를 모두 끄고 import 성공 후 종료한다. 기존 live
   `central-server` 컨테이너를 재시작하거나 Discord에 발화하지 않는다.
5. workflow는 DB의 전체 카드 수·검색 가능 카드 수·source 수·enum 수와 `payload_json`/`embedding_json` 암호화 접두어를
   확인하고, DB의 source fingerprint 집합 digest가 trusted 정책과 정확히 같은지도 확인한다. 카드 문구와 raw source는 출력하지 않는다.

## 관측과 즉시 중지

- `NEXA_SPEECH_STYLE_RAG_ENABLED=false`면 현재 장면을 embedding으로 보내지 않고 예시 없이 Speech가 작동한다.
- `central_openai_requests_total{purpose="nia_speech_style_embedding"}`은 runtime `SPEAK`당 최대 1회여야 한다.
- import job의 batch 호출은 일반 runtime 호출과 별도로 시간 창을 분리해 확인한다.
- `nia_rag_embedding`은 계속 0이어야 한다. 그것은 금지된 Judge/Conversation RAG 경로다.

## 검증 범위

- runtime JSONL exporter/import artifact: 명시적 quality gate, JSONL digest, exact v11 closed-metadata query policy가 든 aggregate-only
  actual retrieval-audit.v4 `PASS`, 그 audit digest에 각각 묶인 blind-quality `PASS`와 fresh comparative RAG-value `PASS`, trusted
  16-source fingerprint-set binding, 검색 가능 카드 수, private file mode 검증.
- 실제 OpenAI embedding + H2 암호화 저장 + query + top-2 selection + prompt trace 비노출 통합 테스트.
- `NIA_PRIVATE_HUMAN_STYLE_RETRIEVAL_EVAL_FILE`이 주어질 때, 독립적으로 작성·라벨링한 private eval query로 의미 검색을 검증한다.
  각 query는 원문 카드의 문장을 그대로 복사하지 않고, 허용되는 카드 ID를 미리 지정한다. 같은 enum만 반환해야 하며
  retrieval-required query는 hit@1 70% 이상·hit@2 90% 이상, abstention query는 빈 결과여야 한다. 결과 로그에는 카드 문구·query·원문 trace를 넣지 않는다.
- 자동 holdout은 안전과 최소 유용성 gate다. enum 일치·top-2·source 다양성·embedding query 뒤 빈 결과 없음에 더해,
  독립 장면의 참고 카드 반환율이 80% 이상이어야 한다. 이 기준은 안전 중단을 검색 품질 통과로 오인하지 않게 한다. 다만
  순위 relevance·말투 리듬·문맥 독립성·provider prompt 안전성·반복성은 별도의 blinded human quality review가 실제로 판정한다.
- blind bundle은 현재 장면과 실제 provider에 전달되는 `rank`·`prompt_surface`·`provider_style_pattern`만 담는다. 카드 ID,
  원문 말풍선, `situation`, `style_signals`, embedding, score, source fingerprint는 검수자에게도 보이지 않는다.
- RAG-value review는 같은 bundle을 fixed response-mode baseline과 비교한다. `STYLE_PATTERN`이 기본 enum 지시보다 실제로 더 적절한
  반응 리듬을 제공하는지와, 그 과정에서 근거 없는 세부 사실을 요구하지 않는지를 독립 검수한다.
- unit/JPA tests: `SPEAK` 전 경로에 RAG 호출 없음, 최대 2개 selection, 외부 embedding 입력에 원문 앞 대화·사람 답변 제외,
  DB payload/vector 암호화, 20자 복사 차단.

### 2026-08-05 v18 실제 retrieval 및 blinded product-quality 검증 상태

- 실제 OpenAI `text-embedding-3-small` endpoint로 v18 private candidate를 ephemeral H2 메모리 DB에만 색인·조회했다.
  1,536개의 encrypted audit record와 540개의 검색 가능 카드는 모두 1,536차원 scene/rhythm embedding을 받았다.
  35개 fixed probe와 35개 독립 holdout에서 같은 public enum의 top-2가 모두 반환됐고, holdout 35개 모두 서로 다른 source의
  두 참고를 받았으며 embedding query 뒤 빈 결과와 정책상 abstention은 모두 0건이었다.
- 이 값은 transport·색인·enum 경계·source 다양성의 구조 gate만 뜻한다. 실제 장면 적합성은 top-2 reference를 source ID·점수 없이
  blind bundle로 만든 뒤, 각 enum을 모르는 fresh reviewer가 판정했다.
- 70건(각 public enum 10건)의 v18 blinded review 결과는 top-1 useful **20/70**, top-2-any useful **26/70**이었다.
  product gate인 전체 top-1 65%, top-2 80%와 모든 enum별 minimum을 충족하지 못했다. `REACTION`, `FOLLOW_UP`,
  `SPECULATION`은 top-2-any useful이 0/10이었다.
- reviewers는 provider prompt safety 49/70, private-context dependency 51/70, copy risk 53/70을 별도 finding으로 기록했다.
  실제 선택된 140개 reference가 모두 `CONTEXT_RESPONSE_PAIR`였기 때문에, context-pair 우선 선택이 가벼운 response-only
  fallback을 전혀 검증하지 못한 것도 확인됐다.
- 따라서 candidate digest와 retrieval-audit digest에 묶인 aggregate-only blind-quality review는 명시적으로 `FAIL`이며,
  prepare/import gate가 이 review를 artifact 생성 전에 거부하는 것까지 확인했다. broad enum fallback, 점수 임계값 완화,
  `response_move`를 hard gate로 되돌리는 방식으로 이 실패를 덮는 것은 금지한다.
- v24 후보는 실제 provider에 원문 대화·실제 답변 대신 닫힌 metadata 기반 `STYLE_PATTERN`만 보낸다. 카드의 앞 장면 trait와
  현재 마지막 발화 trait가 같을 때만 card-specific form/rhythm을 쓰고, 더 좁은 response move는 두 쪽의 정확한 일치가 있을 때만
  provider에 올린다. 이 표면을 대상으로 같은 70-case semantic blind review와 fresh comparative RAG-value review를 새 candidate
  digest에 묶어 재실행한다. 그 전까지 production DB import, live Discord 발화, 실제 서비스 traffic 검증은 수행하지 않는다.

이 계약은 과거의 [초기 설계안](human-dialogue-speech-rag-plan.md)을 대체한다.
