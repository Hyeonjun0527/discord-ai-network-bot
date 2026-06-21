# 바운디드 컨텍스트 계약: participation (행동 선택)

- 작업: NEXA-P01-T004 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.participation`
- 근거 기준선: [current-autoresponse-flow.md](../baseline/current-autoresponse-flow.md),
  [social-model-overlap.md](../baseline/social-model-overlap.md)

## 책임 (한 문장)

지금 이 장면에서 NEXA가 **무엇을 할지 단 하나의 행동을 고른다**: `IGNORE / WAIT / REACT /
SPEAK / CANCEL`. **말 자체를 만들거나 보내지 않는다.**

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 행동 결정(ParticipationDecision) | `IGNORE`(무시) / `WAIT`(더 지켜봄) / `REACT`(반응-이모지 등) / `SPEAK`(발화) / `CANCEL`(예약 취소) 중 하나 |
| feature | 결정 입력 신호(멘션 여부, 질문 형태, 호명, 최근 발화 간격, 채널 모드, 호감도 등)를 정규화한 값 |
| decision log | 어떤 feature로 어떤 행동을 왜 골랐는지의 감사 로그(원문 없이 correlation ID로 연결) |
| 발화 타이밍 정책 | 언제 SPEAK로 전환할지, 연속 발화 억제(쿨다운) 규칙 |

## 비소유 (Does NOT own)

- **관찰 사실(장면·버스트)** → `conversation`에서 읽기만 함
- **기억·관계 상태** → `socialmemory`에서 읽기만 함
- **문장 생성** → `speech`(SPEAK 결정 이후 호출됨)
- **실제 Discord 전송·재시도** → `actionruntime`
- **채널 AI 프로필·모드 SSOT** → `channelai`(participation은 모드를 입력으로 읽음, ADR 0009)

## 포트

- 인바운드: `EvaluateParticipation(scene, channelPolicy)` — 새 이벤트/타이머로 트리거
- 아웃바운드:
  - `SceneQuery`/`BurstQuery`(conversation 읽기)
  - `SocialMemoryQuery`(socialmemory 읽기)
  - `RequestSpeech`(SPEAK일 때 speech에 후보 문구 계획 요청)
  - `ScheduleAction`/`CancelAction`(actionruntime에 실행 예약·취소 명령)

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
