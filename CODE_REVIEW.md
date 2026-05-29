# 코드 리뷰 — 100가지 문제점 체크리스트

> 상태: `[x]` 수정 완료 · `[ ]` 미착수 · `[~]` 보류(아키텍처 대규모/제품 결정 필요)
>
> 잔여 38개 항목은 적대적 검증 워크플로(완료-판정 재검증 + 신규코드 리뷰 + mypy 분류)로
> 교차 확인했습니다. 검증 중 `auto_summary_interval` 경계 불일치(읽기 시 크래시) 회귀를
> 발견·수정했고, 검증이 반증한 #20/#23/#69/#71/#80/#89도 실제로 보강했습니다.
> 최종: `ruff` 통과 · `mypy src/` 0 오류 · `pytest` 112개 통과(테스트 +48).

---

## 🔴 CRITICAL — 즉시 크래시 (1~8)

- [x] 1. `bot.py` — `from discord.ext import tasks` 누락 → `@tasks.loop` 에서 NameError, 봇 시작 즉시 크래시
- [x] 2. `bot.py` — `get_translation` 미임포트 (`from .cache import` 에 없음) → translate 명령 런타임 크래시
- [x] 3. `bot.py` — `set_translation` 미임포트 → translate 명령 런타임 크래시
- [x] 4. `bot.py` — `build_chat_with_history_prompt` 미임포트 → /chat 히스토리 경로 런타임 크래시
- [x] 5. `bot.py` — `HelpView` 미임포트 (`from .ui import` 에 없음) → /help 명령 런타임 크래시
- [x] 6. `bot.py` — `ChannelSelectView` 미임포트 → /summarize-channels 런타임 크래시
- [x] 7. `bot.py` — `build_search_result_prompt` 미임포트 → /search 런타임 크래시
- [x] 8. `bot.py` — `build_image_analysis_prompt` 미임포트 → 이미지 분석 런타임 크래시

---

## 🟠 논리 버그 (9~28)

- [x] 9.  `bot.py` `_check_cooldown` 내부에 `from time import perf_counter as _pc` 중복 임포트 (모듈 상단에 이미 있음)
- [x] 10. `bot.py` `help_command` 내부에 `import os as _os` — 모듈 상단으로 이동해야 함
- [x] 11. `bot.py` `export_command` 내부에 `import io` — 모듈 상단으로 이동해야 함
- [x] 12. `bot.py` `on_error` 내부에 `import sys` — 모듈 상단으로 이동해야 함
- [x] 13. `bot.py` `translate_command` 캐시 히트 경로: defer() 후 `response.is_done()` 항상 True → else 브랜치 데드 코드
- [x] 14. `bot.py` `translate_command` 비캐시 경로: 동일 문제, else 브랜치 데드 코드
- [x] 15. `bot.py` `chat_command` LongResponseView 경로: if/else 두 브랜치 모두 `followup.send` — else 데드 코드
- [x] 16. `bot.py` `on_message` 1241번 줄 bot 체크 후 1245번 줄 `not message.author.bot` 조건 항상 True — 중복 체크
- [x] 17. `bot.py` 이미지 분석: `async with message.channel.typing(): pass` — 타이핑 인디케이터 즉시 종료, no-op
- [x] 18. `bot.py` `/summarize`, `/ask` 에 쿨다운 체크 없음 (translate/chat에는 있음) — 불일관성 → 양쪽 모두 `_check_cooldown` 추가
- [x] 19. `bot.py` `_parse_since` 값 0 허용 (`0h`, `0m`, `0d`) → timedelta(0) → 결과 없음
- [x] 20. `bot.py` DM 핸들러에 쿨다운 없음 — 스팸 가능 → `_check_cooldown(None, ...)`가 무조건 None 반환하던 no-op를 DM 센티널 `_DM_COOLDOWN_GUILD=0`로 실제 동작하게 수정 (검증이 반증 → 보강)
- [x] 21. `bot.py` `_summarize_one` 에서 `settings.max_context_chars // len(channel_ids)` — channel_ids 비어 있으면 ZeroDivisionError
- [x] 22. `bot.py` `on_message` 에서 `bot.process_commands(message)` 가 `bot.user is None` 체크보다 먼저 실행
- [x] 23. `bot.py` `run_ask` 데드 else 브랜치 (`original_response()`) → 항상 defer되므로 도달 불가 → followup.send(wait=True)로 정리 (검증이 반증 → 제거)
- [x] 24. `bot.py` 커스텀 프롬프트 길이 제한 없음 — 거대한 프롬프트로 DoS 가능
- [x] 25. `bot.py` `auto_summary_task` 매 1분마다 폴링 — `get_guilds_with_auto_summary()`로 설정된 길드만 단일 쿼리 조회, 없으면 조기 반환
- [~] 26. `bot.py` `/remind` 봇 재시작 시 예약된 알림 소실 — 영속성 없음 → 보류: reminders 테이블 + 마이그레이션 + 부팅 시 재예약 루프가 필요한 다중 컴포넌트 변경
- [x] 27. `bot.py` `export_command` 텍스트만 내보냄, 첨부파일/임베드 누락 → 첨부 URL·임베드 제목/설명 포함하도록 수정
- [x] 28. `bot.py` `on_guild_join` 권한 있는 채널 없으면 환영 메시지 로그 없이 무시 → `sent` 플래그 + warning 로그 추가

---

## 🟡 메모리 누수 / 성능 (29~35)

- [x] 29. `bot.py` `_cooldowns` dict 무한 증가 — 만료 항목 정리 없음
- [x] 30. `bot.py` `_last_summaries` dict 무한 증가 — 정리 메커니즘 없음
- [x] 31. `bot.py` `_tracked_messages` dict 무한 증가 — 정리 메커니즘 없음
- [x] 32. `storage.py` `chat_history` 행 무한 축적 — 사용자별 200행 상한 + INSERT 시 prune
- [x] 33. `storage.py` SQLite WAL 모드 미설정 — 동시 읽기/쓰기 성능 저하
- [~] 34. `storage.py` 매 작업마다 새 연결 생성 — 연결 재사용 없음 → 보류: 스레드 안전 풀/aiosqlite 전환이 모든 메서드에 영향 (파일 DB+WAL에서 정상 동작은 함, 부하 시 비효율)
- [x] 35. `bot.py` `_summarize_one` 채널마다 새 LLM 클라이언트 생성 — 루프 밖으로 1회 생성하도록 호이스팅

---

## 🔐 보안 (36~44)

- [x] 36. `settings.py` `secret_key == "change-me-in-production"` 기본값 유지 시 경고 없음
- [x] 37. `dashboard/backend/auth.py` OAuth2 state 파라미터 없음 — CSRF 취약점
- [~] 38. `dashboard/frontend/lib/auth.ts` JWT를 localStorage에 저장 — XSS 취약점 → 보류: httpOnly 쿠키 전환은 backend Set-Cookie + CORS/credentials + Next 프록시 + 프론트 인증 흐름 전반 변경
- [x] 39. `dashboard/backend/main.py` 길드 멤버십 검증 없음 — 인증된 사용자가 임의 길드 설정 접근 가능
- [x] 40. `dashboard/backend/main.py` API 응답에 `api_key_encrypted` 포함 — 암호화 키 노출
- [x] 41. `dashboard/backend/main.py` API 속도 제한 없음
- [x] 42. `bot.py` 페르소나 필드 — 프롬프트 인젝션 가능 → `_sanitize_persona`로 제어문자 제거·개행 축약(가짜 role 구분자 차단)
- [x] 43. `bot.py` 커스텀 프롬프트 — 동일하게 프롬프트 인젝션 가능
- [x] 44. `bot.py` `_make_error_embed` 예외 메시지 그대로 노출 — 내부 정보 유출 가능

---

## 🗄️ 데이터 / 스토리지 (45~50)

- [x] 45. `storage.py` `get_chat_history` 에 `guild_id`, `channel_id` 파라미터 받지만 쿼리에서 무시됨 — 전체 사용자 히스토리 반환
- [x] 46. `storage.py` `feedback` 테이블에 `(message_id, user_id)` 유니크 제약 없음 — 중복 피드백 저장 가능
- [x] 47. `storage.py` `PRAGMA foreign_keys = ON` 미설정 — FK 제약 비활성
- [x] 48. `storage.py` `chat_history` 에 `(guild_id, channel_id, user_id)` 복합 인덱스 없음
- [x] 49. `storage.py` `_migrate` 에서 `feedback` 테이블 생성이 별도 트랜잭션 — 원자성 문제
- [x] 50. `storage.py` 대량 결과 페이지네이션 없음 → `get_chat_history`에 `offset` 추가 + 유효성 검사

---

## 🤖 LLM / 프롬프트 (51~64)

- [x] 51. `llm.py` `_with_retry` — `max_attempts=0` 이면 AssertionError (last_exc is None)
- [x] 52. `llm.py` `_with_retry` 재시도 딜레이가 선형 (1초, 2초) — 지수 백오프 권장
- [x] 53. `llm.py` `AnthropicClient` 기본 모델 `claude-3-haiku-20240307` — 구형 모델, `claude-haiku-4-5-20251001` 권장
- [~] 54. `prompts.py` `build_image_analysis_prompt` — URL을 텍스트로 전달, 멀티모달 실제 이미지 데이터 아님 → 보류: 3개 제공자 모두 base64 이미지 블록을 받도록 `generate` 인터페이스 확장 + 첨부 다운로드 필요
- [x] 55. `prompts.py` `build_summarize_prompt` — 섹션 헤더(`핵심 요약` 등) 언어 무관하게 항상 한국어
- [x] 56. `prompts.py` `build_translate_prompt` — 원본 언어 미지정, LLM이 추측해야 함
- [x] 57. `prompts.py` `build_chat_with_history_prompt` — 역할 레이블 `"사용자"`, `"AI"` 언어 무관 한국어 하드코딩
- [x] 58. `prompts.py` `detect_language_from_transcript` — 프랑스어/독일어/스페인어 미지원 (레이블은 있음)
- [x] 59. `llm.py` `OpenAIClient` — system 메시지 없이 user 메시지만 전송, 품질 저하
- [x] 60. `llm.py` `AnthropicClient` — `system` 파라미터 미사용
- [x] 61. `llm.py` `OllamaClient` — `temperature: 0.2`, `num_ctx: 8192` 하드코딩 → `OLLAMA_TEMPERATURE`/`OLLAMA_NUM_CTX` 설정 가능화
- [x] 62. `llm.py` `OllamaManager.pull_model` — `ollama` 바이너리 PATH 가정 → `shutil.which` 체크 + 명확한 오류 메시지
- [x] 63. `llm.py` `OllamaManager._list_sync` — `except Exception: return []` 모든 오류 무음 무시
- [x] 64. `llm.py` 오류 응답 payload 로그·사용자 노출 → 세 제공자 모두 상세는 `logger.debug`로만, 사용자/로그엔 상태코드만

---

## 🖥️ UI / Discord (65~74)

- [x] 65. `ui.py` `ViewCtx` — `@dataclass` frozen 아님, 불변이어야 할 컨텍스트가 변경 가능
- [x] 66. `ui.py` `_APIKeyModal` 타임아웃 미설정
- [x] 67. `bot.py` embed 필드 value `[:1000]` 잘림 — 말줄임표 없이 잘려서 사용자 혼란
- [x] 68. `bot.py` `on_guild_join` 환영 메시지에 `/settings` 설정 안내 없음 → `/settings` 필드 추가
- [x] 69. `bot.py` `/stats` 결과에 기간(날짜 범위) 표시 없음 → `get_stats`에 MIN/MAX(created_at) 추가, 실제 `시작 ~ 종료` 범위 표시 (검증이 반증 → 보강)
- [~] 70. `ui.py` `HelpView` 버튼 레이블 한국어 하드코딩 — 다국어 미지원 → 보류: 번역 카탈로그 도입 + 모든 View에 길드 언어 주입, 데코레이터 레이블 동적화 필요(제품 결정)
- [x] 71. `bot.py` `/summarize` 결과에 반응 이모지 트래킹 없음 (ask에는 있음) → 캐시/라이브 경로 모두 `_track_for_feedback` 헬퍼로 통일 (검증이 캐시 경로 누락 반증 → 보강)
- [x] 72. `bot.py` `LongResponseView` DM 전송 실패 시 사용자에게 안내 없음 → Forbidden/HTTPException 모두 ephemeral 안내
- [x] 73. `bot.py` embed 필드 value가 1024자 초과 가능 — Discord 제한 초과 시 500 에러
- [x] 74. `bot.py` `search_command` 최대 20개 결과 하드코딩 → `MAX_SEARCH_MATCHES` 상수화, embed 레이블에 반영

---

## ⚙️ 코드 품질 (75~85)

- [x] 75. `bot.py` `bot.loop.create_task()` — discord.py 최신에서 deprecated, `asyncio.create_task()` 권장
- [x] 76. `models.py` `UsageLog.latency_ms: int | None` — 항상 계산되므로 `int`여야 함
- [x] 77. `models.py` `GuildConfig` Phase 3 주석 — 코드베이스 범위 참조, 삭제 권장
- [~] 78. `bot.py` `COOLDOWN_SECONDS = 10` 글로벌 상수 — 서버별 설정 불가 → 보류: guild_config 컬럼 추가 + 마이그레이션 + UI + `_check_cooldown` 시그니처 변경(다중 파일)
- [x] 79. `bot.py` `_parse_summarize_sections` 정규식 — 비표준 LLM 응답에 취약 → 미사용 데드 코드로 확인되어 제거
- [x] 80. `bot.py` `_split_discord_text` — 코드 블록 중간 분할 시 Markdown 깨짐 → 코드블록 내 초과 라인도 펜스로 감싸고 마지막 조각은 소스 종료 펜스로 닫도록 수정 (검증이 초과-라인 경로 반증 → 보강)
- [x] 81. `bot.py` `_send_channel_chunks` — 속도 제한 없음 → 청크 간 0.5초 sleep
- [x] 82. `bot.py` `create_bot` 여러 번 호출 시 commands 중복 등록 가능 → 검증 결과 비이슈(매 호출이 독립 인스턴스의 별도 트리에 등록, 공유 전역 트리 없음)
- [x] 83. `bot.py` 모듈 레벨 `_cooldowns` dict — 테스트 간 상태 오염 → `reset_cooldowns()` 헬퍼 추가 + 테스트 setUp/tearDown에서 호출
- [x] 84. `models.py` `GuildConfig.model` 빈 문자열 허용 — 유효성 검사 없음
- [x] 85. `settings.py` `ollama_base_url` 빈 문자열 허용 가능 (env에 공백만 있을 때)

---

## 🐳 Docker / 배포 (86~90)

- [x] 86. `Dockerfile` `HEALTHCHECK` 에서 `/app/scripts/healthcheck.py` 참조하나 `scripts/` 디렉토리 COPY 없음
- [x] 87. `Dockerfile` `EXPOSE 8000` — 봇은 HTTP 서버 아님, 오해 소지
- [x] 88. `docker-compose.yml` dashboard 프로필에 `dashboard/backend/.env` 필요 — 문서 없음 → README_EN에 `--profile dashboard` 사용법 + 서비스명(`bot`) 불일치 수정
- [x] 89. `deploy/install.sh` 경로 `/opt/discord-assistant` 하드코딩 → systemd 유닛이 경로를 하드코딩하여 `INSTALL_DIR` 무력화됨을 확인, 유닛 설치 시 `sed`로 `INSTALL_DIR`/`BOT_USER` 치환하도록 수정 (검증이 반증 → 보강)
- [x] 90. `scripts/backup.sh` 백업 파일 무결성 검증 없음 → `sqlite3 .backup` + `PRAGMA integrity_check`, cp 폴백 시 `-wal`/`-shm` 동반 복사

---

## 🧪 테스트 (91~95)

- [x] 91. Phase 3 신규 명령어 테스트 없음 → `test_storage_phase3.py`(stats/feedback/auto_summary/pagination) + `test_bot_phase3.py`(`_parse_since`/`_sanitize_persona`/쿨다운/`_get_float`)
- [x] 92. 번역 캐시 함수 테스트 없음 → `test_cache_translation.py` (set/get/TTL/eviction/대소문자/clear/purge)
- [x] 93. `auto_summary_task` 테스트 없음 → 구동 쿼리 `get_guilds_with_auto_summary` + 최소 경계/레거시 클램프 테스트
- [x] 94. `pyproject.toml` pytest `addopts` 에 `--cov` 포함 → 기본 실행에서 제거(opt-in), CI에서만 명시 호출
- [x] 95. 실제 SQLite 파일 기반 통합 테스트 없음 → `_FileStoreCase`로 파일 DB 재연결/PRAGMA(WAL·FK) 통합 테스트 (`:memory:` 연결별 분리 한계도 문서화)

---

## 📝 기타 / 문서 (96~100)

- [x] 96. `CHANGELOG.md` 버전 번호(시맨틱 버전) 없음 → SemVer/Keep-a-Changelog 명시 + `[0.1.0] - 2026-05-29` 릴리스 섹션
- [x] 97. `prompts.py` `_LANGUAGE_LABELS` 에 `"kr"`/`"ko"`, `"jp"`/`"ja"` 중복 항목
- [x] 98. `ui.py` `_language_label` 함수가 `prompts.py` 의 `_LANGUAGE_LABELS` 와 동일 데이터 중복 정의
- [x] 99. `.env.example` 에 `OLLAMA_BASE_URL` 키가 두 번 등장 (13번, 25번 줄)
- [x] 100. `models.py` `GuildConfig.auto_summary_interval` 최솟값이 모델에서 강제되지 않음 (스토리지에서만)

---

## 회귀 수정 (검증 중 발견)

- [x] **R1.** `auto_summary_interval` 경계 불일치: 모델은 `>=5`를 강제하나 `set_auto_summary_interval`은 `>=1`만 검사 → 1~4 값이 저장된 레거시 행을 `get_guild_config`가 읽을 때 `ValueError`로 크래시(`/summarize`·`/ask`·`/chat`에서 미포착 전파, `auto_summary_task` 매분 크래시). 최소값 상수(`MIN_AUTO_SUMMARY_INTERVAL_MINUTES=5`)로 모델·스토리지 정합화, 쓰기 시 명확한 한국어 오류, 읽기 시 `_normalize_interval`로 클램프(읽기는 절대 raise 안 함).
- [x] **R2.** DM 핸들러 비-LLM 예외가 원문 그대로 사용자에게 노출 → guild 경로와 동일하게 `UserFacingError`/`LLMError`만 노출, 그 외는 일반 메시지 + `logger.exception`.
- [x] **R3.** `settings._get_float` 가 `nan`/`inf` 통과 → `math.isfinite` 거부 + 상한(`maximum`) 지원, `OLLAMA_TEMPERATURE`에 `0.0~2.0` 적용.
