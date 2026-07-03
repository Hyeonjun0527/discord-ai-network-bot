# 바운디드 컨텍스트 계약: socialmemory (기억)

- 작업: NEXA-P01-T005 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.socialmemory`
- 근거 기준선: [social-model-overlap.md](../baseline/social-model-overlap.md)

## 책임 (한 문장)

NEXA가 사람과 사건에 대해 **관찰로 알게 된 것**을, 시간 유효성을 가진 기억으로 보관한다 —
일화·사실·관계·보류 의도. **문서 검색(knowledge RAG)도, 전역 호감도 점수(ainetwork)도 아니다.**

ADR 0016 이후 socialmemory는 judge 입력의 보조 기억이다. 현재 장면의 raw conversation window를
대체하거나, "외로움이면 말하기" 같은 행동 규칙을 소유하지 않는다.

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 일화(Episode) | "언제 누구와 무슨 일이 있었다"는 시점 있는 사건 기억 |
| 사실(Fact) | 사용자가 밝힌/관찰된 안정적 속성(예: 선호, 역할) — 변할 수 있어 유효기간을 가짐 |
| 관계(Relationship) | guild·channel 스코프에서 관찰 가능한 사회적 관계 상태(친밀도/맥락) |
| 보류 의도(PendingIntent) | "나중에 이걸 하기로 했다"는 미완 의도(예: 답장 약속) |

각 항목은 **시간 유효성(valid-from/until, decay)** 을 가진다 — 오래된 기억은 약화·만료된다.

## 비소유 (Does NOT own)

- **문서·지식 검색(RAG)** → `knowledge`(socialmemory는 일화적 기억, RAG는 외부 문서 근거; 별개)
- **니아 정체성·전역 호감도 스칼라** → `ainetwork`(ADR 0010에서 경계·브리지 확정)
- **관찰 원천 이벤트** → `conversation`(socialmemory는 거기서 추출)
- **행동 결정** → `participation`(기억을 입력으로만 읽음)
- **현재 장면 원문 window** → `conversation`
- **few-shot 판단 헌법** → participation/admin 운영 자산

## knowledge RAG · ainetwork 호감도와의 차이 (acceptance 핵심)

| 축 | socialmemory | knowledge(RAG) | ainetwork 호감도 |
| --- | --- | --- | --- |
| 대상 | 사람·관계·사건(일화적) | 문서·FAQ·서버 지식(의미적) | 사용자별 누적 점수(스칼라) |
| 시간성 | valid-from/until, decay 있음 | 색인 시점 스냅샷 | 단조 누적/감쇠 |
| 스코프 | guild/channel | guild | guild·user |
| 쓰임 | speech가 "이 사람과의 맥락"을 반영 | speech가 "사실 근거"를 인용 | 페르소나 톤 조절 입력 |

연결 포트: socialmemory는 ainetwork 호감도를 **읽기 포트**(`AffinityQuery`)로 참조하되 **복제
저장하지 않는다**. 중복 필드의 소유자·브리지·마이그레이션은 ADR 0010(`docs/adr/0010-ainetwork-socialmemory-boundary.md`, T009 예정)에서 표로 확정한다.

## 포트

- 인바운드: `RecordObservation(fromScene)` — conversation 관찰에서 일화/사실/관계 추출·갱신
- 아웃바운드: `AffinityQuery`(ainetwork 읽기). 외부 모델 호출 없음(추출이 LLM을 쓰면 routing
  `CloudLlm` 포트 경유, speech와 동일 anti-corruption 규칙).

## 금지 의존성 (ArchUnit으로 강제 — ADR 0008)

- `socialmemory.domain`은 Spring/JPA/JDA에 의존하지 않는다.
- socialmemory는 participation/speech/actionruntime 구현에 의존하지 않는다.
- ainetwork 엔티티를 직접 import하지 않고 읽기 포트로만 접근한다.

## 불변식

1. 모든 기억 항목은 시간 유효성 메타데이터를 가진다(영구 진리로 저장 금지).
2. socialmemory는 행동을 결정하지 않는다 — 읽히기만 한다.
3. ainetwork 호감도를 복제 저장하지 않는다(SSOT 단일성, ADR 0010).
4. 개인정보 삭제 요청 시 관련 일화/사실이 함께 만료·삭제 가능해야 한다(P03 이벤트 삭제와 연계).
5. socialmemory는 raw context보다 우선하지 않는다. 오래된 기억이 최신 원문과 충돌하면 judge 입력에서 보조 근거로만 취급한다.
