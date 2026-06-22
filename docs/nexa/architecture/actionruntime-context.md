# 바운디드 컨텍스트 계약: actionruntime (실행)

- 작업: NEXA-P01-T006 · 상위 결정: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 패키지(예정): `com.discordassistant.central.actionruntime`
- 근거 기준선: [current-autoresponse-flow.md](../baseline/current-autoresponse-flow.md)

## 책임 (한 문장)

participation이 고른 행동을 **언제·어떻게 실제로 수행할지**의 상태 머신을 운영한다 —
예약·재평가·취소·재시도·전송. **무엇을 말할지(점수·문장)는 만들지 않는다.**

## 소유 (Owns)

| 개념 | 설명 |
| --- | --- |
| 예약(ScheduledAction) | "이 행동을 t 시점에 수행" 예약(즉시/지연) |
| 상태 머신 | `SCHEDULED → (RE-EVALUATE) → SENDING → SENT / CANCELLED / FAILED(→RETRY)` |
| 재평가(re-evaluate) | 전송 직전 participation에 "지금도 유효한가" 확인(대화가 흘러갔으면 취소) |
| 취소(cancel) | participation의 CANCEL 또는 재평가 실패 시 예약 폐기 |
| 재시도(retry) | 전송 실패의 백오프 재시도(상한 있음) |
| 실행 감사(execution audit) | 각 행동의 상태 전이 기록(원문 없이 correlation ID) |

## 비소유 (Does NOT own)

- **행동 선택·정책 점수 계산** → `participation`
- **문장 내용 생성** → `speech`
- **JDA 전송의 물리적 구현** → `platform/discord` 아웃바운드 어댑터(actionruntime은 포트로 명령)
- **외부 모델 호출** → 없음(전송은 Discord로만 나감)

## 포트

- 인바운드: `ScheduleAction(decision, payloadRef)`, `CancelAction(id)`(participation이 호출)
- 아웃바운드:
  - `ReEvaluate`(participation에 유효성 재확인) — **DIP로 순환 해소**: actionruntime이
    `ReEvaluationPort` 인터페이스를 *소유*하고 participation이 이를 *구현*한다. 따라서 컴파일
    의존은 participation→actionruntime 단방향이며 그래프가 비순환으로 유지된다([module-dag.md](./module-dag.md)).
    대안으로 `ActionReEvaluated` 이벤트로 비동기화해도 동일하게 단방향이다.
  - `ResolveSpeech`(speech가 만든 발화 계획을 전송 직전 확정 조회)
  - `SendToDiscord`(platform/discord 아웃바운드 어댑터)

## 금지 의존성 (ArchUnit으로 강제 — ADR 0008)

- `actionruntime.domain`은 Spring/JPA/JDA에 의존하지 않는다.
- actionruntime은 participation의 정책 점수 계산·speech의 문장 생성 로직을 포함하지 않는다.
- JDA를 직접 import하지 않고 `SendToDiscord` 포트로만 전송한다.

## 다른 컨텍스트와의 관계

- participation → actionruntime: 예약/취소 명령.
- actionruntime → participation: 전송 직전 재평가(타이밍이 사회적으로 여전히 맞는지).
- actionruntime → speech: 발화 계획 확정 조회(버스트 분할 전송 시 단계별).
- actionruntime → platform/discord: 실제 전송.

## 불변식

1. actionruntime은 "무엇을 보낼지"를 만들지 않는다 — 이미 결정된 행동을 수행만 한다.
2. 전송 직전 재평가에서 무효면 전송하지 않고 CANCELLED로 종료한다(흘러간 대화에 늦게 끼어들기 방지).
3. 모든 상태 전이는 실행 감사에 기록된다.
4. 재시도는 상한과 백오프를 가지며 무한 재시도하지 않는다(Fail Fast 후 FAILED 종료).
