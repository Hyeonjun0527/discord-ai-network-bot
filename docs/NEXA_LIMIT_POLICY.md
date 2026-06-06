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

## 3. 결정 — 옵션 ① 엔진을 서버별로 분해 (2026-06-07 채택·적용)

조사 결과 **인프라(WS 연결·ProviderSession·central 라우팅)는 이미 guild별로 분리**돼 있었다.
유일한 전역 지점은 **provider-agent 의 전역 잔여 카운터(`_remaining`)** 뿐이었다.
따라서 **와이어/central 변경 없이 provider-agent 만** 서버별로 바꿔 UI 와 완전히 일치시켰다.

### 적용 내용 (provider-agent only)
- `agent.py`: 전역 `self._remaining`(int) → **`self._remaining_by_guild`(dict)**. `_remaining_for(guild_id)`
  헬퍼(첫 접근 시 `daily_limit` 으로 lazy init). `handle_infer` 가 **연결의 guild 별로 체크·차감**.
  `_build_hello(guild_id)` 가 그 guild 의 잔여를 보고.
- `connection.py`: 연결이 `auth_ok.guild_id` 로 자기 guild 를 확정(`AgentConnection.guild_id`),
  hello 를 **그 guild 로 보고**(`_hello_provider(guild_id)`).
- **와이어 스키마 무변경** — 연결이 이미 guild별이라 `remainingDailyRequests` 필드 값만 guild별로 채움.
- **central-server 무변경** — 이미 `(providerId, guildId)` 세션별로 받아 차감 중. **자동배포 미트리거**.

### 결과
- 같은 daily_limit 가 **서버마다 독립** 적용된다(서버 A 소진이 서버 B 에 영향 없음).
- 검증: `test_daily_limit_per_guild`·`test_build_hello_per_guild`·`test_build_hello_unlimited_reports_zero`
  (provider-agent pytest 245 통과, ruff/mypy 그린).

## 4. 남은 후속 (선택)

- **서버별로 *다른* 한도 값** — 현재는 한 `daily_limit` 가 모든 서버에 독립 적용(같은 값). 데스크톱 앱
  G3(서버별 정책 변경, setServerPolicy)에서 서버마다 다른 값을 주려면 provider-agent 가 guild별
  `daily_limit` override 를 받는 경로(webui API/config) 필요 — **Gap-P 와 연계**, 후속 과제.
- central `ContributionPolicy.dailyLimit`(B)을 라우팅 하드캡으로 추가 적용할지는 선택(현재는 agent
  보고값으로 충분히 서버별 게이트됨).
