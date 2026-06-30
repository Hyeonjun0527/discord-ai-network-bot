# 바운디드 컨텍스트 계약: participation (행동 선택)

- 작업: NEXA-P01-T004 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.participation`
- 근거 기준선: [current-autoresponse-flow.md](../baseline/current-autoresponse-flow.md),
  [social-model-overlap.md](../baseline/social-model-overlap.md)

## 책임 (한 문장)

지금 이 장면에서 NEXA가 **무엇을 할지 단 하나의 행동을 고른다**: `IGNORE / WAIT / REACT /
SPEAK / CANCEL`. **말 자체를 만들거나 보내지 않는다.**

ADR 0016 이후 humanlike v2 경로의 최종 결정자는 단일 participation judge다. judge는 conversation의
원문 window, active few-shot version, socialmemory 보조 기억, consent/channel metadata를 함께 보고
정확히 하나의 action을 반환한다.

## 채널 의도와 경계

| 채널 의도 | 대표 채널 | 책임 경로 | 불변식 |
| --- | --- | --- | --- |
| 자동응답/비서형 채널 | `ai채팅` | 기존 channelai/autoRespond 호환 경로. 멘션 없이도 "답변 요청"으로 다룬다. | 레거시 호환을 위해 유지하며, `니아수다`의 자율 참여 판단과 섞지 않는다. |
| 사람처럼 참여하는 멤버 채널 | `니아수다` | participation MEMBER 경로. 단일 judge가 원문 window와 scene snapshot을 보고 행동 하나를 고른다. | `autoRespond=true`로 켜지지 않는다. 최종 행동은 `IGNORE / WAIT / REACT / SPEAK / CANCEL_PENDING` 중 하나다. |

`니아수다`에서 반복 호출, 위로 요구, 대화 공백이 보이더라도 곧바로 "위로" 같은 세부 enum을 만들지 않는다.
원문 window와 구조화 scene signal을 단일 judge의 evidence로 넣고, 그 judge가 말할지/기다릴지/반응만 할지/침묵할지를
고른다. speech는 `SPEAK` 이후 실제 문구만 만들고, actionruntime은 예약·취소·전송만 수행한다.

message/typing/idle tick/pending wake-up의 실제 깨움 표면은
[participation-runtime.md](./participation-runtime.md)에 둔다. 이 경로는 3표결 없이 단일 judge 평가만 시작한다.

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 행동 결정(ParticipationDecision) | `IGNORE`(무시) / `WAIT`(더 지켜봄) / `REACT`(반응-이모지 등) / `SPEAK`(발화) / `CANCEL`(예약 취소) 중 하나 |
| feature | 결정 입력 신호(멘션 여부, 질문 형태, 호명, 최근 발화 간격, 채널 모드, 호감도 등)를 정규화한 값 |
| decision log | 어떤 feature로 어떤 행동을 왜 골랐는지의 감사 로그(원문 없이 correlation ID로 연결) |
| 발화 타이밍 정책 | 언제 SPEAK로 전환할지, 연속 발화 억제(쿨다운) 규칙 |
| few-shot 헌법 | admin에서 draft/eval/publish/rollback되는 판단 사례집. 규칙 편집기가 아니라 judge의 judgment prior |

## 비소유 (Does NOT own)

- **관찰 사실(장면·버스트)** → `conversation`에서 읽기만 함
- **기억·관계 상태** → `socialmemory`에서 읽기만 함
- **문장 생성** → `speech`(SPEAK 결정 이후 호출됨)
- **실제 Discord 전송·재시도** → `actionruntime`
- **채널 AI 프로필·모드 SSOT** → `channelai`(participation은 모드를 입력으로 읽음, ADR 0009)
- **원문 메시지 보관·pruning** → `conversation`

## 포트

- 인바운드: `EvaluateParticipation(scene, channelPolicy)` — 새 이벤트/타이머로 트리거
- 아웃바운드:
  - `SceneQuery`/`BurstQuery`(conversation 읽기)
  - `SocialMemoryQuery`(socialmemory 읽기)
  - `FewShotSetQuery`(active few-shot version 읽기)
  - `RequestSpeech`(SPEAK일 때 speech에 후보 문구 계획 요청)
  - `ScheduleAction`/`CancelAction`(actionruntime에 실행 예약·취소 명령)
  - `ReEvaluationPort` **구현**(actionruntime이 소유한 포트 인터페이스를 participation이 구현) —
    전송 직전 재평가 요청을 받아 유효성을 답한다. 이 DIP로 participation→actionruntime 단방향이
    유지되어 순환이 없다([module-dag.md](./module-dag.md))

## 금지 의존성 (ArchUnit으로 강제 — ADR 0008)

- `participation.domain`은 Spring/JPA/JDA에 의존하지 않는다.
- participation은 `speech`의 문장 생성 구현·`actionruntime`의 전송 구현·JDA를 직접 호출하지
  않는다(포트를 통해 명령/요청만).
- participation은 `CloudLlm`을 직접 호출하지 않는다 — 발화 텍스트는 speech의 책임.

## 다른 컨텍스트와의 관계

- conversation/socialmemory → (읽기) → participation: 결정 입력.
- participation → speech: SPEAK일 때만 발화 계획 요청.
- participation → actionruntime: 실행 예약/취소(REACT·SPEAK 결과를 언제 보낼지).
- channelai → participation: 채널 모드/자동응답 설정을 입력으로 제공(자동응답 트리거의 이관 대상).

## 불변식

1. 한 번의 평가는 정확히 하나의 행동을 반환한다(복수 행동 동시 선택 금지).
2. participation은 텍스트를 만들지 않는다 — SPEAK는 "말하기로 결정"이지 "말의 내용"이 아니다.
3. 모든 비-IGNORE 결정은 decision log에 근거 feature와 함께 기록된다.
4. participation만이 "말할지 여부"의 최종 결정자다(speech·actionruntime은 이를 뒤집지 않음).
5. 허용 action은 `IGNORE / WAIT / REACT / SPEAK / CANCEL`뿐이다. `EMOTIONAL_SUPPORT` 같은 감정·상황 enum을 action-selection driver로 추가하지 않는다.
6. baseline heuristic과 core intervention rules는 final judge를 대체할 수 없다. safety는 차단·하강만 가능하며 SPEAK를 강제하지 않는다.
