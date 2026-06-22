# ADR 0010: ainetwork · socialmemory 경계 — 정체성/호감도와 관찰 관계의 분리

- 상태(Status): 승인됨 (Accepted) — 인간 결정자 승인 2026-06-21 (NEXA-P01-T009, `human_gate: true`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007 사회적 행위자 모델](./0007-nexa-social-member-context.md),
  [ADR 0009 channelai 책임 재정의](./0009-channelai-responsibility.md)
- 근거 기준선: [social-model-overlap.md](../nexa/baseline/social-model-overlap.md)
- 계약: [socialmemory-context.md](../nexa/architecture/socialmemory-context.md),
  [speech-context.md](../nexa/architecture/speech-context.md)

## 맥락 (Context)

`ainetwork`는 니아 정체성(`NexaIdentity`)과 호감도(`user_affinity`)를 소유한다. ADR 0007이
도입할 `socialmemory`는 guild-스코프의 **관찰 가능한 관계 상태**를 소유한다. 둘은 "관계처럼
보이는" 신호가 겹쳐, 호감도 점수/단계를 socialmemory에 복제하면 **전역 게임화 카운터가 관찰된
관계 상태로 위장**하는 문제가 생긴다([social-model-overlap.md](../nexa/baseline/social-model-overlap.md)
주요 충돌 1·2·4·5).

핵심 사실:

- `user_affinity.user_id`는 `guild_id` 없이 전역 unique다 — 전역 진행도/읽기 모델이지 guild별
  관찰 상태가 아니다.
- 정체성(`NexaIdentity`, 전역 프롬프트셋)은 정적이다 — 동적 사회 상태를 페르소나 텍스트에 쓰면 안 된다.
- 한 번의 사용자 상호작용이 `UsageService.recordSuccess`에서 호감도를 증가시키는데, 미래 이벤트
  projection이 같은 상호작용을 또 처리하면 **이중 부작용**이 생긴다.

## 결정 (Decision)

**ainetwork와 socialmemory는 필드를 중복 저장하지 않는다.** 각 필드의 소유자와 전략을 아래 표로
확정한다. 분류 기호: REUSE(현 소유자 유지·좁은 읽기 포트) / BRIDGE(매핑 뷰만 노출, 직접 import·
이중 쓰기 금지) / MIGRATE(호환 기간 후 책임 이전) / NEW(socialmemory 신규 소유).

| 필드/책임 | 소유자 | 전략 | 경계 규칙 |
| --- | --- | --- | --- |
| `user_affinity.score/stage/stage_ordinal/last_interaction_at` | ainetwork | **BRIDGE** | socialmemory는 `NiaAffinityView`(매핑 stage)만 선택적 입력으로 읽고, score/stage를 복제 저장하지 않는다 |
| 호감도 쓰기 트리거(`UsageService→NiaAffinityService.awardInteraction`) | ainetwork | **MIGRATE** | socialmemory 도입 후 dedup event ID 도입 — 한 상호작용 = 호감도 1회 + 관계 projection 1회 |
| 호감도 stage 프롬프트 문구(legacy `/ask`) | ainetwork(legacy) → speech | **MIGRATE** | 동적 관계 문구는 speech가 소유. NEXA speech는 socialmemory 관계 블록 + 선택적 affinity bridge를 **하나로** 조립(이중 주입 금지) |
| 니아 정체성 커널(`NexaIdentity`, 전역 프롬프트셋) | shared/ainetwork | **REUSE** | 정적 유지. socialmemory는 `NIA_DEFAULT_PERSONA`·프롬프트셋·정체성 프리뷰를 변경하지 않는다 |
| 채널 라우팅 정책(`channel_ai_routing_policy`) | ainetwork/routing | **REUSE** | provider/model 라우팅 유지. NEXA talkativeness/모드를 `responseMode`에 저장 금지(ADR 0009) |
| 품질 피드백(`ai_feedback`) | ainetwork | **REUSE** | provenance용 request/feedback ID 참조는 가능, 평점/신고를 관계 사실로 저장 금지 |
| 네트워크 overview projection | ainetwork | **REUSE** | 운영/대시보드 지표 유지. socialmemory는 별도 replay 가능 projection 노출 |
| guild-스코프 관찰 상호작용·관계/친밀도 projection·시간 유효성·provenance·삭제/replay | **socialmemory** | **NEW** | socialmemory가 신규 소유. ainetwork/channelai JPA 엔티티 import·테이블 변경 금지 |

### 마이그레이션/브리지 전략

1. **브리지 뷰 우선**: socialmemory는 `NiaAffinityBridge`/`ChannelRoutingPolicyView` 읽기 포트로만
   ainetwork를 참조한다. `UserAffinityEntity`/`ChannelAiEntity` 직접 import는 ArchUnit으로 차단(ADR 0008).
2. **dedup key 선행**: `ai_request`/`usage_log`/`user_affinity`/socialmemory projection을 잇는
   event ID를 P02/P03 이벤트 계약에서 먼저 정의한 뒤 두 시스템을 연결한다(이중 부작용 방지).
3. **정체성 불변**: 정체성/행동 설정은 정적 유지. 동적 친밀도·타이밍은 socialmemory/participation으로.
4. **호환 기간**: legacy `/ask`의 affinity 문구는 비-NEXA 경로에 한해 유지 가능하되, NEXA speech는
   단일 관계 블록만 사용한다.

## 비-목표

- socialmemory 스키마·이벤트 계약의 실제 구현 — P02/P03 범위.
- ainetwork 호감도 시스템 폐지 — 폐지하지 않는다. 브리지로 공존한다.
- dedup event ID 형식 확정 — 본 ADR은 "선행 필요"만 명시하고 형식은 이벤트 계약에서 정한다.

## 위험과 되돌림 가능성

| 위험 | 영향 | 완화 / 되돌림 |
| --- | --- | --- |
| 호감도/관계 이중 부작용 | 한 상호작용이 두 번 집계 | dedup key 선행, 연결 전까지 socialmemory를 쓰기 경로에 미연결 |
| 프롬프트 이중 관계 주입 | 어색한 응답 | speech가 관계 블록을 단일 조립(불변식) |
| 브리지가 결국 복제로 변질 | SSOT 분열 | ArchUnit으로 엔티티 직접 import 차단, 읽기 포트만 허용 |
| **되돌림** | — | 본 ADR은 문서 결정(스키마 무변경). REJECTED 시 socialmemory 설계만 보류, ainetwork 현행 무손상 |

## 결과 (Consequences)

**장점**: 전역 호감도와 guild-스코프 관찰 관계가 명확히 분리되어 SSOT가 단일하게 유지된다. speech가
일관된 관계 맥락을 받는다.

**단점**: 브리지 뷰·dedup key 등 연결 장치를 추가로 설계·유지해야 한다.

## 인간 승인 상태 (Approval)

- `NEXA-P01-T009`, `human_gate: true`, `risk: high`.
- acceptance("각 필드의 소유자와 마이그레이션/브리지 전략이 표로 확정") 충족 — 위 소유권 표와
  마이그레이션 전략으로 필드별 소유자·전략·경계 규칙을 확정했다.
- 인간 결정자(Hyeonjun0527)가 2026-06-21에 ACCEPTED로 승인했다. socialmemory 구현(P02~)은 본
  필드 소유권 표와 브리지 전략을 따른다.

## 미해결 질문

- `NiaAffinityView` 매핑 정밀도 — stage만 노출할지, 정규화 친밀도 수치까지 노출할지.
- dedup event ID를 어느 컨텍스트(conversation 이벤트 vs requestlog)가 발급할지(P02/P03에서 결정).
