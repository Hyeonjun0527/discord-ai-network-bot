# 향후 작업 100선 — 로드맵 체크리스트

> 상태: `[ ]` 미착수 · `[x]` 완료 · 난이도 `S`(작음) / `M`(보통) / `L`(큼)
> 실제 코드(`src/`, `dashboard/`, `deploy/`, `.github/`)를 도메인별로 조사해 도출. 완료된 CODE_REVIEW 100개 / CI·CD·자동배포는 제외한 "앞으로 할 일"입니다.

## 🤖 핵심 봇 기능 (1~11)

- [x] 1. 예약 알림 영속화: `reminders` 테이블 + 부팅 시 재예약 루프 (#26) · L
- [x] 2. `/remind` 확장: 임의 텍스트·절대 시각·반복(매일/매주), 60분 제한 해제 · M
- [x] 3. `/reminders` 관리 명령: 대기 중 알림 조회·취소 · S
- [ ] 4. 메시지 우클릭 컨텍스트 메뉴: '번역'·'요약'·'이 메시지로 질문' · M
- [ ] 5. `/summarize` 결과를 새 스레드로 분리 게시하는 옵션 · M
- [ ] 6. 스레드 내 봇 멘션 시 해당 스레드 맥락만으로 응답 · M
- [ ] 7. 슬래시 파라미터 자동완성(language·target_language·prompt_type·since) · S
- [ ] 8. 답장(reply) 맥락 인식: 봇 메시지에 답장하면 대화 이어가기 · M
- [ ] 9. 리액션 트리거 액션: 📝/🌐 이모지로 요약·번역 (on_raw_reaction_add) · M
- [ ] 10. DM 모드 대화 기억: DM 경로에도 chat_history 저장/활용 · M
- [ ] 11. `/digest` 명령: 지정 기간 활동을 액션아이템·결정사항 중심 회고로 · M

## 🧠 LLM / 프롬프트 (12~23)

- [ ] 12. 멀티모달 이미지 입력: `generate`에 이미지 블록 인자 + 3개 제공자 구현 (#54) · L
- [ ] 13. Discord 첨부 이미지 다운로드·base64 인코딩·크기/타입 검증 헬퍼 · M
- [x] 14. 비전 게이트를 모델명 하드코딩(llava/bakllava)에서 제공자별 capability 판정으로 · M
- [ ] 15. `LLMProvider`에 Gemini 추가 + `GeminiClient` 어댑터 · L
- [ ] 16. 스트리밍 응답: `generate_stream` + Discord 메시지 점진 edit · L
- [ ] 17. 토큰 사용량 집계: 제공자 응답 usage 파싱 → `UsageLog` 저장 · M
- [x] 18. 제공자·모델별 단가 테이블 기반 호출 비용 계산 + `/stats` 표시 · M
- [ ] 19. 서버별 일일 토큰/비용 상한 설정 + 초과 시 차단 · M
- [ ] 20. 함수/툴 호출(OpenAI tools, Anthropic tool_use)로 메시지 검색 툴 노출 · L
- [x] 21. `_with_retry`: 4xx 즉시 실패, 429/5xx만 재시도 · S
- [x] 22. OpenAI/Anthropic 하드코딩 파라미터(temperature·max_tokens·system) 설정값 연동 · M
- [ ] 23. 프롬프트 회귀 평가 하니스: 고정 트랜스크립트 골든 출력 채점 · L

## 🗄️ 데이터 / 스토리지 (24~33)

- [ ] 24. `storage.py`를 aiosqlite 기반 단일 영속 연결로 전환 (#34) · L
- [x] 25. `_connect()`에 busy_timeout/synchronous PRAGMA로 'database is locked' 방어 · S
- [ ] 26. 버전 추적형 마이그레이션 프레임워크(schema_version + 순차 migration) · L
- [x] 27. usage_log·chat_history 시간 기반 retention 정리 백그라운드 태스크 · M
- [x] 28. 백업 복원 스크립트(`scripts/restore.sh`) + 백업→복원 왕복 테스트 · M
- [ ] 29. 대시보드 중복 DB 접근 로직을 공용 storage 레이어로 통합 · M
- [x] 30. 대시보드 guild_config PUT의 컬럼 누락(스키마 drift) 수정 · M
- [ ] 31. `DATABASE_URL` postgresql:// 지원을 위한 스토리지 백엔드 추상화 · L
- [x] 32. 쿼리 경로 인덱스 점검(EXPLAIN QUERY PLAN 기반 보강) · M
- [x] 33. 주기적 VACUUM / WAL checkpoint 운영 작업으로 파일 비대화 방지 · S

## 🔐 보안 / 프라이버시 (34~44)

- [ ] 34. 대시보드 JWT를 httpOnly+Secure 쿠키로 전환 (#38) · L
- [ ] 35. JWT 서명키와 Fernet 암호화키 분리(독립 회전 가능) · M
- [x] 36. `SECRET_KEY` 기본값이면 프로덕션 기동 하드 실패 처리 · S
- [ ] 37. 저장된 API 키 재암호화 마이그레이션(시크릿 회전 지원) · M
- [x] 38. 프롬프트 인젝션 방어: 트랜스크립트/질문 본문 구분자 래핑·role 토큰 무력화 · M
- [x] 39. 관리자 작업 감사 로그(audit_log) 테이블 + 변경 이력 기록 · M
- [x] 40. 사용자 데이터 삭제(`/forget-me`) 명령 + 백엔드 삭제 엔드포인트 · M
- [ ] 41. usage_log.error의 PII/시크릿 마스킹 · S
- [x] 42. CORS 와일드카드(allow_methods/headers='*') 제거 + 허용목록 명시 · S
- [x] 43. 보호 대상 API 전체에 레이트리밋 일괄 적용(미들웨어) · S
- [ ] 44. JWT 무효화 메커니즘(jti 블랙리스트 또는 단기 access+refresh) · L

## 📈 신뢰성 / 관측성 (45~55)

- [x] 45. 구조화 JSON 로깅 도입(basicConfig → 컨텍스트 바인딩) · M
- [x] 46. correlation_id(interaction.id) 발급 + 전 로그/usage 전파 · M
- [ ] 47. Prometheus 메트릭 익스포터(명령 카운터·지연·에러율) · M
- [ ] 48. 봇 프로세스 헬스/메트릭 HTTP 서버(/healthz·/readyz·/metrics) · M
- [x] 49. graceful shutdown(SIGTERM/SIGINT) + async 진입점 전환 · M
- [ ] 50. `ConfigStore.close()` 정리 루틴 + 종료 시 호출 · S
- [x] 51. fire-and-forget 태스크 추적 + 예외 핸들러 부착 · M
- [x] 52. LLM provider별 서킷 브레이커 도입 · L
- [x] 53. `notify_developer` 알림 레이트리밋·중복 억제(dedup) · M
- [x] 54. on_disconnect 오탐 제거(on_resumed + 지속 단절 임계 알림) · M
- [ ] 55. Sentry(선택적) 에러 트래킹 통합 · M

## 🧪 테스트 / 품질 (56~67)

- [ ] 56. `discord.Interaction` mock 픽스처를 conftest에 추가 · M
- [ ] 57. `/summarize`·`/ask`·`/chat` 핸들러 통합테스트(쿨다운·권한·에러 분기) · L
- [ ] 58. on_message 멘션 플로우 테스트(ask vs summarize 분기, DM 쿨다운) · L
- [ ] 59. on_reaction_add 피드백·on_guild_join 핸들러 테스트 · M
- [ ] 60. OpenAI/Anthropic `_generate_sync` 응답 파싱·에러 테스트 · M
- [ ] 61. `_with_retry` 재시도/백오프 단위테스트 · S
- [ ] 62. UI View/Modal 콜백 상호작용 테스트 · L
- [ ] 63. e2e_scenarios.md 5개 시나리오를 자동 e2e 테스트로 전환 · L
- [ ] 64. load_test를 Ollama mock 기반 CI 실행 가능하게 재작성 · M
- [ ] 65. 카오스/폴트 인젝션 테스트(타임아웃·연결거부·부분응답) · M
- [x] 66. CI 커버리지 게이트(--cov-fail-under 임계) · S
- [x] 67. mypy strict 단계적 도입 + pre-commit 훅 · M

## 🚀 배포 / 운영 (68~77)

- [x] 68. deploy `up --wait` 실패 시 직전 sha 이미지로 자동 롤백 · M
- [x] 69. 배포 직전 현재 실행 이미지 태그 캡처(롤백 기준값) · S
- [x] 70. Verify를 실제 봇 READY 스모크테스트로 강화 · M
- [x] 71. 배포 실패 시 DEVELOPER_USER_ID DM/Discord 웹훅 알림 · M
- [x] 72. 스테이징 환경 분리(compose.staging.yml + 컨테이너명/DEPLOY_DIR 분기) · L
- [ ] 73. 멀티 호스트 배포(러너 라벨/매트릭스 + DEPLOY_DIR 분기) · L
- [x] 74. 백업 cron 경로를 DEPLOY_DIR 기준으로 정정 + 사이드카 컨테이너화 · M
- [x] 75. 백업 산출물 오프호스트(원격/오브젝트 스토리지) 복제 · M
- [x] 76. logrotate를 Docker json-file 운영에 맞게 정리 + 파일/컨테이너 로그 정책 통일 · S
- [x] 77. GHCR 오래된 sha 태그 정리 워크플로(롤백용 N개 보존) · M

## 🖥️ 대시보드 (78~86)

- [x] 78. 피드백 열람 API(GET /feedback) + 명령별 만족도 차트 페이지 · M
- [x] 79. 길드 설정 편집 권한을 멤버십이 아닌 Administrator 권한으로 강화 · M
- [ ] 80. 설정 UI에 auto_summary_interval 컨트롤 추가(백엔드 모델 확장) · M
- [x] 81. 모델명 입력을 /api/models 기반 드롭다운으로 교체 · S
- [ ] 82. usage_log 토큰/비용 컬럼 + stats 엔드포인트·차트 반영 · L
- [x] 83. 명령별 평균 응답시간(latency) 막대 차트 · M
- [x] 84. 통계 기간 필터(7/30/90일) + 백엔드 daily 쿼리 파라미터화 · M
- [x] 85. JWT 만료 임박 시 자동 갱신/refresh 처리 · M
- [x] 86. CI에 프론트 lint·타입체크·build + 백엔드 pytest 잡 추가 · M

## 🌐 UX / 국제화 (87~94)

- [ ] 87. 번역 카탈로그 도입 + 모든 View/Embed에 길드 언어 주입 (#70 i18n) · L
- [ ] 88. app_commands locale_str로 명령 설명/옵션 클라이언트 로케일 현지화 · L
- [x] 89. 언어 설정을 자유 텍스트 모달 → 지원 언어 Select 드롭다운으로 · M
- [ ] 90. 온보딩 강화: 제공자 미설정 감지 + '지금 설정하기' 버튼 흐름 · M
- [ ] 91. API 키 미설정 에러의 잘못된 메뉴 경로 수정 + 버튼형 안내 · S
- [ ] 92. `_make_error_embed`에 원인별 복구 힌트 + '다시 시도' 버튼 · M
- [ ] 93. 긴 응답 UX 통일(/ask·/summarize·멘션도 프리뷰+DM 버튼) · M
- [ ] 94. `/usage` 명령(사용량·쿨다운·한도 안내) + summary_limit 클램프 고지 · M

## 📚 문서 / 거버넌스 (95~100)

- [x] 95. README 명령어 표 전면 동기화(누락된 /remind·/stats·/search·/config 하위명령 등) · M
- [x] 96. LICENSE(MIT 전문) + SECURITY.md + CONTRIBUTING.md(Conventional Commits) 작성 · M
- [x] 97. 환경변수 SSOT 검증 CI: .env.example ↔ 코드 os.getenv 키 집합 대조 · M
- [x] 98. 루트 AGENTS.md(SSOT) 신설 + CLAUDE.md 포인터화 · S
- [x] 99. 이슈/PR 템플릿 + ADR 디렉터리(핵심 설계 결정 기록) · S
- [x] 100. 문서↔코드 drift 검증 CI(README 커맨드 ↔ bot.py 등록 대조) + ARCHITECTURE/CHANGELOG 정합 · M
