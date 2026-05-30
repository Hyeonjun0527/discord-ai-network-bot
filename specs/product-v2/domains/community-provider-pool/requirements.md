# 요구사항 명세서

> 도메인: **커뮤니티 로컬 AI Provider Pool**
> 5분서 체계 1번 문서(왜 필요하고 무엇을 만족해야 하는가). 정식 출처는
> [`SOURCE_BRIEF.md`](./SOURCE_BRIEF.md), 규약·정식 어휘는
> [`../../README.md`](../../README.md), 설계 근거는
> [`docs/adr/0002-remote-agent-byollm.md`](../../../../docs/adr/0002-remote-agent-byollm.md),
> 구현 로드맵은 [`docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`](../../../../docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md)
> Phase B(차수 13~32, 항목 301~674)다.

---

## 1. 문서 개요

### 1.1 문서 목적

이 문서는 **커뮤니티 로컬 AI Provider Pool** 기능이 *왜 필요하고 무엇을 만족해야 하는지*를
구현 가능한 수준으로 규정한다. 로드맵 674항목(특히 Phase B 301~674)을 구현하는 개발자가
"각 항목이 어떤 요구사항을 충족시키기 위한 것인지"를 이 문서 하나로 판단할 수 있어야 한다.
모든 요구사항에는 추적 가능한 `REQ-###` ID 를 부여하여, 하위 도메인 모델(`DM-`)·화면(`SCR-`)·
네비게이션(`FLOW-`)·API(`API-`) 명세로 일관되게 연결한다.

### 1.2 대상 도메인

Discord 서버(`guild`) 단위로 운영되는 **커뮤니티형 로컬 AI 협동 시스템**이다. 서버 구성원이
각자 감당 가능한 범위의 로컬 LLM(Ollama) 자원을 **프로바이더(Provider)** 로 등록하고, 중앙
봇이 요청자의 권한·요청 무게·**모델 부담 수준**·기여 한도·공정성을 기준으로 요청을
**Provider Pool** 내 적절한 프로바이더에게 분배한다. 이 도메인은 ADR 0002 의 단일 공유 호스트
구조를 **다중 프로바이더 풀**로 일반화한 것이다.

### 1.3 문서 범위

| 포함(In Scope) | 제외(Out of Scope) |
|---|---|
| 봇 설치·서버 LLM 정책·채널/역할 권한 | 봇 자체의 일반 설정(ADR 0001 범위) |
| 프로바이더 등록/승인/기여 정책 | 외부 클라우드 LLM 과금·키 관리 상세 |
| Provider Agent 연결·세션·heartbeat | Agent 패키징(실행파일/Docker)의 빌드 절차 |
| 요청 무게 판단·라우팅·큐·fallback | UI 픽셀 수준 디자인(화면 정의서 §3 범위) |
| 프라이버시 고지·사용량/기여량 기록 | 판매/가격/수수료/정산(§2.3 비-목표) |
| 보안·프라이버시 불변식 준수 | API 와이어 포맷 상세(API 명세서 범위) |

### 1.4 관련 문서

| 종류 | 경로 | 관계 |
|---|---|---|
| 백본 규약 | `../../README.md` | ID 규약·정식 어휘·보안 불변식의 SSOT |
| 원본 기획 브리프 | `./SOURCE_BRIEF.md` | 정식 출처(충돌 시 브리프+README 우선) |
| 설계 결정 | `docs/adr/0002-remote-agent-byollm.md` | 리버스 터널 에이전트·라우팅·프라이버시 결정 |
| 설계 결정(예정) | ADR 0003 community-provider-pool | Provider Pool 일반화(로드맵 301) |
| 구현 로드맵 | `docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md` | Phase B 차수 13~32(항목 301~674) |
| 도메인 모델 | `./domain-model.md` | 본 문서의 `REQ-` 를 `DM-` 로 구체화 |
| 화면 정의서 | `./screens.md` | `SCR-` 화면/메시지 |
| 네비게이션 | `./navigation.md` | `FLOW-` 흐름 |
| API 명세서 | `./api.md` | `API-` 메시지/엔드포인트 |

### 1.5 용어 참조

본 문서는 README §"정식 어휘(Canonical Vocabulary)"를 **글자 그대로** 사용한다(동의어 금지).
핵심 용어 요약:

- **일반 유저(User)** / **서버 관리자(Admin)** / **프로바이더(Provider)** / **시스템 관리자(Operator)**
- **Provider Pool**: 한 서버에 연결된 여러 프로바이더의 집합(`guild_id → provider_pool[]`).
- **Provider Agent**: 프로바이더 PC 의 경량 프로그램. 중앙 서버로 **outbound** WebSocket 연결만 연다.
- **중앙 봇/중앙 서버**: 요청 수신·정책 확인·프로바이더 선택·기록을 수행하는 단일 운영 주체.
- **모델 부담 수준(`DM-V-ModelBurdenLevel`)**: `light` · `standard` · `heavy` · `restricted` (가격 등급이 아니라 처리 부담도).
- **프로바이더 상태(`DM-S-ProviderState`, 10)**: `unregistered` · `pending` · `approved` · `online_idle` · `online_busy` · `paused` · `limited` · `offline` · `unhealthy` · `removed`.
- **요청 상태(`DM-S-RequestState`, 10)**: `received` · `policy_checked` · `routing` · `queued` · `sent_to_provider` · `running` · `completed` · `failed` · `fallback_running` · `rejected`.
- **중심 개념**: 기여(contribution) · 동의(consent) · 수용량(capacity) · 가용성(availability) · 공정성(fairness).

---

## 2. 제품 배경

### 2.1 문제 정의

기존 봇(ADR 0001)은 단일 전역 설정으로 **봇 호스트 머신의 Ollama** 에만 접속한다. 즉 "로컬
LLM" 은 봇 운영자 기준의 로컬일 뿐, 디스코드에서 명령을 쓰는 커뮤니티 구성원의 PC 와는
무관하다. 동시에 커뮤니티에는 고성능 데스크탑·작은 노트북·밤에만 켜는 PC 등 **서로 다른 가용
자원**을 가진 사람들이 흩어져 있고, 이 자원은 모이지 못한 채 낭비된다. 한편 유저 PC 는 보통
공인 IP 가 없고 NAT/방화벽 뒤에 있어 봇 호스트가 유저 PC 로 inbound 접속하는 것이 불가능하며,
임의 URL 노출 방식은 SSRF 위험을 동반한다.

### 2.2 해결하려는 사용자 문제

- **일반 유저**: 무엇이 어디서 처리되는지 신경 쓰지 않고 `/ask` 한 번으로 커뮤니티 자원으로
  답을 받고 싶다(프로바이더 PC 에 직접 접근하지 않고 오직 봇에게만 요청).
- **프로바이더**: "내가 이 커뮤니티를 이 정도까지 도울 수 있다"는 **기여 범위**(제공 모델·받을
  요청 종류·누구까지·하루 몇 번·동시 몇 개·어느 채널/역할·언제 일시정지)를 안전하게 등록하고,
  과부하 없이 보호받으며 돕고 싶다.
- **서버 관리자**: 허용 채널·역할별 모델 수준·프로바이더 승인을 통제하고 풀 상태를 보고 싶다.
- **시스템 관리자**: 중앙 봇 하나로 모든 서버를 운영하고, NAT·SSRF·토큰 누수 없이 안전하게
  유지하고 싶다.

### 2.3 만들지 않는 것 (비-목표)

다음은 명시적 **비-목표**다. 도메인 모델·코드·화면 어디에도 도입하지 않는다(로드맵 302·335).

- 판매자/구매자 개념, AI 모델 판매 서비스
- 가격표·요금제·과금(billing)
- 수수료·정산(payout)·프로바이더 수익
- 모델 마켓플레이스
- 핵심 도메인 모델에 `billing` / `price` / `seller` / `payout` 필드 추가

프로바이더가 정하는 것은 **가격이 아니라 기여 범위**다. 중심은 contribution·consent·capacity·
availability·fairness 다.

### 2.4 핵심 가치

1. **자원 결집**: 흩어진 로컬 LLM 자원을 하나의 Provider Pool 로 묶어 커뮤니티가 함께 쓴다.
2. **프로바이더 보호**: 감당 가능한 범위 안에서만 돕게 하고, 과부하·반복 실패·배터리/절전 시
   자동으로 보호한다. 프로바이더는 **모든 요청을 받을 의무가 없다**.
3. **공정한 분배**: 특정 프로바이더 쏠림을 막고, 가벼운 요청에 heavy 자원을 낭비하지 않는다.
4. **안전한 경로**: 유저는 프로바이더 PC 에 직접 접근할 수 없고, 모든 추론은 인증된 outbound
   연결로만 흐른다(SSRF 원천 차단).
5. **투명한 프라이버시**: 질문이 프로바이더 PC 로 전송될 수 있음을 명확히 고지한다.

### 2.5 성공 기준

| # | 성공 기준 | 측정 방법 |
|---|---|---|
| SC-1 | 멤버가 아무것도 설치하지 않고 `/ask` 로 커뮤니티 풀에서 답을 받는다 | E2E 시나리오 SCN-05 성공 |
| SC-2 | 프로바이더가 기여 범위를 등록하고 그 범위 밖 요청은 받지 않는다 | 필터 파이프라인 단위 테스트 |
| SC-3 | 가벼운 요청이 heavy 프로바이더로 기본 라우팅되지 않는다 | 공정성/라우팅 우선순위 테스트 |
| SC-4 | 특정 프로바이더가 연속 과다 처리되지 않는다(분배 균형) | 공정성 지표 임계 내 |
| SC-5 | 외부에서 프로바이더 PC/Ollama 에 직접 접근 불가 | 보안 리뷰 + SSRF 불가 재확인 |
| SC-6 | 모든 관련 서버에 프라이버시 고지가 노출된다 | 프라이버시 모드 출력 테스트 |
| SC-7 | 단일 프로바이더 장애가 graceful 실패/fallback 으로 처리된다 | 실패/fallback 테스트 |

---

## 3. 사용자 유형

### 3.1 일반 유저 (User)

AI 에게 질문(`/ask`)하는 서버 구성원. 프로바이더 PC 에 **직접 접근 불가**, 오직 봇에게만
요청한다. 내부 프로바이더가 누구인지 몰라도 되지만, "커뮤니티 로컬 AI 풀 처리" 고지는 명확히
받는다. 관련 명령: `/ask` · `/models` · `/my-usage` · `/privacy`.

### 3.2 서버 관리자 (Admin)

서버 LLM 정책(허용 채널·역할별 모델 수준·승인 방식·기본 요청 제한)을 설정하고 프로바이더를
승인/제거하며 Pool 상태를 모니터링한다. 관련 명령: `/llm-settings` · `/llm-allow-channel` ·
`/llm-deny-channel` · `/llm-role-policy` · `/providers` · `/provider-approve` ·
`/provider-remove`.

### 3.3 프로바이더 (Provider)

자기 PC 로컬 LLM 자원을 커뮤니티에 기여하는 사람. 제공 모델·기여 한도·허용 범위·일시정지를
스스로 정한다. 관련 명령: `/provider-join` · `/provider-leave` · `/provider-pause` ·
`/provider-resume` · `/provider-status` · `/provider-models` · `/provider-limit` ·
`/provider-scope`.

### 3.4 시스템 관리자 (Operator)

중앙 봇/중앙 서버를 운영하는 사람. relay/agent 환경변수, 토큰 수명, 보안 종단(wss/TLS),
관측성/메트릭, 백업·롤백을 책임진다. 일반적으로 디스코드 명령이 아닌 운영 환경(env·로그·
대시보드)으로 작업한다.

### 3.5 각 사용자별 권한 요약

| 행위 | 일반 유저 | 서버 관리자 | 프로바이더 | 시스템 관리자 |
|---|---|---|---|---|
| `/ask` 질문 | O | O | O | O |
| 서버 LLM 정책 설정 | X | O | X | (운영) |
| 채널 허용/금지 | X | O | X | (운영) |
| 역할별 모델 수준 설정 | X | O | X | (운영) |
| 프로바이더 승인/제거 | X | O | X | (운영) |
| Pool 상태/기여량 조회 | 본인 usage 만 | O(전체) | 본인 상태만 | O(운영 메트릭) |
| 프로바이더 등록 | X(등록 신청은 가능) | (승인) | O | X |
| 자기 기여 정책 설정 | X | X | O(본인) | X |
| Agent 연결/pause/leave | X | X | O(본인) | X |
| relay/토큰 수명 등 운영 설정 | X | X | X | O |

> 권한 불변식: 프로바이더는 **타 프로바이더**의 설정을 조작할 수 없다(본인 소유권 체크).
> 일반 유저는 정책·승인·운영에 관여할 수 없다.

---

## 4. 핵심 시나리오

각 시나리오는 README 규약대로 `SCN-01`~`SCN-10` 이며, 액터·전제·단계·결과를 명시한다.

### SCN-01 봇 추가
- **액터**: 서버 관리자
- **전제**: 봇 초대 권한 보유, Provider Pool 기능 활성 빌드
- **단계**: 1) 봇을 서버에 초대 → 2) 봇이 기본 GuildPolicy(허용 채널 없음/보수적 기본 제한)
  생성 → 3) 봇이 프라이버시 고지 안내(`/privacy` 안내) 표시
- **결과**: 서버에 봇이 추가되고, 정책 미설정 상태에서는 LLM 요청이 거절되도록 보수적 기본값 적용

### SCN-02 서버 LLM 정책 설정
- **액터**: 서버 관리자
- **전제**: 봇 추가 완료, 관리자 권한(Manage Server/admin_role)
- **단계**: 1) `/llm-allow-channel` 로 `#ai-help` 허용 → 2) `/llm-role-policy` 로 일반 멤버=
  light(하루 20)·신뢰 멤버=light+standard(하루 30)·관리자=light+standard+heavy 설정 →
  3) `/llm-settings` 로 프로바이더 승인 방식(수동) 설정
- **결과**: GuildPolicy 가 저장되고 이후 요청이 이 정책으로 판정됨, 변경은 audit_log 기록

### SCN-03 프로바이더 등록
- **액터**: 프로바이더, 서버 관리자
- **전제**: 서버 정책상 등록 허용, 승인 방식 = 수동
- **단계**: 1) 프로바이더가 `/provider-join` → `pending` 생성 + 동의 고지(프롬프트가 내 PC 로
  전송됨) → 2) 관리자에게 등록 요청 알림 → 3) 관리자가 `/provider-approve` → `approved` →
  4) 일회용 Agent 토큰 DM 발급
- **결과**: 프로바이더가 `approved` 상태가 되고 토큰으로 Agent 를 연결할 수 있음

### SCN-04 Agent 연결
- **액터**: 프로바이더(Provider Agent)
- **전제**: `approved` + 유효 일회용 토큰 + 로컬 Ollama 기동
- **단계**: 1) `agent --token <TOKEN>` 실행 → 2) 중앙 릴레이로 outbound wss 연결 → 3) `auth`
  프레임 전송, 토큰 검증·1회 소비 → 4) `provider_hello`(capability·모델·동시 한도·일일 잔여)
  보고 → 5) ProviderSession 생성, 상태 `approved → online_idle` → 6) 주기 heartbeat 시작
- **결과**: 프로바이더가 Pool 의 라우팅 후보가 됨, inbound 포트는 열리지 않음

### SCN-05 유저 질문 요청
- **액터**: 일반 유저
- **전제**: 허용 채널, 유저 역할이 필요 모델 수준 허용
- **단계**: 1) `/ask` 입력 → 2) 봇이 guild/channel/role/길이·첨부 확인(`received`→
  `policy_checked`) → 3) 요청 무게 판단 → 필요 모델 부담 수준 결정
- **결과**: 정책 통과 시 라우팅으로 진행, 사용자에게 처리 중(defer/typing) 표시

### SCN-06 Provider Pool 라우팅
- **액터**: 중앙 서버(Router)
- **전제**: 필요 모델 부담 수준 확정, Pool 조회 가능
- **단계**: 1) `routing` 진입 → 2) 10단계 필터(부담 감당·온라인·idle·요청자 허용·채널 허용·
  일일 잔여·동시 한도·과다 처리 아님·요청 크기·실패율) → 3) 공정성 점수 계산(쏠림/낭비 패널티)
  → 4) 최종 1인 선택 + 사유 → 5) `queued` → `sent_to_provider`
- **결과**: 적합 프로바이더 1인 선정 및 요청 전송, 선택 사유 기록(관리자 로그)

### SCN-07 응답 반환
- **액터**: Provider Agent, 중앙 서버, 일반 유저
- **전제**: 요청이 `sent_to_provider`
- **단계**: 1) Agent 가 `running` 으로 처리, localhost Ollama 호출 → 2) `result` 프레임 회신 →
  3) 중앙 서버 `completed` → 4) Discord 출력 + 프라이버시 모드별 처리 주체 표시 → 5) 사용량·
  기여량 기록
- **결과**: 유저가 답변 수신, usage_log/contribution_log 갱신

### SCN-08 프로바이더 일시정지·해제
- **액터**: 프로바이더
- **전제**: `online_idle`/`online_busy`
- **단계**: 1) `/provider-pause` → 상태 `paused`, 즉시 라우팅 후보 제외 → 2) (선택) 진행 중
  요청 보호 정책 적용 → 3) `/provider-resume` → `online_idle` 복귀, 또는 `/provider-leave` →
  `removed`
- **결과**: pause 동안 신규 요청 미배정, leave 시 Pool 에서 제거

### SCN-09 오프라인 처리
- **액터**: 중앙 서버
- **전제**: heartbeat 만료 또는 연결 끊김
- **단계**: 1) heartbeat 만료 감지 → 상태 `offline` → 2) 라우팅 후보 제외 → 3) Pool 에 가용
  프로바이더 없으면 "처리 가능한 커뮤니티 AI 없음" 안내
- **결과**: 오프라인 프로바이더 배제, 사용자에게 명확한 안내

### SCN-10 요청 실패와 fallback
- **액터**: 중앙 서버
- **전제**: 요청이 `running` 중 timeout/오류
- **단계**: 1) timeout/오류 감지 → `failed` 후보 표시(temporarily_unavailable) → 2) 동일 조건
  다른 프로바이더 **1회** fallback(`fallback_running`, 원 프로바이더 제외 재필터) → 3) fallback
  성공 시 `completed`, 실패 시 안내
- **결과**: 1회 fallback 후 성공 또는 "현재 처리 가능한 커뮤니티 로컬 AI 없음" 안내

---

## 5. 기능 요구사항

> 형식: **REQ-### · 제목 · 서술 · 수용기준 요약**. 절 번호 기반 ID(README §추적성 ID 규약).

### 5.1 봇 설치
**REQ-501 · 봇 설치** — 서버에 봇을 추가하면 보수적 기본 GuildPolicy(허용 채널 없음, 기본 요청
제한, 승인 방식 기본값)가 생성되어야 한다. 정책 미설정 상태에서 LLM 요청은 거절된다.
*수용기준*: 신규 서버에서 정책 설정 전 `/ask` 가 안전하게 거절되고 안내가 표시된다.

### 5.2 서버 설정
**REQ-502 · 서버 설정** — 관리자는 `/llm-settings` 통합 패널로 허용 채널·역할 정책·승인 방식·
기본 요청 제한을 설정할 수 있어야 하며, 변경은 audit_log 에 기록된다.
*수용기준*: 정책 저장 후 요청 판정에 즉시 반영되고 변경 이력이 남는다.

### 5.3 채널 제한
**REQ-503 · 채널 제한** — `/llm-allow-channel`/`/llm-deny-channel` 로 LLM 사용 가능 채널을
지정할 수 있어야 한다. 허용되지 않은 채널의 요청은 거절된다.
*수용기준*: 비허용 채널 요청은 `rejected` + 사유 안내, 허용 채널만 통과.

### 5.4 역할별 권한
**REQ-504 · 역할별 권한** — 역할별 허용 모델 부담 수준과 일일 요청 한도를 매핑할 수 있어야
하며, 멤버가 다중 역할일 때는 허용 수준의 합집합(가장 높은 허용)을 적용한다.
*수용기준*: 일반/신뢰/관리자 등급별 허용 수준이 정확히 판정되고 미지정 멤버는 기본 정책 적용.

### 5.5 프로바이더 등록
**REQ-505 · 프로바이더 등록** — 유저는 `/provider-join` 으로 등록을 신청할 수 있고 상태는
`pending` 으로 생성된다. 등록 시 "프롬프트가 내 PC 로 전송됨" 동의 고지를 받는다. 중복 등록은
방지된다.
*수용기준*: 신청 시 `pending` 생성·동의 고지 노출, 이미 프로바이더면 중복 차단.

### 5.6 승인
**REQ-506 · 승인** — 승인 방식이 수동이면 관리자가 `/provider-approve` 로 `pending → approved`
전환해야 하며, 승인 시 일회용·단기 만료 Agent 토큰을 DM 으로 발급한다. 승인/거절/만료는
audit_log 에 기록된다.
*수용기준*: 승인 시 토큰 발급 + 상태 전이, 자동 승인 모드면 등록 즉시 approved.

### 5.7 기여 한도
**REQ-507 · 기여 한도** — 프로바이더는 `/provider-limit`/`/provider-scope`/`/provider-models`
로 모델별 일일 한도·동시 처리 한도·요청당 최대 처리 시간·프롬프트 길이 상한·허용 역할/채널·
요청자 범위(전체/신뢰이상/관리자만)를 설정할 수 있어야 한다. 미설정 시 보수적 기본값을 쓴다.
*수용기준*: 설정 범위를 벗어난 요청은 해당 프로바이더 후보에서 제외된다.

### 5.8 Agent 연결
**REQ-508 · Agent 연결** — Provider Agent 는 일회용 토큰으로 중앙 릴레이에 outbound wss 로
연결·인증한 뒤 ProviderSession 을 생성하고 `provider_hello` 로 capability 를 보고해야 한다.
연결 직후 첫 프레임은 `auth` 여야 하며 토큰은 1회 소비된다.
*수용기준*: 인증 성공 시 `online_idle` 전이·capability 저장, 실패 시 연결 종료.

### 5.9 모델 부담 수준 분류
**REQ-509 · 모델 부담 수준 분류** — 모델은 `light`/`standard`/`heavy`/`restricted` 로 분류하며,
알려진 모델명 휴리스틱 + 프로바이더 오버라이드를 지원하고, 미상 모델은 보수적 기본값
(`standard`)을 적용한다. `restricted` 는 특정 역할/채널/관리자 요청만 허용한다.
*수용기준*: 분류 결과가 일관되고 미상 모델이 standard 로 처리되며 restricted 제약이 적용된다.

### 5.10 요청 라우팅
**REQ-510 · 요청 라우팅** — 중앙 서버는 필요 모델 부담 수준과 Pool 상태를 기준으로 10단계
필터를 거친 후보에 공정성 점수를 매겨 최종 1인을 선택해야 한다. light→light 우선,
standard→standard 우선, heavy→heavy 후보 한정이며 heavy 는 light/standard 후보가 없을 때만
예외적으로 사용한다. 가벼운 요청에 heavy 를 기본 사용하지 않는다.
*수용기준*: 라우팅 우선순위·heavy 낭비 방지·쏠림 방지가 테스트로 검증된다.

### 5.11 요청 큐
**REQ-511 · 요청 큐** — 프로바이더별 동시 처리 한도를 초과하는 요청은 큐잉되며 사용자에게
"대기 중" 을 표시한다. 큐 길이 상한을 초과하면 거절한다.
*수용기준*: 동시 한도 초과 시 큐잉·대기 표시, 상한 초과 시 거절 안내.

### 5.12 fallback
**REQ-512 · fallback** — 요청 실패(끊김/타임아웃/오류) 시 동일 조건의 다른 프로바이더로 **1회**
fallback 한다(원 프로바이더 제외 재필터). 실패한 프로바이더는 temporarily_unavailable 로
표시한다. fallback 도 실패하면 사용자에게 안내한다.
*수용기준*: 1회 fallback 동작·원 프로바이더 제외·최종 실패 안내가 검증된다.

### 5.13 사용량 기록
**REQ-513 · 사용량 기록** — 요청 완료 시 요청자 기준 usage_log 와 프로바이더 기준
contribution_log 를 기록하여 일일 한도 판정·공정성 점수·`/my-usage`·`/providers` 기여량 표시에
사용한다. 기록에는 민감 프롬프트 내용을 포함하지 않는다.
*수용기준*: 완료/실패 모두 적절히 집계되고 프롬프트 내용은 기록에서 최소화된다.

### 5.14 프라이버시 안내
**REQ-514 · 프라이버시 안내** — 모든 관련 서버에 "질문 내용이 프로바이더 PC 로 전송될 수 있음·
민감정보 입력 금지" 고지를 노출해야 한다. 처리 주체 표시는 서버 정책 A(익명)/B(부분 공개)/
C(관리자만, 기본 추천) 모드를 지원한다.
*수용기준*: `/privacy` 와 응답에 고지가 노출되고 모드별 출력이 정확하다.

### 5.15 관리자 모니터링
**REQ-515 · 관리자 모니터링** — 관리자는 `/providers` 로 Pool 목록·상태(온라인/바쁨/오프라인)·
capability·기여량을 조회하고, 문제 프로바이더를 강제 pause/제거할 수 있어야 한다. 관리자 행동은
audit_log 에 기록된다.
*수용기준*: Pool 헬스 요약·프로바이더 상세 보기·강제 제어가 동작하고 기록된다.

---

## 6. 정책 요구사항

### 6.1 프로바이더는 판매자가 아님
**REQ-601 · 프로바이더는 판매자가 아님** — 프로바이더는 가격·수익을 정하지 않으며 기여 범위만
정한다. 판매자/구매자/가격표/수수료/정산/마켓플레이스 개념을 도입하지 않는다(§2.3, 로드맵 302·335).
*수용기준*: 도메인 모델·코드에 billing/price/seller/payout 필드가 부재함을 가드로 보장.

### 6.2 기여 기반 운영
**REQ-602 · 기여 기반 운영** — 시스템 중심 개념은 contribution·consent·capacity·availability·
fairness 다. 모든 운영 결정(라우팅·한도·기록)은 이 다섯 개념으로 설명 가능해야 한다.
*수용기준*: 라우팅/기록/한도 로직이 다섯 개념에 대응되어 문서·테스트로 추적된다.

### 6.3 프로바이더 보호
**REQ-603 · 프로바이더 보호** — 프로바이더는 모든 요청을 받을 의무가 없다. 수동 보호
(`/provider-pause`·`/provider-resume`·`/provider-leave`·`/provider-limit`)와 자동 보호(CPU/GPU
과부하 시 수신 중단, 메모리 부족 시 거절, 배터리 모드 시 자동 pause, 절전 시 offline, 네트워크
불안정 시 temporarily unavailable, 동시/시간/길이 제한, 반복 실패 시 unhealthy 자동 비활성)를
제공한다.
*수용기준*: 수동·자동 보호가 라우팅 후보 제외/상태 전이에 즉시 반영된다.

### 6.4 공정 분배
**REQ-604 · 공정 분배** — 특정 프로바이더 쏠림을 방지하고, 무거운 요청은 감당 가능한 사람에게만
보내며, 가벼운 요청에 heavy 를 낭비하지 않고, 최근 많이 도운 프로바이더는 쉬게 하며, 한도 낮은
프로바이더를 존중한다.
*수용기준*: 공정성 점수에 최근 처리량 가산·heavy 낭비 패널티가 반영되고 분배 균형이 검증된다.

### 6.5 민감정보 입력 제한
**REQ-605 · 민감정보 입력 제한** — 질문이 프로바이더 PC 로 전송될 수 있으므로, 비밀번호·API 키·
개인정보·비공개 문서 등 민감정보 입력 금지를 명확히 안내해야 한다(프라이버시 불변식).
*수용기준*: 고지 문구가 표준화되어 관련 서버·응답에 노출된다.

### 6.6 로컬 PC 직접 접근 금지
**REQ-606 · 로컬 PC 직접 접근 금지** — 외부 유저는 프로바이더 PC/Ollama 에 직접 접근할 수 없다.
경로는 `봇 → 중앙 서버 → 인증된 WebSocket → Provider Agent → localhost Ollama` 뿐이다.
Provider Agent 는 outbound 연결만 하고 inbound 포트를 열지 않으며 임의 shell/파일/URL 을
실행하지 않는다(보안 불변식, README §보안·프라이버시 불변식).
*수용기준*: 보안 리뷰에서 직접 접근 불가·포트 미개방·금지 행위 차단이 확인된다.

---

## 7. 비기능 요구사항

### 7.1 보안
**REQ-701 · 보안** — 인증은 일회용·단기 만료 토큰 + 세션 heartbeat 로 한다. 토큰은 해시 저장·
평문 미저장·상수시간 비교하며 로그에 마스킹한다. 봇은 임의 URL 로 나가지 않아 SSRF 가 원천
차단된다. 요청 프레임은 허용 필드 화이트리스트만 받고, 프로바이더 간 요청은 격리된다.
**rate limit(빈도 제한)**: 유저 요청(`/ask`)·프로바이더 등록(`/provider-join`)·관리자/프로바이더
명령 호출 빈도에 서버·유저 단위 상한을 두어 남용·폭주·토큰 brute-force 를 차단한다(프레임
크기 상한과 함께, 로드맵 항목 627).
*수용기준*: 토큰/SSRF/격리/화이트리스트/rate limit 가 보안 단위 테스트와 리뷰로 검증된다.

### 7.2 프라이버시
**REQ-702 · 프라이버시** — 프롬프트가 프로바이더 PC 로 전송될 수 있음을 고지하고, 처리 주체
표시 모드 A/B/C(기본 C)를 지원하며, 로그에서 프롬프트 내용을 최소화한다.
*수용기준*: 고지 노출·모드별 출력·로그 최소화가 검증된다.

### 7.3 안정성
**REQ-703 · 안정성** — 단일 프로바이더 장애가 전체 서비스 장애로 번지지 않아야 한다. 요청
폭주·과부하에도 큐/동시 한도로 안정성을 유지한다.
*수용기준*: 호스트 다운 시 graceful 실패, 폭주 시 큐잉으로 안정 동작.

### 7.4 장애 대응
**REQ-704 · 장애 대응** — 예상 실패(연결 끊김·Ollama 지연·모델 없음·메모리 부족·timeout·pause
전환)에 대해 1회 fallback 과 명확한 사용자 안내를 제공하고, 실패 프로바이더를 일시적으로
후보에서 제외한다.
*수용기준*: 각 실패 유형이 안내/상태 전이로 처리되고 §8 예외 케이스를 모두 커버한다.

### 7.5 성능
**REQ-705 · 성능** — 라우팅 필터/점수 계산은 순수 함수로 분리되어 대규모 풀에서도 합리적
시간에 동작해야 한다. 정책 조회는 캐시로 요청 경로 성능을 보장한다.
*수용기준*: 대규모 풀 라우팅 수동 검증 시 응답 지연이 허용 범위 내.

### 7.6 확장성
**REQ-706 · 확장성** — Phase A 의 단일 공유 호스트 구조를 다중 프로바이더 풀로 일반화한
구조여야 하며, 멀티 세션 레지스트리(`guild → provider[] → session`)로 프로바이더 수 증가를
수용한다.
*수용기준*: 다중 프로바이더 인메모리 통합 테스트가 통과한다.

### 7.7 관측성
**REQ-707 · 관측성** — 활성 연결·처리/대기 수·모드별 라우팅 카운터·필터 단계별 탈락 수·공정성
지표를 메트릭으로 노출하고, 연결/해제/상태 전이/오류를 토큰·프롬프트 미노출로 로깅한다.
*수용기준*: 메트릭/로그가 노출되고 민감정보가 마스킹된다.

### 7.8 운영 편의성
**REQ-708 · 운영 편의성** — relay/agent 환경변수, 토큰 수명, wss/TLS 종단을 문서화하고, env
SSOT/docs-drift 가드와 동기화하며, 롤백 절차(ROLLBACK.md)를 갱신한다.
*수용기준*: 환경변수 표·가드·롤백 문서가 최신 상태로 통과한다.

---

## 8. 예외/실패 케이스

| ID | 케이스 | 처리 | 사용자 안내(요지) |
|---|---|---|---|
| EX-1 | Pool 비어있음 | 후보 0명 → `no_provider_available` (`rejected`) | "처리 가능한 커뮤니티 로컬 AI 가 없습니다" |
| EX-2 | 모든 프로바이더 오프라인 | heartbeat 만료/끊김 → 전원 `offline`, 라우팅 중단 | "잠시 후 다시 시도하거나 더 가벼운 요청으로" |
| EX-3 | 권한 부족 | 역할 허용 수준 미만 → 다운그레이드 제안 또는 `rejected` | "이 요청은 heavy 수준이 필요하지만 현재 역할로는 사용할 수 없습니다" |
| EX-4 | 모델 수준 미지원 | 필요 수준 감당 후보 없음 | "처리 가능한 모델 수준의 프로바이더가 없습니다" |
| EX-5 | 한도 초과 | 유저 일일 한도/프로바이더 한도 소진 | "오늘 사용 한도를 초과했습니다" / 해당 프로바이더 후보 제외 |
| EX-6 | Agent 연결 끊김 | 진행 요청 실패 처리 + fallback 시도, 세션 `offline` | "연결이 끊겨 다른 프로바이더로 재시도합니다" |
| EX-7 | Ollama 응답 실패 | `error` 프레임(OLLAMA_ERROR) → `failed` → fallback | (내부 안내 후 fallback) |
| EX-8 | timeout | 부담 수준/정책 기반 타임아웃 초과 → `failed` + cancel 프레임 | "응답이 지연되어 다른 프로바이더로 재시도합니다" |
| EX-9 | fallback 실패 | 1회 fallback 도 실패 → 최종 안내 | "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다" |

> 모든 예외는 요청 상태머신(`received`~`rejected`)의 명확한 전이로 표현되고 로깅된다.
> 권한 부족(EX-3)은 다운그레이드 제안 경로를, 혼잡(EX-1/EX-2)은 재시도 안내 경로를 사용한다.

---

## 9. 수용 기준

### 9.1 유저 요청
- 허용 채널·허용 역할에서 `/ask` 가 성공해 SCN-05→SCN-07 이 end-to-end 동작한다.
- 비허용 채널/권한 부족/한도 초과 요청은 적절한 안내와 함께 `rejected` 된다.
- 응답에 프라이버시 모드별 처리 주체 표시가 포함된다.

### 9.2 관리자 설정
- `/llm-settings`·채널·역할 정책 설정이 저장되고 요청 판정에 반영된다.
- 정책/승인/제거 등 관리자 행동이 audit_log 에 기록된다.
- `/providers` 가 Pool 헬스·기여량·상태를 정확히 표시한다.

### 9.3 프로바이더 등록
- `/provider-join` 이 `pending` 을 생성하고 동의 고지가 노출된다.
- 수동 승인 모드에서 `/provider-approve` 후에만 토큰이 발급된다.
- 중복 등록이 차단되고 removed 후 재등록 흐름이 동작한다.

### 9.4 Agent 연결
- 일회용 토큰으로 outbound wss 인증이 성공하고 토큰이 1회 소비된다.
- 연결 시 capability 가 저장되고 상태가 `online_idle` 로 전이된다.
- heartbeat 만료 시 `offline` 으로 전이되고 후보에서 제외된다.

### 9.5 라우팅
- 10단계 필터가 부적합 프로바이더를 제외한다.
- light/standard/heavy 우선순위와 heavy 낭비 방지 예외 규칙이 지켜진다.
- 최근 과다 처리 프로바이더가 공정성 점수로 후순위가 된다.
- 실패 시 1회 fallback 이 동작하고 최종 실패 시 안내된다.

### 9.6 보안
- 외부에서 프로바이더 PC/Ollama 에 직접 접근할 수 없다(포트 미개방, outbound only).
- 봇이 임의 URL 로 나가지 않아 SSRF 가 발생하지 않는다.
- 토큰은 평문 미저장·해시·상수시간 비교·로그 마스킹된다.
- 프로바이더 간 요청이 격리되고 권한 상승이 차단된다.

---

## 10. 추적성

> README 정식 어휘/접두사를 사용한다. 화면=SCR, 플로우=FLOW, API=API, 도메인=DM.
> 구체 ID 는 하위 문서 확정 전 추정값이며, 접두사 규약은 고정이다.

### 10.1 요구사항 ID 목록

| 범위 | ID |
|---|---|
| 기능(§5) | REQ-501 ~ REQ-515 |
| 정책(§6) | REQ-601 ~ REQ-606 |
| 비기능(§7) | REQ-701 ~ REQ-708 |
| 시나리오(§4) | SCN-01 ~ SCN-10 |
| 예외(§8) | EX-1 ~ EX-9 |

### 10.2 관련 화면 ID (SCR-)

> screens.md §10.A 인덱스의 실제 SCR ID 로 연결한다(README SSOT: SCR 정의처=screens.md).

| REQ | 관련 SCR |
|---|---|
| REQ-501/502 | SCR-501 LLM 설정 홈 |
| REQ-503/504 | SCR-502 허용 채널, SCR-503 역할별 수준 |
| REQ-505/506 | SCR-601 참여 시작, SCR-602 토큰 발급, SCR-504 승인 대기 |
| REQ-507 | SCR-605/606/607 모델·범위·한도 설정 |
| REQ-510/511/512 | SCR-403 처리 중, SCR-910 fallback 실패 |
| REQ-513/515 | SCR-408 `/my-usage`, SCR-504/505/507 `/providers` |
| REQ-514/605/702 | SCR-409 `/privacy`, SCR-404/405 AI 답변(처리 주체 표시) |
| EX-1~EX-9 | SCR-901/902 혼잡, SCR-906 한도, SCR-907~910 실패/fallback |

### 10.3 관련 API ID (API-)

> api.md §1.3 의 실제 ID 체계(`API-CMD-*`/`API-REST-*`/`API-WS-*`/`API-INT-*`)로 연결한다.

| REQ | 관련 API |
|---|---|
| REQ-501~504/515 | API-REST-GUILD-GET/UPDATE(Web Dashboard REST) |
| REQ-505/506 | API-CMD-PROVIDER-JOIN, API-CMD-PROVIDER-APPROVE(Discord Command API) |
| REQ-508 | API-WS-PROVIDER-HELLO, API-WS-AUTH-OK(Provider Agent WS) |
| REQ-510 | API-INT-SELECT, API-INT-DISPATCH(내부 Routing/State API) |
| REQ-511/512 | API-WS-INFER, API-INT-FALLBACK, API-WS-CANCEL |
| REQ-507/603 | API-WS-PROVIDER-STATUS, API-WS-PING/PONG |
| REQ-513 | API-REST-USAGE-USER/PROVIDER(내부 기록) |
| REQ-514 | API-CMD-PRIVACY, API-REST-GUILD-PRIVACY-SET |

### 10.4 관련 도메인 모델 ID (DM-)

| REQ | 관련 DM(추정) |
|---|---|
| REQ-501/502/504 | DM-E-Guild, DM-E-GuildPolicy, DM-E-RolePolicy |
| REQ-503 | DM-E-AllowedChannel |
| REQ-505/506 | DM-E-Provider, DM-E-ProviderApproval, DM-S-ProviderState |
| REQ-507 | DM-E-ProviderContributionPolicy, DM-E-ProviderCapability |
| REQ-508 | DM-E-ProviderSession, DM-EV-ProviderAgentConnected, DM-E-ProviderHealth |
| REQ-509 | DM-V-ModelBurdenLevel, DM-E-ModelProfile |
| REQ-510 | DM-E-RoutingCandidate, DM-E-RoutingDecision, DM-R-07 |
| REQ-511/512 | DM-E-AiRequest, DM-S-RequestState, DM-EV-FallbackStarted |
| REQ-513 | DM-E-UsageLog, DM-EV-RequestCompleted |
| REQ-514/702 | DM-EV-ProviderResponseReceived(프라이버시 표시 모드 연동) |
| REQ-603 | DM-EV-ProviderPaused, DM-EV-ProviderMarkedUnhealthy |
| REQ-604 | DM-R-07(light→heavy 미배정 등 공정성 규칙) |

### 10.5 관련 네비게이션 플로우 ID (FLOW-)

| REQ / SCN | 관련 FLOW(추정) |
|---|---|
| SCN-01 / REQ-501 | FLOW-01 봇 설치 |
| SCN-02 / REQ-502~504 | FLOW-02 서버 정책 설정 |
| SCN-03 / REQ-505/506 | FLOW-03 프로바이더 등록·승인 |
| SCN-04 / REQ-508 | FLOW-04 Agent 연결 |
| SCN-05 / REQ-509 | FLOW-05 유저 질문 접수 |
| SCN-06 / REQ-510/511 | FLOW-08 요청 라우팅 흐름 |
| SCN-07 / REQ-513/514 | FLOW-09 응답 반환·기록 |
| SCN-08 / REQ-603 | FLOW-10 프로바이더 일시정지·해제 |
| SCN-09 / REQ-703/704 | FLOW-11 오프라인 처리 |
| SCN-10 / REQ-512/704 | FLOW-12 실패·fallback |
