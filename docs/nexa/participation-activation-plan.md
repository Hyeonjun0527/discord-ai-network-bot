# NEXA participation 발화 단계적 활성화 설계 (보안 우선)

> 목적: 니아가 "AI 채팅 채널"에서 **스스로 말할지/리액션만/침묵할지 판단해 자발 발화**하는
> NEXA participation 시스템을, 운영 봇·보안을 깨뜨리지 않고 **단계적으로** LIVE 활성화한다.
> 상태: 설계 확정(2026-06-25). 구현은 1단계부터 인간 게이트로 진행.

## 0. 현황 — 코드 조사로 확정한 사실

NEXA participation/speech/보안은 **이미 광범위하게 구현돼 있고, flag OFF 로 dormant** 하다.
미구현이 아니라 "잠들어 있고, 메시지 핸들러에 연결만 안 됨"이 정확한 상태다.

| 영역 | 상태 | 근거 |
|---|---|---|
| 평가 정책(SPEAK/REACT/IGNORE) | 구현됨(Onnx·heuristic·baseline 다수) | `participation/adapter/outbound/policy/**` |
| ShadowMode 4단계 분기 | 구현됨 OFF/SHADOW_PREDICT/CANARY/LIVE | `ParticipationLane`, `ShadowMode.allowsRealSend` |
| Canary 자동 강등(over_talk·complaint·stale) | 구현됨 | `participation/application/rollout/CanaryHaltDecision.kt` |
| **보안 enforcement(emit 경로 강제)** | **이미 wired** | 아래 참조 |
| speech 파이프라인 | 구현됨 | `speech/application/NexaSpeechPipelineService.kt` |
| **emit production 호출자** | **0 (유일한 dormant 지점)** | `NexaSpeechEmitConfig.kt:35` "dormant 보존" |

### 보안은 이미 emit→pipeline 경로에 박혀 있다 (중요)
`NexaSpeechEmitService.emit(NexaSpeechEmitRequest)` → `NexaSpeechPipelineService.run(...)` 내부:
- `consentGate.checkAllowed(subjectPseudonym, ProcessingStage.SPEECH_GENERATION)` — 생성 직전 동의 (`NexaSpeechPipelineService.kt:68`)
- `consentGate.checkAllowed(subjectPseudonym, ProcessingStage.EXTERNAL_GLM_REQUEST)` — 외부 LLM 요청 직전 동의 (`:89`)
- `candidateSelector = securityCriticSelector()` — SpeechCritic·AiIdentityDisclosureCritic 후보 검열 (`NexaSpeechEmitConfig.kt:69`)
- emit seam 자체가 LIVE 모델 무결성 검증(selectForLiveVerified) 강제 (`NexaSpeechEmitService.kt`)

→ **메시지 핸들러가 emit 만 부르면 동의·검열·모델검증이 자동으로 전부 통과된다.**
보안 클래스 전수: ConsentGate·PolicyBackedConsentGate·ConsentRevocationService(철회),
Redaction cascade(MemoryRedactionCascade·CascadeMemoryRedactionService·RedactingMessageConverter),
Critic(SpeechCritic·AiIdentityDisclosureCritic·AssistantStyleDetector·TargetAndSceneCritic).

## 1. 단계적 활성화 (각 단계 = 인간 게이트, AGENTS §5)

### 단계 1 — 메시지 핸들러 → emit wiring (유일한 코드 작업)
- **무엇**: AI 채팅 채널 메시지 수신 시 participation 평가를 돌리고, 결정이 SPEAK 면
  `NexaSpeechEmitService.emit(NexaSpeechEmitRequest)` 를 호출하도록 연결한다.
- **어디**: `platform/discord/nexa/NexaInboundBridge`(flag 게이트 진입점) 또는 `DiscordBot.onMessageReceived`
  의 채팅채널 분기. participation 평가 → `NexaSpeechEmitRequest`(scene packet) 구성 → emit.
- **안전**: 이 단계에서도 ShadowMode 는 **SHADOW_PREDICT 로 고정** — emit 가 평가·기록은 하되
  `allowsRealSend=false` 라 **실제 전송 0**. 즉 wiring 을 켜도 사용자에게 메시지가 안 나간다.
- **사용자 데모 코드(§2 보존)**: `platform/discord/nexa/NexaLiveSpeechService.kt`(미커밋)가
  pipeline.run 을 호출하는 데모다 — **참고만 하고 수정/의존 금지**. production wiring 은 독립 작성.
- **검증**: SHADOW_PREDICT 로 며칠 운영하며 participation_decision_log 의 판단 품질(SPEAK/SILENT 비율,
  오발화 후보) 관측. 전송 0 이므로 사용자 영향 0.

### 단계 2 — SHADOW_PREDICT 관측 (flag, 코드 0)
- ShadowMode=SHADOW_PREDICT 유지. ShadowStatusController·ShadowDailyReport 로 판단 분포 검토.
- 게이트: 침묵 판단이 과반(니아 핵심가치)·오발화 후보 없음 확인 → 사용자 승인.

### 단계 3 — CANARY (소수 채널 실제 발화)
- ShadowMode=CANARY (관리자 API/DB). 지정 소수 채널만 `allowsRealSend=true`.
- CanaryAutoHaltService 가 over_talk/complaint/stale 시 자동으로 SHADOW_PREDICT 강등.
- 게이트: 카나리 채널 실발화 품질·안전 확인 → 사용자 승인.

### 단계 4 — LIVE (전면)
- ShadowMode=LIVE. 전 채팅채널 자발 발화.
- 인간 게이트(AGENTS §5: Discord LIVE 발화) + 롤백 경로(즉시 SHADOW_PREDICT 강등) 상시.

## 2. 불변·안전 원칙
- 보안 우회 금지: emit 를 거치지 않는 발화 경로를 만들지 않는다(ConsentGate·Critic 우회 차단).
- 단계 승격은 항상 인간 게이트. 강등(LIVE→SHADOW)은 자동·즉시 허용.
- 니아 정체성 고정(I11)·원문 미저장(I10)·서버 격리(I7)는 기존 도메인이 보장.
- 채팅채널 자발 발화는 기존 "AI 질문채널 무조건 답변"(autoRespond)과 **별개 경로**다.

## 3. 다음 작업
단계 1(메시지핸들러→emit wiring)이 유일한 코드 작업이며 규모가 있다(scene packet 구성·평가 연결).
fresh 컨텍스트에서 신중히 착수한다. 보안은 이미 내장돼 있으므로 wiring 자체의 정확성이 관건.
