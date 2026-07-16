# ADR 0006: 중앙 서버 클라우드 LLM 백엔드 (무료질문 직결)

- 상태(Status): 제안됨 (Proposed)
- 날짜(Date): 2026-06-19
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0002 리버스 터널 에이전트](./0002-remote-agent-byollm.md),
  [ADR 0003 커뮤니티 로컬 AI Provider Pool](./0003-community-provider-pool.md) 을 보완한다.

## 맥락 (Context)

`/질문`(무료질문)은 기본적으로 무료 클라우드 모델(`glm-4.5-air`, z.ai)로 답하도록 설계돼 있다
(AskCommandHandler `DEFAULT_FREE_CLOUD_MODEL`, `central.cloud.free-model` 로 오버라이드 가능). 그러나 현재 클라우드 추론은 **각 유저/관리자 PC의
provider-agent**(`provider_agent/glm.py`)가 z.ai 키를 들고 호출하는 구조다. 즉 클라우드
무료질문조차 풀에 **클라우드 모델을 광고하는 에이전트가 온라인**일 때만 동작한다.

결과적으로 **앱(provider-agent)을 설치하지 않은 유저는 무료질문을 쓸 수 없다.** 커뮤니티에
프로바이더가 없거나 전부 오프라인이면 `/질문`이 "온라인 Provider가 없습니다"로 실패한다.
무료질문은 진입장벽이 가장 낮아야 하는데(앱 미설치 신규 유저의 첫 경험), 그 경로가 풀 가용성에
묶여 있는 셈이다.

### 검토한 대안

| 방안 | 요지 | 평가 |
| --- | --- | --- |
| A. 현행 유지(에이전트 경유 클라우드) | 관리자/유저 PC 에이전트가 z.ai 호출 | 앱 미설치 유저 사용 불가, 풀 가용성에 종속 |
| **B. 중앙 직결 + 정책 검사 직후 분기** | central 이 관리자 키 1개로 z.ai 직접 호출. **풀 정책(차단·한도·채널·부담) 검사를 통과한 뒤**에만 분기 | **채택.** 앱 없이 사용 가능 + 정책 우회 0 |
| C. 중앙 직결 + 정책 앞단 분기 | 클라우드면 정책 검사 전에 바로 호출 | 차단 사용자·한도 초과·금지 채널이 클라우드로 새는 **정책 우회 발생** → 거부 |

## 결정 (Decision)

방안 B 를 채택한다. **무료질문(텍스트)만 이번 단계**로 한정한다.

### 1. `CloudLlm` 포트 (단계 0)

`central/routing/application/CloudLlm.kt` 에 아웃바운드 포트를 둔다 — WebSearchAugmenter
(`knowledge/application/WebSearch.kt`)와 **동일한 형태**: 포트 인터페이스 + No-op 기본 구현
(`NoCloudLlm`, 키 없을 때) + 실제 구현(`ZaiCloudLlm`, `@Component`, `@Value` 키 주입,
`java.net.http` 클라이언트). JSON 파싱은 순수 함수(`CloudLlmResponseParser`)로 분리해
단위테스트 가능하게 한다.

```kotlin
interface CloudLlm {
    fun isEnabled(): Boolean
    fun generate(prompt: String, model: String): CloudLlmResult
}
```

- z.ai 는 OpenAI 호환: `POST {base}/chat/completions`, `Authorization: Bearer <key>`,
  body `{"model","messages":[{"role":"user","content":...}]}`, 응답 `choices[0].message.content`.
  base 기본값 `https://api.z.ai/api/paas/v4`, 모델 기본 `glm-5.1`.
- 키는 `central.cloud.zai-api-key`(`${ZAI_API_KEY:}`)로 주입. 비면 `isEnabled()=false`.
- 업스트림 status·body·에러 원문은 **로그로만** 남기고 사용자에겐 일반화 메시지("클라우드 AI
  일시 오류")만 노출한다(예외 원칙 — 내부 상세 미노출).

### 2. 무료질문 라우팅 전환 (단계 1)

`RequestOrchestrator.route()` 에서 **정책 검사(차단 사용자 / 일일 쿼터 / 채널 허용 / 부담
권한) 를 모두 통과한 직후**, 요청 모델이 클라우드(`preferredModel.startsWith("glm")`)이고
`cloudLlm.isEnabled()` 이면 풀 후보 선택/`sendInfer` 를 건너뛰고 `cloudLlm.generate()` 로 직접
처리한다. 성공 시 기존 성공 기록 경로(`recorder.recordSuccess`)를 호출해 통계/로그 일관성을
유지하고 `OrchestrationResult(COMPLETED, providerId=null)` 을 반환한다.

키가 없으면(`isEnabled()=false`) **기존 동작 그대로** — 에이전트 경유 `glm-*` 폴백(하위호환·
롤백 안전). 로컬(Ollama 등 비-glm) 라우팅은 전혀 바뀌지 않는다(`sendInfer` 경로 보존).

### 3. 텍스트 LLM 호출 — thinking 무조건 off + 20초 타임아웃 (불변)

`central.cloud.llm-timeout-seconds` 는 **20초**이고, 1회 시도가 이를 넘으면 취소하고 최대
`llm-max-retries`(2)회만 재요청한다. **thinking(추론 모드)은 무조건 disabled** 로 전송한다.

- **thinking off 근거(실측)**: z.ai GLM 은 thinking 을 명시 안 하면 서버 기본이 "생각 ON"이라 응답이 **7~8초**,
  thinking:disabled 명시 시 로컬 실측 **<2.5초**(큰 프롬프트 포함)다. 즉답 채팅 봇엔 추론 지연이 해이므로,
  `CloudLlm.postChat` 은 호출자가 무엇을 넘기든 **항상 `thinking:disabled`** 를 보낸다(ThinkingRouter/override 무효).
- **타임아웃 20초 근거**: thinking off 면 로컬에선 <2.5초지만, **원격 운영 서버(ssh.yeon.world)→api.z.ai 네트워크
  지연이 로컬보다 커서** 3~4초로는 자주 초과했다(실운영 "시간 초과(3초·3회)" 관측). 20초로 여유를 둬 느린 연결도
  담되, 이는 상한일 뿐 정상 응답은 여전히 수 초 내에 끝난다. thinking 이 켜져 있었다면 20초로도 부족했을 것이므로
  **thinking off 가 선행 조건**이다(타임아웃만 올리는 건 thinking off 없이는 해결이 아니다).
- 실패 사유는 `CloudLlmResponseParser` 가 추출해 사용자(운영자)에게 카테고리로 노출한다(인증/모델없음/잔액/한도/
  서버오류/시간초과/연결실패). `ZAI_LLM_TIMEOUT_SECONDS` 는 compose 가 컨테이너 env 로 넘기지 않으므로 이
  application.yml 기본값(20)이 운영에 그대로 적용된다.
- thinking off·타임아웃 값을 바꾸려면 이 ADR 을 먼저 갱신한다(설정만 몰래 바꾸지 않는다).

### 3-1. 모델 통일 — 모든 GLM 경로는 `glm-4.5-air` (불변)

무료질문·니아 발화 생성(speech)·participation **판단(judge)**·관리 비서 tool calling — 모든 GLM 경로의 기본
모델은 **`glm-4.5-air`** 로 통일한다. `glm-5.1` 은 즉답(속도 최우선)을 제대로 지원하지 못하고 비용도 높아
채팅 봇의 실시간 경로에 부적합하다. 특히 participation judge 는 매 메시지마다 도는 hot path 이므로 가장 빠른
모델이어야 한다. 각 경로는 env 로 개별 override 가능하지만(`ZAI_FREE_MODEL`·`NEXA_SPEECH_MODEL`·
`NEXA_PARTICIPATION_JUDGE_MODEL` 등) 기본값은 모두 `glm-4.5-air` 이며, 기본 모델을 바꾸려면 이 ADR 을 먼저 갱신한다.

### 3-2. 발화는 temperature 로 매번 다르게, 판단은 결정론 (불변)

발화(speech) 생성은 `central.speech.temperature`(기본 0.5)를 z.ai 에 전송하고, **한 번의 모델 호출에서 서로 다른
후보 2개**를 받은 뒤 전송 전 critic 을 통과한 후보만 고른다. 사람처럼 표현은 달라지되, 높은 온도로 페르소나를
벗어나거나 사용자를 밀어내는 문장이 나오는 확률은 낮춘다. temperature=0(결정론)이면 반복 호명에 동일 문장을
되풀이하는 회귀가 생긴다. 반면 **판단(participation judge)·tool calling·무료질문**은 temperature 를 전송하지
않아(z.ai 기본, 결정론에 가까움) 일관된 결정을 유지한다. `CloudLlm.generateSampled(...)` 만 temperature 를 싣고,
나머지 경로(`generate`)는 싣지 않는다. 반복 방지는 (a) 보수적 temperature, (b) 지난 발화 반복 금지 지시,
(c) 후보 2개 중 critic 선택을 함께 사용한다. 후보 수가 늘어도 외부 LLM 호출 횟수는 한 번이다.

### 비-목표 (이번 단계 제외)

- 이미지(클라우드 SD / 안전 심사 / 번역) 직결 — 후속 단계 2(`reviewImagePrompt`/`translate`).
- provider-agent 의 GLM 백엔드 제거 — 후속 단계 3.
- 와이어 계약(`protocol/wire-contract.json`) 변경 — 텍스트 라우팅만, 계약 불변.

## 원칙 관계 (Relationship to ADR 0002 / 0003)

- **SSRF·에이전트 외부호출 금지 원칙 유지·강화**: 외부 HTTP 는 이전과 동일하게 *중앙 서버*에서만
  일어난다. 에이전트는 여전히 임의 URL 로 나가지 않는다. `WebSearch.kt`(SearxngWebSearch)가 이미
  central 에서 외부 검색 API 를 호출하는 선례를 그대로 따른다 — 새로운 신뢰 경계를 만들지 않는다.
- **"AI 모델 판매 서비스가 아니다"(ADR 0003) 정신과의 긴장(명시)**: 클라우드 직결은 관리자 키
  비용을 central 에 집중시킨다. 이는 커뮤니티 기여 풀(0003)과 **대체가 아니라 공존**한다 — 로컬
  프로바이더가 있으면 로컬을 우선(🖥️)하고, 없거나 실패할 때 무료 클라우드(☁️)로 폴백하는 기존
  AskCommandHandler 정책을 유지한다. 클라우드는 "앱 없는 유저의 진입 경로"이지 풀을 대체하는
  판매 서비스가 아니다. 가격표·정산·수익 개념은 도입하지 않는다(0003 비-목표 준수). 0003 은
  수정하지 않고 유효하게 유지한다.

## 결과 (Consequences)

**장점**

- 앱(provider-agent) 미설치 유저도 무료질문을 쓸 수 있다 — 진입장벽 최소화.
- 풀 가용성에 종속되지 않는다(프로바이더 0명이어도 무료질문 동작).
- 정책 검사 **직후** 분기라 차단·한도·채널·부담 정책이 클라우드 경로에도 동일 적용(우회 0).
- WebSearch 선례를 재사용 — 새 인프라/패턴 없이 같은 형태로 읽힌다.

**단점 / 트레이드오프**

- **단일 장애점**: 관리자 z.ai 키가 만료/소진되면 **전 서버의 무료질문(클라우드 경로)**이 한 번에
  중단된다(로컬 프로바이더가 있는 서버는 로컬로 계속 동작). 키 미설정 시 기존 에이전트 폴백으로
  자연 degrade 된다.
- **비용 central 집중**: 무료질문 추론 비용이 관리자 단일 키로 모인다(0003 의 분산 기여와 반대 방향).
  남용 방지는 기존 인당 rate limit(`FreeAskRateLimiter`)과 풀 정책(일일 한도)으로 처리한다.
- `recordSuccess` 의 기여 로그에 central 직결을 합성 providerId(음수 sentinel)로 남긴다 — 실제
  프로바이더 기여 통계와 구분되게 한다.

## 미해결 질문 (Open Questions)

- 키 만료/소진 시 자동 degrade 안내(현재는 일반화 실패 메시지)를 더 명확히 할지.
- 클라우드 직결 사용량을 별도 비용 대시보드로 분리 집계할지(현재 기여 로그에 sentinel 로 혼재).
- 단계 2(이미지)에서 안전 심사(`reviewImagePrompt`)를 central 직결로 옮길 때의 fail-closed 경계.
