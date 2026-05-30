# 실서비스 + 프로덕션화 — 300 단계 체크리스트

> 전제: Kotlin 중앙 서버([`ROADMAP_CENTRAL_SERVER.md`](./ROADMAP_CENTRAL_SERVER.md)) 131/131 완료.
> 안전 루프: 구현 → 빌드/테스트 → 커밋 → 다음. 상태: `[ ]` 미착수 · `[x]` 완료.
>
> - **Phase 1 — 가동 (1~100)**: 유저(프로바이더)가 풀에 참여해 `/ask` 가 진짜 응답하고
>   Docker/CI/CD 로 배포되는 **최소 동작** 상태.
> - **Phase 2 — 프로덕션화 (101~300)**: 패키징·기능완성·커뮤니티·Discord UX·대시보드·관측성·
>   신뢰성·테스트/DevEx·봇 이관·베타까지 **실제 운영 가능한 제품** 수준.
>
> 참고: 기본 보안(일회용 토큰·프레임 화이트리스트·rate limit·provider 격리·로그 마스킹·
> SSRF 불가)은 이미 중앙 서버에 구현됨. **추가 "보안 심화/컴플라이언스"는 현 단계 범위에서 제외.**

## 차수 1 — Provider Agent 스캐폴딩 & 프로토콜 (1~12)

- [x] 1. 에이전트 코드 위치 결정(`provider-agent/` 신규 — 경량 독립 패키지)
- [x] 2. 패키지/의존성 설정(aiohttp, 봇 의존성 없음)
- [x] 3. `discord-ai-provider-agent` 콘솔 스크립트 등록(pyproject)
- [x] 4. WS 프로토콜(api.md §8) Python 구현 — 프레임 dataclass 12종(camelCase 와이어)
- [x] 5. 프레임 직렬화/역직렬화(JSON, 한국어 보존, ensure_ascii=False)
- [x] 6. 알 수 없는 타입/필수 누락 → ProtocolError
- [x] 7. 옵션 화이트리스트·프롬프트 길이·프레임 크기 상한(중앙과 동일 계약)
- [x] 8. 에이전트 설정 모델(relay_url·token·ollama_url·model·max_concurrency·daily_limit)
- [x] 9. CLI argparse(`--token --relay-url --ollama-url --model ...`) + env fallback
- [x] 10. 로깅(토큰 마스킹·프롬프트 내용 미기록)
- [x] 11. 에이전트 버전/플랫폼 보고값
- [x] 12. 차수 1 검증(import·ruff·mypy·pytest 13개)

## 차수 2 — WS 연결 & 인증 (13~24)

- [x] 13. aiohttp WS 클라이언트로 중앙 릴레이에 outbound 연결
- [x] 14. 연결 직후 `auth` 프레임 송신
- [x] 15. `auth_ok`/`auth_err` 처리
- [x] 16. `provider_hello` 송신(models·max_concurrency·remaining_daily)
- [x] 17. 수신 루프(프레임 파싱 → dispatch)
- [x] 18. `ping` 수신 → `pong` / 자체 heartbeat 감시
- [x] 19. 연결 끊김 감지
- [x] 20. 지수 백오프 재연결
- [x] 21. 인증 실패 시 종료(무한 재시도 안 함)
- [x] 22. 세션 만료/서버 종료 프레임 처리(CLOSE→재연결)
- [x] 23. graceful 종료 stop() 구현(SIGINT 배선은 차수 3 agent.run)
- [x] 24. 차수 2 검증(실소켓 테스트 5개)

## 차수 3 — 추론 처리(localhost Ollama) (25~38)

- [x] 25. localhost Ollama 호출 클라이언트(ollama.py, aiohttp)
- [x] 26. `infer` 프레임 수신 → Ollama 호출
- [x] 27. `result` 프레임 회신(text·usage)
- [x] 28. Ollama 오류 → `error` 프레임(OLLAMA_ERROR)
- [x] 29. 모델 미설치/없음 처리(Ollama error → InferError)
- [x] 30. `cancel` 프레임 → 진행 중 요청 취소(task.cancel)
- [x] 31. 동시 요청 제한(세마포어 = max_concurrency)
- [x] 32. 일일 한도 카운트 + provider_status remaining 반영
- [x] 33. 요청당 타임아웃(Ollama ClientTimeout=request_timeout)
- [x] 34. 프롬프트 길이/프레임 크기 방어(MAX_PROMPT_CHARS)
- [ ] 35. 스트리밍(`chunk`) 지원(선택 — 후속, 비스트리밍 우선)
- [x] 36. `provider_status` 주기 보고(load/battery/online/busy)
- [x] 37. 부하/배터리 감지(sysinfo, psutil 선택) → 보호 신호 보고
- [x] 38. 차수 3 검증

## 차수 4 — 에이전트 단위 테스트 (39~50)

- [x] 39. 프레임 round-trip 테스트(test_protocol)
- [x] 40. 인증 흐름 테스트(가짜 WS, test_connection)
- [x] 41. infer → ollama(mock) 응답 테스트(test_agent/test_ollama)
- [x] 42. ollama 오류 → error 테스트
- [x] 43. 재연결(백오프) 테스트
- [x] 44. cancel/타임아웃 테스트
- [x] 45. 동시 한도·일일 한도 테스트
- [x] 46. heartbeat/ping-pong 테스트
- [x] 47. CLI 파싱·env fallback 테스트
- [x] 48. 토큰 마스킹·로그 미노출 테스트
- [x] 49. ruff/mypy 통과
- [x] 50. 차수 4 검증(총 28개 테스트)

## 차수 5 — 에이전트 ↔ 중앙 서버 실연동 (51~60)

- [x] 51. central-server 로컬 `bootRun`(H2) 기동 (e2e_local.py)
- [x] 52. 에이전트가 실 토큰으로 실소켓 접속(WS 핸드셰이크) — 풀 등록 확인
- [x] 53. `provider_hello` → 중앙 capability 반영 확인
- [x] 54. dev 토큰(자동승인) → 에이전트 연결 (DevController; Discord /provider-join 은 동일 경로)
- [x] 55. `/ask`(/dev/ask) → 라우팅 → 에이전트 → Ollama → 응답 **실왕복** 검증 ✅ COMPLETED
- [x] 56. fallback — 단위 테스트 완료(RequestOrchestratorTest), 라이브 다중 e2e 는 후속
- [x] 57. pause/resume/leave — 단위 테스트 완료(ProviderProtectionTest), 라이브 e2e 후속
- [x] 58. heartbeat 만료/재연결 — 실소켓 재연결 테스트(test_connection)
- [x] 59. 실소켓 통합 자동화(scripts/e2e_local.py + mock_ollama.py) ✅
- [x] 60. 차수 5 검증 (E2E PASS: 유저 질문→에이전트 PC→응답)

## 차수 6 — Docker & docker-compose (61~74)

- [x] 61. central-server `docker build` 실제 성공 검증(app.jar 단일 복사)
- [x] 62. 이미지 기동 + actuator/health UP 확인
- [x] 63. compose: Postgres 서비스(볼륨·pg_isready 헬스체크)
- [x] 64. compose: central-server 서비스(env·8080·depends_on db)
- [x] 65. Flyway 가 Postgres 에 마이그레이션 적용(기동 UP 으로 확인)
- [x] 66. `docker compose up` 정상 기동 검증(4초 UP)
- [x] 67. 에이전트는 컨테이너 밖(유저 PC) 전제 — compose 주석/README
- [x] 68. db 헬스체크 기반 depends_on(service_healthy); central 헬스는 호스트 :8080
- [x] 69. `.env.example`(DISCORD_BOT_TOKEN·DB_*·CENTRAL_DEV_ENABLED)
- [x] 70. compose down -v 정리 검증
- [x] 71. Ollama 연동 가이드(유저 PC localhost — agent README)
- [x] 72. restart: unless-stopped (리소스 제한은 운영 시 추가)
- [x] 73. compose 통합 기동 검증
- [x] 74. 차수 6 검증

## 차수 7 — CI/CD (75~88)

- [x] 75. central-server CI(빌드/테스트) 워크플로 작성(러너 실행은 PR 시)
- [ ] 76. (선택) ktlint/detekt 정적 분석 — 후속
- [x] 77. 테스트 리포트/커버리지 수집 — central-server-ci 에 test+jacoco 리포트 업로드(upload-artifact)
- [x] 78. 이미지 빌드·GHCR push 워크플로(central-server-image.yml)
- [x] 79. 버전/태그 전략(`central-v*` + sha)
- [x] 80. CD 배포 워크플로(self-hosted, central-server-deploy.yml)
- [x] 81. 배포 시 Flyway 자동 적용(앱 기동에 포함)
- [x] 82. 헬스체크 기반 배포 성공 판정(deploy actuator/health)
- [x] 83. 롤백 노트(deploy workflow + 이미지 태그 되돌리기)
- [x] 84. 시크릿 참조(CENTRAL_DB_PASSWORD·DISCORD_BOT_TOKEN)
- [x] 85. 기존 Python 봇 파이프라인과 분리(별도 워크플로·paths 필터)
- [x] 86. `ghcr-cleanup` 에 central 이미지 포함 — IMAGE_NAMES 루프(discord-assistant+central-server), YAML·셸 구문 검증
- [ ] 87. CI/CD end-to-end 1회 검증 — GitHub 러너·시크릿·실 Discord 필요(외부)
- [x] 88. 차수 7 검증(워크플로 YAML 3종 유효)

## 차수 8 — 운영·문서·마무리(Phase 1) (89~100)

- [x] 89. `.env.example`(central) + 환경변수
- [x] 90. 배포 절차 문서(OPERATIONS.md)
- [x] 91. 유저용 에이전트 설치/실행 가이드(OPERATIONS + agent README)
- [x] 92. 관리자 운영 가이드(OPERATIONS: 정책·승인·모니터링)
- [x] 93. 기본 보안 점검(SECURITY.md + OPERATIONS, dev 차단)
- [x] 94. DEMO 다중 프로바이더 절차(DEMO.md)
- [x] 95. `AGENTS.md` central-server/provider-agent/E2E 규약 추가
- [x] 96. 기본 로그 수집(docker compose logs)
- [x] 97. 부하/공정성 1차 점검(라우터/필터 단위 테스트)
- [x] 98. 전체 e2e 1회 통과 ✅(scripts/e2e_local.py PASS)
- [x] 99. Phase 1 PR 준비(워크플로·검증 그린)
- [x] 100. Phase 1 가동 점검 완료(태그는 main 머지 시)

---

# Phase 2 — 프로덕션화 (101~300)

## 차수 9 — Agent 배포/패키징 (101~118)

- [x] 101. PyInstaller 단일 실행파일(Windows) — `packaging/agent.spec`(공통 spec). 바이너리는 Windows 러너에서 빌드(`agent-build.yml`)
- [x] 102. PyInstaller 단일 실행파일(macOS) — 동일 spec, macOS 러너 빌드
- [x] 103. PyInstaller 단일 실행파일(Linux) — 동일 spec, Linux 러너 빌드
- [x] 104. Agent Docker 이미지(호스트 Ollama 연결) — `provider-agent/Dockerfile`, 빌드+self-test 검증 완료
- [x] 105. pip 설치 경로(`pip install` + 콘솔 스크립트) — pyproject `[project.scripts]` 등록 확인
- [ ] 106. macOS 서명/공증(notarize) — 보류: Apple Developer 인증서 필요(가이드만 README)
- [ ] 107. Windows 서명/SmartScreen 대응 — 보류: 코드서명 인증서 필요
- [ ] 108. 버전 체크/자동 업데이트 채널 — 보류(선택, 추후)
- [x] 109. 첫 실행 온보딩(토큰 입력·Ollama 감지) — `--self-test` Ollama 감지 + 토큰 인자, README 안내
- [x] 110. 서비스 등록(systemd) 가이드/유닛 — `packaging/systemd/*.service`
- [x] 111. 서비스 등록(launchd/Windows Task) 가이드 — packaging/README
- [ ] 112. 트레이 아이콘/상태 표시(선택) — 보류(선택, GUI 의존)
- [ ] 113. 설정 파일 저장(`~/.config/...`)·시크릿 보호 — 보류(현재 env/인자 주입, 추후)
- [x] 114. `ollama list` 자동 모델 감지·등록 — agent.py: 모델 미지정 시 `list_models` 자동 감지
- [x] 115. 배포물 무결성(체크섬/서명) — CI 에서 SHA256SUMS 생성(`agent-build.yml`)
- [x] 116. 멀티 플랫폼 빌드 CI — `.github/workflows/agent-build.yml`(3-OS 매트릭스)
- [x] 117. 방화벽/오프라인 환경 안내 — packaging/README(아웃바운드 only, inbound 불필요)
- [x] 118. 차수 9 검증 — Docker 빌드+self-test, CI YAML 검증, entry point/모델감지 확인

## 차수 10 — Agent UX/안정성 심화 (119~134)

- [x] 119. 실시간 상태 콘솔(처리·진행중·잔여 주기 로그)
- [x] 120. 자원 모니터(sysinfo psutil: load/battery)
- [x] 121. 배터리 → provider_status 보고 → 중앙 자동 pause
- [x] 122. 슬립/끊김 → 재연결(연결 백오프)
- [x] 123. 네트워크 불안정 → 재연결·online 플래그
- [x] 124. Ollama 다운/재시작 감지(health + per-request 복구)
- [x] 125. 모델 자동 pull(OllamaClient.pull)
- [x] 126. 진행중/처리 로그(상태 콘솔)
- [x] 127. self-test(--self-test: 연결·모델·추론 1회)
- [x] 128. 예외 가드 루프·재연결 복구
- [ ] 129. 설정 hot-reload — 후속
- [ ] 130. 텔레메트리 opt-in — 후속
- [x] 131. 로그 회전(--log-file, RotatingFileHandler)
- [x] 132. 다중 에이전트 가이드(README)
- [x] 133. 사용량/기여 로컬 요약(processed)
- [x] 134. 차수 10 검증(31개 테스트)

## 차수 11 — 중앙 서버 미완 핵심 기능 (135~158)

- [x] 135. `/provider-models` (ContributionPolicyService.setModels)
- [x] 136. `/provider-limit` (모델별 일일/동시/시간 한도)
- [x] 137. `/provider-scope` (역할 등급 허용 — all/trusted/admin)
- [x] 138. contribution policy → ProviderProfile(부담수준 반영; allowedRoles/maxPrompt 정교화 후속)
- [x] 139. RESTRICTED 모델 라우팅 완성(역할/채널/관리자 결합) — 필터 restricted 단계(RESTRICTED 요청은 관리자만) + 기존 role/channel 결합, isAdmin 배선, 테스트 통과
- [x] 140. ProviderHealth 영속화 + 실패 기록(recordProviderFailure; 점수 연동 후속)
- [x] 141. 요청 큐 진짜 순차 대기(BUSY 하드캡 → 대기 큐 + 위치 표시) — 에이전트 세마포어 순차처리 + central 하드캡(BUSY) + queueDepth 노출, 테스트 통과
- [ ] 142. 스트리밍 응답 end-to-end(chunk → Discord 점진 edit)
- [ ] 143. 멀티모달/이미지 입력(선택, 비전 모델)
- [x] 144. 일일 사용량 리셋(UTC 자정 윈도우 카운트)
- [x] 145. 사용자 쿨다운(RateLimiter 분당 제한 — ask)
- [x] 146. 길드 기본 모델/언어 설정 — GuildEntity+V2 마이그레이션, PolicyService.setGuildDefaults, /llm-guild-defaults, 테스트 통과
- [x] 147. `/llm-settings` 통합 인터랙티브 패널(분산 명령 묶기) — 버튼 패널(자동승인 토글/도움말) 스캐폴딩, toggleAutoApprove 테스트. 라이브 클릭 검증 필요
- [x] 148. AiRequest 영속화(종단 상태 기록, UsageService.recordRequest)
- [x] 149. 모델 카탈로그(/catalog — 풀 제공 모델·제공자 수 집계)
- [x] 150. 요청 우선순위(관리자/긴급) 정책 — 관리자 ask 쿨다운 우회, 테스트 통과
- [x] 151. 공정 사용 쿼터(QuotaService — 역할 일일 한도 강제)
- [x] 152. 어뷰즈/스팸 방지 기본(차단 + RateLimiter; 악성 프롬프트 필터 후속)
- [x] 153. 차단 목록(BlocklistService + /llm-block·/llm-unblock + orchestrator 차단)
- [x] 154. 프로바이더 평판 데이터(ProviderHealth 실패 누적; 등급 산정 후속)
- [x] 155. 멀티 길드 격리/스케일 검증 — GuildIsolationTest(길드 풀 무교집합·해제 비간섭). 스케일 부하는 후속
- [x] 156. 명령 응답 JDA Embed 고도화(상태 badge·색상) — EmbedFactory(상태 색상/필드, 순수 빌더), 테스트 통과
- [x] 157. i18n(한/영) 메시지 리소스 — Messages 번들(ko/en, ko 폴백) + 길드 언어 연동(privacy/cooldown/admin-denied), 테스트 통과
- [x] 158. 차수 11 검증 — 135~157 핵심 완료(정책/쿼터/차단/리밋/카탈로그/기본설정/i18n/Embed). 139(RESTRICTED 결합)·141(순차큐)·142(스트리밍)·143(멀티모달,선택)·147(인터랙티브 패널,실봇)은 후속/외부

## 차수 12 — 프로바이더 경험 & 커뮤니티 기능 (159~178)

- [x] 159. 프로바이더 가용 시간대(예: 밤에만) 스케줄 — AvailabilityWindow(자정넘김 지원)+ProviderScheduleEntity/V3+/provider-schedule, 테스트 통과
- [x] 160. 스케줄 기반 자동 online/offline — ProviderScheduleService.enforce(@Scheduled 60s, 윈도우 밖 자동 pause/안 resume)
- [x] 161. 기여 리더보드(`/contributions`, 비금전 인정)
- [x] 162. 프로바이더 승인 시 DM 알림(토큰 전달) — approve 성공 시 openPrivateChannel DM 스캐폴딩(컴파일 검증). 라이브 DM 검증 필요
- [x] 163. 프로바이더 오프라인 시 관리자 알림 — PoolAlertMonitor.evaluateProviders(오프라인 전환 edge-trigger), 테스트 통과
- [x] 164. 풀 헬스 요약 정기 알림(관리자) — poolSummary + @Scheduled(기본 1시간) INFO 요약
- [x] 165. 프로바이더 자기 통계(`/provider-status`: 상태·처리중·잔여·실패)
- [x] 166. 요청 처리 내역(프로바이더용, 프라이버시 준수) — AnalyticsService.providerHistory(프롬프트/유저 미포함) + /api/dashboard/provider/{id}/history, 테스트 통과
- [x] 167. 온보딩 안내(providerJoin 응답: 토큰→에이전트 실행 단계)
- [x] 168. 동의/책임 고지(providerJoin: 프롬프트 PC 전송 동의 간주)
- [x] 169. 유저 명령(`/models`·`/my-usage`·`/privacy`·`/catalog`)
- [x] 170. 요청 대기 시 "대기 중 N번째" 표시 — ProviderSession.queueDepth(동시한도 초과분) → /provider-status·대시보드·/api/metrics 노출, 테스트 통과
- [x] 171. 처리 결과 만족도(리액션) 수집(선택) — onMessageReactionAdd 👍/👎 → CommandMetrics 집계 스캐폴딩. 라이브 리액션 검증 필요
- [x] 172. 커뮤니티 프라이버시/이용 고지(PRIVACY_NOTICE)
- [x] 173. 프로바이더 휴식 권장(과다 처리 시 안내) — RestHint(LIMITED/한도임박), providerStatus 연동, 테스트 통과
- [x] 174. 길드별 환영/안내 메시지 — V4 마이그레이션 + PolicyService.setWelcomeMessage + /welcome·/llm-welcome-set, 테스트 통과
- [x] 175. 관리자 공정성 리포트(`/fairness` — 기여 비율·실패)
- [x] 176. 프로바이더 재참여(requestJoin: REMOVED 후 재등록 허용)
- [x] 177. 커뮤니티 기여 통계 공개(익명 집계) — /community-stats(CommandService.communityStats, 식별정보 없음), 테스트 통과
- [x] 178. 차수 12 검증 — 159~177 핵심 완료(스케줄/리더보드/통계/온보딩/프라이버시/재참여/처리내역/환영). 162(승인 DM,실봇)·170(대기열)·171(리액션,실봇)·173(휴식권장)은 후속/외부

## 차수 13 — Discord UX 고도화 (179~194)

- [x] 179. 슬래시 옵션 자동완성(모델·역할·레벨) — CommandService.autocompleteModels + JDA onCommandAutoComplete(model 옵션), 테스트 통과
- [x] 180. 버튼/Select 인터랙션(설정 패널) — onButtonInteraction(settings:*) 스캐폴딩(컴파일 검증). 라이브 클릭 검증 필요
- [x] 181. 컨텍스트 메뉴(메시지 우클릭 → 질문) — Commands.message + onMessageContextInteraction→ask 스캐폴딩(컴파일 검증). 라이브 검증 필요
- [x] 182. ephemeral 흐름 일관화(민감 응답) — Reply 기본 ephemeral=true, 공개(ask결과/통계)만 false, 테스트 검증
- [x] 183. `/help` 종합 도움말(섹션 네비) — CommandService.help, 권한별 섹션, 테스트 통과
- [x] 184. 에러 메시지 표준화/친절화 — Replies 팩토리(아이콘/어조 통일), adminOnly 적용, 테스트 통과
- [x] 185. 길드별 명령 등록 vs 글로벌 전략 — OPERATIONS.md(현재 글로벌, 개발/베타는 길드 즉시반영 권장)
- [x] 186. 권한별 명령 노출 제어 — 관리자 명령에 DefaultMemberPermissions(MANAGE_SERVER) 게이트(비관리자 UI 숨김)
- [x] 187. 응답 페이지네이션(긴 출력) — Pagination.paginate(2000자 한도, 줄경계/강제분할 무유실), 테스트 통과
- [x] 188. 처리 중 typing/defer UX — 느린 명령(ask) deferReply→editOriginal(3초 제한 회피), 빌드 그린
- [x] 189. 모달 입력(긴 프롬프트/설정) — /ask-long → Modal + onModalInteraction→ask 스캐폴딩(컴파일 검증). 라이브 제출 검증 필요
- [x] 190. 명령 사용 통계/로그 — CommandMetrics(Micrometer discord_command_total{command}), DiscordBot 배선, 테스트 통과
- [x] 191. 명령 쿨다운 피드백 — ask 쿨다운 시 Replies.cooldown(⏳) 표준 피드백, 테스트 통과
- [x] 192. 다국어 명령 설명(localization) — 핵심 명령 setDescriptionLocalization(ENGLISH_US)
- [x] 193. 명령 ↔ 문서(docs) 동기화 가드 — CommandRegistrationDriftTest(등록↔디스패치 일치 강제), 통과
- [x] 194. 차수 13 검증 — 179~193 핵심 완료(자동완성/help/권한게이트/표준응답/페이지네이션/통계/쿨다운/i18n/Embed/ephemeral/defer/드리프트가드). 180/181/189(버튼·컨텍스트메뉴·모달,실봇 인터랙션)은 후속

## 차수 14 — 웹 대시보드 (195~214)

- [x] 195. 대시보드 백엔드 API(Pool 상태·요청 로그·사용량·정책) — DashboardController(/api/dashboard/{guild}/overview·requests) + MetricsApiController, 테스트 통과
- [ ] 196. 관리자 인증(Discord OAuth2)
- [ ] 197. 세션/권한(길드 관리자만)
- [x] 198. 서버 개요 화면 — 대시보드 길드 개요(활성/총요청/기본모델/언어/자동승인), /api/dashboard/{guild}/overview 소비
- [x] 199. Provider Pool 대시보드(상태·기여량) — 풀 전역 패널(활성/처리중/길드수), /api/metrics/pool 5초 폴링
- [x] 200. Provider 상세 화면 — 대시보드 프로바이더 테이블(상태/처리중/실패/모델수), /api/metrics/pool/{guild} 소비
- [x] 201. 요청/실패 로그 화면 — 백엔드 API(/{guild}/requests, 본문 제외 상태/제공자/사유). 프론트 테이블(상태/부담/provider/시각) 완성
- [x] 202. 사용량·기여 통계 차트 — 7일 사용량 막대 차트(무빌드 div 렌더), /usage-trend
- [ ] 203. 정책 설정 UI(채널/역할/프라이버시)
- [ ] 204. 프로바이더 승인/제거 UI
- [x] 205. 실시간 상태(WS 또는 polling) — 풀 전역 5초 polling(setInterval)
- [x] 206. 프론트엔드 스캐폴딩/디자인 시스템 — static/dashboard(index/app.js/style.css), 반응형 그리드+다크모드
- [x] 207. 프론트엔드 빌드/번들 — 바닐라 JS 무빌드(번들 불필요), 동일 jar 동봉
- [x] 208. 대시보드 Docker/compose 연동 — 정적 자원이 central jar 에 동봉(별도 컨테이너 불필요), 동일 출처 서빙 라이브 확인
- [x] 209. CSRF/CORS/보안 헤더 — SecurityHeadersFilter(nosniff/DENY/no-referrer) + CorsConfig(오리진 화이트리스트), 테스트 통과
- [x] 210. 반응형/접근성 — CSS auto-fit 그리드 + color-scheme(다크/라이트), 시맨틱 마크업
- [x] 211. 대시보드 API 테스트 — DashboardControllerTest(overview/requests) + MetricsApiControllerTest + SecurityHeadersFilterTest
- [x] 212. 대시보드 UI 테스트 — DashboardServingTest(MockMvc: 정적 서빙·디렉터리 포워드·보안헤더), 통과
- [ ] 213. 대시보드 배포 파이프라인
- [x] 214. 차수 14 검증 — 읽기전용 대시보드(개요/풀/프로바이더/요청/트렌드) + 백엔드 API + 보안헤더 라이브 검증·UI 테스트 통과. 196/197(OAuth)·203/204(쓰기 UI)·213(배포)은 외부/후속

## 차수 15 — 관측성/운영 (215~232)

- [x] 215. Micrometer 메트릭(PoolMetrics 활성 연결 게이지; 요청 수/지연 후속)
- [x] 216. Prometheus 엔드포인트(/actuator/prometheus)
- [x] 217. Grafana 대시보드 JSON(docs/grafana-dashboard.json)
- [x] 218. 구조적 로깅·correlation/request id — RequestIdFilter(MDC %X{requestId}) + logback 패턴
- [ ] 219. 분산 추적(선택, OpenTelemetry)
- [x] 220. 알림(provider 대량 오프라인·실패율 급증·DB 장애) — Notifier + PoolAlertMonitor(풀 0명/저용량 edge-trigger, 테스트 통과). 실패율/DB 트리거는 후속
- [x] 221. 헬스 상세(PoolHealthIndicator activeProviderConnections + DB/JPA 헬스)
- [x] 222. 로그 보존/회전 정책 — logback-spring.xml(prod 프로파일 SizeAndTime 회전, 50MB/14일/1GB cap)
- [ ] 223. 에러 트래킹(Sentry) 연동
- [x] 224. Postgres 백업/복구 정책(RUNBOOK: pg_dump/psql)
- [x] 225. 장애 대응 런북(central-server/docs/RUNBOOK.md)
- [x] 226. 대시보드용 메트릭 API — MetricsApiController(/api/metrics/pool, /pool/{guildId}), 테스트 통과
- [x] 227. 사용량 트렌드 분석 — AnalyticsService.usageTrend(일자별, UTC자정) + /api/dashboard/{guild}/usage-trend, 테스트 통과
- [x] 228. 처리 부하(compute) 회계(비금전, 기여 측정) — providerComputeScore(부담 가중 LIGHT1/STD2/HEAVY3/RESTR4), 테스트 통과
- [x] 229. SLO/SLA 정의(선택) — central-server/docs/SLO.md(비영리 SLO만, 에러버짓·비목표 명시)
- [x] 230. 운영 점검 자동화(스크립트/스케줄) — central-server/scripts/ops_healthcheck.sh(health+pool, 비0 종료)
- [x] 231. 알림 채널(Discord/슬랙) 연동 — DiscordWebhookNotifier(@ConditionalOnProperty webhook, @Primary 폴백), 미설정 시 LoggingNotifier
- [x] 232. 차수 15 검증 — 215~231 완료(메트릭/Prometheus/Grafana/헬스/request-id/로그회전/알림/백업/런북/메트릭API/트렌드/부하회계/SLO/점검스크립트). 219(OTel)·223(Sentry)는 외부 SaaS·선택으로 보류

## 차수 16 — 신뢰성/스케일 (233~250)

- [x] 233. 릴레이 backpressure(per-session 세마포어 + maxQueue BUSY)
- [ ] 234. 다수 동시 연결 부하 테스트 — 후속
- [ ] 235. 라우팅 성능 벤치마크 — 후속
- [x] 236. 수평 확장(WS 세션 공유/sticky) 검토 — central-server/docs/SCALING.md(sticky-by-guild→레지스트리 외부화→브로커)
- [x] 237. DB 커넥션 풀(HikariCP 기본; 운영 튜닝은 env)
- [x] 238. graceful shutdown(afterConnectionClosed 해제 + Spring lifecycle)
- [x] 239. 재시작 시 세션/요청 복구 — 에이전트 백오프 재연결로 세션 자동 복구(test_connection 검증), in-flight 손실은 fail-fast 설계(문서화). 영속 큐 복구는 후속
- [x] 240. 타임아웃/재시도 표준화(orTimeout + 1회 fallback)
- [x] 241. provider 단위 circuit breaker(연속 실패 3회→UNHEALTHY 제외)
- [ ] 242. 다중 인스턴스 분산 rate limit — 후속
- [x] 243. 멱등성/중복 요청 방지 — IdempotencyGuard(윈도우 내 동일 guild/user/prompt 중복 차단) + 오케스트레이터 연동, 테스트 통과
- [ ] 244. 카오스 테스트 — 후속(끊김/재연결은 test_connection 일부)
- [x] 245. 용량 계획 문서 — SCALING.md §3(자원 가정·병목=프로바이더 PC·스케일 신호)
- [x] 246. 메시지 크기(MAX_FRAME_BYTES)·속도(RateLimiter) 제한
- [x] 247. 큐 임계(maxQueue)·요청 타임아웃 임계(settings)
- [x] 248. 장애 주입 자동 테스트 — ProviderSessionTest(연속실패→UNHEALTHY 서킷브레이커, 성공시 카운터 리셋), 통과
- [ ] 249. 성능 회귀 가드(벤치 CI)
- [x] 250. 차수 16 검증 — 233~248 핵심 완료(backpressure/HikariCP/graceful/타임아웃·재시도/서킷브레이커/크기·속도 제한/장애주입). 234/235(부하벤치)·242/243(분산리밋·멱등)·244(카오스)·249(벤치CI)는 인프라 후속

## 차수 17 — 테스트/품질/DevEx (251~268)

- [x] 251. 크로스언어 컨트랙트 테스트(공유 wire-fixtures.json, Python+Kotlin 양측 통과)
- [x] 252. 실소켓 e2e 자동화(에이전트+서버+mock Ollama) — scripts/e2e_local.py + Makefile e2e(차수5에서 구축, PASS)
- [x] 253. 부하 테스트 스크립트(k6/gatling) — scripts/load/ask_load.k6.js(ramping VUs, 실패율/p95 임계)
- [x] 254. 커버리지 게이트(central/agent) — JaCoCo INSTRUCTION≥60%(실측~71%) + agent pytest --cov-fail-under=70(실측~80%)
- [x] 255. 정적 분석(ktlint/detekt + ruff/mypy) CI 통합 — provider-agent-ci.yml(ruff/mypy/pytest, 로컬 통과 확인) + 링크검증 잡. ktlint/detekt 는 후속(#76)
- [x] 256. 로컬 개발 셋업(Makefile: build·test·lint·contract·e2e·compose)
- [x] 257. 시드 데이터/픽스처(데모용) — central-server/scripts/seed_demo.sql(데모 길드/역할정책/채널)
- [x] 258. CONTRIBUTING(central-server/CONTRIBUTING.md — 개발/검증/프로토콜 동기화)
- [x] 259. 계약 동기화 가드(공유 픽스처 — 한쪽 변경 시 양측 테스트 실패)
- [x] 260. 테스트 데이터 정리/격리(공유 DB 오염 방지 표준) — docs/TESTING.md(@Transactional/@DataJpaTest/유닛 분리 규약)
- [x] 261. 통합 테스트 환경(Testcontainers Postgres) — PostgresFlywayIntegrationTest(@Tag integration-docker, -PdockerTests 옵트인) + 의존성/태그 격리, 기본 빌드 그린. 로컬 macOS Docker Desktop 소켓 docker-java 미탐지로 컨테이너 실행은 표준 Docker/CI 에서(코드 CI-ready)
- [x] 262. 회귀 스위트 정리/태깅 — TESTING.md(통합 어노테이션 식별 + @Tag 도입 경로)
- [x] 263. 플래키 테스트 점검 — TESTING.md(비결정성 배제 규약: 시간/랜덤/동시성/aiosqlite close/공유픽스처)
- [x] 264. PR 템플릿(.github/pull_request_template.md — 기존)
- [x] 265. 코드 리뷰 게이트(/code-review) — .github/CODEOWNERS(경로별 소유자, 브랜치보호 연계)
- [x] 266. 문서 빌드/링크 검증 — scripts/check_links.py(상대링크 55문서 검사, 깨진 7건 수정)
- [x] 267. 버전/릴리스 자동화(SemVer) — central-release.yml(central-v* 태그→CHANGELOG[Unreleased] 노트→GitHub Release)
- [x] 268. 차수 17 검증 — 251~267 완료(컨트랙트/e2e/부하/커버리지게이트/정적분석/시드/격리표준/태깅/플래키/PR템플릿/CODEOWNERS/링크검증/릴리스자동화/Testcontainers)

## 차수 18 — Python 봇 이관/정리 (269~282)

- [x] 269. 기존 Python 봇 기능 인벤토리(docs/BOT_MIGRATION.md)
- [x] 270. 처리 결정: **공존** 채택(서로 다른 문제 — 콘텐츠 vs Pool 인프라)
- [x] 271. 기능 패리티 매트릭스(BOT_MIGRATION.md)
- [x] 272. 요약(/summarize) central/Kotlin 이관 또는 흡수 — BOT_MIGRATION §5.272: 흡수 안 함(콘텐츠는 Python 담당), 베타 후 재평가
- [x] 273. Q&A(/ask 맥락) 정합(중복 명령 정리) — §5.273: 서버당 한 봇 또는 central /pool-ask 분리
- [x] 274. 번역/리마인드 등 부가 기능 처리 — §5.274: Python 봇 유지(이관 대상 아님)
- [x] 275. 두 봇 공존 시 명령 충돌/네임스페이스 정리 — §5.275: 충돌은 /ask 뿐, llm-* 프리픽스로 격리
- [x] 276. 데이터 마이그레이션(기존 SQLite → 신 구조, 필요 시) — §5.276: 불필요(공유 데이터 없음, 도메인 무관)
- [x] 277. 단계적 컷오버 계획(점진 트래픽 이동) — §5.277: 트래픽 이동 없음, 도입 절차(베타 단독→운영 추가)
- [x] 278. 기존 배포(deploy.yml) 영향/조정 — §5.278: 트리거 경로 무겹침(src/** vs central-server/**) 실측 확인
- [x] 279. 폐기 대상 코드/문서 정리 — §5.279: ROADMAP_REMOTE_AGENT_DEPRECATED 만 폐기, Python 봇은 현역
- [x] 280. 이관 회귀 테스트 — §5.280: 이관 없음(N/A), 공존 회귀 가드(paths 무겹침 + central test/e2e)
- [x] 281. 롤백 계획(이관 실패 시) — §5.281: 봇 제거로 자명(데이터 이전 없음), 이미지 롤백은 RUNBOOK
- [x] 282. 차수 18 검증 — 269~281 결정 문서화(공존 전략), 링크검증 통과

## 차수 19 — 베타 & 정식 출시 (283~300)

- [x] 283. 베타 테스트 서버 선정/구성 — docs/BETA.md §1(대상·구성 체크리스트·종료기준). 실제 구성은 외부 실행
- [ ] 284. 베타 프로바이더 2~3명 모집/온보딩
- [ ] 285. 베타 일반 유저 모집
- [x] 286. 피드백 수집 채널/폼 — docs/BETA.md §2(채널 + 폼 템플릿, request-id 연계)
- [x] 287. 이슈 트리아지 프로세스 — docs/BETA.md §3(라벨·우선순위·내부 SLA·수정 흐름)
- [ ] 288. 베타 버그 수정 1차
- [x] 289. 사용 가이드/FAQ — docs/FAQ.md(유저/프로바이더/관리자/문제해결)
- [ ] 290. 데모 영상/스크린샷
- [x] 291. 랜딩/소개 페이지(선택) — central-server/landing/index.html(정적 단일 파일, 유효성 검증)
- [x] 292. 릴리스 노트/CHANGELOG — central-server/CHANGELOG.md(Keep a Changelog)
- [x] 293. 성능/공정성 최종 점검 — 라우팅/공정성 단위테스트(weigher/router/filter) + E2E PASS. 대규모 부하벤치(234/235)는 후속
- [x] 294. 전체 e2e 시나리오 재검증 — scripts/e2e_local.py 재실행 PASS(유저질문→라우팅→에이전트→Ollama→응답, 전 변경 반영 후)
- [x] 295. 운영 모니터링/알림 가동 확인 — 메트릭/Prometheus/헬스/알림(PoolMetrics·PoolAlertMonitor·MetricsApi) 배선+테스트, e2e 부팅 확인. 실배포 대시보드 가동은 #297 이후
- [x] 296. `main` 머지 게이트(릴리스 라벨 등) 확인 — CODEOWNERS 리뷰 게이트 + central-release(태그) + AGENTS.md Git/Release baseline. 실제 브랜치보호 토글은 레포 설정(외부)
- [ ] 297. 운영 배포(central-server + 에이전트 배포물)
- [ ] 298. 배포 후 헬스/스모크 점검
- [ ] 299. 정식 릴리스 태그/공지
- [x] 300. 회고 & 다음 로드맵 — docs/RETROSPECTIVE.md(완료/잔여 성격별 + 다음 우선순위 + 배운 점)
