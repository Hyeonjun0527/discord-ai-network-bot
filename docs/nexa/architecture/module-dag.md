# NEXA 모듈 의존 DAG

- 작업: NEXA-P01-T021 (`human_gate: true`, decision) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md),
  [ADR 0008 ArchUnit 유지](../../adr/0008-spring-modulith-evaluation.md)
- 근거 기준선: [central-package-graph.md](../baseline/central-package-graph.md)
- 강제: ArchUnit(ADR 0008) — 본 DAG의 화살표 반대 방향 의존을 금지 규칙으로 구현(P01-T022/T023)

## 의존 방향 규약

`A → B` = "A가 B에 컴파일 의존(또는 포트 호출)". 화살표는 한 방향만 허용한다. 순환 금지.

## NEXA 핵심 사슬 (관찰 → 선택 → 발화 → 실행)

```
platform/discord(adapter)
        │  EventIngested
        ▼
   conversation ──────(읽기)──────┐
        │                         │
        ▼                         ▼
  participation ──(SPEAK 요청)──▶ speech ──(CloudLlm)──▶ routing
        │                         │  (읽기)
        │ ScheduleAction          ├──▶ socialmemory(읽기)
        ▼                         ├──▶ ainetwork(정체성 브리지, 읽기)
  actionruntime ─────────────────┴──▶ knowledge(SPEAK+retrieval 시)
        │  SendToDiscord
        ▼
platform/discord(adapter, outbound)

socialmemory ──(InteractionObserved 구독)── conversation
socialmemory ──(NiaAffinityBridge 읽기)──── ainetwork
```

## 읽기 의존(정책·설정·정체성 — 단방향 in)

```
guild 정책   ──(EffectiveGuildPolicyView)──▶ participation
channelai    ──(ChannelAiIdentity/ModeView)─▶ participation, speech
licensing    ──(FeatureGateView)───────────▶ participation, speech
globalpromptset/identity ─(IdentityKernelView)▶ speech
```

기존 19개 도메인(routing/quota/requestlog/knowledge/ainetwork/channelai/guild/onboarding/
licensing 등)은 [central-package-graph.md](../baseline/central-package-graph.md) 스냅샷을 따르며,
NEXA는 이들을 **읽기 포트로만** 연결한다(역의존 금지).

## 잠재 순환과 해소 (acceptance — 금지 의존 식별)

| 잠재 순환 | 문제 | 해소 |
| --- | --- | --- |
| participation ⇄ actionruntime | participation이 예약(`ScheduleAction`)하고 actionruntime이 전송 직전 재평가(`ReEvaluate`)를 되부름 | **DIP**: actionruntime이 `ReEvaluationPort`(out)를 정의, participation이 구현(어댑터). 또는 `ActionReEvaluated` 이벤트로 비동기화. 컴파일 의존은 participation→actionruntime 단방향 |
| speech ⇄ actionruntime | actionruntime이 전송 직전 `ResolveSpeech`로 speech 계획 확정 조회 | speech는 actionruntime을 모른다. actionruntime이 speech 읽기 포트를 호출(actionruntime→speech 단방향) |
| conversation ⇄ socialmemory | socialmemory가 관찰을 구독, conversation은 기억을 모름 | 이벤트 구독(socialmemory→conversation 읽기/구독 단방향). conversation은 socialmemory에 의존하지 않음 |
| 어댑터 역참조 | 도메인이 JDA/glm 구현에 의존 | platform/discord·routing 어댑터 경계 뒤로 숨김(speech→routing 포트, 도메인→JDA 금지) |

## 금지 의존 (ArchUnit 규칙으로 구현, T022/T023)

1. conversation은 participation/speech/actionruntime/socialmemory에 의존하지 않는다(관찰은 하류를 모름).
2. participation은 speech 문장 생성·actionruntime 전송 **구현**에 의존하지 않는다(포트만).
3. speech는 JDA·provider-agent glm·Z.AI SDK 타입에 의존하지 않는다(routing 포트만).
4. 기존 19개 도메인은 NEXA 신규 패키지에 **역의존하지 않는다**(NEXA→기존 읽기 포트만, T023).
5. 모든 도메인(`*.domain`)은 Spring/JPA/JDA에 의존하지 않는다(기존 `migratedDomainsArePure` 확장).

## 불변식

1. 전체 그래프(기존 19 + NEXA 6)는 비순환이다(DAG). 양방향 협력은 DIP 포트 또는 이벤트로 단방향화한다.
2. NEXA는 기존 도메인을 읽기 포트로만 소비하고 기존 도메인은 NEXA를 모른다.
3. 본 DAG의 화살표 반대 방향 의존은 ArchUnit 금지 규칙으로 강제된다(T022/T023).
