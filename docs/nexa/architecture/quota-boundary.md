# 경계 계약: quota 적용 시점

- 작업: NEXA-P01-T013 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 관련 계약: [participation-context.md](./participation-context.md),
  [actionruntime-context.md](./actionruntime-context.md), [speech-context.md](./speech-context.md)

## 목적

관찰·행동 선택은 무료(LLM 비호출)인데 quota가 그 단계에서 차감되면 안 된다. **실제 generation
호출 직전에만** quota를 차감하는 시점을 고정한다.

## 적용 시점 (acceptance)

| 단계 | quota 영향 |
| --- | --- |
| conversation 관찰 | 없음 |
| participation IGNORE/WAIT/REACT | **없음** — LLM을 호출하지 않으므로 차감 금지 |
| participation SPEAK → speech 발화 계획 | 모델 호출이 일어나는 generation 직전에 차감 |
| actionruntime 전송 | 없음(이미 generation 시 차감됨) |

## 취소·실패 정책 (acceptance)

- **취소된 예약**(actionruntime 재평가로 CANCELLED): generation을 호출하지 않았으면 차감 없음.
  이미 generation 후 취소면 차감 유지(모델 비용 발생함).
- **실패한 모델 호출**: routing의 기존 실패 처리(`recordSuccess`/실패 기록)와 일치시킨다 —
  제공자 오류로 응답이 없으면 사용자 quota를 복구(미차감/롤백)하되, 정책 위반(차단·한도)으로 막힌
  경우는 차감하지 않는다.
- 정책 검사(차단/한도/채널/부담)는 ADR 0006대로 **generation 분기 직전**에 통과해야 한다(우회 0).

## 불변식

1. 침묵(IGNORE/WAIT) 판단은 quota를 소모하지 않는다.
2. quota는 generation 1회당 1회만 차감된다(중복 차감 금지, correlation ID로 보장).
3. 모델 호출 실패 시 사용자 quota는 부당하게 소모되지 않는다.
