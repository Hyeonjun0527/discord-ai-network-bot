# 하루 한도(daily limit) 정책 — SSOT

> 결정(2026-06-07): **하루 한도는 "서버별" 단위로만 노출·관리한다. 전역(계정) 한도 UI 는 폐기.**
> 데스크톱 앱 UI 는 이 결정을 이미 반영(홈 E1 제거, 서버 상세 G3 만 유지).
> 이 문서는 **백엔드 현실과 목표의 간극**을 기록한다. 함부로 코드를 지우지 않기 위함.

## 1. 현재 백엔드 한도 구조 (조사 2026-06-07)

한도가 **두 층**에 존재한다:

| 층 | 위치 | 스코프 | 라우팅에 실제 쓰임? |
|---|---|---|---|
| **A. 에이전트 전역** | `provider-agent` `AgentConfig.daily_limit`(`--daily-limit`) → `ProviderHelloFrame.remainingDailyRequests` → `ProviderSession.remainingDailyRequests` | **계정/PC 전역**(한 에이전트가 여러 서버에 연결돼도 **합산 공유**) | **예 — 이게 실제 라우팅 게이트** (`RequestOrchestrator` 의 `remainingDaily`) |
| **B. 서버별 정책** | `ContributionPolicyService.setLimit(providerId, model, dailyLimit…)` → `ProviderContributionPolicyEntity` | **provider(=`(userId,guildId)`)·model 단위** = 사실상 서버별 | 저장은 됨. 라우팅 게이트로는 A(에이전트 보고값)를 신뢰 |

- `provider` 레코드는 `unique(providerUserId, guildId)` → **유저가 서버마다 별도 provider**.
- 별개 개념(혼동 주의): `RolePolicy.dailyLimit` 은 **서버 멤버가 `/ask` 쓰는 횟수 제한**(소비자측), provider 기여 한도와 무관.

## 2. 모순

- **UI 목표**: 하루 한도 = 서버별(G3). 사용자가 서버마다 다른 한도를 건다.
- **엔진 현실**: 실제 게이트는 **에이전트 전역 `daily_limit`** 하나. 여러 서버에 연결돼도 **한 풀에서 차감**된다.
- 즉 UI 를 서버별로 좁혀도, 엔진이 전역 합산이면 "서버 A에 500, 서버 B에 200" 이 **독립 보장되지 않는다**(둘이 같은 전역 카운터를 깎음).

## 3. 목표 정렬 (둘 중 택1 — 미결정)

### 옵션 ① 엔진을 서버별로 분해 (UI 와 완전 일치, 큰 작업)
- provider-agent 가 서버(guild)별 잔여 한도를 따로 관리하고 보고하도록 변경.
- 와이어 프로토콜 변경: `ProviderHelloFrame.remainingDailyRequests`(전역 1개) →
  서버별 맵 또는 per-session 보고. `protocol/wire-contract.json` SSOT + `make wire-gen` + 양측 컨트랙트 테스트.
- central `RequestOrchestrator` 게이트를 서버별 잔여로 변경. `ContributionPolicy.dailyLimit`(B)을 실제 게이트로 승격.
- 위험: 와이어 호환성·라우팅 회귀. **자동배포 체인(central-server push → deploy)** 이므로 신중.

### 옵션 ② 전역 한도를 "PC 보호 상한"으로 재포지셔닝 (작은 작업, UX 정렬)
- 에이전트 전역 `daily_limit` 는 **"이 PC 전체가 하루에 처리할 최대"(안전 상한)** 로 의미만 바꾸고 **UI 비노출**.
- 사용자 대면 한도는 **서버별 `ContributionPolicy.dailyLimit`(B)** 로만 설정·표시.
- central 라우팅은 "서버별 한도 AND 전역 안전 상한" 둘 다 통과해야 처리(전역은 숨은 가드).
- 변경 최소: provider-agent 한도 UI 노출만 제거, 서버별 정책 read/write API(Gap-P) 구현.

## 4. 현재 적용 상태

- **데스크톱 앱 UI**: 옵션 ②/① 공통의 UI 부분(전역 한도 비노출, 서버별만)을 **이미 반영**(프로토타입).
- **백엔드 엔진**: **미변경**. 어떤 옵션으로 정렬할지 **결정 대기**.
- 결정 전까지 provider-agent `--daily-limit`(전역)·`ContributionPolicy`(서버별)은 **둘 다 존재**.

> ⚠️ 백엔드 한도 엔진은 와이어·라우팅·자동배포가 얽혀 "즉시 삭제"가 위험하다.
> 옵션 결정 후 단계적으로(와이어 → central → agent → 테스트) 진행한다.
