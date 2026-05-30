# 실서비스 가동까지 — 100 단계 체크리스트

> 전제: Kotlin 중앙 서버([`ROADMAP_CENTRAL_SERVER.md`](./ROADMAP_CENTRAL_SERVER.md)) 131/131 완료.
> 목표: **유저(프로바이더)가 실제로 풀에 참여해 `/ask` 가 진짜로 응답하고, Docker/CI/CD 로
> 배포**되는 상태. 안전 루프: 구현 → 빌드/테스트 → 커밋 → 다음.
> 상태: `[ ]` 미착수 · `[x]` 완료.

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
