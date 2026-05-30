# 실서비스 + 프로덕션화 — 200 단계 체크리스트

> 전제: Kotlin 중앙 서버([`ROADMAP_CENTRAL_SERVER.md`](./ROADMAP_CENTRAL_SERVER.md)) 131/131 완료.
> 안전 루프: 구현 → 빌드/테스트 → 커밋 → 다음. 상태: `[ ]` 미착수 · `[x]` 완료.
>
> - **Phase 1 — 가동 (1~100)**: 유저(프로바이더)가 풀에 참여해 `/ask` 가 진짜 응답하고,
>   Docker/CI/CD 로 배포되는 **최소 동작** 상태.
> - **Phase 2 — 프로덕션화 (101~200)**: 배포/패키징·미완 기능·웹 대시보드·관측성·신뢰성/
>   스케일·보안/컴플라이언스·베타 출시까지 **실제 운영 가능한 제품** 수준.

## 차수 1 — Provider Agent 스캐폴딩 & 프로토콜 (1~12)

- [ ] 1. 에이전트 코드 위치 결정(`provider-agent/` 신규 vs `src/discord_assistant/agent/`)
- [ ] 2. 패키지/의존성 설정(aiohttp 또는 websockets, ollama 호출용 httpx/urllib)
- [ ] 3. `discord-assistant-agent` 콘솔 스크립트 등록(pyproject)
- [ ] 4. WS 프로토콜(api.md §8) Python 구현 — 프레임 dataclass 12종
- [ ] 5. 프레임 직렬화/역직렬화(JSON, 한국어 보존, ensure_ascii=False)
- [ ] 6. 알 수 없는 타입/필수 누락 → ProtocolError
- [ ] 7. 옵션 화이트리스트·프롬프트 길이·프레임 크기 상한(중앙과 동일 계약)
- [ ] 8. 에이전트 설정 모델(relay_url·token·ollama_url·model·max_concurrency·daily_limit)
- [ ] 9. CLI argparse(`--token --relay-url --ollama-url --model ...`) + env fallback
- [ ] 10. 로깅(토큰 마스킹·프롬프트 내용 미기록)
- [ ] 11. 에이전트 버전/플랫폼 보고값
- [ ] 12. 차수 1 검증(import·ruff·mypy)

## 차수 2 — WS 연결 & 인증 (13~24)

- [ ] 13. aiohttp WS 클라이언트로 중앙 릴레이에 outbound 연결
- [ ] 14. 연결 직후 `auth` 프레임 송신
- [ ] 15. `auth_ok`/`auth_err` 처리
- [ ] 16. `provider_hello` 송신(models·max_concurrency·remaining_daily)
- [ ] 17. 수신 루프(프레임 파싱 → dispatch)
- [ ] 18. `ping` 수신 → `pong` / 자체 heartbeat 감시
- [ ] 19. 연결 끊김 감지
- [ ] 20. 지수 백오프 재연결
- [ ] 21. 인증 실패 시 명확한 종료/안내
- [ ] 22. 세션 만료/서버 종료 프레임 처리
- [ ] 23. SIGINT graceful 종료(진행 요청 정리)
- [ ] 24. 차수 2 검증

## 차수 3 — 추론 처리(localhost Ollama) (25~38)

- [ ] 25. localhost Ollama 호출 클라이언트(경량 또는 기존 로직 참고)
- [ ] 26. `infer` 프레임 수신 → Ollama 호출
- [ ] 27. `result` 프레임 회신(text·usage)
- [ ] 28. Ollama 오류 → `error` 프레임(코드 매핑: OLLAMA_ERROR 등)
- [ ] 29. 모델 미설치/없음 처리(안내)
- [ ] 30. `cancel` 프레임 → 진행 중 요청 취소
- [ ] 31. 동시 요청 제한(로컬 세마포어 = max_concurrency)
- [ ] 32. 일일 한도 카운트 + provider_status 에 remaining 반영
- [ ] 33. 요청당 타임아웃
- [ ] 34. 프롬프트 길이/프레임 크기 방어
- [ ] 35. 스트리밍(`chunk`) 지원(선택)
- [ ] 36. `provider_status` 주기 보고(load/battery/online/busy)
- [ ] 37. 부하/배터리 감지(psutil) → 보호 신호 보고
- [ ] 38. 차수 3 검증

## 차수 4 — 에이전트 단위 테스트 (39~50)

- [ ] 39. 프레임 round-trip 테스트
- [ ] 40. 인증 흐름 테스트(가짜 WS)
- [ ] 41. infer → ollama(mock) 응답 테스트
- [ ] 42. ollama 오류 → error 테스트
- [ ] 43. 재연결(백오프) 테스트
- [ ] 44. cancel/타임아웃 테스트
- [ ] 45. 동시 한도·일일 한도 테스트
- [ ] 46. heartbeat/ping-pong 테스트
- [ ] 47. CLI 파싱·env fallback 테스트
- [ ] 48. 토큰 마스킹·로그 미노출 테스트
- [ ] 49. ruff/mypy 통과
- [ ] 50. 차수 4 검증

## 차수 5 — 에이전트 ↔ 중앙 서버 실연동 (51~60)

- [ ] 51. central-server 로컬 `bootRun`(H2) 기동
- [ ] 52. 에이전트가 실 토큰으로 실소켓 접속(WS 핸드셰이크)
- [ ] 53. `provider_hello` → 중앙 capability 반영 확인
- [ ] 54. `/provider-join`(자동승인 모드) → 토큰 → 에이전트 연결 수동 검증
- [ ] 55. `/ask` → 라우팅 → 에이전트 → Ollama → 응답 **실왕복** 검증
- [ ] 56. 프로바이더 2개 띄워 fallback 실연동
- [ ] 57. pause/resume/leave 실연동
- [ ] 58. heartbeat 만료/재연결 실연동
- [ ] 59. 실소켓 통합 테스트(자동화 1개) 또는 검증 로그 문서화
- [ ] 60. 차수 5 검증

## 차수 6 — Docker & docker-compose (61~74)

- [ ] 61. central-server `docker build` 실제 성공 검증
- [ ] 62. 이미지 기동(`docker run`) + actuator/health 확인
- [ ] 63. compose: Postgres 서비스 정의(볼륨·헬스체크)
- [ ] 64. compose: central-server 서비스(env·포트 8080·depends_on db)
- [ ] 65. Flyway 가 Postgres 에 마이그레이션 적용되는지 확인
- [ ] 66. `docker compose up` 으로 서버 정상 기동 검증
- [ ] 67. 에이전트는 컨테이너 밖(유저 PC) 전제 — 연결 주소 가이드
- [ ] 68. healthcheck 기반 depends_on 조건
- [ ] 69. `.env` 템플릿(DISCORD_BOT_TOKEN·DB_URL/USER/PASSWORD)
- [ ] 70. compose down/volume 정리 가이드
- [ ] 71. (선택) Ollama 호스트/컨테이너 연동 가이드
- [ ] 72. 리소스 제한·restart 정책
- [ ] 73. compose 통합 기동 검증
- [ ] 74. 차수 6 검증

## 차수 7 — CI/CD (75~88)

- [ ] 75. central-server CI(빌드/테스트) 러너에서 실제 그린 확인
- [ ] 76. (선택) ktlint/detekt 정적 분석 추가
- [ ] 77. 테스트 리포트/커버리지 수집(선택)
- [ ] 78. 이미지 빌드·GHCR push 워크플로
- [ ] 79. 버전/태그 전략(`central-vX.Y.Z`)
- [ ] 80. CD 배포 워크플로(self-hosted runner)에 central-server 추가
- [ ] 81. 배포 시 Flyway 마이그레이션 자동 적용 보장
- [ ] 82. 헬스체크 기반 배포 성공 판정
- [ ] 83. 롤백 절차(이미지 태그 되돌리기) 문서
- [ ] 84. 시크릿 관리(러너 env/secret, 토큰·DB)
- [ ] 85. 기존 Python 봇 파이프라인과 분리/공존 확인
- [ ] 86. `ghcr-cleanup` 에 central-server 이미지 포함
- [ ] 87. CI/CD end-to-end 1회 검증
- [ ] 88. 차수 7 검증

## 차수 8 — 운영·문서·마무리 (89~100)

- [ ] 89. `.env.example`(central) 정리 + 환경변수 표
- [ ] 90. central README 실행/배포 절차 갱신
- [ ] 91. 유저용 에이전트 설치/실행 가이드(토큰 받기 → 실행)
- [ ] 92. 관리자 운영 가이드(정책·승인·모니터링)
- [ ] 93. SECURITY 최종 점검(토큰·SSRF·격리)
- [ ] 94. DEMO 다중 프로바이더 실연 절차 갱신
- [ ] 95. `AGENTS.md` 에 central-server 빌드/검증/배포 규약 추가
- [ ] 96. (선택) 모니터링/로그 수집 연동
- [ ] 97. 부하/공정성 수동 점검
- [ ] 98. 전체 e2e 시나리오 1회 통과(유저 질문→프로바이더 처리→응답)
- [ ] 99. PR 준비(`feat/remote-agent-byollm` → main 게이트 확인)
- [ ] 100. 최종 점검 & 태그/릴리스

---

# Phase 2 — 프로덕션화 (101~200)

## 차수 9 — Agent 배포/패키징 (101~115)

- [ ] 101. PyInstaller 단일 실행파일(Windows/macOS/Linux)
- [ ] 102. Agent Docker 이미지(호스트 Ollama 연결)
- [ ] 103. pip 설치 경로(`pip install` + 콘솔 스크립트)
- [ ] 104. 코드 서명/공증(macOS notarize, Windows SmartScreen 대응)
- [ ] 105. 버전 체크/자동 업데이트 채널
- [ ] 106. 첫 실행 온보딩(토큰 입력·Ollama 감지)
- [ ] 107. 서비스 등록(systemd/launchd/Task Scheduler) 가이드
- [ ] 108. 트레이 아이콘/상태 표시(선택)
- [ ] 109. 최소 권한 실행·샌드박스 원칙
- [ ] 110. 방화벽/오프라인 환경 안내
- [ ] 111. 설정 파일 저장(`~/.config/...`)·시크릿 보호
- [ ] 112. `ollama list` 자동 모델 감지·등록
- [ ] 113. 배포물 무결성(체크섬/서명)
- [ ] 114. 멀티 플랫폼 빌드 CI
- [ ] 115. 차수 9 검증

## 차수 10 — 중앙 서버 미완 기능 보강 (116~132)

- [ ] 116. `/provider-models` (contribution policy 모델 등록)
- [ ] 117. `/provider-limit` (모델별 일일/동시/시간 한도)
- [ ] 118. `/provider-scope` (역할/채널/요청종류 허용)
- [ ] 119. contribution policy → ProviderProfile 실제 반영(allowedRoles/channels/maxPrompt/failureRate)
- [ ] 120. RESTRICTED 모델 라우팅 완성(역할/채널/관리자 결합)
- [ ] 121. ProviderHealth 영속화 + 실패율 집계 → 라우팅 연동
- [ ] 122. 요청 큐 진짜 순차 대기(BUSY 하드캡 → 대기 큐)
- [ ] 123. 스트리밍 응답 end-to-end(chunk → Discord 점진 edit)
- [ ] 124. 멀티모달/이미지 입력(선택, 비전 모델)
- [ ] 125. 일일 사용량 리셋(타임존 기준 자정)
- [ ] 126. 사용자 쿨다운(요청 간격) 정교화
- [ ] 127. 길드 기본 모델/언어 설정
- [ ] 128. `/llm-settings` 통합 패널(분산 명령 묶기, 인터랙티브)
- [ ] 129. 명령 응답 JDA Embed 고도화(상태 badge·색상)
- [ ] 130. i18n(한/영) 메시지 리소스
- [ ] 131. AiRequest 영속화(상태 추적·감사 조회)
- [ ] 132. 차수 10 검증

## 차수 11 — 웹 대시보드 (133~146)

- [ ] 133. 대시보드 백엔드 API(Pool 상태·요청 로그·사용량·정책)
- [ ] 134. 관리자 인증(Discord OAuth2)
- [ ] 135. 서버 개요 화면
- [ ] 136. Provider Pool 대시보드(상태·기여량)
- [ ] 137. Provider 상세 화면
- [ ] 138. 요청/실패 로그 화면
- [ ] 139. 사용량·기여 통계 차트
- [ ] 140. 정책 설정 UI(채널/역할/프라이버시)
- [ ] 141. 실시간 상태(WS 또는 polling)
- [ ] 142. 프론트엔드 빌드/번들
- [ ] 143. 대시보드 Docker/compose 연동
- [ ] 144. 권한/세션 보안(CSRF·CORS)
- [ ] 145. 대시보드 테스트(API/UI)
- [ ] 146. 차수 11 검증

## 차수 12 — 관측성/운영 (147~160)

- [ ] 147. Micrometer 메트릭(요청 수·지연·provider별 처리/실패)
- [ ] 148. Prometheus 엔드포인트 노출
- [ ] 149. Grafana 대시보드(or 메트릭 시각화)
- [ ] 150. 구조적 로깅(JSON)·correlation/request id
- [ ] 151. 분산 추적(선택, OpenTelemetry)
- [ ] 152. 알림(provider 대량 오프라인·실패율 급증·DB 장애)
- [ ] 153. 헬스 상세(DB·WS 릴레이·pool) 표면화
- [ ] 154. 로그 보존/회전 정책
- [ ] 155. 에러 트래킹(Sentry) 연동
- [ ] 156. Postgres 백업/복구 정책
- [ ] 157. 장애 대응 런북
- [ ] 158. SLO/SLA 정의(선택)
- [ ] 159. 운영 점검 자동화(스크립트/스케줄)
- [ ] 160. 차수 12 검증

## 차수 13 — 신뢰성/스케일 (161~174)

- [ ] 161. 릴레이 backpressure/과부하 방어
- [ ] 162. 다수 동시 연결 부하 테스트
- [ ] 163. 라우팅 성능(대규모 풀) 벤치마크
- [ ] 164. 중앙 서버 수평 확장(WS 세션 공유/sticky) 검토
- [ ] 165. DB 커넥션 풀 튜닝
- [ ] 166. graceful shutdown(진행 요청 드레인)
- [ ] 167. 재시작 시 세션/요청 복구 정책
- [ ] 168. 타임아웃/재시도 표준화
- [ ] 169. provider 단위 circuit breaker
- [ ] 170. 다중 인스턴스 rate limit(분산)
- [ ] 171. 멱등성/중복 요청 방지
- [ ] 172. 카오스 테스트(연결 끊기·지연)
- [ ] 173. 용량 계획 문서
- [ ] 174. 차수 13 검증

## 차수 14 — 보안 심화/컴플라이언스 (175~188)

- [ ] 175. TLS/wss 종단(리버스 프록시) 실제 구성·검증
- [ ] 176. 토큰 회전/폐기 운영 흐름
- [ ] 177. 에이전트 인증 강화(선택 mTLS)
- [ ] 178. 프롬프트/로그 데이터 최소수집·보존 정책
- [ ] 179. 개인정보 처리방침/프라이버시 고지 확장
- [ ] 180. 권한 상승/정책 우회 침투 점검
- [ ] 181. 의존성 취약점 스캔(Dependabot/OWASP)
- [ ] 182. 컨테이너 이미지 취약점 스캔
- [ ] 183. 시크릿 스캐닝(커밋 가드)
- [ ] 184. 감사 로그 영속화·조회 UI
- [ ] 185. 프로바이더 동의/책임 고지 기록
- [ ] 186. 보안 리뷰(`/security-review`) 1회
- [ ] 187. 사고 대응(IR) 절차
- [ ] 188. 차수 14 검증

## 차수 15 — Python 봇 정리 & 베타 출시 (189~200)

- [ ] 189. 기존 Python 봇(요약/Q&A) 처리 결정: 이관 vs 공존 vs 폐기
- [ ] 190. (결정에 따라) 요약/Q&A 를 central 으로 흡수 또는 분리 운영
- [ ] 191. 중복/충돌 명령 정리(두 봇 공존 시)
- [ ] 192. 베타 테스트 서버·프로바이더 2~3명 온보딩
- [ ] 193. 피드백 수집 채널/이슈 트리아지
- [ ] 194. 베타 버그 수정 1차
- [ ] 195. 사용 가이드/FAQ
- [ ] 196. 데모 영상/스크린샷
- [ ] 197. 릴리스 노트/CHANGELOG
- [ ] 198. 성능/공정성 최종 점검
- [ ] 199. `main` 머지 & 운영 배포
- [ ] 200. 최종 점검 & 정식 릴리스 태그
