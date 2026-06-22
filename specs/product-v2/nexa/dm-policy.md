# DM 처리 정책

- 작업: NEXA-P02-T005 (`human_gate: true`, decision) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [data-categories.md](./data-categories.md), [consent-model.md](./consent-model.md),
  [user-opt-out.md](./user-opt-out.md)

## 결정

**DM(다이렉트 메시지)은 기본적으로 NEXA 관찰·기억·학습에서 완전 제외한다.** NEXA는 길드(서버)
스코프의 사회적 멤버이며, DM은 공개 서버 맥락과 본질적으로 다른 사적 채널이다.

- 기본값: **완전 제외** — DM 이벤트를 관찰하지 않는다.
- 확장(향후): DM에서 NEXA 기능을 쓰려면 **별도의 명시 동의**를 그 DM 맥락에서 따로 받아야 하며,
  길드 동의가 DM 동의를 대신하지 않는다. (현 단계는 완전 제외로 고정, 확장은 별도 ADR.)

## 스코프 격리 (acceptance)

- **DM 정보가 길드 관계 상태나 학습 데이터로 묵시적으로 합쳐지지 않는다**: socialmemory 관계
  projection·학습 산출물은 guild 스코프이며, DM에서 얻은 어떤 정보도 guild 키로 흘러들지 않는다.
- DM과 공개 서버 맥락을 같은 장면·기억·프롬프트에 섞지 않는다.

## 불변식

1. DM은 기본 완전 제외(관찰·기억·학습 안 함).
2. DM 데이터는 guild 스코프 관계/학습과 격리된다(묵시적 병합 금지).
3. DM 기능 확장은 그 DM 맥락의 별도 명시 동의 + 별도 ADR 없이는 열지 않는다.
