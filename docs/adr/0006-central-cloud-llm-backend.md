# ADR 0006: 중앙 서버 OpenAI Luna 백엔드

- 상태(Status): 채택됨 (Accepted)
- 최초 결정(Date): 2026-06-19
- 최신 개정(Amended): 2026-07-18
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0002 리버스 터널 에이전트](./0002-remote-agent-byollm.md),
  [ADR 0003 커뮤니티 로컬 AI Provider Pool](./0003-community-provider-pool.md)

## 맥락

Discord `/질문`과 NIA의 판단·발화는 Provider Pool 가용성과 별개로 동작하는 중앙 클라우드 경로가
필요하다. 이전 구현은 z.ai GLM을 중앙과 Provider Agent 양쪽에서 호출했지만, 모델 품질과 운영 경로를
하나로 통일하기 위해 2026-07-18부터 OpenAI Luna만 사용한다.

## 결정

### 중앙 직접 호출

`CloudLlm`의 운영 구현은 `OpenAiCloudLlm` 하나다. `RequestOrchestrator`는 모든 정책 검사 이후
요청 모델이 `gpt-*`이고 OpenAI 키가 설정됐을 때만 Provider Pool을 건너뛰고 중앙에서 직접 처리한다.
로컬 Ollama 모델은 기존 `ProviderSession.sendInfer` 경로를 유지한다.

- API: `POST https://api.openai.com/v1/responses`
- 인증 설정: `central.cloud.openai-api-key` / `OPENAI_API_KEY`
- 기본 모델: `gpt-5.6-luna`
- 기본 모델 설정: `OPENAI_FREE_MODEL`, `OPENAI_FAST_MODEL`, `NEXA_SPEECH_MODEL`,
  `NEXA_PARTICIPATION_JUDGE_MODEL`
- 키가 없으면 중앙 클라우드 경로는 비활성화되며 Z.AI로 폴백하지 않는다.

### Reasoning none 불변

모든 OpenAI 요청은 호출자의 힌트와 무관하게 다음 설정을 보낸다.

```json
{
  "reasoning": { "effort": "none" },
  "store": false
}
```

운영자가 reasoning을 켤 수 있었던 Discord `thinking` 옵션과 규칙 기반 `ThinkingRouter`는 제거한다.
타임아웃과 재시도는 `OPENAI_LLM_TIMEOUT_SECONDS`, `OPENAI_LLM_MAX_RETRIES`로 관리한다.

### Responses API 계약

대화는 `input`의 `user`/`assistant` 항목으로 보내고, 시스템 지시는 `instructions`에 둔다. 응답은
`output[].content[].text`, tool call은 `output[type=function_call]`, 사용량은
`usage.input_tokens`/`usage.output_tokens`에서 읽는다. 함수 도구는 Responses API의 평면 형태
(`type`, `name`, `description`, `parameters`)로 변환한다.

Luna 경로에는 `temperature`를 보내지 않는다. 발화 다양성은 최근 장면, 관리형 few-shot, 완전 행동 후보와
사후 평가에서 만들며, 지원이 불명확한 샘플링 파라미터에 의존하지 않는다.

### Provider Agent의 외부 텍스트 LLM 제거

Provider Agent는 로컬 Ollama와 이미지 픽셀 백엔드만 제공한다. `ZAI_API_KEY`, `GlmClient`, GLM 모델
광고와 런타임 키 변경 API는 제거한다. 구버전 데스크톱 계약의 `geminiConfigured` 필드는 하위호환을 위해
항상 `false`로만 반환한다.

이미지 프롬프트 심사와 번역도 중앙 OpenAI 경로가 담당한다. Provider Agent는
`imagePolicy.preTranslated=true`가 없는 이미지 요청을 fail-closed로 거부하고, 확인된 프롬프트의 픽셀
생성만 수행한다.

### 정책과 보안

- 차단 사용자, 일일 한도, 채널 허용, 모델 부담 정책을 통과한 요청만 중앙 클라우드로 간다.
- `store=false`로 OpenAI 응답 저장을 요청하지 않는다.
- API 키는 config tree 또는 환경 변수로만 주입하고 저장소·로그·응답에 노출하지 않는다.
- 직접 호출 성공은 합성 provider ID를 사용해 실제 커뮤니티 기여 통계와 구분한다.
- 이미지 안전 심사 실패나 OpenAI 비활성 상태에서는 생성하지 않는다.

## 결과

### 장점

- NIA 판단·발화·관리 도구와 무료질문의 모델 및 API 계약이 하나로 통일된다.
- Provider Agent에 외부 LLM 키를 배포하지 않는다.
- reasoning과 저장 정책이 호출 지점마다 달라질 수 없다.
- 이미지 안전 판단과 픽셀 생성의 신뢰 경계가 분명해진다.

### 트레이드오프

- OpenAI 키나 Luna 모델이 중단되면 중앙 클라우드 기능이 함께 중단된다.
- 클라우드 비용이 중앙 운영 키에 집중된다.
- `gpt-5.6-luna` 접근 권한은 배포 전 운영 계정에서 별도로 확인해야 한다.

## 비-목표

- 커뮤니티 로컬 Ollama Provider Pool 제거
- 클라우드 비용 정산 또는 판매 기능 도입
- Provider Agent의 Stability, RunPod, ComfyUI 이미지 픽셀 백엔드 제거
