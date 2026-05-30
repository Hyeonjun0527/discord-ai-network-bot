# 화면 정의서

> 5분서 체계 3번 문서. 이 문서는 **유저가 보는 모든 화면/명령/메시지**를 정의한다.
> 출처: [`SOURCE_BRIEF.md`](./SOURCE_BRIEF.md) · [`README.md`](../../README.md) · ADR
> [`0002-remote-agent-byollm.md`](../../../../docs/adr/0002-remote-agent-byollm.md) ·
> [`ROADMAP_REMOTE_AGENT.md`](../../../../docs/ROADMAP_REMOTE_AGENT.md) 차수 25~28.
> 추적성: 위로는 `REQ-###`/`DM-###`, 아래로는 `FLOW-###`/`API-###` 와 물린다.

---

## 1. 문서 개요

### 1.1 목적
**커뮤니티 로컬 AI Provider Pool** 기능에서 일반 유저·서버 관리자·프로바이더·시스템 관리자가
마주치는 모든 화면(Discord 슬래시 명령 응답·Embed·Button·Select·Modal·설정 패널·Provider
Agent 콘솔·웹 대시보드)을 한 곳에 정의한다. 각 화면의 진입 조건·표시 데이터·사용자 액션·호출
API·상태 변화·예외 메시지·관련 요구사항을 명세하여 navigation/api 문서가 흐름과 통신을
상세화할 수 있는 기준을 제공한다.

### 1.2 화면 범위
| 포함 | 비포함 |
|---|---|
| Discord 슬래시 명령 응답(Embed/Field/Button/Select/Modal) | 가격표·결제·정산·구매/판매 UI(비-목표) |
| 관리자 설정 패널, 프로바이더 설정 화면 | 마켓플레이스/수익 대시보드 |
| Provider Agent CLI 콘솔 화면(텍스트 출력) | Discord 플랫폼 자체 UI(서버 설정 등) |
| 웹 대시보드(읽기 중심 관리자 화면) | 봇 운영자(Operator) 인프라 콘솔(별도 문서) |
| 오류·안내·프라이버시 고지 메시지 | Ollama 자체 설치 화면(외부 도구) |

차수 25~27 의 **모든 명령**(유저 `/ask` `/models` `/my-usage` `/privacy`; 관리자
`/llm-settings` `/llm-allow-channel` `/llm-deny-channel` `/llm-role-policy` `/providers`
`/provider-approve` `/provider-remove`; 프로바이더 `/provider-join` `/provider-leave`
`/provider-pause` `/provider-resume` `/provider-status` `/provider-models`
`/provider-limit` `/provider-scope`)의 응답 화면을 빠짐없이 포함한다.

### 1.3 화면 ID 규칙
- 모든 화면에 `SCR-###` 부여. 그룹별 100단위 대역:
  - 공통 컴포넌트: `SCR-3xx`
  - 일반 유저: `SCR-4xx`
  - 관리자: `SCR-5xx`
  - 프로바이더: `SCR-6xx`
  - Provider Agent: `SCR-7xx`
  - 웹 대시보드: `SCR-8xx`
  - 오류·안내: `SCR-9xx`
- 한 번 부여한 ID 는 재사용/변경하지 않는다(README §추적성 규약).
- 화면이 참조하는 에러는 `ERR-` 코드, 요구사항은 `REQ-`, 상태는 `DM-S-`, 플로우는 `FLOW-`,
  API 는 `API-` 로 표기한다. 아직 작성 전인 형제 문서의 ID 는 **선참조(forward reference)** 로
  기재하며, 해당 문서가 확정되면 일치시킨다.

### 1.4 관련 문서
| 문서 | 경로 | 연결 |
|---|---|---|
| 백본/규약/어휘 | `../../README.md` | ID 규칙·정식 어휘·로드맵 매핑 |
| 기획 브리프(정식 출처) | `./SOURCE_BRIEF.md` | 모든 화면 텍스트의 근거 |
| 요구사항 명세서 | `./requirements.md` | `REQ-###`(선참조) |
| 도메인 모델 명세서 | `./domain-model.md` | `DM-S-###` 상태·`DM-E-###` 엔티티(선참조) |
| 네비게이션 명세서 | `./navigation.md` | `FLOW-###`(선참조) |
| API 명세서 | `./api.md` | `API-###`·`ERR-###`(선참조) |
| ADR 0002 | `../../../../docs/adr/0002-remote-agent-byollm.md` | Agent·릴레이·토큰·프라이버시 |
| 구현 로드맵 | `../../../../docs/ROADMAP_REMOTE_AGENT.md` | 차수 25~28 명령/프라이버시 |

### 1.5 공통 UI 원칙
1. **정식 어휘 그대로**: "커뮤니티 로컬 AI Provider Pool", "모델 부담 수준", "기여",
   "프로바이더", "일반 유저"를 동의어 없이 사용한다(README §정식 어휘).
2. **ephemeral 우선**: 토큰·개인 사용량·정책 설정 등 타인에게 보이면 안 되는 응답은 모두
   ephemeral(본인만 보기)로 표시한다(로드맵 200·208).
3. **프라이버시 불변식 노출**: 질문 내용이 프로바이더 PC 로 전송될 수 있다는 고지를 답변/안내
   화면에 모드(A/B/C)에 맞게 노출한다(README §보안·프라이버시, 브리프 §10).
4. **상태는 항상 사람이 읽을 수 있게**: 프로바이더/요청 상태는 Badge + 이모지 + 한국어 라벨로
   표기한다(§3.5, §3.6).
5. **부담 수준 = 부담도, 가격 아님**: light/standard/heavy/restricted 를 가격 등급으로 오해할
   문구를 쓰지 않는다(브리프 §4).
6. **거절도 친절하게**: 권한·혼잡·한도 거절 시 항상 다음 행동(대기/다운그레이드/관리자 문의)을
   제시한다(브리프 §8·§11).
7. **색상 규약**: COLORS 토큰 재사용 — 성공=초록, 정보=파랑, 경고=노랑, 오류=빨강,
   프라이버시 고지=보라(로드맵 199·564·584).
8. **보안 표면 최소화**: 토큰은 코드블록 1회 노출, 만료 안내 동반, 로그/Embed 재노출 금지
   (ADR 페어링 흐름, 로드맵 178·262).

---

## 2. 화면 그룹

### 2.1 유저 (`SCR-4xx`)
일반 유저가 `/ask`로 질문하고 답변·처리 안내·사용량·모델 수준·프라이버시를 확인하는 화면군.
프로바이더 PC 내부는 노출하지 않는다(경로는 봇만).

### 2.2 관리자 (`SCR-5xx`)
서버 LLM 정책(허용 채널·역할별 수준·승인 방식·라우팅·프라이버시)과 Provider Pool 운영(승인
대기·상세·제거·헬스·사용량 요약)을 관리하는 화면군. 전부 관리자 권한 가드 + ephemeral.

### 2.3 Provider 등록 (`SCR-6xx`, 등록 영역)
`/provider-join`으로 풀에 참여하고 토큰을 받아 Agent를 연결하기까지의 온보딩 화면군.

### 2.4 Provider Agent (`SCR-7xx`)
프로바이더 PC에서 실행되는 경량 Agent 프로그램의 콘솔(CLI) 화면군. Discord가 아닌 터미널
텍스트 출력이며, outbound 연결·인증·Ollama 호출·상태 보고를 표시한다.

### 2.5 웹 대시보드 (`SCR-8xx`)
관리자용 REST 기반 웹 화면군. 서버 개요·Pool 대시보드·요청 로그·사용량/기여량 통계·정책
설정·장애 로그. 읽기 중심이며 민감 프롬프트 내용은 표시하지 않는다.

### 2.6 오류·안내 (`SCR-9xx`)
프로바이더 없음·전원 오프라인·권한 부족·채널 불가·수준 미지원·한도 초과·Agent 끊김·Ollama
실패·timeout·fallback 실패·민감정보 주의 등 예외 상황 메시지군.

---

## 3. 공통 컴포넌트

> 여러 화면에서 재사용되는 UI 빌딩 블록. 각 컴포넌트는 `SCR-3xx` 로 식별한다.

### 3.1 Embed — `SCR-301`
Discord 응답의 기본 컨테이너. 표준 슬롯:
- **title**: 화면명(예: "🤖 커뮤니티 로컬 AI — 답변").
- **description**: 본문(답변/안내/정책 요약).
- **color**: COLORS 규약(§1.5-7).
- **fields[]**: name/value 쌍(모델 수준·상태·잔여 한도 등). inline 최대 3열.
- **footer**: 처리 주체 고지(프라이버시 모드별) + 타임스탬프.
- **author**: 봇명/아이콘 고정.

### 3.2 Button — `SCR-302`
| 라벨 | style | 용도 |
|---|---|---|
| 다시 시도 | secondary | 실패 후 재요청 |
| 더 가벼운 요청으로 | primary | 다운그레이드 제안 |
| 승인 / 거절 | success / danger | 프로바이더 승인 대기 |
| 일시정지 / 재개 | secondary / success | 프로바이더 상태 토글 |
| 풀에서 나가기 | danger | `/provider-leave` 확인 |
| 토큰 재발급 | secondary | 토큰 rotate |
| 프라이버시 자세히 | secondary | `/privacy` 점프 |
| 자세히(대시보드) | link | 웹 대시보드 URL |

### 3.3 Select — `SCR-303`
드롭다운(StringSelect). 사용처:
- 프로바이더 상세 대상 선택(`/providers` 목록 → provider 선택).
- 역할별 허용 부담 수준 선택(다중 선택: light/standard/heavy/restricted).
- 라우팅 모드 선택(personal / shared).
- 프라이버시 모드 선택(A 익명 / B 부분공개 / C 관리자만).
- 제공 모델 선택(`/provider-models`, 감지된 Ollama 모델 목록).

### 3.4 Modal — `SCR-304`
팝업 입력 폼(최대 5 TextInput). 사용처:
- `/ask` 긴 질문 입력 Modal(선택적, 장문 입력 편의).
- `/provider-limit` 한도 입력(일일 한도·동시 한도·요청당 최대 초).
- `/provider-scope` 범위 입력(허용 역할/채널/요청 종류).
- `/llm-role-policy` 역할별 일일 한도 수치 입력.

### 3.5 상태 Badge — `SCR-305`
요청 상태(`DM-S-RequestState`, 10) 표기:
| 상태 | 라벨 | 이모지 |
|---|---|---|
| received | 접수됨 | 📥 |
| policy_checked | 정책 확인됨 | ✅ |
| routing | 라우팅 중 | 🧭 |
| queued | 대기 중 | ⏳ |
| sent_to_provider | 전송됨 | 📤 |
| running | 처리 중 | ⚙️ |
| completed | 완료 | ✔️ |
| failed | 실패 | ❌ |
| fallback_running | 재시도 중 | 🔁 |
| rejected | 거절됨 | 🚫 |

### 3.6 Provider 상태 표시 — `SCR-306`
프로바이더 상태(`DM-S-ProviderState`, 10) 표기:
| 상태 | 라벨 | 이모지 |
|---|---|---|
| unregistered | 미등록 | ⚪ |
| pending | 승인 대기 | 🟡 |
| approved | 승인됨 | 🔵 |
| online_idle | 온라인·대기 | 🟢 |
| online_busy | 온라인·처리중 | 🟠 |
| paused | 일시정지 | ⏸️ |
| limited | 한도 도달 | 🚥 |
| offline | 오프라인 | ⚫ |
| unhealthy | 비정상 | 🔴 |
| removed | 제거됨 | 🗑️ |

### 3.7 모델 부담 수준 표시 — `SCR-307`
가격이 아닌 **처리 부담도**(`DM-V-ModelBurdenLevel`, 브리프 §4):
| 수준 | 라벨 | 이모지 | 설명 |
|---|---|---|---|
| light | 가벼움 | 🟩 | 작은 모델·짧은 질문·간단 요약·가벼운 Q&A |
| standard | 표준 | 🟦 | 일반 로컬 모델·코딩 질문·일반 문서 요약 |
| heavy | 무거움 | 🟥 | 큰 모델·GPU/메모리 부담·긴 코드 분석·복잡 설계 리뷰 |
| restricted | 제한됨 | 🔒 | 프로바이더 특별 제한(특정 역할·채널·관리자만) |

### 3.8 Privacy Notice — `SCR-308`
프라이버시 고지 컴포넌트(보라색 Embed footer 또는 별도 필드). 기본 문구(브리프 §10):
> "이 서버는 커뮤니티 로컬 AI Provider Pool 을 사용합니다. 질문 내용은 요청을 처리하는
> 커뮤니티 프로바이더의 PC 로 전송될 수 있습니다. 비밀번호·API 키·개인정보·비공개 문서 등
> 민감 정보는 입력하지 마세요."

노출 빈도는 서버 설정(최초 1회 / 주기 / 매 응답)을 따른다(로드맵 596, ADR Open Questions).

### 3.9 Error Message — `SCR-309`
표준 오류 Embed(빨강). 슬롯: 제목(❗ + 무엇이 실패) · 사유 · 다음 행동(버튼/안내) · `ERR-` 코드
(관리자/디버그용 footer). 본문에는 프롬프트 원문을 절대 싣지 않는다(프라이버시).

### 3.10 Loading/Processing — `SCR-310`
처리 지연 표시. Discord `defer`(생각 중…) + 진행 상태 Badge(§3.5) 갱신. 공유/풀 처리가 큐에
들어가면 "⏳ 대기 중 — 앞에 N건"을 표시한다(로드맵 268·511).

---

## 4. 일반 유저 화면

### SCR-401 — /ask 입력
- **화면 ID**: SCR-401
- **화면명**: AI 질문 입력(`/ask`)
- **진입 조건**: 허용 채널에서 일반 유저가 `/ask` 입력. (장문은 §3.4 Modal 옵션)
- **표시 대상**: 일반 유저
- **주요 UI 요소**: 슬래시 명령 입력창, `질문`(필수)·`모델수준`(선택, light/standard/heavy
  Select)·`첨부`(선택) 파라미터, 장문 입력 Modal(SCR-304)
- **표시 데이터**: 입력 가이드, 현재 채널 사용 가능 여부 힌트
- **사용자 액션**: 질문 입력 → 제출
- **호출 API**: `API-CMD-ASK`(Discord Command), 이후 내부 `API-INT-SELECT`
- **이동/상태**: `FLOW-08` 시작 → SCR-402(접수). 요청 상태 `received`(`DM-S-RequestState`)
- **예외 메시지**: 채널 불가 시 `ERR-CHANNEL-NOT-ALLOWED`→SCR-904, 빈 질문 시 입력 거부
- **관련 요구사항**: `REQ-510`(/ask 라우팅 연결), `REQ-510`(라우팅)

### SCR-402 — 요청 접수
- **화면 ID**: SCR-402
- **화면명**: 요청 접수 알림
- **진입 조건**: `/ask` 제출 직후 정책 검사 시작
- **표시 대상**: 요청자(ephemeral 또는 채널)
- **주요 UI 요소**: Embed(파랑), 상태 Badge `📥 접수됨`
- **표시 데이터**: "요청을 접수했습니다. 커뮤니티 풀에서 처리할 프로바이더를 찾는 중…", 추정 모델
  부담 수준(§3.7)
- **사용자 액션**: 대기(취소 버튼 옵션)
- **호출 API**: 내부 `API-INT-CREATE-REQUEST`
- **이동/상태**: SCR-403(처리 중)으로 자동 전이. 상태 `received → policy_checked → routing`
- **예외 메시지**: 정책 위반 시 SCR-903/904/905 로 분기
- **관련 요구사항**: `REQ-510`, `REQ-504`(권한별 수준)

### SCR-403 — 처리 중
- **화면 ID**: SCR-403
- **화면명**: 처리 중(라우팅·실행)
- **진입 조건**: 프로바이더 선택 완료 후 Agent 전송·실행
- **표시 대상**: 요청자
- **주요 UI 요소**: SCR-310 Loading, 상태 Badge(`🧭 라우팅 중`→`📤 전송됨`→`⚙️ 처리 중`),
  큐 대기 시 "⏳ 대기 중 — 앞에 N건"
- **표시 데이터**: 진행 상태, 모델 부담 수준
- **사용자 액션**: 대기
- **호출 API**: `API-WS-INFER`(Provider Agent WebSocket Protocol, ADR `infer` 프레임)
- **이동/상태**: SCR-404(답변) 또는 SCR-907~910(실패). 상태 `routing → queued → sent_to_provider
  → running → completed`
- **예외 메시지**: timeout `ERR-TIMEOUT`→SCR-909, 끊김 `ERR-AGENT-DISCONNECTED`→SCR-907
- **관련 요구사항**: `REQ-510`, `REQ-704`(타임아웃)

### SCR-404 — AI 답변
- **화면 ID**: SCR-404
- **화면명**: AI 답변 메시지
- **진입 조건**: 프로바이더가 결과 반환(`result` 프레임)
- **표시 대상**: 요청자(채널)
- **주요 UI 요소**: Embed(초록), title "🤖 커뮤니티 로컬 AI — 답변", 답변 본문, footer
  프라이버시/처리주체 고지(§4.5 모드별), 버튼 [다시 시도][프라이버시 자세히]
- **표시 데이터**: 답변 텍스트, 모델 부담 수준(§3.7), 처리 주체 안내(모드 A/B/C)
- **사용자 액션**: 읽기, 후속 질문, 다시 시도
- **호출 API**: `API-WS-RESULT`(ADR `result`), 완료 후 내부 `API-REST-USAGE-USER`
- **이동/상태**: `FLOW-08` 종료. 상태 `completed`, `RequestCompleted`/`ProviderResponseReceived`
  이벤트
- **예외 메시지**: 빈 응답 시 SCR-910(fallback 실패) 경로
- **관련 요구사항**: `REQ-514`(풀 처리 안내), `REQ-601`(usage 기록)

### SCR-405 — Pool 처리 안내(프라이버시/처리주체)
- **화면 ID**: SCR-405
- **화면명**: 커뮤니티 풀 처리 안내(모드 A/B/C)
- **진입 조건**: SCR-404 footer 또는 별도 안내로 부착, 모드 = guild 프라이버시 설정
- **표시 대상**: 일반 유저(모드 C 에서는 관리자만 provider 식별)
- **주요 UI 요소**: 프라이버시 Notice(SCR-308) + 처리주체 라인
- **표시 데이터(모드별 실제 문구)**:
  - **모드 A(익명)**: "커뮤니티 로컬 AI 풀에서 처리됨. 모델 수준: standard"
  - **모드 B(부분 공개)**: "커뮤니티 프로바이더가 처리. 모델 수준: standard / 처리 위치:
    community local provider"
  - **모드 C(관리자만, 추천)**: 일반 유저에게는 "커뮤니티 로컬 AI 풀에서 처리됨", 관리자에게만
    "처리 프로바이더: @provider — 모델 llama3:8b" 추가 표시
- **사용자 액션**: [프라이버시 자세히] → SCR-409
- **호출 API**: 내부 `API-CMD-PRIVACY`(모드별 텍스트 빌더)
- **이동/상태**: 표시 전용. `RoutingDecision` 의 owner 정보 사용(모드 C 만 노출)
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-514`/`REQ-514`/`REQ-514`(모드 A/B/C), `REQ-514`

### SCR-406 — 권한 부족
- **화면 ID**: SCR-406
- **화면명**: 권한 부족 안내(요청 수준 > 허용 수준)
- **진입 조건**: 요청 무게가 필요로 한 부담 수준이 요청자 역할 허용 상한 초과
- **표시 대상**: 요청자(ephemeral)
- **주요 UI 요소**: Embed(노랑), 다운그레이드 제안 버튼 [더 가벼운 요청으로]
- **표시 데이터(실제 문구)**: "이 요청은 heavy 수준이 필요하지만 현재 역할로는 사용할 수
  없습니다. 관리자에게 권한 요청 또는 더 짧은 질문으로 다시 시도하세요."
- **사용자 액션**: 다운그레이드 재시도, 관리자 문의
- **호출 API**: 내부 `API-INT-CREATE-REQUEST`(권한 판정)
- **이동/상태**: 상태 `rejected`, `FLOW-08` 권한 분기 → SCR-903 동일군
- **예외 메시지**: `ERR-PERMISSION-DENIED` / `ERR-LEVEL-UNSUPPORTED`
- **관련 요구사항**: `REQ-510`(다운그레이드/거절), `REQ-510`

### SCR-407 — 모델 수준 조회(`/models`)
- **화면 ID**: SCR-407
- **화면명**: 서버 사용 가능 모델 부담 수준
- **진입 조건**: 유저가 `/models` 실행
- **표시 대상**: 일반 유저
- **주요 UI 요소**: Embed(파랑), 부담 수준 표(§3.7), 내 역할 기준 허용 수준 강조
- **표시 데이터**: light/standard/heavy/restricted 각 라벨·이모지·설명, 현재 풀에서 가용한 수준,
  내 역할 허용 범위·일일 한도
- **사용자 액션**: 읽기, `/ask` 로 이동
- **호출 API**: `API-CMD-MODELS`(Discord Command), 내부 `API-INT-HEALTH-MODEL`
- **이동/상태**: 표시 전용. `ProviderCapability` 집계 사용
- **예외 메시지**: 풀에 가용 프로바이더 없음 시 SCR-902 안내 병기
- **관련 요구사항**: `REQ-509`(/models), `REQ-509`(부담 수준 요약 빌더)

### SCR-408 — 내 사용량(`/my-usage`)
- **화면 ID**: SCR-408
- **화면명**: 내 오늘 사용량
- **진입 조건**: 유저가 `/my-usage` 실행
- **표시 대상**: 일반 유저(ephemeral)
- **주요 UI 요소**: Embed(파랑), 수준별 사용/잔여 게이지(텍스트 막대)
- **표시 데이터**: 오늘 요청 수, 역할 일일 한도, 잔여, 수준별 분포
- **사용자 액션**: 읽기
- **호출 API**: `API-CMD-MY-USAGE`, 내부 `API-REST-USAGE-USER`
- **이동/상태**: 표시 전용. `UsageLog` 조회(요청자 기준)
- **예외 메시지**: 기록 없음 시 "오늘 사용 기록이 없습니다."
- **관련 요구사항**: `REQ-513`(/my-usage), `REQ-604`(유저 일일 집계)

### SCR-409 — 프라이버시 안내(`/privacy`)
- **화면 ID**: SCR-409
- **화면명**: 프라이버시·처리 방식 안내
- **진입 조건**: 유저가 `/privacy` 실행 또는 SCR-405 의 [프라이버시 자세히]
- **표시 대상**: 일반 유저
- **주요 UI 요소**: Privacy Notice(SCR-308, 보라), 현재 서버 프라이버시 모드(A/B/C) 표시
- **표시 데이터(실제 문구)**: 기본 고지(§3.8) + "현재 이 서버의 처리 주체 표시 모드: C(관리자만
  공개) — 일반 유저에게는 풀 처리됨으로만 표시되며, 어떤 프로바이더가 처리했는지는 관리자만
  확인할 수 있습니다."
- **사용자 액션**: 읽기
- **호출 API**: `API-CMD-PRIVACY`(Discord Command)
- **이동/상태**: 표시 전용. guild 프라이버시 모드 설정 조회
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-514`(/privacy)

### SCR-410 — 도움말(`/help`)
- **화면 ID**: SCR-410
- **화면명**: 명령·사용법 도움말
- **진입 조건**: 유저/관리자/프로바이더가 `/help` 실행(로드맵 547)
- **표시 대상**: 모든 사용자(ephemeral)
- **주요 UI 요소**: Embed(파랑), 역할별 사용 가능 명령 목록(유저 4종/관리자 7종/프로바이더 8종),
  부담 수준 안내(§3.7), 프라이버시 고지 링크(SCR-308)
- **표시 데이터**: 호출자 권한에 맞는 명령 카탈로그·간단 설명·예시, 풀 안내, 거절 시 다음 행동 가이드
- **사용자 액션**: 읽기, 각 명령으로 이동, [프라이버시 자세히]→SCR-409
- **호출 API**: `API-CMD-HELP`(Discord Command)
- **이동/상태**: 표시 전용. ⓘ 상태 불변
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-501`~`REQ-515`(명령 카탈로그), `REQ-514`(프라이버시 고지)

### SCR-411 — 쿨다운 안내
- **화면 ID**: SCR-411
- **화면명**: 요청 빈도 제한(쿨다운) 안내
- **진입 조건**: 유저/프로바이더 명령이 rate limit(빈도 제한) 쿨다운에 걸림(로드맵 548, REQ-701 rate limit)
- **표시 대상**: 요청자(ephemeral)
- **주요 UI 요소**: Embed(노랑), 남은 쿨다운 시간 표시, [내 사용량 보기]→SCR-408
- **표시 데이터(실제 문구)**: "요청이 너무 잦습니다. N초 후 다시 시도해 주세요. (서버 빈도 제한 보호)"
- **사용자 액션**: 대기 후 재시도
- **호출 API**: 내부 rate-limit 가드(§3 권한·가드 단계에서 차단)
- **이동/상태**: 요청 미생성(거절). ⓘ 상태 불변(요청 생성 전 차단)
- **예외 메시지**: `ERR-RATE-LIMITED`
- **관련 요구사항**: `REQ-701`(보안·rate limit)

---

## 5. 관리자 화면

> 모든 관리자 화면은 권한 가드(Manage Server/관리자/admin_role) + ephemeral. 변경은 audit_log
> 기록(로드맵 563).

### SCR-501 — LLM 설정 홈(`/llm-settings`)
- **화면 ID**: SCR-501
- **화면명**: LLM 설정 통합 패널
- **진입 조건**: 관리자가 `/llm-settings` 실행
- **표시 대상**: 관리자
- **주요 UI 요소**: Embed(파랑) + Select(섹션 이동: 허용 채널/역할 정책/승인 방식/라우팅/프라이버시)
  + 버튼 [Pool 상태][사용량 요약][대시보드 열기]
- **표시 데이터**: 현재 정책 요약(허용 채널 수·역할 정책 수·승인 방식·라우팅 모드·프라이버시 모드)
- **사용자 액션**: 섹션 선택 → SCR-502~510 진입
- **호출 API**: `API-CMD-LLM-SETTINGS`(Discord Command), `API-REST-ADMIN-DASHBOARD`(웹 연계)
- **이동/상태**: 허브 화면. 각 하위 화면으로 분기
- **예외 메시지**: 권한 없음 `ERR-PERMISSION-DENIED`→SCR-903
- **관련 요구사항**: `REQ-502`(/llm-settings), `REQ-502`

### SCR-502 — 허용 채널(`/llm-allow-channel` · `/llm-deny-channel`)
- **화면 ID**: SCR-502
- **화면명**: 허용/금지 채널 관리
- **진입 조건**: `/llm-allow-channel` 또는 `/llm-deny-channel`, 또는 SCR-501 → 채널 섹션
- **표시 대상**: 관리자
- **주요 UI 요소**: 채널 Select(ChannelSelect), 현재 허용 목록 필드, 버튼 [추가][금지][제거]
- **표시 데이터**: 허용 채널 목록(예 #ai-help, #coding-help), 금지 채널
- **사용자 액션**: 채널 추가/금지/제거
- **호출 API**: `API-CMD-LLM-ALLOW-CHANNEL` / `API-CMD-LLM-DENY-CHANNEL`
- **이동/상태**: `GuildPolicy.allowed_channels` 갱신, audit_log
- **예외 메시지**: 존재하지 않는 채널 `ERR-INVALID-CHANNEL`
- **관련 요구사항**: `REQ-503`/`REQ-503`, `REQ-503`/`REQ-503`

### SCR-503 — 역할별 수준(`/llm-role-policy`)
- **화면 ID**: SCR-503
- **화면명**: 역할별 허용 부담 수준·한도
- **진입 조건**: `/llm-role-policy` 또는 SCR-501 → 역할 섹션
- **표시 대상**: 관리자
- **주요 UI 요소**: 역할 Select(RoleSelect) + 부담 수준 다중 Select(§3.3) + 일일 한도 Modal(§3.4)
- **표시 데이터(예시)**: "일반 멤버: light, 하루 20 / 신뢰 멤버: light+standard, 하루 30 / 관리자:
  light+standard+heavy"
- **사용자 액션**: 역할 선택 → 허용 수준·한도 설정 저장
- **호출 API**: `API-CMD-LLM-ROLE-POLICY`
- **이동/상태**: `RolePolicy` 갱신, audit_log
- **예외 메시지**: 존재하지 않는 역할 `ERR-INVALID-ROLE`
- **관련 요구사항**: `REQ-504`~`REQ-504`, `REQ-504`

### SCR-504 — Provider 승인 대기(`/providers` 승인 탭)
- **화면 ID**: SCR-504
- **화면명**: 프로바이더 승인 대기 목록
- **진입 조건**: `/providers` 실행 시 pending 존재, 또는 등록 요청 알림(로드맵 367)
- **표시 대상**: 관리자
- **주요 UI 요소**: 각 대기 항목 Embed + 버튼 [승인][거절], Provider 상태 Badge `🟡 승인 대기`
- **표시 데이터**: 신청자(@user), 신청 시각, 보고된 capability(있으면), 동의 고지 수락 여부
- **사용자 액션**: 승인 → 토큰 발급(SCR-602 흐름), 거절 → 안내
- **호출 API**: `API-CMD-PROVIDER-APPROVE`(Discord Command), 내부 `API-REST-PROVIDER-APPROVE`
- **이동/상태**: `pending → approved`(`ProviderApproved` 이벤트), `FLOW-02.3`
- **예외 메시지**: 이미 승인/제거됨 `ERR-PROVIDER-STATE-CONFLICT`
- **관련 요구사항**: `REQ-506`(/provider-approve), `REQ-506`

### SCR-505 — Provider 상세(`/providers` → 선택)
- **화면 ID**: SCR-505
- **화면명**: 프로바이더 상세 보기
- **진입 조건**: `/providers` 목록에서 provider Select(§3.3)
- **표시 대상**: 관리자
- **주요 UI 요소**: Embed(파랑), 상태 Badge(§3.6), 버튼 [강제 일시정지][제거][대시보드 상세]
- **표시 데이터**: capability(제공 모델·부담 수준·최대 컨텍스트·예상 속도), 기여 정책(허용
  역할/채널·일일 한도·동시 한도·최대 처리 시간), 현재 상태·last_seen, 누적 기여량
- **사용자 액션**: 강제 pause, 제거(SCR-506), 대시보드 열기
- **호출 API**: 내부 `API-REST-PROVIDER-GET`
- **이동/상태**: 표시 + 액션. `Provider`/`ProviderCapability`/`ProviderContributionPolicy` 조회
- **예외 메시지**: offline 시 일부 데이터 캐시 표시 주석
- **관련 요구사항**: `REQ-515`(provider 상세), `REQ-515`

### SCR-506 — Provider 제거 확인(`/provider-remove`)
- **화면 ID**: SCR-506
- **화면명**: 프로바이더 제거 확인
- **진입 조건**: `/provider-remove` 또는 SCR-505 [제거]
- **표시 대상**: 관리자
- **주요 UI 요소**: Embed(빨강), 경고 문구, 버튼 [제거 확정(danger)][취소]
- **표시 데이터**: "이 프로바이더를 풀에서 제거하면 진행 중 요청이 종료되고 토큰이 폐기됩니다.
  계속하시겠습니까?"
- **사용자 액션**: 제거 확정 / 취소
- **호출 API**: `API-CMD-PROVIDER-REMOVE`
- **이동/상태**: `→ removed`(`DM-S-ProviderState`), 세션·토큰 정리, audit_log
- **예외 메시지**: 이미 제거됨 `ERR-PROVIDER-STATE-CONFLICT`
- **관련 요구사항**: `REQ-515`(/provider-remove), `REQ-515`, `REQ-515`

### SCR-507 — Pool 상태(헬스 요약)
- **화면 ID**: SCR-507
- **화면명**: Provider Pool 헬스 대시보드(Discord)
- **진입 조건**: `/providers` 헬스 탭 또는 SCR-501 [Pool 상태]
- **표시 대상**: 관리자
- **주요 UI 요소**: Embed(파랑), 상태별 카운트 필드, 프로바이더 리스트(Badge §3.6), [웹 대시보드]
- **표시 데이터**: 온라인/바쁨/오프라인/일시정지/한도 도달/비정상 수, 처리 중 요청 수, 대기 큐 길이
- **사용자 액션**: provider 선택 → SCR-505, 대시보드 열기 → SCR-803
- **호출 API**: 내부 `API-REST-ADMIN-POOL`
- **이동/상태**: 표시 전용. `ProviderHealth` 집계
- **예외 메시지**: 프로바이더 0명 `ERR-NO-PROVIDER`→SCR-901 안내 병기
- **관련 요구사항**: `REQ-515`(pool 헬스 요약)

### SCR-508 — 서버 사용량 요약
- **화면 ID**: SCR-508
- **화면명**: 서버 사용량·기여량 요약(Discord)
- **진입 조건**: SCR-501 [사용량 요약]
- **표시 대상**: 관리자
- **주요 UI 요소**: Embed(파랑), 상위 요청자/상위 기여 프로바이더 필드, 공정성 지표
- **표시 데이터**: 기간 총 요청 수, 수준별 분포, provider별 처리량, 분배 균형(공정성)
- **사용자 액션**: 대시보드에서 자세히(SCR-806)
- **호출 API**: 내부 `API-REST-USAGE-GUILD`
- **이동/상태**: 표시 전용. `DM-E-UsageLog`(요청자)/`DM-E-ContributionLog`(기여) 집계
- **예외 메시지**: 데이터 없음 안내
- **관련 요구사항**: `REQ-605`(공정성 지표), `REQ-604`(공정성 리포트)

### SCR-509 — 라우팅 정책
- **화면 ID**: SCR-509
- **화면명**: 라우팅/공정성 정책 설정
- **진입 조건**: SCR-501 → 라우팅 섹션
- **표시 대상**: 관리자
- **주요 UI 요소**: 라우팅 모드 Select(personal/shared, ADR), 공정성 가중치 토글, 승인 방식
  Select(자동/수동), 기본 요청 제한 Modal
- **표시 데이터(예시 원칙)**: "가벼운 요청은 light 프로바이더 우선, 무거운 요청만 heavy 로, 특정
  프로바이더 쏠림 방지" + 현재 가중치
- **사용자 액션**: 모드·가중치·승인 방식 저장
- **호출 API**: `API-REST-GUILD-UPDATE`, 내부 `API-REST-GUILD-UPDATE`
- **이동/상태**: `GuildPolicy` 갱신. shared 모드 전환 시 호스트 미등록 경고(로드맵 231)
- **예외 메시지**: shared 인데 호스트 없음 `ERR-HOST-OFFLINE` 경고
- **관련 요구사항**: `REQ-510`(가중치 설정), `REQ-502`(승인 방식), `REQ-510`

### SCR-510 — 프라이버시 정책
- **화면 ID**: SCR-510
- **화면명**: 프라이버시 표시 모드 설정(A/B/C)
- **진입 조건**: SCR-501 → 프라이버시 섹션
- **표시 대상**: 관리자
- **주요 UI 요소**: 프라이버시 모드 Select(§3.3: A 익명/B 부분공개/C 관리자만), 고지 노출 빈도
  Select(최초/주기/매 응답), 미리보기 Embed
- **표시 데이터**: 각 모드 설명 + 현재 모드 + 일반 유저에게 보일 예시 문구(§4.5)
- **사용자 액션**: 모드·빈도 저장
- **호출 API**: `API-REST-GUILD-PRIVACY-SET`
- **이동/상태**: `GuildPolicy.privacy_mode` 갱신, audit_log
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-514`(모드 설정), `REQ-514`(C 기본 추천), `REQ-514`(빈도)

---

## 6. 프로바이더 화면

> 프로바이더 본인만 조작(소유권 체크, 로드맵 577). 토큰 화면은 ephemeral + 코드블록 1회.

### SCR-601 — 참여 시작(`/provider-join`)
- **화면 ID**: SCR-601
- **화면명**: Provider Pool 참여 시작
- **진입 조건**: 멤버가 `/provider-join` 실행
- **표시 대상**: 신청자(ephemeral)
- **주요 UI 요소**: Embed(파랑), 동의 고지 Notice(프롬프트가 내 PC로 전송됨), 버튼 [동의하고 신청]
- **표시 데이터(실제 문구)**: "프로바이더로 참여하면 다른 멤버의 질문이 내 PC의 로컬 LLM 으로
  전송되어 처리됩니다. 나는 감당 가능한 범위(모델·한도·시간·역할·채널)만 설정해 기여하며,
  언제든 일시정지/이탈할 수 있습니다."
- **사용자 액션**: 동의하고 신청 → pending 생성
- **호출 API**: `API-CMD-PROVIDER-JOIN`(Discord Command)
- **이동/상태**: `unregistered → pending`(`ProviderRegistered`), `FLOW-03.1`. 자동 승인이면
  바로 SCR-602
- **예외 메시지**: 이미 프로바이더 `ERR-ALREADY-PROVIDER`, removed 후 재등록 분기
- **관련 요구사항**: `REQ-505`(/provider-join), `REQ-505`(동의 고지), `REQ-505`

### SCR-602 — 토큰 발급
- **화면 ID**: SCR-602
- **화면명**: Agent 페어링 토큰 발급
- **진입 조건**: 승인 완료(관리자 승인 또는 자동 승인)
- **표시 대상**: 프로바이더(DM 우선, 실패 시 ephemeral fallback)
- **주요 UI 요소**: Embed(초록), 토큰 코드블록(예 `ABC-123-XYZ`), 만료 안내, 버튼 [토큰 재발급]
- **표시 데이터(실제 문구)**: "아래 일회용 토큰으로 PC에서 Agent 를 실행하세요. 토큰은 N분 후
  만료되며 1회 연결에만 사용됩니다." + 실행 예: `discord-assistant-agent --token ABC-123-XYZ`
- **사용자 액션**: 토큰 복사, Agent 실행, 재발급
- **호출 API**: 내부 `API-REST-PROVIDER-APPROVE`(평문 1회, 해시 저장)
- **이동/상태**: `approved` 유지, 토큰 owner 바인딩(provider_id, guild_id). SCR-603 대기
- **예외 메시지**: DM 차단 시 ephemeral 안내, 재발급 시 이전 토큰 폐기
- **관련 요구사항**: `REQ-506`(토큰 발급), `REQ-506`(실행 안내), `REQ-506`(재발급)

### SCR-603 — Agent 연결 대기
- **화면 ID**: SCR-603
- **화면명**: Agent 연결 대기
- **진입 조건**: 토큰 발급 후 Agent 미연결
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(노랑), Loading(SCR-310), 상태 Badge `🔵 승인됨(연결 대기)`
- **표시 데이터**: "PC에서 Agent 를 실행해 연결을 기다리는 중입니다…"
- **사용자 액션**: 대기, 재발급
- **호출 API**: 내부 `API-WS-AUTH` 대기(ADR `auth` 프레임)
- **이동/상태**: 연결·인증 성공 시 SCR-604. `ProviderAgentConnected` 이벤트 대기
- **예외 메시지**: 토큰 만료 `ERR-TOKEN-EXPIRED`, 인증 실패 `ERR-AUTH-FAILED`
- **관련 요구사항**: `REQ-508`(세션 생성), `REQ-701`(토큰 검증)

### SCR-604 — 연결 성공
- **화면 ID**: SCR-604
- **화면명**: Agent 연결 성공
- **진입 조건**: Agent 인증 + capability 보고(`provider_hello`)
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(초록), 상태 Badge `🟢 온라인·대기`, 버튼 [제공 모델 설정][한도 설정][범위 설정]
- **표시 데이터**: 감지된 모델 목록, 기본 동시 한도, 현재 상태
- **사용자 액션**: 모델/한도/범위 설정으로 진입
- **호출 API**: `API-WS-PROVIDER-HELLO`(capability), 내부 `API-WS-AUTH-OK`
- **이동/상태**: `approved → online_idle`. SCR-605/607/606 으로 분기
- **예외 메시지**: capability 없음 시 보수적 기본값(standard) 안내
- **관련 요구사항**: `REQ-508`(capability 수신), `REQ-508`(상태 전이)

### SCR-605 — 제공 모델 설정(`/provider-models`)
- **화면 ID**: SCR-605
- **화면명**: 제공 모델 설정
- **진입 조건**: `/provider-models` 또는 SCR-604 [제공 모델 설정]
- **표시 대상**: 프로바이더
- **주요 UI 요소**: 모델 다중 Select(감지된 Ollama 모델), 각 모델 부담 수준 오버라이드 Select(§3.7)
- **표시 데이터(예시)**: "감지된 모델: llama3:8b(light), mistral:7b(standard), qwen32b(heavy)"
- **사용자 액션**: 제공할 모델 선택, 부담 수준 조정 저장
- **호출 API**: `API-CMD-PROVIDER-MODELS`
- **이동/상태**: `ProviderCapability` 갱신, 세션 즉시 반영(로드맵 429·579)
- **예외 메시지**: 모델 미감지 시 SCR-706 재시도 안내
- **관련 요구사항**: `REQ-507`(/provider-models), `REQ-509`(수준 오버라이드), `REQ-507`

### SCR-606 — 기여 범위 설정(`/provider-scope`)
- **화면 ID**: SCR-606
- **화면명**: 기여 범위 설정(역할·채널·요청 종류)
- **진입 조건**: `/provider-scope` 또는 SCR-604 [범위 설정]
- **표시 대상**: 프로바이더
- **주요 UI 요소**: 요청자 허용 범위 Select(전체/신뢰 이상/관리자만), 허용 채널 Select, 긴
  프롬프트 허용 토글, 범위 Modal(§3.4)
- **표시 데이터(예시)**: "qwen32b: 신뢰 멤버 이상, #ai-help 채널만 / 긴 코드 분석: 관리자만"
- **사용자 액션**: 허용 역할/채널/요청 종류 저장
- **호출 API**: `API-CMD-PROVIDER-SCOPE`
- **이동/상태**: `ProviderContributionPolicy` 갱신, 즉시 반영
- **예외 메시지**: 모순 정책 `ERR-INVALID-POLICY` 경고
- **관련 요구사항**: `REQ-507`(/provider-scope), `REQ-507`, `REQ-507`

### SCR-607 — 한도 설정(`/provider-limit`)
- **화면 ID**: SCR-607
- **화면명**: 한도 설정(일일·동시·최대 처리 시간·프롬프트 길이)
- **진입 조건**: `/provider-limit` 또는 SCR-604 [한도 설정]
- **표시 대상**: 프로바이더
- **주요 UI 요소**: 한도 Modal(§3.4: 일일 한도, 동시 한도, 요청당 최대 초, 프롬프트 길이 상한)
- **표시 데이터(예시)**: "하루 50회, 동시 1, 요청당 60초, 긴 문서 거절"
- **사용자 액션**: 수치 입력 저장
- **호출 API**: `API-CMD-PROVIDER-LIMIT`
- **이동/상태**: `ProviderContributionPolicy` 갱신. 한도 도달 시 라우팅에서 `limited`
- **예외 메시지**: 범위 밖 값 `ERR-INVALID-POLICY`
- **관련 요구사항**: `REQ-507`(/provider-limit), `REQ-507`~`REQ-507`, `REQ-507`

### SCR-608 — 상태 조회(`/provider-status`)
- **화면 ID**: SCR-608
- **화면명**: 내 프로바이더 상태
- **진입 조건**: 프로바이더가 `/provider-status` 실행
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(파랑), 상태 Badge(§3.6), 버튼 [일시정지][재개][풀에서 나가기]
- **표시 데이터(예시)**: "status=online, load=idle, battery=charging, models=[llama3:8b,mistral:7b],
  max_concurrency=1, remaining_daily_requests=42" + 누적 기여량
- **사용자 액션**: pause/resume/leave 진입
- **호출 API**: `API-CMD-PROVIDER-STATUS`, 내부 `API-INT-HEALTH-SESSION`
- **이동/상태**: 표시 + 액션. `ProviderSession`/`ProviderHealth` 스냅샷
- **예외 메시지**: Agent 미연결 시 `🟡 연결 대기`/오프라인 안내(로드맵 578)
- **관련 요구사항**: `REQ-515`(/provider-status), `REQ-513`(기여 요약), `REQ-515`

### SCR-609 — pause(`/provider-pause`)
- **화면 ID**: SCR-609
- **화면명**: 일시정지 확인/완료
- **진입 조건**: `/provider-pause` 또는 SCR-608 [일시정지]
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(노랑), 상태 Badge `⏸️ 일시정지`, 버튼 [재개]
- **표시 데이터**: "요청 수신을 일시정지했습니다. 진행 중 요청은 보호되며, 신규 요청은 받지
  않습니다."
- **사용자 액션**: 재개로 전환
- **호출 API**: `API-CMD-PROVIDER-PAUSE`
- **이동/상태**: `online_idle/online_busy → paused`(`ProviderPaused`), 즉시 라우팅 후보 제외
- **예외 메시지**: 이미 일시정지 안내
- **관련 요구사항**: `REQ-515`(/provider-pause), `REQ-603`(즉시 제외), `REQ-603`

### SCR-610 — resume(`/provider-resume`)
- **화면 ID**: SCR-610
- **화면명**: 재개 완료
- **진입 조건**: `/provider-resume` 또는 SCR-609 [재개]
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(초록), 상태 Badge `🟢 온라인·대기`
- **표시 데이터**: "요청 수신을 재개했습니다. 다시 커뮤니티 요청을 처리합니다."
- **사용자 액션**: 상태 확인(SCR-608)
- **호출 API**: `API-CMD-PROVIDER-RESUME`
- **이동/상태**: `paused → online_idle`(`ProviderResumed`)
- **예외 메시지**: 오프라인 상태에서 재개 시 "Agent 연결 후 자동 온라인됩니다" 안내
- **관련 요구사항**: `REQ-603`(/provider-resume), `REQ-603`

### SCR-611 — leave 확인(`/provider-leave`)
- **화면 ID**: SCR-611
- **화면명**: 풀 이탈 확인
- **진입 조건**: `/provider-leave` 또는 SCR-608 [풀에서 나가기]
- **표시 대상**: 프로바이더(ephemeral)
- **주요 UI 요소**: Embed(빨강), 경고, 버튼 [나가기 확정(danger)][취소]
- **표시 데이터**: "풀에서 나가면 토큰이 폐기되고 진행 중 요청이 종료됩니다. 언제든
  `/provider-join` 으로 다시 참여할 수 있습니다."
- **사용자 액션**: 이탈 확정 / 취소
- **호출 API**: `API-CMD-PROVIDER-LEAVE`
- **이동/상태**: `→ removed`/등록 해제, 세션·토큰 정리, `ProviderAgentDisconnected`
- **예외 메시지**: 미등록 상태 안내
- **관련 요구사항**: `REQ-603`(/provider-leave), `REQ-603`

---

## 7. Provider Agent 화면

> Discord 가 아닌 프로바이더 PC 터미널의 CLI 콘솔 텍스트 출력. inbound 포트 미개방, outbound
> 연결만(ADR 보안 불변식). 토큰은 로그에 노출하지 않는다(로드맵 262).

### SCR-701 — 초기 실행
- **화면 ID**: SCR-701
- **화면명**: Agent 초기 실행 화면
- **진입 조건**: 사용자가 `discord-assistant-agent` 실행
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 배너, 버전/플랫폼 출력, 인자 안내(`--token` `--relay-url` `--ollama-url`)
- **표시 데이터**: "discord-assistant-agent vX.Y · platform=darwin · relay=wss://… · ollama=http://localhost:11434"
- **사용자 액션**: 토큰 인자 제공 또는 SCR-702 입력
- **호출 API**: 없음(기동 단계)
- **이동/상태**: 토큰 있으면 SCR-703, 없으면 SCR-702
- **예외 메시지**: 인자 오류 시 usage 출력
- **관련 요구사항**: `REQ-508`(CLI 엔트리), `REQ-508`(버전/플랫폼 보고)

### SCR-702 — 토큰 입력
- **화면 ID**: SCR-702
- **화면명**: 토큰 입력 프롬프트
- **진입 조건**: `--token` 미제공 + AGENT_TOKEN env 없음
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 입력 프롬프트(마스킹 권장)
- **표시 데이터**: "페어링 토큰을 입력하세요(디스코드 DM 에서 발급): "
- **사용자 액션**: 토큰 입력
- **호출 API**: 없음(입력 단계)
- **이동/상태**: SCR-703(연결 중)
- **예외 메시지**: 빈 입력 재요청
- **관련 요구사항**: `REQ-508`(argparse), `REQ-508`(env fallback)

### SCR-703 — 연결 중
- **화면 ID**: SCR-703
- **화면명**: 릴레이 연결 중
- **진입 조건**: 토큰 확보 후 relay 로 outbound wss 연결 시도
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 스피너/점진 로그
- **표시 데이터**: "릴레이에 연결 중… (wss)", "auth 프레임 전송"
- **사용자 액션**: 대기
- **호출 API**: `API-WS-AUTH`(ADR `auth` 프레임)
- **이동/상태**: 성공 SCR-704, 실패 SCR-710 또는 인증오류 종료
- **예외 메시지**: `AuthErr` → "토큰이 만료되었거나 잘못되었습니다(ERR-AUTH-FAILED)"
- **관련 요구사항**: `REQ-508`(연결), `REQ-508`(auth 송신)

### SCR-704 — 연결 성공
- **화면 ID**: SCR-704
- **화면명**: 연결 성공/대기
- **진입 조건**: `AuthOk` 수신 + capability 보고 완료
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 성공 표시, 상태 라인
- **표시 데이터**: "연결됨 · 온라인 · 모델=[llama3:8b,mistral:7b] · 동시한도=1 · 일일잔여=50.
  커뮤니티 요청 대기 중…"
- **사용자 액션**: 상시 대기(Ctrl+C 종료)
- **호출 API**: `API-WS-PROVIDER-HELLO`(capability)
- **이동/상태**: idle 대기. 요청 수신 시 SCR-708
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-508`(AuthOk 처리), `REQ-508`

### SCR-705 — Ollama 감지 실패
- **화면 ID**: SCR-705
- **화면명**: 로컬 Ollama 감지 실패
- **진입 조건**: 기동 시 `localhost:11434` 점검 실패
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 경고 블록 + 가이드
- **표시 데이터**: "로컬 Ollama 를 찾을 수 없습니다(http://localhost:11434). Ollama 를 설치/실행한
  뒤 다시 시도하세요. 연결은 유지하되 요청은 거절됩니다(ERR-OLLAMA-FAILED)."
- **사용자 액션**: Ollama 실행 후 재시도
- **호출 API**: 로컬 Ollama health(외부 도구)
- **이동/상태**: Ollama 복구 시 SCR-706/704
- **예외 메시지**: `ERR-OLLAMA-FAILED`
- **관련 요구사항**: `REQ-606`(사전 점검), `REQ-603`(메모리/Ollama 거절)

### SCR-706 — 모델 목록 감지
- **화면 ID**: SCR-706
- **화면명**: 제공 가능 모델 감지
- **진입 조건**: Ollama 정상, 모델 목록 조회
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 모델 표(이름·추정 부담 수준)
- **표시 데이터**: "감지된 모델: llama3:8b → light, mistral:7b → standard, qwen32b → heavy. 디스코드
  `/provider-models` 에서 제공 모델/수준을 조정할 수 있습니다."
- **사용자 액션**: 디스코드에서 SCR-605 로 조정
- **호출 API**: `API-WS-PROVIDER-HELLO`(모델 보고)
- **이동/상태**: 보고 후 SCR-704
- **예외 메시지**: 모델 0개 시 안내
- **관련 요구사항**: `REQ-509`(휴리스틱), `REQ-507`(런타임 갱신)

### SCR-707 — 현재 상태
- **화면 ID**: SCR-707
- **화면명**: 상태 라인(주기 갱신)
- **진입 조건**: 연결 유지 중 주기적 갱신/heartbeat
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 단일 상태 라인(in-place 갱신)
- **표시 데이터**: "online · load=idle · battery=charging · 처리=12건 · 대기=0 · last_ping=2s"
- **사용자 액션**: 모니터링
- **호출 API**: `API-WS-PING`/`API-WS-PONG`, `API-WS-PROVIDER-STATUS`
- **이동/상태**: 상태 보고를 서버로 송신(상태머신 반영)
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-508`(heartbeat), `REQ-508`(provider_status)

### SCR-708 — 요청 처리 중
- **화면 ID**: SCR-708
- **화면명**: 추론 요청 처리 중
- **진입 조건**: `infer` 프레임 수신
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 처리 로그(요청 메타만, 프롬프트 원문 미표시)
- **표시 데이터**: "요청 수신 req=… model=llama3:8b · Ollama 호출 중… · 완료(842ms)" — 프라이버시상
  프롬프트 본문은 출력하지 않는다(로드맵 280)
- **사용자 액션**: 모니터링
- **호출 API**: `API-WS-INFER` 수신 → 로컬 Ollama → `API-WS-RESULT` 회신
- **이동/상태**: `online_idle → online_busy → online_idle`
- **예외 메시지**: Ollama 오류 `ERR-OLLAMA-FAILED` → `error` 프레임 회신
- **관련 요구사항**: `REQ-510`(infer→Ollama), `REQ-510`(result 회신)

### SCR-709 — 일시정지(콘솔 반영)
- **화면 ID**: SCR-709
- **화면명**: 일시정지/보호 상태 표시
- **진입 조건**: 디스코드 `/provider-pause` 또는 자동 보호(배터리/부하) 트리거
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 경고 라인
- **표시 데이터**: "일시정지됨(사유: 사용자 요청 / 배터리 모드 / CPU 임계 초과). 신규 요청 수신
  중단. 진행 중 요청은 보호됩니다."
- **사용자 액션**: 디스코드에서 재개 또는 보호 해제 대기
- **호출 API**: `API-WS-PROVIDER-STATUS`(보호 신호)
- **이동/상태**: `→ paused`/`limited`, 라우팅 후보 제외
- **예외 메시지**: 없음
- **관련 요구사항**: `REQ-603`(배터리 자동 pause), `REQ-603`(CPU/GPU), `REQ-603`(보호 신호)

### SCR-710 — 연결 끊김·재연결
- **화면 ID**: SCR-710
- **화면명**: 연결 끊김 / 재연결
- **진입 조건**: WS 연결 끊김(네트워크/절전/릴레이 다운)
- **표시 대상**: 프로바이더(콘솔)
- **주요 UI 요소**: 재연결 백오프 로그
- **표시 데이터**: "연결이 끊겼습니다. 지수 백오프로 재연결 시도 중… (5s/10s/20s)"
- **사용자 액션**: 대기(Ctrl+C 종료)
- **호출 API**: `API-WS-AUTH` 재연결
- **이동/상태**: 서버측 `offline`(heartbeat 만료), 복구 시 `online_idle`
- **예외 메시지**: 인증 실패 시 종료(`ERR-AUTH-FAILED`)
- **관련 요구사항**: `REQ-706`(지수 백오프), `REQ-508`(heartbeat 만료→offline)

---

## 8. 웹 대시보드 화면

> 관리자 전용 REST 웹 화면(`API-REST-ADMIN-*`). 읽기 중심, 민감 프롬프트 원문 미표시(로드맵 612).

### SCR-801 — 로그인·서버 선택
- **화면 ID**: SCR-801
- **화면명**: 로그인 및 서버(Guild) 선택
- **진입 조건**: 대시보드 접속
- **표시 대상**: 관리자
- **주요 UI 요소**: Discord OAuth 로그인 버튼, 관리 권한 보유 서버 목록(카드)
- **표시 데이터**: 로그인 사용자, 관리 가능 서버 목록
- **사용자 액션**: 로그인 → 서버 선택
- **호출 API**: `API-REST-ADMIN-GUILDS`, `API-REST-ADMIN-GUILDS`
- **이동/상태**: 서버 선택 → SCR-802
- **예외 메시지**: 권한 없음 안내
- **관련 요구사항**: `REQ-708`(대시보드 연동)

### SCR-802 — 서버 개요
- **화면 ID**: SCR-802
- **화면명**: 서버 개요
- **진입 조건**: 서버 선택 후
- **표시 대상**: 관리자
- **주요 UI 요소**: 요약 카드(프로바이더 수·온라인 수·오늘 요청 수·공정성 지표), 사이드 내비
- **표시 데이터**: Pool 규모, 활동 요약, 정책 요약
- **사용자 액션**: 각 섹션(Pool/로그/통계/정책/장애)으로 이동
- **호출 API**: `API-REST-ADMIN-DASHBOARD`
- **이동/상태**: SCR-803~808 허브
- **예외 메시지**: 데이터 없음
- **관련 요구사항**: `REQ-515`, `REQ-605`

### SCR-803 — Pool 대시보드
- **화면 ID**: SCR-803
- **화면명**: Provider Pool 대시보드
- **진입 조건**: 개요 → Pool
- **표시 대상**: 관리자
- **주요 UI 요소**: 프로바이더 테이블(상태 Badge §3.6·모델·부담 수준·처리량·last_seen), 상태 필터
- **표시 데이터**: 전 프로바이더 상태/capability/기여량, 대기 큐 길이
- **사용자 액션**: provider 행 클릭 → SCR-804, 강제 pause/제거
- **호출 API**: `API-REST-ADMIN-POOL`, `API-REST-PROVIDER-PAUSE`
- **이동/상태**: SCR-804 상세
- **예외 메시지**: 프로바이더 0명 안내
- **관련 요구사항**: `REQ-515`(헬스), `REQ-515`(상세)

### SCR-804 — Provider 상세(웹)
- **화면 ID**: SCR-804
- **화면명**: 프로바이더 상세(웹)
- **진입 조건**: Pool 테이블 행 선택
- **표시 대상**: 관리자
- **주요 UI 요소**: capability·정책·상태 패널, 기여량 차트, 상태 전이 이력
- **표시 데이터**: 제공 모델·부담 수준·한도·허용 범위·세션 이력·실패율
- **사용자 액션**: 강제 pause/제거, 정책 보기
- **호출 API**: `API-REST-ADMIN-PROVIDER`
- **이동/상태**: 액션 시 상태머신 반영
- **예외 메시지**: offline 캐시 표시 주석
- **관련 요구사항**: `REQ-515`, `REQ-603`(처리량 집계)

### SCR-805 — 요청 로그
- **화면 ID**: SCR-805
- **화면명**: 요청 로그
- **진입 조건**: 개요 → 로그
- **표시 대상**: 관리자
- **주요 UI 요소**: 요청 테이블(시각·요청자·채널·필요 수준·선택 provider·상태 Badge §3.5·결과)
- **표시 데이터**: 요청 메타·상태·실패 사유(프롬프트 원문 제외)
- **사용자 액션**: 필터, 상세(상태 전이 이력), 실패 사유 확인
- **호출 API**: `API-REST-LOG-REQUESTS`
- **이동/상태**: 표시 전용. `AiRequest` 조회
- **예외 메시지**: 데이터 없음
- **관련 요구사항**: `REQ-510`(상태 전이 로깅), `REQ-702`(민감내용 미포함)

### SCR-806 — 사용량·기여량 통계
- **화면 ID**: SCR-806
- **화면명**: 사용량·기여량·공정성 통계
- **진입 조건**: 개요 → 통계
- **표시 대상**: 관리자
- **주요 UI 요소**: 차트(요청자별 사용량·provider별 기여량·분배 균형), 기간 선택
- **표시 데이터**: 유저 사용량, provider 기여량, 공정성 지표(쏠림 정도)
- **사용자 액션**: 기간 변경, 내보내기
- **호출 API**: `API-REST-USAGE-GUILD`
- **이동/상태**: 표시 전용. `DM-E-UsageLog`(요청자)/`DM-E-ContributionLog`(기여) 집계
- **예외 메시지**: 데이터 없음
- **관련 요구사항**: `REQ-605`(공정성 지표), `REQ-513`(기여량), `REQ-604`

### SCR-807 — 정책 설정(웹)
- **화면 ID**: SCR-807
- **화면명**: 정책 설정(웹)
- **진입 조건**: 개요 → 정책
- **표시 대상**: 관리자
- **주요 UI 요소**: 폼(허용 채널·역할별 수준/한도·승인 방식·라우팅 모드·공정성 가중치·프라이버시 모드)
- **표시 데이터**: 현재 정책 전체(Discord SCR-502~510 과 동일 SSOT)
- **사용자 액션**: 정책 일괄 편집·저장
- **호출 API**: `API-REST-ADMIN-DASHBOARD`(GET/PUT)
- **이동/상태**: `GuildPolicy` 갱신, audit_log. Discord 패널과 동기화
- **예외 메시지**: 검증 실패 인라인 표시
- **관련 요구사항**: `REQ-502`(설정), `REQ-502`(일괄 보기/내보내기)

### SCR-808 — 장애·실패 로그
- **화면 ID**: SCR-808
- **화면명**: 장애·실패 로그
- **진입 조건**: 개요 → 장애
- **표시 대상**: 관리자
- **주요 UI 요소**: 실패 이벤트 테이블(timeout/끊김/Ollama 오류/fallback 실패), `ERR-` 코드, 영향 provider
- **표시 데이터**: 실패 사유·빈도·영향 프로바이더·자동 비활성화(unhealthy) 이력
- **사용자 액션**: 필터, provider 상세로 이동
- **호출 API**: `API-REST-LOG-FAILURES`
- **이동/상태**: 표시 전용. `RequestFailed`/`ProviderMarkedUnhealthy` 이벤트 집계
- **예외 메시지**: 데이터 없음
- **관련 요구사항**: `REQ-603`(반복 실패→unhealthy), `REQ-504`(temporarily unavailable)

---

## 9. 오류 화면·메시지

> 모두 표준 오류 Embed(SCR-309, 빨강/노랑) 기반. 본문에 프롬프트 원문 미포함. `ERR-` 코드는
> footer(관리자/디버그)에만.

### SCR-901 — Provider 없음
- **화면 ID**: SCR-901 · **화면명**: 사용 가능한 프로바이더 없음
- **진입 조건**: 풀에 승인·연결된 프로바이더가 한 명도 없음
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(노랑), 버튼 [나중에 다시]
- **표시 데이터(실제 문구)**: "현재 이 서버에는 연결된 커뮤니티 로컬 AI 프로바이더가 없습니다.
  프로바이더가 참여하면 다시 이용할 수 있습니다."
- **사용자 액션**: 대기 · **호출 API**: 내부 `API-INT-SELECT`(후보 0)
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-NO-PROVIDER`
- **관련 요구사항**: `REQ-510`(후보 0 신호), `REQ-510`

### SCR-902 — 모두 오프라인
- **화면 ID**: SCR-902 · **화면명**: 프로바이더 전원 오프라인/혼잡
- **진입 조건**: 프로바이더는 있으나 전부 offline/busy/limited/paused 로 후보 0
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(노랑), 버튼 [다시 시도][더 가벼운 요청으로]
- **표시 데이터(실제 문구)**: "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. 잠시
  후 다시 시도하거나 더 가벼운 요청으로 시도해 주세요."
- **사용자 액션**: 재시도/다운그레이드 · **호출 API**: 내부 `API-INT-SELECT`
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-ALL-OFFLINE` / `ERR-POOL-BUSY`
- **관련 요구사항**: `REQ-510`, `REQ-506`(혼잡 안내)

### SCR-903 — 권한 부족
- **화면 ID**: SCR-903 · **화면명**: 권한 부족(역할/명령)
- **진입 조건**: 요청자 역할이 명령/수준을 사용할 권한 없음
- **표시 대상**: 요청자/명령 사용자 · **주요 UI 요소**: Embed(노랑)
- **표시 데이터(실제 문구)**: "이 요청은 heavy 수준이 필요하지만 현재 역할로는 사용할 수
  없습니다. 관리자에게 권한 요청 또는 더 짧은 질문으로 시도하세요."
- **사용자 액션**: 관리자 문의/다운그레이드 · **호출 API**: 내부 `API-INT-CREATE-REQUEST`
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-PERMISSION-DENIED`
- **관련 요구사항**: `REQ-510`, `REQ-504`(관리자 전용), `REQ-504`

### SCR-904 — 채널 사용 불가
- **화면 ID**: SCR-904 · **화면명**: 허용되지 않은 채널
- **진입 조건**: 현재 채널이 허용 목록에 없음/금지됨
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(노랑)
- **표시 데이터(실제 문구)**: "이 채널에서는 커뮤니티 로컬 AI 를 사용할 수 없습니다. 허용된
  채널(예: #ai-help, #coding-help)에서 다시 시도하세요."
- **사용자 액션**: 허용 채널로 이동 · **호출 API**: 내부 `API-INT-CREATE-REQUEST`
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-CHANNEL-NOT-ALLOWED`
- **관련 요구사항**: `REQ-503`(채널 판정), `REQ-503`

### SCR-905 — 모델 수준 미지원
- **화면 ID**: SCR-905 · **화면명**: 요청 수준 미지원
- **진입 조건**: 필요 부담 수준을 감당할 프로바이더가 풀에 없음(예: heavy 미보유)
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(노랑), 버튼 [더 가벼운 요청으로]
- **표시 데이터(실제 문구)**: "이 요청은 heavy 수준이 필요하지만 현재 커뮤니티 풀에서 감당
  가능한 프로바이더가 없습니다. 더 가벼운 요청으로 시도하거나 관리자에게 문의하세요."
- **사용자 액션**: 다운그레이드 · **호출 API**: 내부 `API-INT-SELECT`
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-LEVEL-UNSUPPORTED`
- **관련 요구사항**: `REQ-510`(다운그레이드 제안), `REQ-510`

### SCR-906 — 한도 초과
- **화면 ID**: SCR-906 · **화면명**: 일일 요청 한도 초과
- **진입 조건**: 요청자 역할 일일 한도 소진
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(노랑), [내 사용량 보기]
- **표시 데이터(실제 문구)**: "오늘 사용 가능한 요청 한도를 모두 사용했습니다(N/N). 한도는
  매일 초기화됩니다."
- **사용자 액션**: SCR-408 확인 · **호출 API**: 내부 `API-REST-USAGE-USER`
- **이동/상태**: `rejected` · **예외 메시지**: `ERR-QUOTA-EXCEEDED`
- **관련 요구사항**: `REQ-604`(일일 집계), `REQ-504`(역할 한도)

### SCR-907 — Agent 끊김
- **화면 ID**: SCR-907 · **화면명**: 처리 중 Agent 연결 끊김
- **진입 조건**: 처리 중 선택 프로바이더 Agent 연결 끊김
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(빨강), 상태 Badge `🔁 재시도 중`/`❌ 실패`
- **표시 데이터(실제 문구)**: "처리 중 프로바이더 연결이 끊겨 다른 프로바이더로 다시 시도합니다…"
  (fallback 진입) / 실패 시 SCR-910
- **사용자 액션**: 자동 fallback 대기 · **호출 API**: 내부 `API-INT-SELECT`(fallback)
- **이동/상태**: `running → failed → fallback_running`, 끊긴 provider `→ offline`
- **예외 메시지**: `ERR-AGENT-DISCONNECTED`
- **관련 요구사항**: `REQ-505`(끊김 복구), `REQ-512`(fallback)

### SCR-908 — Ollama 실패
- **화면 ID**: SCR-908 · **화면명**: 프로바이더 Ollama 처리 실패
- **진입 조건**: Agent 가 `error` 프레임(`OLLAMA_ERROR`/모델 없음/메모리 부족) 반환
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(빨강), [다시 시도]
- **표시 데이터(실제 문구)**: "프로바이더 측 처리에 실패했습니다. 다른 프로바이더로 다시
  시도하거나 잠시 후 재시도해 주세요."
- **사용자 액션**: 재시도(자동 fallback) · **호출 API**: `API-WS-ERROR` 수신 → `API-INT-SELECT`
- **이동/상태**: `failed → fallback_running`, provider `→ temporarily unavailable`
- **예외 메시지**: `ERR-OLLAMA-FAILED`
- **관련 요구사항**: `REQ-704`(error 프레임), `REQ-504`

### SCR-909 — timeout
- **화면 ID**: SCR-909 · **화면명**: 요청 시간 초과
- **진입 조건**: 부담 수준/정책 기반 타임아웃 초과
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(빨강), [다시 시도][더 가벼운 요청으로]
- **표시 데이터(실제 문구)**: "응답이 제한 시간 안에 오지 않았습니다. 더 가벼운 요청으로
  시도하거나 잠시 후 다시 시도해 주세요."
- **사용자 액션**: 재시도/다운그레이드 · **호출 API**: 내부 `API-INT-SELECT`(`cancel` 프레임 송신)
- **이동/상태**: `running → failed`(→ fallback 1회), provider 상태 반영
- **예외 메시지**: `ERR-TIMEOUT`
- **관련 요구사항**: `REQ-704`(타임아웃), `REQ-704`

### SCR-910 — fallback 실패
- **화면 ID**: SCR-910 · **화면명**: 재시도(fallback) 최종 실패
- **진입 조건**: fallback 프로바이더에서도 실패/후보 없음
- **표시 대상**: 요청자 · **주요 UI 요소**: Embed(빨강), [나중에 다시]
- **표시 데이터(실제 문구)**: "현재 이 요청을 처리할 수 있는 커뮤니티 로컬 AI 가 없습니다. 잠시
  후 다시 시도하거나 더 가벼운 요청으로 시도해 주세요."
- **사용자 액션**: 대기/다운그레이드 · **호출 API**: 내부 `API-INT-SELECT`
- **이동/상태**: `fallback_running → failed`(최종) · **예외 메시지**: `ERR-FALLBACK-FAILED`
- **관련 요구사항**: `REQ-503`(fallback 실패 안내), `REQ-704`

### SCR-911 — 민감정보 주의
- **화면 ID**: SCR-911 · **화면명**: 민감정보 입력 주의(프라이버시 강조)
- **진입 조건**: 입력에 민감정보 패턴(API 키/비밀번호 형태) 의심, 또는 최초/주기 프라이버시 고지 시점
- **표시 대상**: 요청자/서버 전체(채널 안내) · **주요 UI 요소**: Privacy Notice(SCR-308, 보라), [프라이버시 자세히]
- **표시 데이터(실제 문구, 브리프 §10)**: "이 서버는 커뮤니티 로컬 AI Provider Pool 을
  사용합니다. 질문 내용은 요청을 처리하는 커뮤니티 프로바이더의 PC 로 전송될 수 있습니다.
  비밀번호·API 키·개인정보·비공개 문서 등 민감 정보는 입력하지 마세요."
- **사용자 액션**: 확인 후 진행/취소 · **호출 API**: 내부 `API-CMD-PRIVACY`
- **이동/상태**: 표시 전용. 노출 빈도는 모드 설정(SCR-510) 준수
- **예외 메시지**: `ERR-SENSITIVE-WARNING`(경고, 차단 아님)
- **관련 요구사항**: `REQ-605`(서버 고지), `REQ-702`(로그 최소화), `REQ-605`

---

## 10. 화면별 상세 정의(형식 정의)

> §4~§9 의 모든 화면은 아래 11개 항목 형식으로 정의되었다. 본 절은 그 **표준 형식**과 각 화면에
> 적용된 항목의 의미를 규정한다(개별 화면 상세는 §4~§9 본문 참조 — 중복 기재 금지).

| # | 항목 | 의미 | 예 |
|---|---|---|---|
| 10.1 | 화면 ID | `SCR-###` 고유 식별자 | `SCR-404` |
| 10.2 | 화면명 | 사람이 읽는 화면 이름 | "AI 답변 메시지" |
| 10.3 | 진입 조건 | 이 화면이 표시되는 트리거 | "`result` 프레임 수신" |
| 10.4 | 표시 대상 | 누구에게 보이는가(유저/관리자/프로바이더/콘솔/채널/ephemeral) | "요청자(채널)" |
| 10.5 | 주요 UI 요소 | Embed/Button/Select/Modal/Badge 등 구성요소 | "Embed + 버튼 2개" |
| 10.6 | 표시 데이터 | 화면이 보여주는 데이터·실제 문구 | "답변 텍스트 + 모델 수준" |
| 10.7 | 사용자 액션 | 사용자가 할 수 있는 동작 | "다시 시도, 후속 질문" |
| 10.8 | 호출 API | 트리거되는 API/메시지 타입(`API-`) | `API-WS-RESULT` |
| 10.9 | 이동/상태 변화 | 다음 화면·플로우·상태머신 전이(`FLOW-`/`DM-S-`) | "`completed`, FLOW-08 종료" |
| 10.10 | 예외 메시지 | 발생 가능 에러 코드(`ERR-`) | `ERR-TIMEOUT` |
| 10.11 | 관련 요구사항 ID | 추적 대상 요구사항(`REQ-`) | `REQ-514` |

### 10.A 화면 인덱스(전체 SCR 목록)

| SCR ID | 화면명 | 그룹 | 주요 명령/진입 |
|---|---|---|---|
| SCR-301~310 | 공통 컴포넌트(Embed/Button/Select/Modal/Badge/상태/수준/Privacy/Error/Loading) | 공통 | 재사용 |
| SCR-401 | /ask 입력 | 유저 | `/ask` |
| SCR-402 | 요청 접수 | 유저 | `/ask` 후 |
| SCR-403 | 처리 중 | 유저 | 라우팅·실행 |
| SCR-404 | AI 답변 | 유저 | `result` |
| SCR-405 | Pool 처리 안내(모드 A/B/C) | 유저 | 답변 footer |
| SCR-406 | 권한 부족(수준) | 유저 | 권한 분기 |
| SCR-407 | 모델 수준 조회 | 유저 | `/models` |
| SCR-408 | 내 사용량 | 유저 | `/my-usage` |
| SCR-409 | 프라이버시 안내 | 유저 | `/privacy` |
| SCR-410 | 도움말 | 유저(전체) | `/help` |
| SCR-411 | 쿨다운 안내 | 유저 | rate limit |
| SCR-501 | LLM 설정 홈 | 관리자 | `/llm-settings` |
| SCR-502 | 허용 채널 | 관리자 | `/llm-allow-channel`·`/llm-deny-channel` |
| SCR-503 | 역할별 수준 | 관리자 | `/llm-role-policy` |
| SCR-504 | Provider 승인 대기 | 관리자 | `/providers`·`/provider-approve` |
| SCR-505 | Provider 상세 | 관리자 | `/providers` 선택 |
| SCR-506 | Provider 제거 확인 | 관리자 | `/provider-remove` |
| SCR-507 | Pool 상태 | 관리자 | `/providers` 헬스 |
| SCR-508 | 서버 사용량 요약 | 관리자 | 설정 홈 |
| SCR-509 | 라우팅 정책 | 관리자 | 설정 홈 |
| SCR-510 | 프라이버시 정책 | 관리자 | 설정 홈 |
| SCR-601 | 참여 시작 | 프로바이더 | `/provider-join` |
| SCR-602 | 토큰 발급 | 프로바이더 | 승인 후 |
| SCR-603 | Agent 연결 대기 | 프로바이더 | 토큰 후 |
| SCR-604 | 연결 성공 | 프로바이더 | 인증·capability |
| SCR-605 | 제공 모델 설정 | 프로바이더 | `/provider-models` |
| SCR-606 | 기여 범위 설정 | 프로바이더 | `/provider-scope` |
| SCR-607 | 한도 설정 | 프로바이더 | `/provider-limit` |
| SCR-608 | 상태 조회 | 프로바이더 | `/provider-status` |
| SCR-609 | pause | 프로바이더 | `/provider-pause` |
| SCR-610 | resume | 프로바이더 | `/provider-resume` |
| SCR-611 | leave 확인 | 프로바이더 | `/provider-leave` |
| SCR-701 | 초기 실행 | Agent | 실행 |
| SCR-702 | 토큰 입력 | Agent | 토큰 미제공 |
| SCR-703 | 연결 중 | Agent | wss 연결 |
| SCR-704 | 연결 성공 | Agent | AuthOk |
| SCR-705 | Ollama 감지 실패 | Agent | 점검 실패 |
| SCR-706 | 모델 목록 감지 | Agent | 모델 조회 |
| SCR-707 | 현재 상태 | Agent | heartbeat |
| SCR-708 | 요청 처리 중 | Agent | `infer` |
| SCR-709 | 일시정지 | Agent | pause/보호 |
| SCR-710 | 연결 끊김·재연결 | Agent | 끊김 |
| SCR-801 | 로그인·서버 선택 | 대시보드 | 접속 |
| SCR-802 | 서버 개요 | 대시보드 | 서버 선택 |
| SCR-803 | Pool 대시보드 | 대시보드 | 개요→Pool |
| SCR-804 | Provider 상세(웹) | 대시보드 | 행 선택 |
| SCR-805 | 요청 로그 | 대시보드 | 개요→로그 |
| SCR-806 | 사용량·기여량 통계 | 대시보드 | 개요→통계 |
| SCR-807 | 정책 설정(웹) | 대시보드 | 개요→정책 |
| SCR-808 | 장애·실패 로그 | 대시보드 | 개요→장애 |
| SCR-901 | Provider 없음 | 오류 | 후보 0 |
| SCR-902 | 모두 오프라인 | 오류 | 전원 비가용 |
| SCR-903 | 권한 부족 | 오류 | 권한 |
| SCR-904 | 채널 사용 불가 | 오류 | 채널 |
| SCR-905 | 모델 수준 미지원 | 오류 | 수준 |
| SCR-906 | 한도 초과 | 오류 | 한도 |
| SCR-907 | Agent 끊김 | 오류 | 끊김 |
| SCR-908 | Ollama 실패 | 오류 | error 프레임 |
| SCR-909 | timeout | 오류 | 타임아웃 |
| SCR-910 | fallback 실패 | 오류 | 최종 실패 |
| SCR-911 | 민감정보 주의 | 오류·안내 | 프라이버시 |

### 10.B 명령어 ↔ 화면 매핑(차수 25~28 커버리지 검증)

| 명령 | 분류 | 차수 | 응답 화면 |
|---|---|---|---|
| `/ask` | 유저 | 25 | SCR-401→402→403→404(+405) / 거절 SCR-903~910 |
| `/models` | 유저 | 25 | SCR-407 |
| `/my-usage` | 유저 | 25 | SCR-408 |
| `/privacy` | 유저 | 25 | SCR-409 |
| `/help` | 유저(전체) | 25 | SCR-410 |
| (쿨다운/빈도 제한) | 유저 | 25 | SCR-411 |
| `/llm-settings` | 관리자 | 26 | SCR-501(허브)→502~510 |
| `/llm-allow-channel` | 관리자 | 26 | SCR-502 |
| `/llm-deny-channel` | 관리자 | 26 | SCR-502 |
| `/llm-role-policy` | 관리자 | 26 | SCR-503 |
| `/providers` | 관리자 | 26 | SCR-504/505/507/508 |
| `/provider-approve` | 관리자 | 26 | SCR-504(승인) |
| `/provider-remove` | 관리자 | 26 | SCR-506 |
| `/provider-join` | 프로바이더 | 27 | SCR-601→602→603→604 |
| `/provider-leave` | 프로바이더 | 27 | SCR-611 |
| `/provider-pause` | 프로바이더 | 27 | SCR-609 |
| `/provider-resume` | 프로바이더 | 27 | SCR-610 |
| `/provider-status` | 프로바이더 | 27 | SCR-608 |
| `/provider-models` | 프로바이더 | 27 | SCR-605 |
| `/provider-limit` | 프로바이더 | 27 | SCR-607 |
| `/provider-scope` | 프로바이더 | 27 | SCR-606 |
| 프라이버시 모드 A/B/C | (관리자 설정·유저 노출) | 28 | SCR-510(설정) · SCR-405/409/911(노출) |

> 검증: 브리프 §12 의 일반 유저 4종·관리자 7종·프로바이더 8종 = 19개 명령 전부, 그리고 차수 28
> 프라이버시 모드 A/B/C 까지 대응 화면이 정의되었다.
