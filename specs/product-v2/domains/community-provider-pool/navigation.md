# 네비게이션 명세서

> 5분서 체계의 4번 문서. 입력은 `requirements.md`(REQ-###) · `domain-model.md`(DM-###) ·
> `screens.md`(SCR-###), 출력은 `api.md`(API-###) 로 물린다. 정식 출처는
> [`SOURCE_BRIEF.md`](./SOURCE_BRIEF.md) 와 [`../../README.md`](../../README.md) 규약,
> [`docs/adr/0002-remote-agent-byollm.md`](../../../../docs/adr/0002-remote-agent-byollm.md),
> [`docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md`](../../../../docs/ROADMAP_REMOTE_AGENT_DEPRECATED.md)(차수 16~24)다.

---

## 1. 문서 개요

### 1.1 목적
이 문서는 **커뮤니티 로컬 AI Provider Pool** 의 화면 이동과 도메인 상태 변화를 하나의 흐름으로
정의한다. 단순히 "어느 화면에서 어느 화면으로 가는가" 만이 아니라, **Discord 명령(슬래시/버튼/
모달) → 봇 응답 화면 → 도메인 상태 전이 → Provider Agent 연결/요청 라우팅** 까지의 인과 사슬을
플로우 단위로 추적 가능하게 만든다. 개발자·검증자·관리자가 동일한 흐름 지도를 공유하는 것이 목표다.

### 1.2 범위
- 포함: 일반 유저(`/ask` 등), 관리자(채널/역할/승인/정책), 프로바이더(등록~탈퇴), Provider Agent
  연결 수명주기, 요청 라우팅 19단계, 실패·fallback, 5개 상태머신의 전이.
- 제외: 판매/구매/가격/수수료/정산/마켓플레이스(README 비-목표), 화면 픽셀 디자인(→ `screens.md`),
  메시지 프레임 스키마 상세(→ `api.md`), 도메인 규칙 산식 상세(→ `domain-model.md`).
- 라우팅 모드는 ADR 0002 의 개인/공유 모드를 **다중 프로바이더 풀**로 일반화한 형태를 기준으로 한다.

### 1.3 Flow ID 규칙
- 모든 플로우는 **2자리 일련번호** `FLOW-##` 형식(README ID 정합 규칙 — 3자리/16진 금지).
  requirements.md §10.5 의 상위 앵커와 일치시킨다.
- 상위 앵커(requirements §10.5 와 동일):
  - `FLOW-01` — 봇 설치·초기 설정(§5.1)
  - `FLOW-02` — 서버 정책 설정(채널/역할/승인/제거/Pool/라우팅/프라이버시, §5.2~5.8)
  - `FLOW-03` — 프로바이더 등록·승인~탈퇴(§6)
  - `FLOW-04` — Agent 연결 수명주기(§7)
  - `FLOW-05` — 유저 질문 접수·보조 조회(§4)
  - `FLOW-08` — 요청 라우팅(§8)
  - `FLOW-09` — 응답 반환·기록(§8 종단)
  - `FLOW-10` — 프로바이더 일시정지·재개·이탈(§6 pause/resume/leave)
  - `FLOW-11` — 오프라인 처리(§7 끊김·세션 만료)
  - `FLOW-12` — 실패·fallback(§9)
- 하위 단계는 `FLOW-##.n` 으로 인용한다(예: `FLOW-08.9` 선택 단계).
- 한 번 부여한 FLOW ID 는 재사용/변경하지 않는다.

### 1.4 관련 문서
| 문서 | 역할 | 본 문서와의 관계 |
|---|---|---|
| `../../README.md` | 백본(어휘·ID·상태 목록) | 정식 어휘·10상태·ID 접두사 출처 |
| `SOURCE_BRIEF.md` | 기획 브리프(권위) | §7 19단계·§8 선택 규칙·§11 실패 처리 정합 |
| `requirements.md` | 요구사항(REQ-###) | 각 플로우 §11.11 에서 역참조 |
| `domain-model.md` | 도메인/상태(DM-###) | §10 전이표의 DM-S-* 와 ID 일치 |
| `screens.md` | 화면(SCR-###) | 각 단계의 시작/종료 화면 참조 |
| `api.md` | API(API-###) | 각 단계의 호출 API 참조 |
| ADR 0002 | 리버스 터널 에이전트 | Agent outbound·토큰·라우팅 키 근거 |
| ROADMAP 차수 16~24 | 등록/세션/정책/무게/라우팅/큐/보호 | 단계별 구현 매핑 |

### 1.5 상태 전이 표기 규칙
- 단계 시퀀스 표기: `SCR-### → (행위자: 액션) → API-### → DM-S-…:상태`.
  - 행위자: `유저` / `관리자` / `프로바이더` / `봇` / `중앙서버` / `Agent` / `시스템`.
- 상태 전이 표기: `현재상태 ──[트리거]──▶ 다음상태` (DM-S-* 머신 한정, §10).
- 도메인 이벤트는 `DM-EV-*`, 에러는 `ERR-*` 로 인용한다.
- ⓘ 표기는 화면만 바뀌고 도메인 상태는 불변(§2.7) 임을 뜻한다.

---

## 2. 네비게이션 원칙

### 2.1 명령 기반 흐름
모든 진입은 **Discord 슬래시 명령**(인터랙션)에서 시작한다(URL/딥링크 없음). 명령 → (필요 시)
모달/버튼 → ephemeral 또는 공개 응답 화면으로 분기한다. 토큰·민감 항목은 ephemeral 또는 DM 으로만
노출한다(ADR 0002 페어링 원칙). 명령 자체는 화면 전환이며, 도메인 상태 변화는 그 결과로만 일어난다.

### 2.2 관리자 설정 흐름
관리자 흐름(채널/역할/승인/정책)은 항상 **권한 가드 → 설정 화면 → 저장 → audit 기록** 순서다.
설정 변경은 `GuildPolicy`/`AllowedChannel`/`RolePolicy`/`ProviderApproval` 의 도메인 상태에만 영향을
주며, 진행 중 요청 라우팅에는 다음 요청부터 반영된다(즉시 전역 중단 아님).

### 2.3 Provider 등록 흐름
프로바이더 등록은 **신청(`/provider-join`) → 승인 대기 → 관리자 승인 → 토큰 발급 → Agent 실행 →
연결 → capability 보고 → 정책 설정 → 활성화** 의 직렬 파이프라인이다. 각 단계가 `DM-S-ProviderState`
한 상태씩 전진시킨다(§10.1). 동의 고지(프롬프트가 내 PC 로 전송됨)는 신청 단계에서 필수다.

### 2.4 Agent 연결 흐름
Agent 는 **outbound WS only**(ADR 0002 보안 불변식). 연결은 `실행 → auth → WS 수립 → Ollama 확인 →
모델 보고 → heartbeat` 순. 연결/끊김은 `DM-S-AgentConnState` 와 `DM-S-ProviderSessionState` 을 동시에
움직이며, 끊김 시 자동 재연결(지수 백오프)을 시도하되 토큰 세션이 만료되면 재페어링이 필요하다.

### 2.5 요청 처리 흐름
`/ask` 한 번이 브리프 §7 의 **19단계** 를 통과한다(§8). 각 단계는 `DM-S-RequestState` 를 전진시키고,
정책/필터/점수는 순수 함수로 분리된다(ROADMAP 차수 20~22). 사용자에게는 접수→처리중→답변의 3개
가시 화면만 노출되고, 19단계 내부는 시스템 내부 라우팅으로 숨긴다(§2.7, §3.5).

### 2.6 실패·fallback 흐름
실패는 브리프 §11 규칙을 따른다: ①request_id 생성 ②전송 ③timeout 설정 ④실패 시 **동일 조건 다른
provider 1회 fallback** ⑤fallback 실패 → 안내 ⑥실패 provider 를 temporarily unavailable 로 표시.
fallback 은 **단 1회**이며 원 provider 를 제외하고 동일 필터 결과에서 재선택한다(§9.6).

### 2.7 화면 이동과 도메인 상태 변화 분리 원칙
화면 전환(SCR)과 도메인 상태(DM-S)는 **별개의 축**이다.
- 화면만 변하고 상태는 불변인 경우(ⓘ): `/models`·`/privacy`·`/provider-status` 조회 등.
- 상태가 변하되 사용자 화면은 동일(ephemeral 안내 1개)인 경우: 라우팅 19단계 대부분.
- 보안 불변식상, 외부 유저 화면은 절대 Provider PC/Ollama 를 직접 가리키지 않는다(경로는 봇→중앙
  서버→WS→Agent→localhost Ollama 뿐). 화면은 "커뮤니티 풀 처리" 추상만 노출한다.

---

## 3. 전체 사용자 흐름 지도

### 3.1 유저
```
/ask ─▶ SCR-401 입력 → SCR-402 접수(ephemeral) ─▶ SCR-403 처리중(typing/defer)
        └▶ 성공 SCR-404 답변(+모드별 프라이버시 라벨 SCR-405)
        └▶ 권한부족 SCR-406 / Provider없음 SCR-901 / timeout SCR-909 / fallback실패 SCR-910
보조: /models→SCR-407 · /my-usage→SCR-408 · /privacy→SCR-409
```

### 3.2 관리자
```
봇 초대 ─▶ /llm-settings SCR-501(설정 패널)
   ├▶ /llm-allow-channel · /llm-deny-channel ─▶ SCR-502
   ├▶ /llm-role-policy ─▶ SCR-503
   ├▶ /providers ─▶ SCR-504(승인 대기)/SCR-505(상세)/SCR-507(헬스) ─▶ /provider-approve SCR-504 · /provider-remove SCR-506
   ├▶ pool 헬스 ─▶ SCR-507
   ├▶ 라우팅 정책 ─▶ SCR-509
   └▶ 프라이버시 모드 A/B/C ─▶ SCR-510
```

### 3.3 프로바이더
```
/provider-join ─▶ SCR-601 약관·동의(승인요청 접수 포함)
   ─▶ (승인) SCR-602 토큰 DM ─▶ SCR-603 Agent 연결 대기
   ─▶ 연결성공 SCR-604 ─▶ /provider-models SCR-605
   ─▶ /provider-scope SCR-606(기여범위) ─▶ /provider-limit SCR-607(한도)
   ─▶ 상태조회 SCR-608 ⇄ /provider-pause SCR-609 ⇄ /provider-resume SCR-610
   ─▶ /provider-leave SCR-611 탈퇴
```

### 3.4 Provider Agent
```
agent --token ─▶ provider_hello/auth(API-WS-PROVIDER-HELLO) ─▶ WS 수립 ─▶ Ollama 상태확인
   ─▶ auth_ok(API-WS-AUTH-OK) ─▶ heartbeat(API-WS-PING/PONG)
   ─▶ infer 수신(API-WS-INFER) ─▶ result/error 회신(API-WS-RESULT/API-WS-ERROR)
   ─▶ 끊김 → 백오프 재연결 / 세션만료 → 재페어링 / SIGINT → graceful 종료
```

### 3.5 시스템 내부 라우팅
```
AiRequestReceived ─▶ 정책체크(guild/channel/user/role) ─▶ 무게판단·필요수준
   ─▶ Pool 조회 ─▶ 10필터 ─▶ provider_score ─▶ 최종선택 ─▶ WS 전송
   ─▶ running ─▶ result ─▶ UsageLog/ContributionLog(DM-E-ContributionLog) 기록 ─▶ Discord 출력
   (실패 분기 → fallback 1회 → 안내 + health 업데이트)
```

---

## 4. 일반 유저 네비게이션

> 행위자: 일반 유저(User). 가시 화면은 접수/처리중/답변 3종 + 보조 조회. 19단계 내부는 §8 으로 위임.

### FLOW-05.1 `/ask` 시작
- SCR-401 명령 입력 → (유저: `/ask question:<텍스트>` 실행) → API-CMD-ASK 호출 → `AiRequest` 신규 생성.
- 도메인: DM-S-RequestState `received` 진입(DM-EV-AiRequestReceived).

### FLOW-05.2 접수
- SCR-402 접수 안내(ephemeral, "요청을 받았어요. 커뮤니티 풀에서 처리 중…") ⓘ 화면.
- 봇: `defer()` → 정책 체크 시작(§8.2~8.4, API-INT-CREATE-REQUEST). 상태: `received ──[정책검사 시작]──▶ policy_checked`.

### FLOW-05.3 처리 중
- SCR-403 처리중(typing/진행 표시) → 라우팅·전송(§8.5~8.10) → 상태 `routing → queued → sent_to_provider → running`.
- 큐 대기 시 SCR-403 에 "대기 중 n번째" 추가 표기(ROADMAP 511).

### FLOW-09 답변 수신
- Agent result 수신 → API-WS-RESULT → 상태 `running ──[result]──▶ completed`(DM-EV-RequestCompleted).
- SCR-404 답변 메시지(+ §5.8 모드별 프라이버시 라벨 SCR-405). UsageLog/ContributionLog 기록(§8.12).

### FLOW-05.4 권한 부족
- 정책 체크에서 역할 허용 수준 < 필요 수준 → 상태 `policy_checked ──[권한미달]──▶ rejected`.
- SCR-406 권한부족 안내(브리프 §11: "heavy 가 필요하지만 현재 역할로는… 관리자에게 권한 요청 또는
  더 짧은 질문"). 다운그레이드 가능 시 SCR-406 에 "light 로 다시 시도" 버튼 제시.

### FLOW-05.5 Provider 없음
- 10필터 후 후보 0명 → 상태 `routing ──[후보없음]──▶ failed`(no_provider_available 신호, §9.1).
- SCR-901 안내("현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. 잠시 후 또는 더 가벼운 요청으로").

### FLOW-12 timeout·fallback 실패
- 전송 후 timeout 또는 처리 중 끊김 → fallback 1회(§9.6) → fallback 도 실패 →
  상태 `fallback_running ──[fallback 실패]──▶ failed`(DM-EV-RequestFailed).
- SCR-909(timeout)/SCR-910(fallback 최종 실패) 안내 + 재시도 가이드. 실패 provider 들은 temporarily unavailable(§9.10).

### FLOW-05.6 `/models`
- SCR-407 사용 가능 모델/부담 수준 표(역할별 허용 수준 반영). API-CMD-MODELS 조회. ⓘ 상태 불변.

### FLOW-05.7 `/my-usage`
- SCR-408 오늘 내 요청 수/잔여 한도. API-CMD-MY-USAGE(UsageLog 집계). ⓘ 상태 불변.

### FLOW-05.8 `/privacy`
- SCR-409 프라이버시 안내(서버 모드 A/B/C 문구, 브리프 §10). API-CMD-PRIVACY. ⓘ 상태 불변.

---

## 5. 관리자 네비게이션

> 행위자: 서버 관리자(Admin). 공통 전제: 권한 가드(Manage Server/관리자/admin_role) → 설정 → audit.

### FLOW-01 초대 후 초기 설정
- 봇 초대 → SCR-501 `/llm-settings` 패널(미설정 경고 병기). API-CMD-LLM-SETTINGS → API-REST-GUILD-GET.
- 도메인: `Guild`/`GuildPolicy` 기본값 생성(승인방식·기본제한). ROADMAP 405~407.

### FLOW-02.1 사용 채널 설정
- SCR-501 → `/llm-allow-channel #ai-help` / `/llm-deny-channel` → SCR-502 채널 목록.
- API-CMD-LLM-ALLOW-CHANNEL / API-CMD-LLM-DENY-CHANNEL(위임: API-REST-GUILD-CHANNEL-ADD/REMOVE)
  → `AllowedChannel` 추가/제거. 다음 요청부터 §8.3 채널 필터에 반영.

### FLOW-02.2 역할별 수준
- SCR-503 `/llm-role-policy role:<역할> levels:<light,standard,heavy> daily:<n>`.
- API-CMD-LLM-ROLE-POLICY(위임: API-REST-GUILD-ROLE-UPDATE) → `RolePolicy` 저장(역할 `DM-V-RoleId`
  →허용 부담수준·일일한도, 다중 역할은 합집합). §8.4 에 반영.

### FLOW-02.3 Provider 승인
- SCR-504 `/providers`(pending 표시) → `/provider-approve provider:<유저>`.
- API-CMD-PROVIDER-APPROVE(위임: API-REST-PROVIDER-APPROVE) → DM-S-ApprovalState `requested ──[승인]──▶ approved`,
  DM-S-ProviderState `pending ──[관리자 승인]──▶ approved`(DM-EV-ProviderApproved) → 토큰 발급 트리거(§6 FLOW-03.5).

### FLOW-02.4 Provider 제거
- SCR-504 → SCR-506 `/provider-remove provider:<유저>` (확인 모달).
- API-CMD-PROVIDER-REMOVE(위임: API-REST-PROVIDER-REMOVE) → DM-S-ProviderState `* ──[관리자 제거]──▶ removed`. 진행 중 요청은 fallback 처리.

### FLOW-02.5 Pool 상태 확인
- SCR-507 pool 헬스(온라인/바쁨/오프라인/unhealthy 수 + provider별 기여량). API-REST-ADMIN-POOL. ⓘ 상태 불변.

### FLOW-02.6 라우팅 정책 수정
- SCR-509 라우팅 정책(쏠림 방지 가중치·heavy 낭비 패널티 토글 등 §8.8 튜닝 포인트). API-REST-GUILD-UPDATE.
- 도메인: `GuildPolicy` 갱신. 다음 요청 점수 계산에 반영(전이 아님, 값 변경).

### FLOW-02.7 프라이버시 안내 설정
- SCR-510 프라이버시 모드 선택 A(익명)/B(부분공개)/C(관리자만, 기본·추천, `DM-V-PrivacyMode`). API-REST-GUILD-PRIVACY-SET.
- 도메인: `GuildPolicy.privacy_mode` 갱신 → 답변 라벨(SCR-404/SCR-405)·`/privacy`(SCR-409) 출력에 반영.

---

## 6. 프로바이더 네비게이션

> 행위자: 프로바이더(Provider). 핵심 상태머신 DM-S-ProviderState(10상태, §10.1) 를 따라 전진/회귀.

### FLOW-03.1 참여 시작
- SCR-601 `/provider-join` 실행(ephemeral). API-CMD-PROVIDER-JOIN.
- 도메인: DM-S-ProviderState `unregistered ──[/provider-join]──▶ pending`(DM-EV-ProviderRegistered).

### FLOW-03.2 약관·프라이버시 확인
- SCR-601 동의 화면(브리프: "질문 프롬프트가 내 PC 로 전송됨" 고지, ROADMAP 368) → 동의 버튼 필수.
- 미동의 시 신청 중단(상태 회귀 `pending → unregistered`). ⓘ 화면 단계, 상태 전진은 동의 후.

### FLOW-03.3 승인 요청
- 동의 → SCR-601 "승인 대기" + 관리자 알림(채널/DM, ROADMAP 367). API-REST-PROVIDER-REGISTER.
- 도메인: `ProviderApproval` DM-S-ApprovalState `none ──[신청]──▶ requested`.

### FLOW-03.4 토큰 발급
- 관리자 승인(§5 FLOW-02.3) → SCR-602 일회용 Agent 토큰 DM(짧은 만료·1회 폐기, 코드블록). API-REST-PROVIDER-APPROVE.
- 도메인: `agent_token` 해시 저장(owner=provider_id,guild_id). 평문은 DM 직후 미저장(ROADMAP 178).

### FLOW-03.5 토큰 발급(재발급)
- SCR-602 "토큰 재발급" 옵션 → 기존 토큰 revoke + 신규 발급. API-REST-PROVIDER-APPROVE(재발급).
- DM-S-ApprovalState 불변(이미 approved). 토큰 레코드만 교체.

### FLOW-03.6 Agent 실행 안내
- SCR-603 실행 가이드("PC 에서 `agent --token <TOKEN>` 실행", 연결 대기). ⓘ 화면. Ollama 미기동 사전점검 안내(ROADMAP 260).

### FLOW-03.7 연결 성공
- Agent 연결·인증 성공(§7) → SCR-604 "연결됨". API-WS-AUTH-OK.
- 도메인: `ProviderSession` 생성, DM-S-ProviderState `approved ──[Agent 연결]──▶ online_idle`(DM-EV-ProviderAgentConnected).

### FLOW-03.8 모델 감지
- Agent provider_hello → SCR-604 감지된 모델 목록(부담수준 자동 분류 휴리스틱, ROADMAP 343).
- 도메인: `ProviderCapability` 저장(모델·부담수준·최대컨텍스트·예상속도).

### FLOW-03.9 제공 모델 선택
- SCR-605 `/provider-models`(감지 모델 중 제공할 것 선택/부담수준 오버라이드). API-CMD-PROVIDER-MODELS.
- 도메인: `ProviderCapability` 갱신. 즉시 세션 반영(ROADMAP 429).

### FLOW-03.10 기여 범위 설정
- SCR-606 `/provider-scope`(허용 역할: 전체/신뢰이상/관리자만, 허용 채널, 요청 종류). API-CMD-PROVIDER-SCOPE.
- 도메인: `ProviderContributionPolicy`(역할·채널·요청자 범위) 저장. §8.7 필터에 반영.

### FLOW-03.11 한도 설정
- SCR-607 `/provider-limit`(모델별 일일한도·동시한도·요청당 최대시간·긴 프롬프트 허용·길이 상한). API-CMD-PROVIDER-LIMIT.
- 도메인: `ProviderContributionPolicy`(한도부) 저장 + 일일 잔여 카운터 초기화.

### FLOW-03.12 활성화
- 모델·범위·한도 설정 완료 → SCR-608 "기여 활성화"(상태 조회). 상태는 online_idle 유지(연결+정책완비 = 라우팅 후보 자격).
- 도메인: 라우팅 후보 풀에 포함(§8.6). DM-S-ProviderState 불변(online_idle), 자격 플래그만 on.

### FLOW-10.1 pause
- SCR-609 `/provider-pause` → API-CMD-PROVIDER-PAUSE.
- 도메인: DM-S-ProviderState `online_idle|online_busy ──[/provider-pause]──▶ paused`(DM-EV-ProviderPaused).
  즉시 라우팅 후보 제외(§9.x). 진행 중 요청은 보호 정책에 따름(ROADMAP 520).

### FLOW-10.2 resume
- SCR-610 `/provider-resume` → API-CMD-PROVIDER-RESUME.
- 도메인: DM-S-ProviderState `paused ──[/provider-resume]──▶ online_idle`(DM-EV-ProviderResumed).

### FLOW-10.3 탈퇴
- SCR-611 `/provider-leave`(확인 모달) → API-CMD-PROVIDER-LEAVE.
- 도메인: DM-S-ProviderState `* ──[/provider-leave]──▶ removed`. 세션 종료·연결 해제·토큰 폐기.

---

## 7. Agent 연결 흐름

> 행위자: Provider Agent(프로바이더 PC). DM-S-AgentConnState(§10.4) + DM-S-ProviderSessionState(§10.2) 동시 구동.
> 불변식: outbound WS only, inbound 포트 미개방, 임의 shell/파일/URL 금지(README 보안).

### FLOW-04.0 실행
- 프로바이더 PC: `agent --token <TOKEN> --relay-url wss://… --ollama-url http://localhost:11434`.
- DM-S-AgentConnState `disconnected ──[프로세스 실행]──▶ connecting`.

### FLOW-04.1 토큰 검증
- Agent → 첫 프레임 provider_hello/auth(API-WS-PROVIDER-HELLO: 토큰·버전·플랫폼·capability) → 중앙서버 consume_agent_token.
- 성공: API-WS-AUTH-OK. 실패: API-WS-AUTH-ERR(ERR-AGENT-AUTH-FAILED) → 연결 종료, 상태 `connecting → disconnected`.

### FLOW-04.2 WS 연결
- auth_ok 수신 → DM-S-AgentConnState `connecting ──[auth ok]──▶ connected`.
- 중앙서버: 레지스트리 등록(owner→connection), 동일 owner 기존 연결은 축출(graceful close, ROADMAP 118).

### FLOW-04.3 Ollama 상태 확인
- Agent: localhost Ollama 헬스/모델 목록 조회. 미기동 시 콘솔 안내(ROADMAP 260) + 보고 보류.
- ⓘ Agent 내부 점검(중앙 상태 전이 없음, 다음 보고로 반영).

### FLOW-04.4 모델 보고
- Agent → provider_hello(API-WS-PROVIDER-HELLO: capability·모델·동시한도·일일잔여).
- 도메인: `ProviderCapability` 갱신(§6 FLOW-03.8). 런타임 모델 추가/제거는 API-WS-CAPABILITY-UPDATE 로 반영(ROADMAP 385).

### FLOW-04.5 상태 보고(주기)
- Agent → provider_status(API-WS-PROVIDER-STATUS: load·battery·online/busy) 주기 송신.
- 도메인: `ProviderHealth`·`ProviderSession` 갱신 → DM-S-ProviderState online_idle↔online_busy(§10.1).

### FLOW-04.6 heartbeat 시작
- 중앙서버 ping ⇄ Agent pong. last_seen 갱신. DM-S-AgentConnState `connected ──[heartbeat ok]──▶ alive`(논리적 유지).
- API-WS-PING / API-WS-PONG.

### FLOW-11.1 끊김
- 네트워크/프로세스 종료 → pong 미수신·소켓 close → DM-S-AgentConnState `alive ──[heartbeat 만료]──▶ disconnected`.
- 도메인: DM-S-ProviderState `* ──[연결 끊김]──▶ offline`(DM-EV-ProviderAgentDisconnected). 진행 요청은 §9.3.

### FLOW-04.7 자동 재연결
- Agent: 지수 백오프 재연결 시도 → 재auth → 성공 시 FLOW-04.2 로 복귀(online_idle 복원).
- DM-S-AgentConnState `disconnected ──[백오프 재시도]──▶ connecting`. 토큰 유효 시 재페어링 불필요.

### FLOW-11.2 세션 만료
- 토큰 TTL 만료 또는 revoke 후 재연결 시 auth 실패 → ERR-AGENT-AUTH-FAILED → SCR-603 재발급 안내.
- DM-S-AgentConnState `connecting ──[토큰 만료]──▶ disconnected`. 재페어링(§6 FLOW-03.5) 필요.

### FLOW-11.3 수동 종료
- 프로바이더: SIGINT(Agent 종료) 또는 `/provider-leave`(§6 FLOW-10.3) → graceful close.
- DM-S-AgentConnState `alive ──[수동 종료]──▶ disconnected`, DM-S-ProviderState → offline(또는 leave 시 removed).

---

## 8. 요청 라우팅 흐름 (브리프 §7 19단계 정합)

> FLOW-08 요청 라우팅. 시작 트리거: `/ask`(FLOW-05.1). 행위자: 봇·중앙서버. DM-S-RequestState 전진.
> 아래 8.1~8.13(=FLOW-08.1~08.13)은 브리프 19단계를 13개 가시 노드로 묶은 것이며, 괄호로 19단계 번호를 명시한다.

### 8.1 수신 (1)
- API-CMD-ASK 수신 → API-INT-CREATE-REQUEST → guild 확인. DM-S-RequestState `received`(DM-EV-AiRequestReceived).

### 8.2 서버 정책 (2,3)
- channel 확인 → 채널 LLM 사용 가능 확인(AllowedChannel). 불허 채널 → `rejected`(§9 외 정책 거절).

### 8.3 채널 정책 (3)
- AllowedChannel 매칭 통과. 실패 시 SCR-405 계열 안내로 `rejected`.

### 8.4 역할 정책 (4)
- user/role 확인 → RolePolicy 로 최대 허용 부담수준·일일한도 조회. 한도 초과 시 `rejected`.
- 통과 시 `received ──[정책검사 완료]──▶ policy_checked`(DM-S-RequestState).

### 8.5 무게 계산 (5,6)
- 질문 길이·첨부 확인 → 요청 무게 휴리스틱(ROADMAP 437~439). `policy_checked ──[라우팅 시작]──▶ routing`.

### 8.6 후보 생성 (7,8)
- 필요 모델 부담수준 결정(ROADMAP 440) → Provider Pool 조회(guild→provider[] 활성 후보, §6.B).

### 8.7 필터링 (9~12)
- 10필터 파이프라인(브리프 §8): ①모델수준 감당 ②온라인 ③idle ④요청자 허용 ⑤채널 허용
  ⑥일일 한도 잔여 ⑦동시 한도 미초과 ⑧최근 과다처리 아님 ⑨요청 크기 ≤ 제한 ⑩응답 실패율 낮음
  (+ RESTRICTED 특수 필터). 후보 0명 → §9.1.

### 8.8 점수 계산 (13)
- `provider_score = 모델 적합도 + 온라인 + idle + 남은 한도 + (최근 처리량 적을수록 가산) - 최근
  실패율 - 현재 부하 - heavy 낭비 패널티`(브리프 §15). light→light·standard→standard 우선,
  heavy→heavy 한정, heavy 는 light 요청에 후보 없을 때만 예외.

### 8.9 선택 (14)
- 최고 점수 1인 선택(동점 시 분산: 라운드로빈/시드). API-INT-SELECT. DM-EV-ProviderSelected.
- `RoutingDecision` 생성 → `routing ──[provider 선택]──▶ queued`.

### 8.10 전송 (15)
- per-host 동시 슬롯 획득 → WS 전송(API-INT-DISPATCH → API-WS-INFER). DM-EV-RequestSentToProvider.
- `queued ──[WS 전송]──▶ sent_to_provider`. 슬롯 만석 시 큐 대기(SCR-403 "대기 중").

### 8.11 응답 수신 (16,17)
- Agent: localhost Ollama 호출 → result 프레임 회신(API-WS-RESULT → API-INT-COLLECT, DM-EV-ProviderResponseReceived).
- `sent_to_provider ──[Agent 처리 시작]──▶ running ──[result]──▶ completed`(DM-EV-RequestCompleted).

### 8.12 사용량 기록 (19)
- UsageLog(요청자 기준 `DM-E-UsageLog`) + ContributionLog(provider 기준 `DM-E-ContributionLog`) 기록(공정성). API-REST-USAGE-USER/PROVIDER(내부 기록 트리거).
- 일일 카운터·최근 처리량 갱신 → 다음 §8.8 점수에 영향.

### 8.13 Discord 출력 (18) — FLOW-09
- SCR-404 답변(+ 모드별 프라이버시 라벨 SCR-405). API-CMD-ASK result. RequestState `completed` 종료.

---

## 9. 실패·fallback 흐름 (브리프 §11 정합)

> FLOW-12 실패·fallback. fallback 은 **동일 조건 다른 provider 1회**(브리프 §11-④). 행위자: 중앙서버.

### 9.1 후보 없음
- §8.7 후 후보 0명 → no_provider_available. `routing ──[후보없음]──▶ failed`.
- SCR-901 안내(§4 FLOW-05.5). fallback 불가(전송 전 단계). ERR-NO-PROVIDER.

### 9.2 전송 실패
- WS 전송 직후 소켓 오류/Agent 미수신 → ERR-SEND-FAILED → fallback 트리거(§9.6).

### 9.3 처리 중 끊김
- running 중 Agent 끊김(§7.77) → 진행 요청 실패 판정 → fallback 트리거.
- 원 provider: DM-S-ProviderState → offline.

### 9.4 Ollama 실패
- Agent error 프레임(모델 없음/메모리 부족/Ollama 오류, ERR-OLLAMA-*) → fallback 트리거.

### 9.5 timeout
- 요청 타임아웃(부담수준/정책 기반) 도달 → cancel 프레임 송신 → ERR-TIMEOUT → fallback 트리거.
- `running ──[timeout]──▶ failed`(fallback 진입 전 임시) → fallback 시 fallback_running.

### 9.6 fallback 선택
- 원 provider 제외 + §8.7 동일 필터 재실행 → 후보 1인 재선택(1회 한정).
- `failed/running ──[fallback 시작]──▶ fallback_running`(DM-EV-FallbackStarted). 후보 없으면 §9.8.

### 9.7 fallback 성공
- 대체 provider result 수신 → `fallback_running ──[result]──▶ completed`(DM-EV-RequestCompleted). API-INT-FALLBACK.
- SCR-404 답변(사용자에겐 정상 답변, fallback 사실은 모드 C 관리자 로그에만).

### 9.8 fallback 실패
- 대체 provider 도 실패 또는 fallback 후보 0명 → `fallback_running ──[fallback 실패]──▶ failed`(DM-EV-RequestFailed).
- 추가 재시도 없음(1회 한정 규칙).

### 9.9 사용자 안내
- SCR-910 최종 실패 안내(브리프 §11 문구: "현재 처리 가능한 커뮤니티 로컬 AI 가 없습니다. 잠시 후
  또는 더 가벼운 요청으로"). timeout 사유면 SCR-909, 권한 사유면 SCR-406 문구.

### 9.10 health 업데이트
- 실패에 기여한 provider → temporarily unavailable(DM-S-ProviderState → limited/unhealthy, 반복 실패 시
  DM-EV-ProviderMarkedUnhealthy). ProviderHealth 실패율 갱신 → 다음 §8.7 ⑩필터/§8.8 점수에 반영.

---

## 10. 상태 전이

> README 정식 상태 목록 사용. domain-model.md 의 `DM-S-*` 와 ID 일치. 형식: 현재 → [트리거] → 다음.

### 10.1 Provider (`DM-S-ProviderState`, 10상태)
| 현재 | 트리거 | 다음 |
|---|---|---|
| unregistered | `/provider-join` | pending |
| pending | 관리자 승인(`/provider-approve`) | approved |
| pending | 승인 거절/만료 | unregistered |
| approved | Agent 연결·인증(provider_hello) | online_idle |
| online_idle | 요청 배정·처리 시작 | online_busy |
| online_busy | 처리 완료(슬롯 반환) | online_idle |
| online_idle / online_busy | `/provider-pause` · 배터리/절전 자동 | paused |
| paused | `/provider-resume` | online_idle |
| online_idle / online_busy | 일일/동시 한도 소진·CPU·GPU 임계 | limited |
| limited | 한도 회복·부하 해소 | online_idle |
| online_idle / online_busy / limited | 연결 끊김·heartbeat 만료·절전 | offline |
| offline | 재연결·재auth 성공 | online_idle |
| online_busy / offline | 반복 실패 임계 초과 | unhealthy |
| unhealthy | 정상 응답 재개 | online_idle |
| 모든 상태 | `/provider-leave` · `/provider-remove` | removed |
| removed | 재등록(`/provider-join`) | pending |

### 10.2 Provider Session (`DM-S-ProviderSessionState`)
| 현재 | 트리거 | 다음 |
|---|---|---|
| (없음) | Agent 연결·인증 성공 | active(연결·heartbeat·capability 바인딩) |
| active | heartbeat 정상 | active(last_seen 갱신) |
| active | 처리 슬롯 점유 | busy |
| busy | 처리 종료 | active |
| active / busy | heartbeat 만료·소켓 close | closed |
| active / busy | `/provider-leave`·`/provider-remove`·SIGINT | closed |
| closed | 재연결·재auth | active(신규 세션) |

### 10.3 Request (`DM-S-RequestState`, 10상태)
| 현재 | 트리거 | 다음 |
|---|---|---|
| (없음) | `/ask` 수신 | received |
| received | 정책 검사 완료(guild/channel/user/role) | policy_checked |
| received / policy_checked | 채널 불허·역할 미달·한도 초과 | rejected |
| policy_checked | 무게 판단·라우팅 시작 | routing |
| routing | 후보 없음 | failed |
| routing | provider 선택(RoutingDecision) | queued |
| queued | per-host 슬롯 획득·WS 전송 | sent_to_provider |
| sent_to_provider | Agent 처리 시작 | running |
| running | result 수신 | completed |
| running / sent_to_provider | timeout·전송실패·끊김·Ollama 오류 | failed |
| failed / running | fallback 시작(동일조건 다른 provider 1회) | fallback_running |
| fallback_running | 대체 provider result | completed |
| fallback_running | 대체 실패·fallback 후보 0 | failed |

### 10.4 Agent 연결 (`DM-S-AgentConnState`)
| 현재 | 트리거 | 다음 |
|---|---|---|
| disconnected | 프로세스 실행(`agent --token`) | connecting |
| connecting | auth ok(AuthOk) | connected |
| connecting | auth 실패·토큰 만료 | disconnected |
| connected | heartbeat 정상 | alive |
| alive | ping/pong 유지 | alive |
| alive | heartbeat 만료·소켓 close·SIGINT | disconnected |
| disconnected | 지수 백오프 재시도(토큰 유효) | connecting |
| disconnected | 토큰 만료/revoke | disconnected(재페어링 필요) |

### 10.5 Approval (`DM-S-ApprovalState`)
| 현재 | 트리거 | 다음 |
|---|---|---|
| none | `/provider-join` 동의 완료 | requested |
| requested | 관리자 `/provider-approve` | approved |
| requested | 관리자 거절·만료 | rejected |
| approved | 토큰 재발급(rotate) | approved(토큰만 교체) |
| approved | `/provider-remove`·`/provider-leave` | revoked |
| rejected / revoked | 재신청(`/provider-join`) | requested |

---

## 11. 플로우별 상세 정의

> 형식: 11.1 Flow ID / 11.2 시작 트리거 / 11.3 시작 화면 / 11.4 종료 화면 / 11.5 참여 사용자 /
> 11.6 전제 조건 / 11.7 단계별 화면·상태 / 11.8 호출 API / 11.9 성공 조건 / 11.10 실패 조건 /
> 11.11 관련 요구사항 ID / 11.12 관련 화면 ID. 아래 8개 주요 플로우를 상세화한다.

### 11-A. `/ask` 요청 라우팅 (FLOW-08)
- **11.1** FLOW-08 (하위 §8.1~8.13 = FLOW-08.1~08.13, 답변은 FLOW-09)
- **11.2** 유저 `/ask question:<텍스트>` 실행
- **11.3** SCR-401 입력 → SCR-402 접수
- **11.4** SCR-404 답변
- **11.5** 일반 유저 + (내부) 중앙서버·선택 provider Agent
- **11.6** 봇 초대·`AllowedChannel` 1개 이상·활성 provider 1명 이상·요청 채널이 허용 채널
- **11.7** SCR-402 received → policy_checked → routing → (필터/점수) → queued → sent_to_provider →
  running → completed → SCR-404 (전이 상세 §10.3)
- **11.8** API-CMD-ASK, API-INT-CREATE-REQUEST, API-INT-SELECT, API-INT-DISPATCH, API-WS-INFER, API-WS-RESULT
- **11.9** result 수신 + SCR-404 출력 + UsageLog/ContributionLog 기록
- **11.10** 후보 0명(§9.1)·timeout(§9.5)·fallback 실패(§9.8)·권한 미달(§8.4)
- **11.11** REQ-510 요청 라우팅, REQ-604 공정 분배, REQ-514/702 프라이버시 라벨
- **11.12** SCR-401, SCR-402, SCR-403, SCR-404, SCR-405, SCR-406, SCR-901, SCR-909, SCR-910

### 11-B. 프로바이더 등록·승인 (FLOW-03.1 → FLOW-02.3)
- **11.1** FLOW-03.1(신청) + FLOW-03.3(요청) + FLOW-02.3(승인)
- **11.2** 프로바이더 `/provider-join`
- **11.3** SCR-601 약관·동의
- **11.4** SCR-602 토큰 DM (승인 시) / SCR-601 대기 (미승인)
- **11.5** 프로바이더 + 관리자
- **11.6** 미등록 상태(unregistered)·승인방식 정책 존재·동의 가능
- **11.7** SCR-601 unregistered →[동의] pending(DM-EV-ProviderRegistered) → ApprovalState requested →
  관리자 SCR-504 →[승인] approved(DM-EV-ProviderApproved) → SCR-602 토큰 발급
- **11.8** API-CMD-PROVIDER-JOIN, API-REST-PROVIDER-REGISTER, API-CMD-PROVIDER-APPROVE, API-REST-PROVIDER-APPROVE
- **11.9** approved 도달 + 토큰 DM 성공
- **11.10** 동의 거부(pending→unregistered)·관리자 거절/만료(→rejected)·중복 등록 방지
- **11.11** REQ-505 프로바이더 등록, REQ-506 승인, REQ-701 보안(토큰)
- **11.12** SCR-601, SCR-602, SCR-603, SCR-504

### 11-C. Agent 연결 (FLOW-04.0~04.6)
- **11.1** FLOW-04.0~04.6
- **11.2** 프로바이더 PC `agent --token <TOKEN>`
- **11.3** (콘솔) 실행 + SCR-603 연결 대기
- **11.4** SCR-604 연결됨(+ 모델 감지)
- **11.5** 프로바이더(Agent)
- **11.6** approved 상태·유효 토큰·Ollama 기동
- **11.7** AgentConnState disconnected →connecting →[auth ok] connected →alive,
  ProviderState approved →[연결] online_idle(DM-EV-ProviderAgentConnected),
  provider_hello → ProviderCapability 저장
- **11.8** API-WS-PROVIDER-HELLO, API-WS-AUTH-OK, API-WS-PING/PONG
- **11.9** online_idle 도달 + capability 보고 + heartbeat 유지
- **11.10** auth 실패(ERR-AGENT-AUTH-FAILED)·토큰 만료·Ollama 미기동
- **11.11** REQ-508 Agent 연결, REQ-706 확장성(세션/heartbeat), REQ-606 보안(outbound only)
- **11.12** SCR-603, SCR-604

### 11-D. pause/resume (FLOW-10.1 / FLOW-10.2)
- **11.1** FLOW-10.1(pause) + FLOW-10.2(resume)
- **11.2** 프로바이더 `/provider-pause` / `/provider-resume`
- **11.3** SCR-609 pause 확인 / SCR-610 resume 확인
- **11.4** SCR-608 상태(활성/일시정지 라벨)
- **11.5** 프로바이더(본인)
- **11.6** 본인 소유 provider·online_idle/online_busy(pause) 또는 paused(resume)
- **11.7** ProviderState online_idle|online_busy →[pause] paused(DM-EV-ProviderPaused) → 후보 제외;
  paused →[resume] online_idle(DM-EV-ProviderResumed) → 후보 복귀
- **11.8** API-CMD-PROVIDER-PAUSE, API-CMD-PROVIDER-RESUME
- **11.9** 상태 전이 + 라우팅 후보 즉시 반영
- **11.10** 타 provider 조작 시도(소유권 가드 거부)·Agent 미연결 시 동작 정의
- **11.11** REQ-603 프로바이더 보호(수동)
- **11.12** SCR-608, SCR-609, SCR-610

### 11-E. fallback (FLOW-12 / §9.2~9.10)
- **11.1** FLOW-12
- **11.2** 전송 실패·timeout·처리 중 끊김·Ollama 오류
- **11.3** SCR-403 처리중(사용자엔 동일 화면 유지)
- **11.4** SCR-404 답변(성공) 또는 SCR-910 실패
- **11.5** 중앙서버 + 원 provider + 대체 provider
- **11.6** 이미 sent_to_provider/running 상태·동일 조건 후보 1명 이상(원 provider 제외)
- **11.7** failed/running →[fallback 시작] fallback_running(DM-EV-FallbackStarted) →
  대체 result →completed(§9.7) | 대체 실패 →failed(§9.8); 원 provider →limited/unhealthy(§9.10)
- **11.8** API-INT-FALLBACK, API-WS-INFER(재전송), API-WS-CANCEL(원)
- **11.9** 대체 provider result + SCR-404
- **11.10** fallback 후보 0명·대체도 실패(1회 한정, 추가 재시도 없음)
- **11.11** REQ-512 fallback, REQ-510 라우팅 재선택, REQ-704 장애 대응
- **11.12** SCR-403, SCR-404, SCR-907, SCR-908, SCR-909, SCR-910

### 11-F. 관리자 채널 설정 (FLOW-02.1)
- **11.1** FLOW-02.1
- **11.2** 관리자 `/llm-allow-channel` / `/llm-deny-channel`
- **11.3** SCR-501 설정 패널
- **11.4** SCR-502 채널 목록
- **11.5** 서버 관리자
- **11.6** 관리자 권한(Manage Server/admin_role)·길드 초기화 완료
- **11.7** SCR-501 →[채널 추가/제거] AllowedChannel 갱신(상태머신 아님, 컬렉션 변경) → audit 기록 → SCR-502
- **11.8** API-CMD-LLM-ALLOW-CHANNEL, API-CMD-LLM-DENY-CHANNEL(위임: API-REST-GUILD-CHANNEL-ADD/REMOVE)
- **11.9** AllowedChannel 저장 + 다음 요청 §8.3 반영
- **11.10** 권한 부족·존재하지 않는 채널(정책 검증 방어)
- **11.11** REQ-503 채널 제한
- **11.12** SCR-501, SCR-502

### 11-G. 역할 정책 (FLOW-02.2)
- **11.1** FLOW-02.2
- **11.2** 관리자 `/llm-role-policy role:<역할> levels:<…> daily:<n>`
- **11.3** SCR-501 설정 패널
- **11.4** SCR-503 역할 정책 화면
- **11.5** 서버 관리자
- **11.6** 관리자 권한·대상 역할 존재
- **11.7** SCR-503 →[저장] RolePolicy 갱신(역할 `DM-V-RoleId` →허용 부담수준·일일한도, 다중 역할 합집합) → §8.4 반영
- **11.8** API-CMD-LLM-ROLE-POLICY(위임: API-REST-GUILD-ROLE-UPDATE)
- **11.9** RolePolicy 저장 + 멤버 최대 허용 수준 해석 반영
- **11.10** 존재하지 않는 역할·모순 정책(검증 거부)·권한 부족
- **11.11** REQ-504 역할별 권한, REQ-510 라우팅(권한 필터)
- **11.12** SCR-501, SCR-503

### 11-H. 프라이버시 설정 (FLOW-02.7)
- **11.1** FLOW-02.7
- **11.2** 관리자 `/llm-settings` → 프라이버시 모드 선택
- **11.3** SCR-501 설정 패널
- **11.4** SCR-510 프라이버시 모드 화면
- **11.5** 서버 관리자
- **11.6** 관리자 권한·길드 초기화 완료
- **11.7** SCR-510 →[A/B/C 선택] GuildPolicy.privacy_mode 갱신(`DM-V-PrivacyMode`, 기본 C_ADMIN_ONLY) →
  SCR-404/SCR-405 답변 라벨·`/privacy`(SCR-409) 출력에 반영
- **11.8** API-REST-GUILD-PRIVACY-SET
- **11.9** privacy_mode 저장 + 이후 답변/조회 출력 모드 일치
- **11.10** 권한 부족·잘못된 모드 값
- **11.11** REQ-514 프라이버시 안내, REQ-605 민감정보 입력 제한
- **11.12** SCR-510, SCR-404, SCR-409
