# ADR 0008: Spring Modulith 도입 여부 — 기존 ArchUnit 유지(REJECTED)

- 상태(Status): 승인됨 (Accepted) — 결정 결과는 **Spring Modulith 도입 REJECTED**.
  인간 결정자 승인 2026-06-21 (NEXA-P01-T002, `human_gate: true`)
- 날짜(Date): 2026-06-21
- 결정자(Deciders): Hyeonjun0527
- 관련: [ADR 0007 NEXA 사회적 행위자 모델](./0007-nexa-social-member-context.md),
  [ADR 0004 Kotlin/Spring central-server](./0004-kotlin-spring-central-server.md)
- 근거 기준선(P00): [archunit-rules.md](../nexa/baseline/archunit-rules.md),
  [central-package-graph.md](../nexa/baseline/central-package-graph.md)

## 맥락 (Context)

ADR 0007이 NEXA를 6개 바운디드 컨텍스트(conversation/participation/socialmemory/speech/
actionruntime + platform·discord)로 재편하기로 했다. 새 컨텍스트가 늘면 **모듈 경계를 무엇으로
강제·검증할 것인가**라는 질문이 생긴다. 후보는 (1) 이미 쓰는 ArchUnit을 확장하거나 (2) Spring
Modulith를 도입하는 것이다.

P00 기준선이 현재 상태를 코드로 확인했다([archunit-rules.md](../nexa/baseline/archunit-rules.md)):

- `com.tngtech.archunit:archunit-junit5:1.3.0`로 **9개 활성 규칙**이 헥사고날 경계를 강제한다 —
  shared 순수성, controller 위치/persistence·repository 격리, `@Service` 위치, migrated domain
  순수성, JPA `@Entity` 위치, routing domain/application 경계.
- 기준선 결과: `BUILD SUCCESSFUL`, 9 tests, **위반 0**.
- 즉 "도메인은 Spring/JPA/JDA에 의존하지 않는다", "controller는 persistence를 모른다" 같은
  NEXA가 필요로 하는 경계가 이미 강제·통과되고 있다.

### 검토한 대안

| 방안 | 요지 | 빌드/중복 영향 | 평가 |
| --- | --- | --- | --- |
| **A. ArchUnit 유지 + NEXA 컨텍스트 규칙 추가** | 기존 9규칙에 6개 컨텍스트 경계 규칙을 `@ArchTest`로 추가 | 새 의존성 0. 기존 패턴·테스트 그대로 확장 | **채택.** 검증 책임 일원화, 학습비용 0, 위반 0 기준선 유지 |
| B. Spring Modulith 전면 도입 | central을 Modulith 모듈로 재배치, `@ApplicationModuleTest`·이벤트 통신 채택 | `spring-modulith-*` 다수 추가, 패키지를 모듈 관례(모듈=직속 하위 패키지, named interface)로 재배치, 기존 헥사고날 레이어(adapter.inbound/outbound)와 충돌 가능 | 거부 — 큰 구조 이동 비용, ArchUnit과 검증 중복, 현 시점 추가가치 미입증 |
| C. Modulith를 ArchUnit과 병행(보조) | 문서 자동생성·모듈 검증만 Modulith로 보조 | 의존성 추가 + 두 검증 체계 동시 유지 | 거부 — 동일 경계를 두 도구가 검사하는 **중복**, DRY/KISS 위반, 유지보수 분산 |

## 결정 (Decision)

**Spring Modulith를 도입하지 않는다(REJECTED).** NEXA 6개 바운디드 컨텍스트의 경계는 기존
ArchUnit 규칙 체계를 확장해 강제한다(방안 A).

근거:

1. **이미 충분(YAGNI)** — ArchUnit 9규칙이 NEXA가 요구하는 핵심 경계(도메인 순수성, 레이어 격리,
   어댑터 경계)를 위반 0으로 강제하고 있다. Modulith가 주는 추가 강제력은 현재 입증된 필요가 없다.
2. **중복 회피(DRY)** — Modulith 보조 도입(방안 C)은 같은 경계를 두 도구가 검사하게 만든다. 검증
   실패 시 어느 체계의 책임인지 모호해지고 유지보수가 분산된다.
3. **구조 이동 비용(KISS)** — Modulith 전면 도입(방안 B)은 헥사고날 레이어 구조(`adapter.inbound`/
   `adapter.outbound`/`application`/`domain`)를 Modulith 모듈 관례로 재배치해야 하며, 이는 ADR
   0004가 정한 구조와 충돌·재작업을 부른다.
4. **이벤트 통신은 별개 결정** — 컨텍스트 간 이벤트 기반 통신이 필요하면 Spring의 기본
   `ApplicationEventPublisher`로 충분히 시작할 수 있고, 그것이 Modulith 채택을 강제하지 않는다.

### NEXA 컨텍스트 경계의 ArchUnit 강제 방향(후속 작업 연결)

ADR 0007의 6개 컨텍스트는 P01 계약 작업에서 다음 ArchUnit 규칙으로 표현한다(상세는 각 task):

- conversation/participation/socialmemory/speech/actionruntime `..domain..`은 Spring/JPA/JDA에
  의존하지 않는다(`migratedDomainsArePure` 패턴 확장).
- participation은 speech/actionruntime의 *문장 생성·전송*에 의존하지 않는다(단방향 책임 사슬).
- speech는 JDA·provider-agent glm 타입에 의존하지 않고 routing의 `CloudLlm` 포트만 사용한다
  (ADR 0007 T010, ADR 0006 포트 재사용).
- platform/discord 어댑터 밖의 어떤 도메인도 JDA 타입을 참조하지 않는다(T011).

이 규칙들은 **기존 9규칙을 약화하지 않고** 새 `@ArchTest`로 추가하며, 추가 시
[archunit-rules.md](../nexa/baseline/archunit-rules.md)의 "Baseline separation rule"을 따른다
(새 NEXA 규칙 실패는 기존 baseline 위반과 구분해 기록).

## 비-목표 (이번 결정에서 제외)

- 위 NEXA ArchUnit 규칙의 실제 코드 작성 — 본 ADR은 프레임워크 채택 여부만 결정한다. 규칙 구현은
  P01 계약 작업과 P02~ 구현 프로그램에서 진행한다.
- 컨텍스트 간 통신 메커니즘(동기 포트 vs 이벤트) 확정 — 필요 시 별도 검토하되 본 결정과 독립적이다.
- 향후 재평가 금지 — Modulith가 주는 가치(모듈 문서 자동생성 등)가 명확히 필요해지면 본 ADR을
  superseding ADR로 다시 열 수 있다.

## 위험과 되돌림 가능성 (Risks & Reversibility)

| 위험 | 영향 | 완화 / 되돌림 |
| --- | --- | --- |
| ArchUnit 규칙이 늘며 테스트가 비대해짐 | 유지보수 부담 | 규칙을 컨텍스트별로 그룹화하고 의도를 주석으로 명시. baseline separation rule로 회귀와 신규를 구분 |
| Modulith 미도입으로 모듈 문서 자동화 부재 | 아키텍처 문서를 수기 유지 | `docs/nexa/architecture/*` 계약 문서(P01-T003~)로 대체. 부족하면 본 ADR을 재평가 |
| 컨텍스트 간 통신이 복잡해지면 단순 포트로 부족 | 결합도 상승 | Spring `ApplicationEventPublisher`로 점증 가능, Modulith 없이도 이벤트 통신 도입 가능 |
| **되돌림** | — | 본 ADR은 **의존성을 추가하지 않는 결정**이라 되돌릴 빌드 변경이 없다. 향후 Modulith가 필요하면 새 ADR로 채택하면 되고 기존 ArchUnit 자산은 그대로 보존된다 |

## 결과 (Consequences)

**장점**

- 새 의존성·구조 이동 0 — 현재 빌드와 패키지 구조(ADR 0004)를 그대로 유지한다.
- 경계 검증이 ArchUnit 한 곳으로 일원화되어 실패 원인이 명확하다.
- NEXA 6개 컨텍스트를 기존 검증 패턴의 자연스러운 확장으로 도입할 수 있다.

**단점 / 트레이드오프**

- 모듈 의존성 문서/다이어그램 자동생성 같은 Modulith 편의는 포기한다(수기 계약 문서로 대체).
- 컨텍스트가 많아질수록 ArchUnit 규칙 수가 늘어 테스트 가독성 관리가 필요하다.

## 인간 승인 상태 (Approval)

- 이 작업은 `NEXA-P01-T002`, `human_gate: true`, `risk: high`다.
- acceptance 요구("근거 없이 새 프레임워크를 추가하지 않고 결론이 ACCEPTED 또는 REJECTED로
  명확하다")는 충족된다 — **결론: Spring Modulith 도입 REJECTED**, ArchUnit 유지 ACCEPTED.
- 인간 결정자(Hyeonjun0527)가 2026-06-21에 ACCEPTED로 승인했다. NEXA 컨텍스트 경계는 본 결정에
  따라 기존 ArchUnit 체계 확장으로 강제한다.

## 미해결 질문 (Open Questions)

- NEXA 컨텍스트 간 통신을 동기 포트로 시작할지, 초기부터 `ApplicationEventPublisher` 이벤트로
  설계할지(P01 후속/P02에서 결정).
- ArchUnit 규칙 수 증가 시 컨텍스트별 테스트 클래스 분리 기준.
