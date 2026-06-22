# 괴롭힘·모욕 안전 정책 (banter safety)

- 작업: NEXA-P17-T014 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [user-opt-out.md](./user-opt-out.md), [threat-model.md](../../../docs/nexa/security/threat-model.md),
  [participation-context.md](../../../docs/nexa/architecture/participation-context.md)
- 구현: `BanterSafetyOverride`(T015, participation 도메인 서비스), `PolicySafetyConstraint`(T021, action 게이트)

## 결정

NEXA(니아)는 사람처럼 가벼운 장난(banter)을 할 수 있지만, **"사람 같음"을 이유로 괴롭힘에 가담하거나
증폭하지 않는다.** 친근한 장난과 괴롭힘의 경계를 정의하고, 위험 조합은 페르소나·재미보다 우선해
하드 override 로 제거한다(안전이 캐릭터를 이긴다).

### 행동 분류

| 구분 | 정의 | NEXA 행동 |
| --- | --- | --- |
| 친근한 장난 | 상호 합의된 맥락의 가벼운 농담(TEASE) | 관계·맥락 허용 시에만 정책이 선택 |
| 직접 모욕 | 특정인을 향한 비하·모욕 | 절대 생성·증폭 안 함(제거) |
| 반복 표적화 | 같은 대상을 임계 이상 반복 겨냥 | 공격적 발화(TEASE/DISAGREE/CORRECT) 제거 |
| 중단 신호 | 대상이 "그만"·차단 등 의사 표시 | 그 대상에 대한 모든 비-침묵 발화 중지(존중) |

### 제한 규칙 (하드 override)

- **opt-out 우선**: 대상이 banter 를 opt-out([user-opt-out.md])했으면 그 대상에게 TEASE 를 제거한다.
- **표적 괴롭힘 차단**: 같은 대상을 임계(기본 3회) 이상 반복 표적화한 상태면 공격적 act 를 제거한다.
- **중단 신호 존중**: 대상이 중단 신호를 보냈으면 그 대상에 대한 모든 비-침묵 발화 act 를 제거한다.
- **발화 취소**: 위 제거로 안전한 발화 종류가 하나도 남지 않으면 SPEAK 자체를 접고 IGNORE 로 물러선다.

이 규칙은 모델 확률을 이긴다 — 모델이 TEASE 0.9 라도 override 가 막으면 0 으로 만들고 분포를 재정규화한다.

### 사용자별 opt-out

장난 수신 거부는 개인 결정이다([user-opt-out.md] 의 개인 거부 우선 원칙 계승). 길드가 NEXA 를 켰더라도
개별 사용자는 자신을 향한 banter 를 거부할 수 있고, NEXA 는 그 대상에게 TEASE 를 생성하지 않는다.

### 감사성 (decision log)

안전 override 는 **은폐 없이** raw policy 와 함께 기록된다(T015 acceptance). decision log 는 (1) 모델 raw
분포, (2) override 적용 분포, (3) 무엇이 왜 제거됐는지(`SafetyOverrideRemoval`: 대상·사유 코드)를 나란히
남긴다 — 안전 개입이 사후 검증 가능해야 한다.

## 비범위

- 콘텐츠 안전(혐오·성적·폭력 등 텍스트 차원)은 `ContentSafety` 가드레일과 생성 후 critic(P17-T003 등)이 담당한다.
- 고위험 도움 요청(자해·의료·법률)은 별도 경계([disclosure.md] 인접 — T016 `HighRiskFallbackBoundary`)가 처리한다.
