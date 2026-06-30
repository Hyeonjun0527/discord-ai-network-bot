# 바운디드 컨텍스트 계약: conversation (관찰)

- 작업: NEXA-P01-T003 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.conversation`
- 근거 기준선: [current-autoresponse-flow.md](../baseline/current-autoresponse-flow.md)

## 책임 (한 문장)

Discord에서 일어난 일을 **정규화된 관찰 사실로 기록·투영**한다. 무엇이 일어났는가만 알고,
**무엇을 할지(행동)는 결정하지 않는다.**

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 정규화 이벤트(NormalizedEvent) | platform/discord가 변환한 `DiscordEventEnvelope`를 받아 도메인 이벤트(message/edit/delete/typing/reaction)로 정규화 |
| 버스트(Burst) | 같은 화자·짧은 간격의 연속 메시지를 하나의 발화 묶음으로 그룹화 |
| 스레드(Thread) | 답글·참조 관계로 이어진 메시지 사슬 |
| 장면 projection(Scene) | 채널의 최근 N개 이벤트를 "지금 무슨 대화가 오가는가"로 요약한 읽기 모델 |
| 원문 맥락 window(RawContextWindow) | consent가 허용한 채널 원문 메시지를 문자 수 한도 안에서 오래된 메시지부터 pruning하는 현재 장면 1차 근거 |

## 비소유 (Does NOT own)

- **행동 선택(IGNORE/WAIT/REACT/SPEAK/CANCEL)** → `participation` 소유
- **사람·사건의 장기 기억·관계** → `socialmemory` 소유
- **문장 생성** → `speech` 소유
- **전송 타이밍·상태 머신** → `actionruntime` 소유
- **few-shot 판단 헌법** → participation/admin 운영 자산. conversation은 예시를 해석하지 않는다

## 포트

- 인바운드: `IngestNormalizedEvent`(platform/discord 어댑터가 호출)
- 아웃바운드: 없음(상태 변경을 외부에 명령하지 않음). 다른 컨텍스트는 `SceneQuery`/`BurstQuery`
  같은 **읽기 포트**로 conversation을 조회만 한다. NIA judge v2는 `RawContextWindowQuery`로 최신 원문
  window를 읽되, 원문 선택·요약·행동 판단을 conversation에 밀어 넣지 않는다.

## 금지 의존성 (ArchUnit으로 강제 — ADR 0008)

- `conversation.domain`은 Spring, JPA, JDA, 그리고 participation/speech/actionruntime/socialmemory
  application·adapter에 의존하지 않는다(`migratedDomainsArePure` 패턴 확장).
- conversation은 AI 모델(`CloudLlm`)을 호출하지 않는다 — 관찰은 추론을 트리거하지 않는다.

## 다른 컨텍스트와의 관계

- platform/discord → conversation: 정규화 이벤트 인입(단방향).
- conversation → participation: participation이 장면·버스트를 **읽어** 행동을 판단(conversation은
  participation을 모름; 의존 방향은 participation → conversation 읽기 포트).
- conversation → socialmemory: socialmemory가 관찰에서 일화·관계를 **추출해 갱신**(socialmemory가
  conversation 읽기 포트에 의존).

## 불변식

1. conversation에 쓰기를 유발하는 유일한 입력은 정규화된 Discord 이벤트다.
2. conversation의 어떤 메서드도 "응답할지 여부"를 반환하지 않는다.
3. 장면 projection은 순수 읽기 모델이며 외부 호출(모델·네트워크) 없이 계산된다.
4. raw context window는 현재 장면의 1차 근거다. 요약·feature·socialmemory는 이를 대체할 수 없다.
5. raw context retention은 message boundary를 보존하고, 한도 초과 시 가장 오래된 메시지부터 제거한다.
