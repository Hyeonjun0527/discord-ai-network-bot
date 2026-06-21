# 경계 계약: multiresponse · speech BurstPlan

- 작업: NEXA-P01-T014 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 관련 계약: [speech-context.md](./speech-context.md),
  [actionruntime-context.md](./actionruntime-context.md)

## 목적

speech가 만든 `BurstPlan`(여러 메시지로 나눠 보내는 인간식 다중 버블)을 기존 multiresponse가
어떻게 전달할지 정하고, **스트리밍**과 **인간식 다중 버블**을 구분한다.

## 구분

| 개념 | 정의 | 소유 |
| --- | --- | --- |
| 스트리밍 | 한 응답을 토큰 단위로 점진 표시(한 메시지의 갱신) | routing/전송 표현 계층 |
| 인간식 다중 버블(BurstPlan) | 의도적으로 나눈 여러 개의 독립 메시지·간격 | speech가 계획, actionruntime이 전송 |

## 규칙 (acceptance)

- multiresponse는 speech `BurstPlan`을 **전달만** 한다 — 메시지 수·내용·간격은 speech가 정한다.
- multiresponse는 **정책을 재판단하지 않는다**(말할지 여부는 participation, 내용은 speech).
- multiresponse는 **무제한 메시지를 생성하지 못한다** — BurstPlan에 명시된 상한 내에서만 전송하고,
  각 버블 전송은 actionruntime 상태 머신·재평가를 거친다(흘러간 대화면 남은 버블 취소).

## 불변식

1. 버블 개수·간격의 상한은 speech BurstPlan이 정하며 multiresponse가 늘리지 않는다.
2. multiresponse는 participation/speech의 결정을 재실행하지 않는다(전달 계층).
3. 다중 버블 전송 중 actionruntime 재평가가 무효를 반환하면 남은 버블을 보내지 않는다.
