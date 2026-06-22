# 채널 제외 정책

- 작업: NEXA-P02-T004 (`human_gate: true`, decision) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [guild-policy-boundary.md](../../../docs/nexa/architecture/guild-policy-boundary.md),
  [data-categories.md](./data-categories.md), [consent-model.md](./consent-model.md)

## 결정

관리자는 **카테고리·채널·스레드 단위로 NEXA의 관찰과 발화를 분리해서** 설정할 수 있다.
"발화 금지"와 "관찰 금지"는 서로 다른 축이다.

### 두 축

| 축 | 의미 | 기본값 |
| --- | --- | --- |
| 관찰(observe) | conversation이 이벤트를 본다(장면·기억의 원천) | 활성 채널만 |
| 발화(speak) | participation이 SPEAK/REACT 할 수 있다 | 채널 모드(ASSISTANT/MEMBER) |

- **발화 금지 ≠ 관찰 금지**: 발화는 막되 관찰은 허용(맥락 학습만), 또는 관찰도 발화도 금지가 모두 가능.
- **관찰 금지 채널에서는 메타데이터도 최소화**: 이벤트를 수집하지 않으며, 불가피한 운영 메타데이터도
  최소로 줄인다(원문·식별자 비수집, [data-categories.md](./data-categories.md) 경계 준수).
- 적용 단위는 카테고리 → 채널 → 스레드로 상속되며 하위에서 더 제한적으로 좁힐 수 있다.
- 편집은 웹 관리 대시보드(guild-policy-boundary: 디스코드 명령 금지).

## acceptance 충족

- **관찰 금지 채널에서는 메타데이터도 최소화**되고, **발화 금지와 관찰 금지가 구분**되어 각각 독립
  설정된다(발화만 끄고 관찰 유지, 또는 둘 다 끄기 가능).

## 불변식

1. 관찰 축과 발화 축은 독립이다(하나를 끈다고 다른 하나가 자동으로 꺼지지 않는다).
2. 관찰 금지 채널에서는 이벤트·메타데이터를 수집하지 않는다.
3. 제외 설정은 카테고리→채널→스레드로 상속되고 하위는 더 보수적으로만 좁힌다.
