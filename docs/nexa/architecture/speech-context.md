# 바운디드 컨텍스트 계약: speech (발화 생성)

- 작업: NEXA-P01-T007 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.speech`
- 근거 기준선: [current-llm-flow.md](../baseline/current-llm-flow.md)
- 포트 계약: routing-integration.md (`docs/nexa/architecture/routing-integration.md`, T010 예정)

## 책임 (한 문장)

participation이 `SPEAK`를 고른 **뒤에만**, 장면·기억·정체성을 사용해 **후보 문구와 버스트
계획**을 만든다. **말할지 여부는 정하지 않고, JDA로 직접 보내지도 않는다.**

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 발화 계획(SpeechPlan) | 하나의 SPEAK에 대한 후보 문구 + 버스트 분할(여러 메시지로 나눠 보낼지)·순서·간격 |
| 프롬프트 구성 | 장면(conversation) + 기억(socialmemory) + 정체성(ainetwork 니아 페르소나)을 합쳐 모델 입력 구성 |
| 후처리 | 길이·안전·톤 정리(가드레일은 기존 central 정책 재사용) |

## 비소유 (Does NOT own)

- **말할지 여부** → `participation`(speech는 SPEAK 이후에만 호출됨)
- **전송·재시도·타이밍** → `actionruntime`
- **외부 모델 제공자 선택·쿼터·요청 로그** → `routing`(provider-neutral)
- **정체성 SSOT** → `ainetwork`(speech는 페르소나를 읽어 프롬프트에 주입만)

## 외부 모델 호출 규칙 (anti-corruption — T010)

- speech는 **오직** routing의 provider-neutral `CloudLlm` 포트만 호출한다([ADR 0006](../../adr/0006-central-cloud-llm-backend.md) 재사용).
- `provider-agent`의 `glm.py`·특정 Z.AI SDK 타입·모델 식별자 문자열이 `speech.domain`에 노출되지
  않는다. 모델 선택·정책·쿼터·requestlog는 routing이 책임진다([current-llm-flow.md](../baseline/current-llm-flow.md)).
- 따라서 NEXA 발화도 반드시 central routing을 거쳐 차단·한도·채널 정책·관측 일관성을 유지한다.

## 포트

- 인바운드: `BuildSpeechPlan(decisionContext)`(participation이 SPEAK일 때 요청, actionruntime이
  전송 직전 `ResolveSpeech`로 확정 조회)
- 아웃바운드:
  - `SceneQuery`(conversation 읽기), `SocialMemoryQuery`(socialmemory 읽기),
    `IdentityQuery`(ainetwork 페르소나 읽기)
  - `CloudLlm`(routing 아웃바운드 포트)

## 금지 의존성 (ArchUnit으로 강제 — ADR 0008)

- `speech.domain`은 Spring/JPA/JDA에 의존하지 않는다.
- speech는 JDA를 직접 호출하지 않는다(전송은 actionruntime→platform/discord).
- speech는 participation의 행동 결정 로직을 포함하지 않는다(말할지 여부 재결정 금지).
- speech는 provider-agent glm·Z.AI SDK 타입에 의존하지 않는다(`CloudLlm` 포트만).

## 다른 컨텍스트와의 관계

- participation → speech: SPEAK 시 발화 계획 요청.
- speech → conversation/socialmemory/ainetwork: 프롬프트 구성용 읽기.
- speech → routing(CloudLlm): 텍스트 생성.
- actionruntime → speech: 전송 직전 계획 확정 조회.

## 불변식

1. speech는 participation의 SPEAK 결정 없이는 호출되지 않는다.
2. speech의 모든 외부 모델 호출은 routing `CloudLlm` 포트를 거친다(직접 외부 HTTP 금지).
3. speech는 발화 계획을 반환할 뿐 전송하지 않는다.
4. 발화 계획은 actionruntime의 재평가로 폐기될 수 있다(speech는 전송을 보장받지 않음).
