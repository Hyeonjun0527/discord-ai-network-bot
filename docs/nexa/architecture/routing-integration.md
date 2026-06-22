# 연동 계약: speech → routing (CloudLlm anti-corruption port)

- 작업: NEXA-P01-T010 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0006 중앙 클라우드 LLM 백엔드](../../adr/0006-central-cloud-llm-backend.md)
- 계약 대상: `central-server/.../central/speech/application/port/out/**`
- 근거 기준선: [current-llm-flow.md](../baseline/current-llm-flow.md)

## 목적

speech가 외부 모델을 호출할 때 **기존 routing의 provider-neutral `CloudLlm` 유스케이스만** 거치게
하여, 모델 제공자 세부(특히 provider-agent GLM·Z.AI)가 speech 도메인에 새지 않게 한다.

## 포트 (speech.application.port.out)

```
interface SpeechModelPort {
    fun generate(plan: SpeechModelRequest): SpeechModelResult
}
```

- `SpeechModelRequest`/`SpeechModelResult`는 speech 도메인 타입이다(제공자 중립).
- 구현 어댑터는 routing의 `CloudLlm`(ADR 0006)으로 위임한다. speech는 어댑터 구현을 모른다.

## 금지 (acceptance — ArchUnit 강제, ADR 0008)

- `speech.domain`·`speech.application`은 `provider-agent` glm.py 개념, 특정 Z.AI SDK 타입,
  `glm-*` 모델 식별자 문자열에 의존하지 않는다.
- speech는 외부로 직접 HTTP를 보내지 않는다 — 외부 호출 신뢰 경계는 routing/central에만 있다
  (ADR 0006의 SSRF 경계 유지).

## 책임 분리

| 관심사 | 소유 |
| --- | --- |
| 모델 선택·제공자 폴백·쿼터·requestlog | routing |
| 프롬프트 구성·후보 문구·버스트 계획 | speech |
| 정책 통과 후 분기(로컬/클라우드) | routing `RequestOrchestrator`(ADR 0006) |

## 불변식

1. NEXA 발화의 모든 외부 모델 호출은 routing을 거쳐 차단·한도·채널 정책·관측 일관성을 받는다.
2. speech는 `SpeechModelPort` 하나로만 모델에 접근한다(직접 제공자 호출 금지).
