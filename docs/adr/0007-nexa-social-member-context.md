# ADR 0007: NEXA 시스템 컨텍스트 — 사회적 행위자 모델

- 상태(Status): 승인됨 (Accepted) — 인간 결정자 승인 2026-06-21 (NEXA-P01-T001, `human_gate: true`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0003 커뮤니티 Provider Pool](./0003-community-provider-pool.md),
  [ADR 0004 Kotlin/Spring central-server](./0004-kotlin-spring-central-server.md),
  [ADR 0006 중앙 클라우드 LLM 백엔드](./0006-central-cloud-llm-backend.md) 를 보완한다.
- 근거 기준선(P00): [current-autoresponse-flow.md](../nexa/baseline/current-autoresponse-flow.md),
  [current-llm-flow.md](../nexa/baseline/current-llm-flow.md),
  [social-model-overlap.md](../nexa/baseline/social-model-overlap.md)
- 이 ADR이 여는 후속 작업: NEXA-P01-T003~T007(바운디드 컨텍스트 계약),
  T008/T009(channelai·ainetwork 책임 재정의), T010~T013(포트·로그·quota 경계)

## 맥락 (Context)

현재 NEXA(central-server + JDA 봇)는 본질적으로 **요청-응답 봇**이다. P00 기준선이 이를 코드로
확인했다([current-autoresponse-flow.md](../nexa/baseline/current-autoresponse-flow.md)):

- 슬래시 명령·멘션·자동응답이 모두 `CommandService.ask → AskCommandHandler.ask →
  RequestOrchestrator → ProviderSession.sendInfer → DiscordAnswerRenderer` 라는 **단일 동기
  요청-응답 경로**로 수렴한다.
- "자동응답"조차 채널 단위 hot-path 캐시 + 임계치로 *매 메시지에 답할지*만 가르는 즉답 트리거다.
  관찰·맥락 축적·행동 선택·발화 타이밍이라는 사회적 판단 계층이 없다.
- 정체성(니아)·호감도는 `ainetwork`가 전역 스칼라로 들고 있고, 채널 AI 프로필·자동응답 설정은
  `channelai`가 들고 있어 **장차 도입할 사회적 기억(socialmemory)과 소유권이 겹친다**
  ([social-model-overlap.md](../nexa/baseline/social-model-overlap.md)는 각 항목을
  REUSE/BRIDGE/MIGRATE/DEPRECATE로 분류).

목표 제품(NEXA)은 "서버에 연결된 **AI 멤버**"다. 멤버는 호명될 때만 답하는 함수가 아니라,
**대화를 관찰하고 / 지금 끼어들지 말지를 스스로 정하고 / 사람·사건을 기억하고 / 그 위에서 말을
만드는** 사회적 행위자다. 이 정체성을 코드 구조가 뒷받침하지 못하면, 자동응답 임계치 튜닝 같은
국소 수정이 무한 반복될 뿐 "멤버"라는 제품 약속에 도달하지 못한다.

### 검토한 대안

| 방안 | 요지 | 평가 |
| --- | --- | --- |
| A. 현행 유지(요청-응답 + 자동응답 임계치 강화) | 기존 `RequestOrchestrator` 경로에 트리거 규칙만 더함 | 관찰·기억·발화 타이밍이 한 핫패스에 뭉쳐 결합도 증가. "멤버" 제품 약속 미달. 거부 |
| B. 자동응답에 LLM "끼어들까?" 판단만 추가 | 매 메시지에 LLM 호출로 응답 여부 결정 | 비용·지연 폭증, 결정 로그·기억 부재로 일관성 없음. 정책 우회·관측 불가. 거부 |
| **C. 사회적 행위자 모델 — 관찰/선택/기억/발화/실행 분리** | NEXA를 **이벤트 관찰 → 행동 선택 → 기억 활용 → 발화 생성 → 실행** 파이프라인으로 정의하고 바운디드 컨텍스트로 분리 | **채택.** 각 책임이 독립 검증·교체 가능, 기존 routing/quota/requestlog 재사용, 점진 마이그레이션 가능 |

## 결정 (Decision)

**NEXA를 요청-응답 봇이 아니라, Discord 이벤트를 관찰하고 행동을 스스로 선택하는 사회적
행위자(social member)로 정의한다.** 이 정체성을 다음 **6개 바운디드 컨텍스트**의 단방향 책임
사슬로 구현한다(상세 계약은 P01-T003~T011 후속 작업에서 확정):

```
platform/discord ──(정규화)──▶ conversation ──▶ participation ──▶ speech ──▶ actionruntime ──(전송)──▶ platform/discord
                                     ▲                  │            ▲
                                     └─ socialmemory ◀──┘ (기억 갱신) └─ (장면·기억·정체성 읽기)
```

1. **platform/discord (어댑터)** — JDA 이벤트를 내부 `DiscordEventEnvelope`로 정규화하고,
   전송도 이 경계 뒤에서만 한다. 이후 어떤 도메인도 JDA 타입을 보지 않는다. (T011)
2. **conversation (관찰)** — 정규화 이벤트·버스트·스레드·장면(scene) projection만 소유한다.
   **AI 행동을 결정하지 않는다.** (T003)
3. **participation (행동 선택)** — `IGNORE / WAIT / REACT / SPEAK / CANCEL` 정책, feature,
   decision log만 소유한다. **문장 생성·Discord 전송은 책임에서 제외**한다. 즉 "말할지 여부"의
   유일한 결정자다. (T004)
4. **socialmemory (기억)** — 시간 유효성이 있는 일화·사실·관계·보류 의도를 소유한다. knowledge
   RAG(문서 검색)·ainetwork 호감도와 **구분**되며 포트로만 연결된다. (T005)
5. **speech (발화 생성)** — participation이 `SPEAK`를 고른 *뒤에만*, 장면·기억·정체성을 사용해
   후보 문구와 버스트 계획을 만든다. **직접 JDA를 호출하거나 말할지 여부를 정하지 않는다.** 외부
   모델 호출은 기존 routing의 provider-neutral `CloudLlm` 포트만 쓴다(ADR 0006 재사용). (T007/T010)
6. **actionruntime (실행)** — 예약·재평가·취소·재시도·전송 상태 머신과 실행 감사만 소유한다.
   **정책 점수 계산·문장 생성을 포함하지 않는다.** (T006)

핵심 불변식: **"무엇을 관찰했는가(conversation) / 말할 것인가(participation) / 무엇을 아는가
(socialmemory) / 어떻게 말하는가(speech) / 언제 실제로 보내는가(actionruntime)"가 서로 다른
컨텍스트의 책임이며, 한 컨텍스트가 다른 컨텍스트의 결정을 대신하지 않는다.**

### 기존 자산과의 관계

- **routing / quota / requestlog 재사용**: 외부 모델 호출·일일 한도·차단·요청 로그는 기존
  central 경로를 그대로 쓴다. NEXA의 발화도 반드시 central routing(`CloudLlm`)을 거쳐 정책·
  관측 일관성을 유지한다([current-llm-flow.md](../nexa/baseline/current-llm-flow.md) 전제 확인).
- **channelai 존치·축소(T008)**: channelai는 채널별 AI 프로필·설정·모드의 SSOT로 남기되, 기존
  자동응답 트리거는 단계적으로 participation으로 이관한다(하위호환 기간·제거 순서는 ADR 0009).
- **ainetwork 경계(T009)**: 니아 정체성·호감도(ainetwork)와 관찰 가능한 관계 상태(socialmemory)를
  중복 저장하지 않도록 필드별 소유자·브리지 전략을 ADR 0010에서 표로 확정한다.

## 비-목표 (이번 결정에서 제외)

- 6개 컨텍스트의 **상세 계약·스키마·코드** — 본 ADR은 시스템 컨텍스트 결정만 한다. 계약은
  P01-T003~T013, 구현은 후속 프로그램(P02~)에서 진행한다.
- **Spring Modulith 같은 새 프레임워크 도입 여부** — ADR 0008(P01-T002)에서 별도로 평가한다.
  본 ADR은 기존 ArchUnit 패키지 규칙 위에서도 성립한다.
- **wire protocol·provider-agent 변경** — speech는 provider-neutral `CloudLlm` 포트만 호출하며,
  glm.py·Z.AI SDK 타입은 도메인에 노출되지 않는다(T010). 와이어 계약은 불변.
- 기존 슬래시 `/ask` 즉답 경로의 즉시 제거 — 관찰/참여 경로와 **공존**하며 점진 이관한다.

## 위험과 되돌림 가능성 (Risks & Reversibility)

| 위험 | 영향 | 완화 / 되돌림 |
| --- | --- | --- |
| 과설계 — 6개 컨텍스트가 현행 대비 과한 추상화 | 유지보수 부담, KISS/YAGNI 위반 | 계약을 문서로 먼저 고정(T003~)하고 ArchUnit으로만 강제. 새 인프라 없이 패키지 경계로 시작. Modulith는 0008에서 별도 판단 |
| 발화 타이밍 오작동 — 멤버가 너무 자주/엉뚱하게 끼어듦 | 사용자 경험 악화 | participation decision log + actionruntime 재평가/취소 상태머신으로 관측·롤백. 임계치는 데이터로 조정 |
| 비용 — 관찰 기반 발화가 LLM 호출 증가 | central 비용 상승 | "말할지 여부(participation)"와 "말 생성(speech)"을 분리해 SPEAK일 때만 모델 호출. 기존 quota·rate limit 그대로 적용 |
| 기억·정체성 중복(ainetwork↔socialmemory) | 데이터 불일치 | T009/ADR 0010에서 필드 소유자 확정 전까지 socialmemory를 쓰기 경로에 연결하지 않음 |
| **되돌림** | — | 본 ADR은 **문서 결정**이며 런타임/스키마/마이그레이션을 만들지 않는다. REJECTED 시 후속 P01 계약 작업만 중단하면 되고 기존 요청-응답 경로는 무손상으로 유지된다 |

## 결과 (Consequences)

**장점**

- "AI 멤버"라는 제품 약속이 코드 구조로 표현된다 — 관찰/선택/기억/발화/실행이 독립적으로 검증·교체 가능.
- 각 컨텍스트가 단일 책임을 가져 결합도가 낮아지고, 자동응답 핫패스의 god-path가 해소된다.
- 기존 routing·quota·requestlog·channelai를 재사용해 새 신뢰 경계·인프라를 만들지 않는다.
- 점진 마이그레이션 — 기존 경로와 공존하며 컨텍스트별로 도입/롤백할 수 있다.

**단점 / 트레이드오프**

- 컨텍스트가 6개로 늘어 초기 학습·문서화 비용이 든다(완화: 계약을 본 시리즈에서 명문화).
- participation·speech·actionruntime의 협력으로 한 응답의 추적 경로가 길어진다(완화: correlation ID로 연결, T012).
- ainetwork·channelai와의 경계 정리(T008/T009)가 끝나기 전에는 일부 책임이 잠정 중복된다.

## 인간 승인 상태 (Approval)

- 이 작업은 `NEXA-P01-T001`, `human_gate: true`, `risk: high`다.
- acceptance 요구(범위·대안·위험·되돌림 가능성·인간 승인 상태 기록)는 본 문서의 *맥락/대안 표/
  결정/비-목표/위험과 되돌림/이 절*에서 충족한다.
- 상태는 **Accepted**다 — 인간 결정자(Hyeonjun0527)가 2026-06-21에 ACCEPTED로 승인했다. 후속
  P01 계약 작업(T003~)은 이 ADR의 6개 컨텍스트 경계를 전제로 설계하되, 런타임/스키마/마이그레이션을
  만드는 되돌릴 수 없는 변경은 각 구현 프로그램(P02~)의 게이트에서 별도로 다룬다.

## 미해결 질문 (Open Questions)

- participation의 SPEAK 판단을 규칙 기반으로 시작할지, 경량 신호(멘션·질문·호명)부터 점증할지(P02 범위).
- socialmemory의 시간 유효성(decay) 정책과 보존 기간 — 개인정보·삭제 요청과의 정합(P03 이벤트 삭제와 연계).
- 기존 `/ask` 즉답 경로와 관찰 기반 발화의 최종 통합 시점 및 channelai 자동응답 제거 일정(ADR 0009에서 확정).
