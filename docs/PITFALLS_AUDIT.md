# 함정 감사 보고서 — discord-assistant

> 14개 영역 병렬 finder → 독립 verifier 적대적 검증. 이후 심각도 웨이브별 수정.


## 요약

- 확정 **137개** · ✅ 수정 **102** · ⏸ 보류 **34** · ❓ 불확실 10
- 검증: ruff/mypy 통과, pytest 994 passed/1 skipped 클린 종료, 대시보드 32 passed
- 보류는 아키텍처 재설계/crypto 마이그레이션/회귀 위험 항목 + 타 위치에서 이미 해결된 중복.


---

## 🟠 HIGH (20)


### 이벤트 핸들러 (`bot-events`)

**1. @멘션 응답 경로에 쿨다운 검사가 전혀 없음 (LLM 비용 폭증/스팸)**  
`src/discord_assistant/bot.py:3069-3171` · 리소스/비용 남용 (rate-limit 누락) · ✅ 수정: 멘션 처리 진입부(user_id 확정 직후)에 다른 경로와 동일하게 _check_cooldown 을 호출하고, 쿨다운 중이면 조용히 return.  
- 문제: on_message 의 @멘션 처리 경로는 다른 모든 LLM 진입점(슬래시 명령/DM/답장/리액션/컨텍스트 메뉴)이 호출하는 _check_cooldown 을 한 번도 호출하지 않는다. 멘션마다 곧바로 _enforce_token_budget 후 _collect_transcript + llm.generate 가 돌고, 이미지 첨부 시에는 비전 분석(3097-3122)까지 무제한으로 실행된다.
- 수정안: 멘션 경로 진입부(3072 `started = perf_counter()` 직후, guild_id/user_id 확정 후)에서 다른 경로와 동일하게 `remaining = _check_cooldown(guild_id, user_id)` 를 호출하고, None 이 아니면 짧은 안내(또는 조용히 return) 후 종료한다.

**2. 리액션·멘션·답장 경로가 allowed_role_id(명령 사용 권한 역할)를 우회**  
`src/discord_assistant/bot.py:3032-3067, 3082-3171, 3357-3420` · 보안/접근제어 (권한 우회) · ✅ 수정: _member_has_allowed_role 헬퍼 추가 후 @멘션(message.author)·봇 답장(message.author)·리액션(payload.member/guild.get_member) 경로에 역할 검사를 추가해 권한 없으면 조용히 ret  
- 문제: 슬래시 명령 /summarize(1441)·/ask(1614)는 `if not _has_allowed_role(interaction, config.allowed_role_id)` 로 사용 권한 역할을 강제하지만, @멘션 ask/summarize·봇 답장 chat·리액션(📝/🌐) 경로는 이 검사를 전혀 하지 않는다.
- 수정안: 세 경로 모두 LLM 호출 전 사용자 역할을 검사한다. 멘션/답장은 message.author.roles 를, 리액션은 payload.member(또는 guild.get_member(payload.user_id)).roles 를 사용해 allowed_role_id 를 확인하고, 권한이 없으면 조용히 return 한다.


### 수명주기/동시성 (`bot-lifecycle`)

**3. 토큰 일일 상한 검사가 check-then-act 레이스 — 동시 요청이 상한을 크게 초과 가능**  
`src/discord_assistant/bot.py:1033-1034, 1512-1514, 1557-1561` · race-condition · ⏸ 보류: 토큰 일일 상한 check-then-act 레이스 수정은 길드별 asyncio.Lock 을 _enforce_token_budget 검사~_record_usage 기록 구간(LLM 호출 포함)에 걸쳐 잡아야 하는데, 이는 같은 길드 동시 요청을 직렬화하는 동작/성능 변경이고 호출지점 ~1  
- 문제: 토큰 일일 상한이 read-modify-write 가 아니라 read-then-(LLM호출)-then-write 구조다. 검사 시점과 기록 시점 사이에 가장 긴 await(LLM 호출)가 끼어 있어, 동시에 들어온 여러 명령이 전부 '아직 상한 미만'으로 읽고 통과한다. 예약(reservation)이나 락이 없다.
- 수정안: 길드별 asyncio.Lock 으로 _enforce_token_budget 검사~_record_usage 기록 구간을 직렬화하거나(가장 단순), 호출 직전에 예상 토큰을 선차감(reservation)해 log_usage 에 먼저 기록한 뒤 실제값으로 보정한다. 더 견고하게는 get_today_token_usage 를 DB 레벨 조건부 UPSERT 로 원자화한다.


### 리마인더 (`bot-reminders`)

**4. 취소(/reminders cancel)가 라이브 sleep 태스크를 멈추지 못해 알림이 그대로 발송됨**  
`src/discord_assistant/bot.py:2090-2102, 1320-1337, 2061` · correctness/concurrency · ✅ 수정: reminder_id->Task 레지스트리(_reminder_tasks) 추가; _schedule_reminder 가 자신을 등록/해제, /reminders cancel 경로가 살아있는 태스크를 cancel; _deliver_reminder 시작부에서  
- 문제: /reminders cancel:<ID> 는 DB 행만 지우고, /remind 가 만든 in-memory sleep 태스크를 취소하지 않는다. _deliver_reminder 는 발송 직전 DB 상태(행 삭제/sent)를 재확인하지 않아 취소된 알림도 그대로 보낸다.
- 수정안: reminder_id->Task dict 를 유지해 취소/발송 완료 시 해당 태스크를 cancel + 제거한다. 추가 방어로 _deliver_reminder 시작부에서 store 로 행이 아직 존재하고 sent=0 인지 재확인 후 전송한다(취소/중복 동시 방어).

**5. on_ready 재발화(재연결)마다 reschedule 무가드 실행 → 미발송 리마인더 중복 재예약(중복 DM)**  
`src/discord_assistant/bot.py:2896-2907, 1356-1371` · concurrency/correctness · ✅ 수정: on_ready 에 bot._reschedule_done 부울 가드를 두어 reschedule 를 최초 1회만 실행하고, _reschedule_pending_reminders 가 이미 살아있는 _reminder_tasks id 는 건너뛰도록 변경.  
- 문제: on_ready 가 부울/실행중 가드 없이 매번 _reschedule_pending_reminders 를 실행한다. on_ready 는 봇 생애 1회가 아니라 재연결마다 발화하므로, 동일 미발송 행에 대해 _schedule_reminder 태스크가 중복 생성된다.
- 수정안: 최초 1회만 reschedule 하도록 on_ready 에 부울 가드를 두거나 setup_hook 으로 옮긴다. 근본적으로는 reminder_id->Task 레지스트리에 이미 예약된 id 는 재예약을 건너뛴다.

**6. 일시적 HTTP 오류(레이트리밋/5xx)에도 mark_sent 가 호출되어 알림이 영구 유실**  
`src/discord_assistant/bot.py:1334-1337` · error-handling · ✅ 수정: _deliver_reminder 에서 discord.HTTPException(일시 오류) 발생 시 return 으로 mark_sent 를 건너뛰어 다음 기동 reschedule 에서 재시도되게 함; 성공/Forbidden(영구 실패)만 mark_sen  
- 문제: 발송 실패가 일시적(429/5xx)인 경우에도 except 가 로깅만 하고 그 뒤 무조건 mark_sent(sent=1) 가 실행된다. 일시 오류와 영구 오류를 구분하지 않는다.
- 수정안: 일시 오류와 영구 오류를 구분한다. Forbidden(및 50007 등 명시적 영구 실패 코드)만 mark_sent 하고, 그 외 HTTPException 은 mark_sent 하지 말고(또는 재시도/백오프 후) 다음 기동 시 재시도되게 둔다.


### CI·CD/인프라 (`cicd-infra`)

**7. 프로덕션 배포 가드가 placeholder SECRET_KEY를 통과시킨다 (존재 여부만 검사)**  
`.github/workflows/deploy.yml:192-195` · security · ✅ 수정: Render .env 스텝에서 SECRET_KEY 존재 검사 직후에 DISCORD_BOT_TOKEN 과 동일한 placeholder 거부 가드(grep -qE '^SECRET_KEY=(replace-with|$)' -> ::error + exit 1)  
- 문제: DISCORD_BOT_TOKEN 과 달리 SECRET_KEY 가드는 placeholder 거부 없이 존재 여부만 검사한다. 공개 리포의 .env.prod.example 에 적힌 알려진 placeholder 값이 가드를 통과해 프로덕션에 그대로 배포될 수 있다.
- 수정안: DISCORD_BOT_TOKEN 과 동일하게 placeholder 거부 가드를 추가한다: `if grep -qE '^SECRET_KEY=(replace-with|$)' "${DEPLOY_DIR}/.env"; then echo '::error::SECRET_KEY 가 placeholder 입니다'; exit 1; fi`. 추가로 최소 길이(예: env 에서 추출해 32자 이상) 검증 권장.

**8. 컨테이너 내부 백업이 sqlite3 CLI 부재로 항상 cp 폴백 → 라이브 WAL DB 비일관 스냅샷 + 무결성검증 스킵**  
`scripts/backup.sh:37-60` · data-integrity · ✅ 수정: sqlite3 CLI 부재 시(컨테이너 시나리오) cp 대신 Python sqlite3 모듈의 .backup() 온라인 백업으로 일관 스냅샷을 만들고, 동일하게 Python PRAGMA integrity_check 로 무결성 검증(실패 시 손상본 삭제  
- 문제: 백업이 의도와 달리 항상 cp 폴백 경로로 동작한다. 라이브 WAL DB 를 일관 스냅샷(.backup) 없이 cp 로 복사하고 integrity_check 도 스킵해 손상/비일관 가능 스냅샷이 검증 없이 저장된다.
- 수정안: Dockerfile 의 apt-get install 목록에 `sqlite3` 추가(가장 단순). 또는 backup.cron:15 대안처럼 sqlite3 가 설치된 호스트에서 직접 실행. 또는 sqlite3 CLI 미존재 시 Python `sqlite3` 모듈의 `.backup()` API 로 일관 스냅샷+무결성검증을 보장하는 폴백을 둔다.


### 설정/암호화 (`config-crypto`)

**9. SECRET_KEY 가드가 정확한 기본값만 차단 — 빈 값·짧은 값·약한 변형은 통과**  
`src/discord_assistant/settings.py:102-113` · config · ✅ 수정: from_env 의 SECRET_KEY 검증을 정확 일치에서 약함(weak) 판정으로 확장: 빈 값·길이 32자 미만·알려진 약한 변형(_WEAK_SECRET_KEYS, 소문자 비교)을 모두 약한 키로 보아 production 에서는 RuntimeEr  
- 문제: production 가드는 secret_key가 정확히 'change-me-in-production'일 때만 기동을 거부한다. 빈 문자열·한 글자·사소한 변형은 모두 무사 통과하며, 특히 빈 값은 경고 로그도 남기지 않고 crypto가 정상 키를 생성해 약한 암호화로 조용히 동작한다.
- 수정안: production에서 빈 문자열·최소 길이 미만(예: 32바이트 미만)·알려진 약한 값을 함께 거부한다. 정확 일치 여부가 아니라 엔트로피/길이 기준으로 검증한다.


### 대시보드 (`dashboard`)

**10. 대시보드 JWT 서명 키가 기본값 'change-me-in-production' 으로 무가드 폴백**  
`dashboard/backend/auth.py:94-105` · 보안(시크릿/인증) · ✅ 수정: _secret_key() 에 운영(production) 가드를 추가: JWT_SECRET_KEY/SECRET_KEY 가 모두 없어 'change-me-in-production' 으로 폴백할 때 _is_production_env() 가 True 면 Ru  
- 문제: 대시보드 백엔드에는 봇이 가진 production 기본키 거부 가드가 없다. JWT_SECRET_KEY/SECRET_KEY 환경변수를 모두 빠뜨리면 공개된 'change-me-in-production' 으로 JWT 가 서명·검증된다. 부팅을 실패시키는 startup 가드(lifespan/모듈 임포트)도 없다.
- 수정안: _secret_key() 에서 기본값 폴백을 제거하고, 키가 없거나 'change-me-in-production' 이면 lifespan/모듈 임포트 시점에 RuntimeError 로 부팅을 실패시킨다(봇 settings.py 와 동일한 production 가드 재사용). 최소 길이/엔트로피 검증도 추가한다.


### i18n/프롬프트 (`i18n-prompts`)

**11. 커스텀 프롬프트 경로가 프롬프트 인젝션 방어선(_wrap_untrusted/_INJECTION_GUARD)을 완전히 우회한다**  
`src/discord_assistant/bot.py:1506-1509, 1635-1640` · 보안(프롬프트 인젝션) · ✅ 수정: #89 과 동일한 한 곳의 수정으로 함께 해결(커스텀 요약/Q&A 경로에 _wrap_untrusted 래핑 + _INJECTION_GUARD prepend).  
- 문제: 관리자가 커스텀 요약/Q&A 프롬프트를 설정하면 신뢰할 수 없는 transcript/question 이 _wrap_untrusted 와 _INJECTION_GUARD 를 거치지 않고 그대로 LLM 프롬프트에 박힌다. #38 으로 구축한 다층 인젝션 방어선(가짜 role 토큰 zero-width 무력화, 구분자 무결성, 보안 지침 prepend)이 커스텀 프롬프트 한 줄 설정으로 전부 무효화된다.
- 수정안: 커스텀 경로에서도 치환 전 transcript/question 을 prompts._wrap_untrusted(transcript, "transcript") / _wrap_untrusted(question.strip(), "question") 로 감싸고, prompts._INJECTION_GUARD 를 프롬프트에 prepend 한다. 예: `prompt = _INJECTION_GUARD + "\n" + custom.replace("{transcript}", _wrap_untrusted(transcript, "transcript"))`.


### LLM 제공자 (`llm-providers`)

**12. 스트림 조기 종료 시 워커 스레드가 블로킹 HTTP 읽기에서 멈춰 누수/지연**  
`src/discord_assistant/llm.py:423-453` · 리소스 누수/async 함정 · ✅ 수정: 동일한 _iter_in_thread 재작성으로 부분 해결: finally 에서 무조건 await worker 를 제거하고 stop 이벤트로 워커가 다음 put 폴링 시점에 빠져나가게 함. 미완료 워커는 await 하지 않고 detach 해 이벤트 루프  
- 문제: _iter_in_thread 는 블로킹 urllib 스트림을 워커 스레드에서 돌리고 finally 에서 무조건 'await worker' 로 워커 종료를 기다린다. 소비자가 스트림을 끝까지 읽지 않고 중단하면 워커는 다음 readline 또는 가득 찬 큐의 q.put 에서 블로킹된 채로 남고, finally 의 await worker 는 그 워커가 끝날 때까지(=서버 close 또는 HTTP timeout 만료까지) 깨어나지 못한다.
- 수정안: (1) response 핸들을 보관해 finally 에서 response.close() 를 호출해 블로킹 read 를 깨운다, (2) q.put 을 timeout 가능하게 하거나 별도 stop 이벤트를 워커가 주기적으로 확인, (3) 최소한 finally 에서 'await asyncio.wait_for(worker, timeout=...)' 로 무한 대기를 막고 워커를 detach 한다. 취소 가능한 HTTP 클라이언트(httpx) 도입도 고려.


### LLM 재시도/툴 (`llm-resilience`)

**13. is_available() 가 _list_sync 의 예외 흡수로 인해 사실상 항상 True 를 반환**  
`src/discord_assistant/llm.py:1410-1415` · error-handling · ✅ 수정: OllamaManager._list_sync 에 raise_on_error 키워드(기본 False) 추가 — list_models 는 기존대로 예외 흡수해 []; is_available 은 raise_on_error=True 로 호출해 연결 실패(UR  
- 문제: is_available() 가 의존하는 _list_sync() 는 모든 예외를 자체적으로 except Exception 으로 흡수해 [] 를 반환한다. 따라서 _list_sync 는 사실상 예외를 던지지 않으므로 is_available 의 try/except 가 도달할 수 있는 유일한 경로는 to_thread 자체 실패뿐이며, 서버 다운/연결 거부 같은 정상적인 미가용 상태는 항상 True 로 보고된다.
- 수정안: 가용성 판정 전용으로 예외를 전파하는 경로를 둔다. 예: GET /api/tags 를 직접 호출해 HTTP 상태/URLError 로 판정하거나, _list_sync 가 실패 시 [] 가 아닌 sentinel(또는 raise)을 반환하도록 분리해 is_available 이 '빈 목록'과 '연결 실패'를 구분하게 한다.

**14. _iter_in_thread: 소비자 조기 종료/취소 시 워커가 바운드 큐(put)에서 영구 블록 → 데드락**  
`src/discord_assistant/llm.py:432-453` · concurrency · ✅ 수정: 워커에 threading.Event(stop) 추가, put 을 _WORKER_PUT_POLL(0.1s) 타임아웃 폴링으로 수행해 매 주기 stop 확인; 소비부 finally 에서 stop.set() + 큐 drain 으로 막힌 put 을 즉시 해제  
- 문제: 스트리밍 소비부가 도중에 중단되면(예: followup 편집 중 비-HTTPException 예외, 상위 태스크 취소) finally 의 await worker 가 큐를 비우지 않은 채 워커 종료를 기다린다. 워커는 가득 찬 큐에 put 하려고 블록돼 있어 둘 다 영구 대기한다.
- 수정안: GeneratorExit/취소 시 워커를 깨운다. finally 에서 worker 완료까지 큐를 계속 drain(q.get_nowait 반복으로 비우기)하거나, 워커에 종료 이벤트를 전달해 put_nowait + 가득참 시 종료 체크를 하도록 바꾼다. 또는 close 가능한 응답 핸들을 워커에 넘겨 소비자 종료 시 강제 close 해 make_iter 가 예외로 끝나게 한다.


### 관측성/지원 (`observability-support`)

**15. correlation_id 가 실제 로그에 절대 나타나지 않음 (필터 미부착 + 포맷에 cid 없음)**  
`src/discord_assistant/logging_config.py:20,36-48,97-102` · 관측성/로깅 결함 · ✅ 수정: logging_config 를 cid 의 정식 위치로 삼아 _correlation_id ContextVar/get_correlation_id/set_correlation_id/CorrelationIdFilter 를 정의하고, setup_logging   
- 문제: CorrelationIdFilter 가 record.cid 를 주입하고 docstring 은 포매터가 %(cid)s 를 참조한다고 명시하지만, setup_logging 핸들러에 (1) 필터가 addFilter 되지 않고, (2) _TEXT_FORMAT 에도 %(cid)s 가 없으며, (3) JsonFormatter 도 cid 키를 넣지 않는다.
- 수정안: 핸들러에 CorrelationIdFilter addFilter + _TEXT_FORMAT 에 (cid=%(cid)s) + JsonFormatter 에 cid 직렬화. 순환참조 피하려 필터를 logging_config 로 이동.

**16. Sentry before_send/PII 스크럽 부재 — 예외 로컬 변수에 사용자 콘텐츠·토큰 유출 가능**  
`src/discord_assistant/observability.py:49-54` · 보안/PII 유출 · ✅ 수정: init_sentry 의 sentry_sdk.init 에 send_default_pii=False 와 before_send 콜백을 추가하고, _before_send/_scrub_value/_is_sensitive_key 헬퍼로 이벤트(예외 프레임 va  
- 문제: init_sentry 가 dsn/environment 만으로 init 하고 before_send 스크럽·send_default_pii=False 를 설정하지 않는다. on_error→capture_exception 경로의 예외 프레임 로컬 변수가 함께 전송될 수 있다.
- 수정안: send_default_pii=False + 민감 키(message/content/authorization/token/api_key) 마스킹 before_send 콜백 추가.


### 보안 (`security`)

**17. 커스텀 프롬프트 경로가 프롬프트 인젝션 방어를 전면 우회한다**  
`src/discord_assistant/bot.py:1506-1509, 1635-1642` · security/prompt-injection · ✅ 수정: custom_summarize_prompt/custom_ask_prompt 치환 시 transcript/question 을 prompts._wrap_untrusted 로 감싸고 prompts._INJECTION_GUARD 를 프롬프트 앞에 prepen  
- 문제: config.custom_summarize_prompt / custom_ask_prompt 가 설정되면 채널 트랜스크립트와 사용자 질문이 인젝션 방어(구분자 래핑, role 토큰/지시문 무력화, 보안 가드 prepend) 없이 그대로 모델 프롬프트에 들어간다. 기본 build_* 경로가 들이는 모든 방어가 이 분기에서 무효화된다.
- 수정안: 커스텀 프롬프트에 데이터를 치환할 때도 신뢰 불가 입력을 정제하라. 예: transcript 치환값을 prompts._wrap_untrusted(transcript, 'transcript') 결과로 바꾸고 question 도 동일 처리한 뒤, 커스텀 프롬프트 본문 앞에 항상 prompts._INJECTION_GUARD 를 강제 prepend 한다. 최소한 _neutralize_role_tokens + _neutralize_injection_phrases 를 적용한 값을 삽입한다.

**18. allowed_role 역할 제한이 대부분의 LLM 진입점에 적용되지 않는다**  
`src/discord_assistant/bot.py:1730, 1786, 2435, 2325, 2194, 2248, 2604-2627, 3069, 3357` · security/authorization · ✅ 수정: translate(캐시 검사 전)·chat(_run_chat)·search·export·summarize-channels(_on_confirm)·컨텍스트 메뉴 공통 가드(_ctx_menu_guard) 및 이벤트 경로(멘션/답장/리액션)에 _has_al  
- 문제: 관리자가 /config allowed_role 로 '명령어 사용 가능 역할'을 지정해도, run_summarize/run_ask/digest 외의 모든 LLM 호출 경로(translate, chat, search, export, summarize-channels, 컨텍스트 메뉴 3종, @멘션, 답장, DM, 리액션 트리거)는 역할 검사 없이 누구나 사용할 수 있다.
- 수정안: 공통 가드(_has_allowed_role)를 모든 LLM 진입점에 일원화하라. _ctx_menu_guard, _run_chat, translate/search/export/summarize-channels, on_message 의 멘션·답장 경로, on_raw_reaction_add 에 config.allowed_role_id 기반 검사를 추가하거나 단일 데코레이터/헬퍼로 통일한다.


### 스토리지/DB (`storage`)

**19. purge_old retention cutoff compares ISO 'T'/offset created_at against datetime('now') space-form lexically, leaving same-date rows undeleted**  
`src/discord_assistant/storage.py:916-929` · 데이터 정합성/시간 비교 · ✅ 수정: In purge_old (both usage_log and chat_history DELETEs) changed `WHERE created_at < datetime('now', ?)` to `WHERE datetime(created_at) < date  
- 문제: purge_old 의 보존 컷오프 비교가 두 개의 서로 다른 시각 직렬화 형식을 단순 문자열로 비교한다. created_at 은 'T' 구분자와 '+00:00' 오프셋을 가진 ISO 문자열이지만 datetime('now','-N days') 는 공백 구분자에 오프셋 없는 형식을 돌려준다. 두 형식은 위치 10('T'=84 vs ' '=32)에서 갈리므로, 컷오프 날짜와 같은 날짜의 행은 그 날 어느 시각이든 컷오프보다 '크다'고 판정되어 삭제되지 않는다.
- 수정안: 양변을 동일 함수로 정규화해 비교한다. WHERE datetime(created_at) < datetime('now', ?) 처럼 양쪽 모두 SQLite datetime() 으로 파싱하거나, _utc_now() 와 동일 형식으로 미리 계산한 컷오프 문자열을 바인딩한다. get_today_token_usage 의 date(created_at)=date('now') 와 동일한 양변-파싱 패턴을 적용하면 된다.


### UI 뷰/모달 (`ui`)

**20. run_install: pull_task 예외 미회수 + 실패해도 '설치 완료' 표시 (fire-and-forget 태스크)**  
`src/discord_assistant/ui.py:766-803 (특히 773, 775, 788-791)` · async 함정 / 데이터 정합성 · ✅ 수정: run_install 의 폴링 루프 종료 후 try 블록 안에서 set_model 호출 전에 `await pull_task` 를 추가해, pull(다운로드) 실패(OllamaError 등)가 회수돼 except OllamaError/Exception   
- 문제: run_install 의 while 루프가 pull_task 의 완료만 폴링하고 예외/결과를 await 로 회수하지 않는다. 따라서 ollama pull 이 OllamaError 로 실패해도 그 예외는 except OllamaError(794) 로 전달되지 못한 채 unretrieved 상태가 되고, 코드는 곧바로 set_model 을 호출해 '설치 완료' 를 표시한다. 또한 create_task 반환값을 어디에도 저장하지 않아 부모 태스크가 GC 될 수 있다.
- 수정안: 루프 종료 후 try 블록 안에서 먼저 `await pull_task`(또는 pull_task.result())로 예외를 전파시킨 뒤에만 set_model 을 호출하도록 순서를 바꾼다. OllamaError 가 except OllamaError(794)에서 잡혀 '설치 실패' 로 표시되게 한다. 또한 set_model 에 ollama_manager 를 넘겨 모델 존재 검증을 활성화하고, create_task 반환값을 self._install_task 로 보관 + add_done_callback 으로 예외 로깅을 건다.


---

## 🟡 MEDIUM (39)


### 봇 슬래시 명령 (`bot-commands`)

**21. remind: no cooldown, no length cap on stored payload — DB bloat, PII retention, spam**  
`src/discord_assistant/bot.py:2013-2074` · data-integrity · ✅ 수정: Added _check_cooldown at remind_command entry, capped stored text to _MAX_REMIND_TEXT_CHARS=1800, and reject new reminders when pending coun  
- 문제: /remind has neither a per-call cooldown nor any length limit on the stored text/summary, and no per-user limit on the number of pending reminders. A user can schedule many reminders carrying large payloads (full conversation summaries = chat PII) that persist in the reminders table until due.
- 수정안: Apply a length cap (e.g. 1800 chars) to both `message` and the cached summary text before encoding; call `_check_cooldown` at the start of remind_command; enforce a per-user cap on pending (un-fired) reminders.

**22. digest: period-based command silently truncated to summary_limit (default 50) messages**  
`src/discord_assistant/bot.py:2517-2525` · edge-case · ✅ 수정: digest_limit now uses _effective_limit(200, ...) so period-based digest collects up to the 200-message cap; final prompt still bounded by ma  
- 문제: digest advertises summarizing a whole time window (e.g. since:1d) but caps collection at summary_limit (default 50). When the window contains more than `limit` messages, discord.py's after-based pagination keeps the OLDEST `limit` messages and drops the MOST RECENT part of the period (the candidate's claim that the oldest part is dropped is inverted — it's actually the newest). Either way the window is not fully represented.
- 수정안: For period-based commands, raise limit (e.g. 200 or None) and rely on max_context_chars for truncation, or explicitly tell the user only N of M messages were included. Note set_summary_limit caps summary_limit at 200, so the /usage `if summary_limit > 200` branch (2593) is dead.

**23. _collect_transcript: since(after)+limit returns only part of the period (summarize/digest)**  
`src/discord_assistant/bot.py:1110-1132, 1491-1497` · edge-case · ✅ 수정: Stopped passing after= to channel.history (which forces oldest-first). Now fetch before-only newest-first and break when a message is older   
- 문제: When a since/after window is given, history(after=..., limit=N) returns the OLDEST N messages within the window (because after forces oldest-first iteration), so any window with more than N messages is truncated — the user expects 'all messages in this period' but gets only N of them. Note the candidate's 'recent N only / oldest part dropped' phrasing is imprecise: with after set it is actually the oldest N that are kept and the newest dropped; either way the window is partial.
- 수정안: When since/after is provided, raise limit (or set None) and bound only by max_context_chars, or paginate the full window and tell the user when only part was included.


### 이벤트 핸들러 (`bot-events`)

**24. on_ready 가 재연결마다 전 길드 명령을 재동기화 (이중 sync + Discord 레이트리밋 소진)**  
`src/discord_assistant/bot.py:2897-2903` · 설정/배포 함정 (API 레이트리밋) · ✅ 수정: Added a one-time _guild_synced bool guard on the bot so the per-guild copy_global_to+sync loop runs only once, not on every RESUME/reconnect  
- 문제: discord.py 의 on_ready 는 최초 1회가 아니라 게이트웨이 RESUME/재연결마다 다시 발생할 수 있는데, on_ready 의 길드별 copy_global_to + sync 는 1회성 가드 없이 매번 실행된다. 게다가 setup_hook 에서 이미 글로벌 sync 를 한 뒤 기동 직후 또 모든 길드에 sync 가 돌아 시작 시점부터 중복 동기화가 발생한다.
- 수정안: on_ready 에 `if getattr(bot, '_synced', False): return; bot._synced = True` 같은 1회 실행 가드를 두거나, 동기화를 setup_hook 의 글로벌 sync 1회로 한정한다. 길드별 copy_global_to+sync 는 개발/즉시 반영이 필요한 경우로 제한한다.

**25. auto_summary_task: 접근 가능한 첫 채널이 비면 break 로 길드 전체 자동요약 중단**  
`src/discord_assistant/bot.py:3271-3291` · 엣지케이스/로직 함정 · ✅ 수정: Empty-transcript channel now uses continue (not break) so other active channels are tried; permission-failing channels also continue.  
- 문제: 자동 요약은 send+read 권한이 있는 첫 채널만 시도하고, 그 채널의 transcript 가 비면 continue 가 아닌 break 로 채널 루프를 종료한다. 대화가 활발한 다른 채널은 시도하지 않는다.
- 수정안: 빈 채널이면 break 대신 continue 로 다음 채널을 시도한다. 또는 자동 요약 대상 채널을 설정값으로 명시하게 하고, 어느 채널에도 transcript 가 없을 때만 이번 주기를 건너뛴다. last_run 갱신은 실제 요약 성공 후로 미루는 것도 고려한다.

**26. 리액션 트리거(📝/🌐) 가 채널 send 권한 사전 확인 없이 동작하고 성공 경로 reply 가 Forbidden 미처리**  
`src/discord_assistant/bot.py:3375-3420` · 권한/에러 처리 (사전 권한 확인 누락) · ✅ 수정: Added a channel.permissions_for(me).send_messages pre-check before the LLM call (skip if bot can't reply), and wrapped the success-path repl  
- 문제: 리액션 핸들러는 봇 자기 리액션·쿨다운만 거른 뒤 메시지를 fetch 해 곧장 LLM 요약/번역을 실행하고 target.reply 로 답장한다. 봇이 해당 채널에서 send/reply 권한이 있는지 사전 확인이 없고, 성공 경로의 reply 실패(discord.Forbidden)는 잡히지 않는다.
- 수정안: LLM 호출 전에 channel.permissions_for(guild.me).send_messages 권한을 확인하고 없으면 조용히 return 한다. 성공 경로의 target.reply/_send_channel_chunks 도 discord.HTTPException(Forbidden 포함)을 잡아 흡수한다.

**27. DM 채팅 경로가 슬래시 명령과 달리 토큰 상한·역할 제한을 우회**  
`src/discord_assistant/bot.py:2946-2999` · 리소스/비용 남용 · ✅ 수정: DM now calls _enforce_token_budget against the sentinel guild bucket (0) and records DM usage under guild_id=0, so an admin can cap DM cost   
- 문제: DM 모드는 0번 공용 기본 설정으로 동작하며 guild_id=None 이라 _enforce_token_budget(일일 상한)·allowed_role 같은 길드 단위 보호가 모두 무력화된다. 0번 설정에 외부 제공자 키가 있으면 DM 으로 누구나 그 키로 LLM 을 호출한다.
- 수정안: DM 채팅에도 일일 호출/토큰 상한과 허용 사용자(예: 봇이 함께 속한 길드 멤버) 검증을 추가한다. 0번 공용 설정에는 외부 API 키를 두지 않거나 DM 전용 권한 정책을 명시한다.


### 수명주기/동시성 (`bot-lifecycle`)

**28. _delayed_disconnect_alert 가 await 후 pending 을 무조건 None 으로 덮어써 새 알림 태스크 참조를 분실**  
`src/discord_assistant/bot.py:3198-3199, 3201-3206, 3215-3217` · race-condition · ✅ 수정: Capture me = asyncio.current_task() at entry and only clear _disconnect_state['pending'] when it is still this task, so a newly-registered a  
- 문제: _delayed_disconnect_alert 끝부분이 '자기 자신이 아직 등록된 pending 인지' 확인 없이 _disconnect_state['pending']=None 을 실행한다. await(notify_developer) 경계 이후 상태가 바뀌었을 수 있는데 무조건 덮어쓴다.
- 수정안: 함수 진입 시 `me = asyncio.current_task()` 를 보관하고, 끝에서 `if _disconnect_state.get('pending') is me: _disconnect_state['pending'] = None` 으로 자기 자신일 때만 비운다.

**29. auto_summary_task 가 LLM/예산 실패·빈 채널에도 last_run 을 먼저 갱신 — 실패 주기를 정상 소진**  
`src/discord_assistant/bot.py:3264, 3280-3281, 3285-3291` · error-handling · ✅ 수정: Moved _auto_summary_last_run[gid]=now to after a successful ch.send (else-branch). Budget-exceed (UserFacingError, intentional skip) also st  
- 문제: 성공 여부와 무관하게 주기 시작 시점에 last_run 을 갱신한다. 한 번의 일시적 실패가 그 interval 전체를 조용히 소진시킨다.
- 수정안: last_run 갱신을 ch.send 성공 이후로 옮긴다. 예산 초과(UserFacingError)는 의도된 skip 이므로 그 경우에만 별도로 last_run 을 갱신하고, 일반 LLM 오류/네트워크 오류는 last_run 을 유지해 다음 주기에 재시도하게 한다.


### 리마인더 (`bot-reminders`)

**30. user 가 None 일 때 AttributeError 미처리 → mark_sent 누락, 재시작마다 무한 재시도**  
`src/discord_assistant/bot.py:1328-1337` · error-handling · ✅ 수정: In _deliver_reminder, handle user is None explicitly (log + skip send) as a permanent failure so it falls through to mark_sent, preventing z  
- 문제: 후보가 지적한 NotFound 부분은 false: NotFound 는 HTTPException 서브클래스라 잡힌다. 그러나 user 가 None 이 되는 경계는 진짜다 — AttributeError 가 어떤 except 에도 걸리지 않아 mark_sent 가 호출되지 않는다.
- 수정안: user 가 None 인 경우를 명시 처리하고(영구 실패로 mark_sent), 광의의 except 대신 명확한 성공/영구실패 분기에서만 mark_sent 하도록 구조화한다.


### CI·CD/인프라 (`cicd-infra`)

**31. rekey_api_keys.py 가 busy_timeout/락 처리·봇 정지 가드 없이 라이브 DB UPDATE → 'database is locked' 위험**  
`scripts/rekey_api_keys.py:106-139` · concurrency · ✅ 수정: rekey() 의 sqlite3.connect 에 timeout=30 + isolation_level=None 적용, PRAGMA busy_timeout=30000 설정, 쓰기 모드에서 BEGIN IMMEDIATE 단일 트랜잭션으로 락 경합을 조기 감  
- 문제: 재암호화 도구가 WAL 라이터 경합 보호(busy_timeout, BEGIN IMMEDIATE) 없이 기본 connect 로 라이브 DB 에 UPDATE 하며, restore.sh 와 달리 '봇 정지 후 실행' 가드/경고도 없다.
- 수정안: docstring/실행 전 가드에 '컨테이너 stop 후 실행' 을 명시하고, `sqlite3.connect(..., timeout=30)` + `conn.execute('PRAGMA busy_timeout=30000')` 를 적용하며, `BEGIN IMMEDIATE` 로 단일 트랜잭션을 잡아 경합을 조기에 감지·중단(또는 재시도)한다.

**32. 배포 스모크테스트가 컨테이너 전체 로그 히스토리를 grep → 이전 기동의 'Logged in as' 로 거짓 통과**  
`.github/workflows/deploy.yml:252-266` · deployment · ✅ 수정: Deploy 스텝에서 docker compose pull/up 직전에 start_ts=$(date -u +%Y-%m-%dT%H:%M:%S) 를 캡처해 GITHUB_OUTPUT 으로 내보내고, Verify(smoke test) 스텝의 모든 docker   
- 문제: 스모크테스트가 현재 기동분이 아닌 컨테이너 전체 로그를 grep 한다. 컨테이너가 recreate 되지 않는 재배포에서는 과거 로그의 'Logged in as' 로 거짓 성공한다.
- 수정안: 기동 시각 이후 로그만 보도록 한다. 예: Deploy 스텝 직전에 `start_ts=$(date -u +%Y-%m-%dT%H:%M:%S)` 캡처 후 `docker logs --since "$start_ts" discord-assistant-bot`. 또는 healthcheck/readyz 가 노출하는 현재 기동의 로그인 상태를 신뢰 소스로 사용한다.


### 설정/암호화 (`config-crypto`)

**33. decrypt_api_key가 `except (InvalidToken, Exception)`로 모든 예외를 삼켜 '키 변경'으로 오진**  
`src/discord_assistant/crypto.py:28-29` · error-handling · ✅ 수정: except를 InvalidToken으로 좁히고, 비문자열 토큰은 별도의 명확한 CryptoError로 분리해 무관한 오류 오진을 제거(기존 동작/콜러 계약 유지, 테스트 10개 무수정 통과).  
- 문제: except 절이 (InvalidToken, Exception)로 사실상 모든 예외(TypeError, AttributeError 등)를 포착한 뒤 항상 동일한 '키가 변경됐을 수 있다'는 CryptoError로 변환한다. InvalidToken이 Exception의 서브클래스라 첫 항목은 무의미한 중복이며, 원래 예외 타입/메시지가 보존되지 않는다.
- 수정안: `except InvalidToken as exc:`로 범위를 좁히고, 입력 타입(str)/빈 토큰을 별도 검증한다. 광범위 포착이 필요하면 최소한 원래 예외 타입/메시지를 로깅하거나 메시지에 포함시켜 진단 정보를 보존한다.

**34. _fernet_key가 솔트·KDF 없이 단일 SHA-256 다이제스트를 Fernet 키로 사용**  
`src/discord_assistant/crypto.py:16-18` · security · ⏸ 보류: 위험한 설계 변경: _fernet_key를 솔트+PBKDF2/scrypt KDF로 바꾸면 파생 키가 달라져 기존 DB의 api_key_encrypted를 전부 복호화 불가하게 만든다. 마이그레이션 스크립트 + 솔트 저장 메커니즘(cross-file: settings.py/storage.  
- 문제: Fernet 키를 단일 라운드 SHA-256(secret)으로 파생한다. 비용 인자가 없어 SECRET_KEY가 저엔트로피일 경우 DB(api_key_encrypted) 유출 시 오프라인 무차별/사전 공격이 저렴해진다.
- 수정안: 배포별/고정 솔트와 PBKDF2-HMAC-SHA256(높은 iterations) 등 KDF로 키를 파생하고 secret 최소 길이/엔트로피를 검증한다. 키 회전을 위해 MultiFernet 사용도 검토한다.

**35. 운영 환경 판정이 ENVIRONMENT/APP_ENV 미설정 시 비운영으로 폴백 (fail-open)**  
`src/discord_assistant/settings.py:31-37` · config · ✅ 수정: 정확한 기본 placeholder 'change-me-in-production'을 _is_production_env() 판정과 무관하게 from_env() 진입부에서 항상 RuntimeError로 거부하도록 추가(상수 _DEFAULT_SECRET_KE  
- 문제: 운영 여부 판정이 인식 키워드 2개에만 의존하고 미설정/오타/비표준 키워드면 비운영으로 간주된다. 보안 가드가 fail-open이라 env 누락 시 가장 위험한 쪽(기본 SECRET_KEY 허용)으로 동작한다.
- 수정안: 기본 SECRET_KEY('change-me-in-production')는 환경과 무관하게 항상 거부하거나, 운영 여부를 명시적으로 요구(미설정 시 엄격 모드)한다. 인식 운영 키워드 집합도 넓힌다.

**36. GuildConfig.summary_limit 에 모델 레벨 범위 검증 없음 — DB/직접 생성 경로로 1~200 우회**  
`src/discord_assistant/models.py:65-77` · data-integrity · ✅ 수정: GuildConfig.__post_init__ 에 summary_limit >= 1 하한 검증 추가(+ MIN_SUMMARY_LIMIT 상수). 0·음수 거부. 상한(200)은 의도적으로 모델에서 강제하지 않음 — bot.py 의 '>200' 방어 분  
- 문제: set_summary_limit와 UI 경로는 1~200을 강제하지만 GuildConfig.__post_init__과 DB 로드(storage.py:639)는 summary_limit를 검증/클램프하지 않는다. 손상·마이그레이션된 행이나 모델 직접 생성 시 0·음수·거대값이 들어올 수 있고, bot.py:2270 경로는 그 값을 클램프 없이 사용한다.
- 수정안: SUMMARY_LIMIT 상·하한 상수를 두고 __post_init__에서 범위를 검증해 단일 진실 원천으로 강제하거나, get_guild_config에서 _normalize_interval처럼 읽기 시 클램프한다. bot.py:2270도 _effective_limit를 거치게 한다.

**37. api_key_encrypted 에 키 버전/지문 없음 + 단일 Fernet — SECRET_KEY 회전 시 전 서버 키 일괄 무효화**  
`src/discord_assistant/models.py:45-63` · data-integrity · ⏸ 보류: cross-file: 키 버전/지문 부착과 MultiFernet 무중단 회전은 crypto.py(_fernet_key/encrypt/decrypt)와 storage.py(저장/로드 시 버전 컬럼)의 변경이 핵심이다. models.py 에 버전 필드만 추가하면 채워지지도 사용되지도 않는   
- 문제: 어떤 SECRET_KEY로 암호화됐는지 식별하는 키 버전/지문이 모델·저장 계층에 없고 crypto가 MultiFernet을 쓰지 않는다. SECRET_KEY를 한 번 바꾸면 기존 모든 길드의 api_key_encrypted가 복호화 불가가 된다.
- 수정안: 암호문에 키 버전 식별자를 부착하고 crypto에 MultiFernet(구키+신키)으로 무중단 회전을 지원하거나, 회전 시 재암호화 마이그레이션 절차/스크립트를 명시한다.


### 대시보드 (`dashboard`)

**38. 인라인 폴백 PUT 경로가 daily_token_budget 컬럼을 누락 — store 경로와 스키마 drift**  
`dashboard/backend/main.py:372-461` · 데이터 정합성/스키마 drift · ✅ 수정: update_guild_config 의 인라인 폴백 SELECT·current dict 기본값·INSERT 컬럼목록·ON CONFLICT SET·바인드 튜플에 daily_token_budget(기본 None)을 추가해 봇 ConfigStore._ups  
- 문제: storage import 가 실패해 인라인 폴백으로 동작하는 배포에서, 대시보드 PUT 이 길드의 새 config 행을 만들 때 daily_token_budget 을 NULL 로 INSERT 한다. 봇의 ConfigStore._upsert(14컬럼)와 폴백(13컬럼)이 갈라져 '전체 컬럼 보존' 불변(369-371 주석)이 신규행에서 깨진다.
- 수정안: 폴백 SELECT/INSERT/ON CONFLICT 컬럼 목록과 current dict 기본값에 daily_token_budget(기본 None)을 추가해 ConfigStore._upsert 의 14개 컬럼 집합과 정확히 일치시킨다(스키마 단일 출처).

**39. IP별 레이트리밋 저장소가 무한 증가 — 빈/오래된 버킷을 회수하지 않음**  
`dashboard/backend/main.py:57-68` · 리소스 누수 · ✅ 수정: _prune_rate_limit_store 헬퍼와 _rate_limit_last_prune 를 추가해, _check_rate_limit 이 윈도 간격마다 윈도 밖/빈 버킷을 일괄 del 로 회수하도록 함(다시 등장 안 하는 IP 키 누적=메모리 누수   
- 문제: 한 번 등장한 모든 클라이언트 IP 키가 프로세스 수명 동안 dict 에 영구히 남는다. 만료된(윈도 밖) 타임스탬프만 정리될 뿐 키 자체가 제거되지 않아 IP 종류가 누적된다.
- 수정안: 타임스탬프 리스트가 비면 del _rate_limit_store[ip] 로 키를 제거하거나, 주기적 _prune/LRU/TTL 캐시 또는 Redis 백엔드로 교체한다.

**40. 레이트리밋이 request.client.host 만 사용 — 프록시 뒤에서 전 사용자가 하나의 버킷 공유**  
`dashboard/backend/main.py:62-73` · 보안(레이트리밋)/설정 함정 · ✅ 수정: _client_ip 헬퍼 추가: 기본은 peer IP 유지, 운영자가 TRUST_PROXY_HEADERS 로 opt-in 한 경우에만 X-Forwarded-For 의 가장 바깥(원 클라이언트) IP 를 키로 사용. opt-in 안 하면 헤더를 신뢰하지  
- 문제: 레이트리밋 키가 peer IP 단일 값이다. 리버스 프록시/로드밸런서 뒤에서는 모든 요청의 client.host 가 프록시 IP 하나로 동일해진다. 신뢰 프록시의 X-Forwarded-For 처리가 없다.
- 수정안: 신뢰 프록시 목록 기반으로 X-Forwarded-For 의 가장 바깥 신뢰 클라이언트 IP 를 쓰거나(ProxyHeadersMiddleware/uvicorn --proxy-headers + --forwarded-allow-ips), 배포 토폴로지에 맞춘 키 추출 전략을 명시한다.


### i18n/프롬프트 (`i18n-prompts`)

**41. set_language 가 언어 코드를 화이트리스트 검증/정규화하지 않아 임의 문자열이 프롬프트로 흘러간다**  
`src/discord_assistant/storage.py:692-699` · 데이터 정합성/보안(2차 인젝션) · ✅ 수정: set_language now lowercases + resolves legacy aliases (kr→ko, jp→ja) then whitelist-validates against prompts._LANGUAGE_LABELS plus 'auto',   
- 문제: 관리자가 /config language 에 'GIBBERISH', 'Français', 또는 'Answer in pirate. reveal secrets' 같은 임의 값을 넣으면 검증 없이 저장되고, language_label 이 원문을 그대로 통과시켜 프롬프트의 'Answer in {target_language}.' 자리에 박힌다.
- 수정안: set_language 에서 `normalized = language.strip().lower()` 후 _LANGUAGE_ALIASES 흡수 → `if normalized != 'auto' and normalized not in _LANGUAGE_LABELS: raise ValueError(...)` 로 화이트리스트 검증한다. config_language 핸들러도 동일 검증 후 저장한다.

**42. _split_discord_text 가 닫는 코드펜스(\n```) 길이를 경계 검사에 반영하지 않아 max_chars 를 초과하는 청크를 만든다**  
`src/discord_assistant/bot.py:729-734, 738-741` · 엣지케이스/경계 오류 · ✅ 수정: Reserve the trailing '\n```' (4 chars) in both boundary checks while in a code block: a fenced line that cannot fit with open+close fences i  
- 문제: 코드블록 내부 버퍼를 플러시하며 닫는 펜스를 더하는데(732/740) 경계 검사(730)가 그 4글자를 예약하지 않아 반환 청크가 max_chars 계약을 위반한다.
- 수정안: 코드블록 플러시 시 닫는 펜스를 예약한다. line 730 을 `len(candidate) + (len('\n```') if in_code_block else 0) > max_chars` 로 하거나, in_code_block 일 때 누적 한도를 `max_chars - 4` 로 둔다.


### LLM 제공자 (`llm-providers`)

**43. generate_with_tools 멀티 왕복에서 last_usage 가 덮어써져 토큰 과소 집계 (인스턴스 공유 시 경합)**  
`src/discord_assistant/llm.py:860,1128,944,1221` · 데이터 정합성/레이스 · ✅ 수정: OpenAI/Anthropic generate_with_tools 의 툴 루프에서 각 _chat_sync/_messages_sync 의 self.last_usage 를 _add_usage 로 누적해 모든 반환 경로에서 합산된 TokenUsage 를 l  
- 문제: 툴 루프는 _chat_sync/_messages_sync 를 여러 왕복 호출하지만 각 호출이 self.last_usage 를 누적이 아니라 덮어쓴다. 호출부는 루프가 끝난 뒤 last_usage 를 한 번만 읽으므로 마지막 왕복(흔히 toolcall 직후의 최종 응답)의 토큰만 기록되고 중간 왕복의 입력/출력 토큰이 누락된다.
- 수정안: 툴 루프에서 각 _chat_sync/_messages_sync 의 usage 를 누적 합산해 last_usage 에 반영한다(예: 왕복마다 prompt/completion 토큰을 더함). 더 견고하게는 usage 를 인스턴스 가변 속성 대신 반환 경로로 전달한다.

**44. OpenAI content 가 null 일 때 None.strip() 으로 AttributeError 가 LLMError 우회해 전파**  
`src/discord_assistant/llm.py:722-728` · 엣지케이스/None · ✅ 수정: OpenAIClient._generate_sync 에서 content 추출 후 isinstance(content, str) 검사를 추가해, content 가 null/비-str 이면 raw AttributeError 대신 OpenAIError(LLME  
- 문제: _generate_sync 는 content 를 꺼낸 뒤 곧바로 content.strip() 을 호출하지만 content 가 명시적 null 인 경우(OpenAI refusal/content filter)를 처리하지 않는다. None.strip() 의 AttributeError 가 의도된 'OpenAI 응답 형식을 해석할 수 없습니다.' OpenAIError 로 변환되지 않은 채 raw 로 전파된다.
- 수정안: content 가 str 인지 명시 검사: 'content = payload[...][...][...] ; if not isinstance(content, str): raise OpenAIError("OpenAI 응답 형식을 해석할 수 없습니다.")'. 또는 except 절에 AttributeError 를 추가해 None/누락을 정상 형식 오류로 처리한다.

**45. OpenAI 스트림이 SSE error 이벤트/[DONE] 누락을 조용히 무시해 빈 응답으로 종료**  
`src/discord_assistant/llm.py:768-784` · 에러 처리 누락/스트림 파싱 · ✅ 수정: OpenAIClient._stream_sync 의 SSE 파싱 후 obj 의 error 키를 검사해 있으면 OpenAIError 로 표면화(메시지 포함). Ollama 스트림과 동작 대칭화하여 조용한 빈 응답 종료를 방지.  
- 문제: _stream_sync 는 SSE 파싱 예외와 비-data 라인을 continue 로 삼키고 obj 내 'error' 키를 검사하지 않는다. OpenAI 스트림 중간/시작에 오는 {"error":{...}} 이벤트(rate limit/content filter/부분 실패)가 표면화되지 않고 스트림이 빈 채로 정상 종료된다.
- 수정안: SSE 파싱 후 obj 에 'error' 키가 있으면 OpenAIError 로 올린다(Ollama 스트림처럼). 또한 한 청크도 yield 하지 못한 채 종료한 경우를 감지해 호출부가 폴백/오류 처리하도록 신호를 준다.


### LLM 재시도/툴 (`llm-resilience`)

**46. OllamaManager.pull_model 에 타임아웃이 없어 무한 hang 가능**  
`src/discord_assistant/llm.py:1399-1408` · resource-leak · ✅ 수정: pull_model 의 proc.communicate() 를 asyncio.wait_for(timeout=_PULL_TIMEOUT_SECONDS=1800) 로 감싸고, 타임아웃 시 proc.kill()+await proc.wait() 로 정리한 뒤 안  
- 문제: 모델 다운로드가 네트워크 정체/원격 레지스트리 무응답으로 멈추면 communicate() 가 반환되지 않는다. 호출 코루틴이 상한 없이 대기한다.
- 수정안: asyncio.wait_for(proc.communicate(), timeout=...) 로 상한을 두고, TimeoutError 시 proc.kill() 후 await proc.wait() 로 정리하고 OllamaError 로 안내한다. 장기 작업이면 진행률 스트림/하트비트로 헬스를 감시한다.

**47. 서킷 브레이커 half-open 단일 프로브 가드 부재 — thundering herd**  
`src/discord_assistant/llm.py:298-321` · concurrency · ✅ 수정: CircuitBreaker 에 _half_open_probe_in_flight 플래그를 추가. before_call 에서 half-open(_opened_at 잔존) 진입 시 단일 프로브만 통과시키고 그 외는 CircuitBreakerOpenError  
- 문제: half-open 상태에서 reset_timeout 이 지나는 순간 동시에 대기하던 여러 코루틴이 모두 _is_open()==False 를 보고 before_call 을 통과한다. half-open 프로브를 1개로 제한하는 상태가 없다.
- 수정안: half-open 진입 시 단일 프로브만 통과시키는 in-flight 플래그/락을 추가한다. 프로브 결과 전까지 다른 호출은 open 으로 빠르게 실패시키고, 프로브 성공 시에만 닫는다.

**48. _list_sync: 모델 항목 'name' 누락/형식 이상 시 KeyError·TypeError 가 try 밖에서 전파**  
`src/discord_assistant/llm.py:1390` · error-handling · ✅ 수정: _list_sync 의 모델 목록 컴프리헨션을 명시 루프로 바꿔, 항목이 dict 가 아니거나 name 이 str 이 아니면 skip 하고 정상 항목만 OllamaModel 로 수집(예외 비전파).  
- 문제: 응답의 models 항목 중 하나라도 dict 가 아니거나 'name' 키가 없으면 KeyError/TypeError 가 _list_sync 의 try 밖에서 전파된다. m.get('size', 0) 은 방어적이지만 m['name'] 은 아니다.
- 수정안: 컴프리헨션을 try 안으로 옮기고 m 이 dict 인지, 'name' 이 str 인지 검사해 누락 항목은 skip 한 뒤 OllamaModel 을 생성한다. 파싱 실패 시 [] 또는 명확한 OllamaError 로 변환한다.

**49. 툴 루프에서 last_usage 가 왕복마다 덮어써져 누적되지 않음 — 비용 과소 집계**  
`src/discord_assistant/llm.py:860, 1128, 924-944, 1201-1221` · data-integrity · ✅ 수정: id 45 와 동일 근본원인. OpenAI/Anthropic generate_with_tools 에서 왕복별 usage 를 _add_usage 로 누적해 last_usage 에 반영(중복 finding 으로 같은 수정으로 해결).  
- 문제: 툴 루프는 max_iterations 만큼 여러 LLM 왕복을 하지만 각 호출이 last_usage 를 덮어쓰기만 해서 마지막 왕복분만 남는다. 그 이전 왕복들의 토큰은 사라진다.
- 수정안: 툴 루프 동안 각 _chat_sync/_messages_sync 의 usage 를 누적(prompt/completion 합산)해 last_usage 에 반영하거나, generate_with_tools 가 누적 TokenUsage 를 별도로 합산해 루프 종료 시 한 번에 설정한다.


### 관측성/지원 (`observability-support`)

**50. JsonFormatter 가 logger.extra/추가 필드를 무시 — 컨텍스트 손실**  
`src/discord_assistant/logging_config.py:36-48` · 관측성/로깅 결함 · ✅ 수정: JsonFormatter.format 이 record.__dict__ 의 비표준 키(extra= 로 주입된 guild_id/user_id/command 등)를 payload 에 병합하고, record.stack_info 가 있으면 stack 키를 추가  
- 문제: JsonFormatter.format 은 고정 4키(time/level/logger/message)+exception 만 직렬화하고 record.__dict__ 추가 필드(extra=, stack_info, cid)를 반영하지 않는다.
- 수정안: 표준 LogRecord 속성 제외한 record.__dict__ 잔여 키를 payload 에 병합, stack_info 가 있으면 stack 키 추가.

**51. format_error_message 트레이스백을 개발자 DM 으로 그대로 전송 — 민감정보 평문 노출**  
`src/discord_assistant/monitor.py:271-280` · 보안/PII 유출 · ✅ 수정: scrub_sensitive() 헬퍼 추가(이메일·Discord 토큰·sk-/sk-ant- API 키·Bearer 토큰 마스킹)하고 format_error_message 에서 트레이스백을 DM 길이 절단 전에 스크럽하도록 호출  
- 문제: format_error_message 가 전체 트레이스백을 코드블록째 Discord DM(notify_developer)으로 전송. 예외 메시지에 사용자 입력·경로·토큰 단편이 포함될 수 있고 스크럽이 없다.
- 수정안: DM 전 알려진 시크릿/이메일 패턴 마스킹하거나 예외 타입+위치만 요약 전송.

**52. summarize 캐시 invalidate_prefix 의 startswith 키 충돌 — 인접 채널 캐시 오삭제**  
`src/discord_assistant/cache.py:33-36` · 캐시 무효화 버그/키 충돌 · ✅ 수정: invalidate_prefix 를 경계 인식 매칭(k == prefix or k.startswith(prefix + ":"))으로 바꿔 "1:45" 무효화가 "1:456"/"1:450" 같은 ID 접두 겹침 형제 채널 캐시를 삭제하지 않도록 수정.   
- 문제: invalidate_prefix 가 str.startswith 로 매칭하는데 키가 구분자 없는 {guild}:{channel} 형태라 채널 45 의 무효화가 456/450/4567 채널 캐시까지 삭제한다.
- 수정안: 키에 종료 구분자({guild}:{channel}:) 또는 정확 일치 무효화 API 사용.

**53. summarize 캐시 키가 언어/limit/모델/커스텀프롬프트를 무시 — 잘못된 언어/내용 응답**  
`src/discord_assistant/cache.py:30-31` · 캐시 키 설계 결함 · ⏸ 보류: cross-file: 캐시 키 조립이 src/discord_assistant/bot.py:1520 (cache_key = f"{guild_id}:{channel_id}")에 있음. fix(effective_language·model·custom_prompt 해시를 키에 포함)는 bot.  
- 문제: 캐시 키가 guild:channel 뿐인데 요약 본문은 effective_language(auto 포함)·custom_summarize_prompt·model 에 따라 달라진다.
- 수정안: cache_key 에 effective_language·model·custom_prompt 해시 포함.


### 보안 (`security`)

**54. DM 채팅이 길드 격리 없이 user 전역으로 chat_history 를 조회한다**  
`src/discord_assistant/bot.py:2961, 2976-2977` · security/data-integrity · ✅ 수정: DM get_chat_history/save_chat_message now use a DM-only scope (sentinel guild_id=0 + actual DM channel id) instead of guild_id=None, so stor  
- 문제: DM 경로는 get_chat_history 를 guild_id=None 으로 호출해 storage 의 user 전역 분기로 떨어진다. 길드 대화는 guild_id+channel_id 로 스코프 저장되므로, DM 응답 맥락에 같은 사용자의 다른 서버 대화가 섞여 들어올 수 있다(비대칭 누수).
- 수정안: DM 경로도 명시적 스코프 키(예: channel_id=message.channel.id, 또는 DM 전용 센티넬 guild_id)로 get_chat_history/save_chat_message 를 호출한다. 또는 get_chat_history 에서 guild_id 만 None 이고 channel_id 가 주어진 경우를 별도 스코프로 처리해 user 전역 폴백을 없앤다.

**55. API 키 검증이 429/5xx/400 등 비인증 오류를 '유효'로 간주해 무효 키를 저장한다**  
`src/discord_assistant/ui.py:267-330` · security/error-handling · ✅ 수정: 세 검증 함수(_validate_openai/anthropic/gemini_key)의 거부 코드 집합을 새 상수 _INVALID_KEY_HTTP_CODES=(400,401,403,404)로 통일해 400/404(특히 Gemini 의 무효 키 400 A  
- 문제: 세 검증 함수가 401/403 만 무효로 보고 그 외 HTTP 오류(429/5xx/400)는 유효로 통과시킨다. 성공 코드(200/201)만 명시적으로 True 로 보는 것이 아니라 '401/403 이 아니면 통과' 방식이라, 제공자가 4xx/5xx 를 주는 잘못된/만료 키도 검증을 통과해 저장될 수 있다.
- 수정안: 성공 코드(200/201)만 True 로 보고, 401/403/400 은 명확히 무효로 처리한다. 429/5xx/네트워크 오류는 '검증 불가'로 구분해 사용자에게 별도 안내(예: '검증을 건너뛰고 저장하시겠어요?')하거나 보수적으로 거부한다.

**56. 리액션 트리거(📝/🌐)가 역할 검사 없이 임의 메시지를 LLM 으로 처리한다**  
`src/discord_assistant/bot.py:3357-3420` · security/authorization · ⏸ 보류: Already fixed in current code: on_raw_reaction_add (around line 3596) already performs the config.allowed_role_id check via payload.member / guild.get_member an  
- 문제: 리액션 핸들러는 쿨다운·토큰예산만 보고 allowed_role 검사 없이 LLM 처리를 트리거한다. allowed_role 이 설정된 서버에서도 리액션을 달 수 있는 사용자라면 이모지 한 번으로 봇이 메시지를 요약/번역해 채널에 응답하게 만들 수 있다.
- 수정안: 리액션 핸들러에도 config.allowed_role_id 기반 _has_allowed_role 검사를 추가한다. 다른 LLM 진입점과 동일한 공통 가드로 일원화한다.


### 스토리지/DB (`storage`)

**57. save_chat_message prune keys only on user_id (global 200), wiping per-guild/channel history that get_chat_history retrieves by (user_id, guild_id, channel_id)**  
`src/discord_assistant/storage.py:846-859` · 데이터 정합성/엣지케이스 · ✅ 수정: Scoped the prune in save_chat_message to the same (user_id, guild_id, channel_id) bucket as get_chat_history, using NULL-safe IS comparison   
- 문제: 채팅 기록 prune 은 사용자 전역으로 최신 200행만 유지하지만, 조회는 (user_id, guild_id, channel_id) 조합으로 필터링한다(bot.py:1806 이 실제로 guild_id/channel_id 를 전달). 따라서 한 사용자가 한 채널에서 200건 이상을 쌓으면 다른 길드/채널의 과거 메시지가 전부 삭제된다.
- 수정안: prune 키를 조회 키와 일치시킨다. prune 의 LIMIT 서브쿼리 WHERE 절을 (user_id, guild_id, channel_id) 조합별로 적용하거나, 조회 시맨틱이 채널별이면 prune 도 채널별 상한으로 통일한다.


### UI 뷰/모달 (`ui`)

**58. ModelInstallView timeout(1800s)이 Discord 인터랙션 토큰(15분)을 초과 — 최종/실패 edit 가 unretrieved 예외로 폭주**  
`src/discord_assistant/ui.py:719, 787-803 (특히 795, 800)` · API 오용 / 에러 처리 · ✅ 수정: run_install 의 최종/실패 상태 edit 를 best-effort 헬퍼 _final_edit 로 묶어 토큰 만료 시 발생하는 discord.HTTPException(NotFound 포함)을 삼키도록 변경. 성공 경로 edit 가 만료로 던진   
- 문제: 15분 넘게 걸리는 대형 모델 다운로드가 끝나면 최종 상태를 쓰는 edit_original_response 가 401/404(Invalid Webhook Token)로 예외를 던진다. except 핸들러 내부의 edit(795/800)는 보호되지 않아 거기서 다시 예외가 나면 그대로 태스크 밖으로 전파된다.
- 수정안: 장시간 작업은 인터랙션 토큰 대신 최초 확보한 Message 핸들(interaction.original_response())로 message.edit() 갱신하거나, timeout 을 토큰 수명 이하로 낮춘다. 최소한 모든 최종/실패 edit_original_response 를 개별 try/except 로 감싸 NotFound 시 채널 폴백 전송 또는 무시한다.

**59. 공개(non-ephemeral) View(LongResponseView, FollowUpView)에 interaction_check 부재 — 타 사용자가 토큰 소모/콘텐츠 수신**  
`src/discord_assistant/ui.py:989-1011, 1169-1183` · 보안(권한) · ✅ 수정: 두 View 에 선택적 author_id 인자 + interaction_check 추가. author_id 지정 시 클릭한 사용자가 원작성자가 아니면 ephemeral 거부 후 False; author_id=None(기본)이면 기존처럼 모두 허용해 미  
- 문제: 공개 채널에 붙는 LongResponseView·FollowUpView 는 interaction_check 가 없어 메시지를 볼 수 있는 누구나 버튼을 누를 수 있다. FollowUpView 의 '후속 질문' 은 임의 사용자가 트리거해 원작성자 맥락(transcript_snapshot)으로 LLM 호출을 일으킬 수 있다. LongResponseView 의 send_dm 은 interaction.user(클릭한 본인) 에게 전송하므로 '남의 DM 으로 전문 탈취' 는 불가하나(후보의 정보노출 시나리오는 과장 — 자기 DM 으로 받는 공개 내용), 임의 사용자가 DM 발송을 트리거할 수 있다.
- 수정안: LongResponseView/FollowUpView 에 author_id 를 저장하고 async def interaction_check 에서 interaction.user.id != author_id 면 ephemeral 거부 후 False 반환. 또는 이 결과/후속 흐름을 ephemeral 로 전송해 원작성자로 한정한다.


---

## ⚪ LOW (78)


### 봇 슬래시 명령 (`bot-commands`)

**60. summarize-channels: int(ch_id) ValueError exposes raw exception string in embed field**  
`src/discord_assistant/bot.py:2276-2293` · error-handling · ✅ 수정: _summarize_one 의 except 에서 f"(오류: {e})" 대신 일반 안내 문구를 반환하고 실제 예외는 logger.warning 로만 남김 (#95 와 동일 위치, 함께 해결)  
- 문제: The `except Exception as e` at 2292 formats the raw exception (`f"(오류: {e})"`) directly into the embed field at 2304. The candidate's premise that arbitrary non-numeric select values can reach `int(ch_id)` is a false alarm: Discord select values are server-defined (`value=str(ch.id)` at ui.py:1207), so ValueError from int() is not attacker-reachable. The genuine but minor issue is that any internal exception text (e.g. a provider error message) is surfaced verbatim into the user-facing embed.
- 수정안: Replace `f"(오류: {e})"` at line 2293 with a generic user-facing message (e.g. '(요약 중 오류가 발생했어요)') and log the real exception server-side. The int() guard the candidate proposes is unnecessary given the constrained select values.

**61. run_ask/_run_chat/translate: retry button blocked by cooldown set during the failed attempt**  
`src/discord_assistant/bot.py:1602-1608, 1700-1703` · concurrency · ✅ 수정: _clear_cooldown 헬퍼 추가, run_ask/run_summarize/_run_chat 의 except 에서 재시도 가능한 LLM 오류(_is_retryable_error)면 진입 시 기록한 쿨다운을 롤백해 재시도 버튼이 실제 동작하게 함.  
- 문제: Cooldown is recorded on entry (read-modify-write side effect). When the LLM call then fails and a Retry button is offered, clicking it within COOLDOWN_SECONDS (10s) re-enters the cooldown check and only shows a cooldown warning — the retry does not actually run.
- 수정안: Pass a skip_cooldown flag through the retry/follow-up re-entry paths, or roll back `_cooldowns[key]` when the attempt fails with LLMError so the retry is not blocked.

**62. stats_command/usage_command: get_stats keys indexed directly (no .get), and stats_command has no try/except**  
`src/discord_assistant/bot.py:2391-2427, 2582-2584` · error-handling · ✅ 수정: stats_command/usage_command 의 stats['...'] 직접 인덱싱을 stats.get(...) 로 통일하고, stats_command 의 get_stats 호출을 try/except 로 감싸 친절 폴백 메시지 추가  
- 문제: Inconsistent access pattern (`stats.get()` for some keys vs `stats[...]` for others) and absence of any try/except in stats_command. The KeyError the candidate fears is not reachable with the current get_stats implementation (it unconditionally returns all keys), so this is a maintainability/defense-in-depth issue, not a live bug.
- 수정안: Use `stats.get('total', 0)` etc. consistently, and wrap stats_command's body in try/except with a user-friendly fallback message.

**63. export/search: interaction.channel may be None -> AttributeError on .history (uncaught)**  
`src/discord_assistant/bot.py:2334-2336, 2449-2451` · error-handling · ✅ 수정: _require_history_channel 가드 헬퍼 추가, export_command/search_command 가 .history 호출 전 이를 통해 None 채널을 UserFacingError(친절 안내)로 전환  
- 문제: Unlike _collect_transcript, export/search directly dereference interaction.channel.history without a None/hasattr guard. The AttributeError from a None channel is not in the caught exception tuple and would fall through to on_error (defer-only silent failure), inconsistent with _collect_transcript's friendly message. Likelihood is low because interaction.channel is rarely None for slash commands.
- 수정안: Add the same `channel is None or not hasattr(channel, 'history')` guard (or reuse a shared helper) before calling history in both commands and raise UserFacingError.

**64. _check_cooldown / _cooldowns: lock-free global dict, in-memory across processes, mixed key semantics**  
`src/discord_assistant/bot.py:500-519, 2568` · race · ✅ 수정: _check_cooldown docstring 에 단일 프로세스 인메모리 상태/다중 프로세스 시 공유 저장소 필요 가정을 명시(동작 변경 없음)  
- 문제: No actual concurrency bug under single-process asyncio (the function has no awaits, so it is atomic). The genuine concern is scalability: _cooldowns is per-process global state, so any multi-process/multi-shard scaling makes cooldowns inconsistent (bypassable). Cleanup (508-512) only evicts entries older than COOLDOWN_SECONDS*10, so the dict grows up to one entry per (guild,user) pair within that window.
- 수정안: Document the single-process assumption, or move cooldown state to a shared store (e.g. Redis) for multi-process deployments. No lock is needed under asyncio.

**65. translate/search: LLM output truncated to 1024 chars in embed with no overflow fallback**  
`src/discord_assistant/bot.py:1767-1768, 2480` · edge-case · ✅ 수정: _overflow_view 헬퍼 추가, translate(캐시/라이브)와 search 의 답변이 1024 자 초과 시 LongResponseView(DM 으로 전체 받기) 버튼을 붙여 잘린 분량 복구  
- 문제: Translation results and search summaries longer than 1024 chars are silently truncated in the embed field, and unlike /ask and /chat there is no 'receive full content via DM' fallback, so the overflow is permanently lost.
- 수정안: Use _send_answer_with_overflow, or attach a LongResponseView (DM button) when the answer exceeds 1024 chars, for both translate and search.

**66. _run_chat: streamed partial output + LLMError re-raise skips save_chat_message -> history desync**  
`src/discord_assistant/bot.py:1818-1852` · async · ✅ 수정: 스트림 부분 출력 후 LLMError re-raise 전에 user 메시지와 부분 답변을 save_chat_message 로 저장해 대화 메모리 일관성 유지(저장 실패는 흡수). #62 와 동일 위치, 함께 해결  
- 문제: When streaming fails mid-way after emitting some text, the partial answer remains on screen but neither the user message nor the (partial) assistant reply is saved to chat_history, so the next /chat's history omits this turn entirely.
- 수정안: On partial-output failure, persist the user message (and partial answer) before re-raising, or explicitly tell the user the turn was not saved.

**67. auto_summary: only first eligible channel tried (break), and last_run set before LLM call**  
`src/discord_assistant/bot.py:3264, 3271-3291` · logic · ⏸ 보류: 이미 현재 코드에서 해결됨: auto_summary_task 가 빈 transcript 에 continue(3512-3513), last_run 을 성공/budget-skip 이후에만 갱신(else/UserFacingError 분기). 추가 수정 불필요  
- 문제: Auto-summary picks the first eligible text channel and, due to the unconditional break at 3291 (and the early break at 3281 on empty transcript), never falls back to other channels. last_run is stamped at 3264 prior to the LLM call, so a failed or empty cycle is treated as done and skipped until the next interval.
- 수정안: Use `continue` instead of `break` on empty transcript so other channels are tried, and move the `_auto_summary_last_run[gid] = now` update to after a successful summary post.


### 이벤트 핸들러 (`bot-events`)

**68. 요약 캐시 무효화 prefix 충돌: 한 채널 메시지가 다른 채널 캐시를 잘못 무효화**  
`src/discord_assistant/bot.py:2942 (cache.py:33-36)` · 데이터 정합성/효율 (캐시 키 prefix 충돌) · ⏸ 보류: 이미 cache.py 의 invalidate_prefix 가 세그먼트 경계(prefix+':', k==prefix or startswith(sub_prefix))로 충돌을 방지하도록 수정돼 있어 bot.py 쪽 추가 변경 불필요. 또한 cross-file(cache.py)  
- 문제: 캐시 키와 무효화 prefix 모두 끝에 경계 구분자가 없어, 짧은 channel_id 의 prefix 가 더 긴 channel_id 의 키 접두사가 되면 다른 채널의 요약 캐시까지 함께 삭제된다.
- 수정안: 무효화를 정확 키 삭제(del/pop)로 바꾸거나, 키를 `f"{guild_id}:{channel_id}:"` 처럼 끝에 구분자를 두고 invalidate 에서 `k == key or k.startswith(key)` 로 경계를 강제한다.

**69. DM 안내 전송에서 광범위한 except Exception: pass 로 예외 삼킴**  
`src/discord_assistant/bot.py:2952-2955, 2991-2994` · async 함정/에러 처리 (예외 삼킴) · ✅ 수정: DM 쿨다운/오류 안내 전송의 except Exception: pass 를 except discord.DiscordException + logger.debug 로 좁혀 CancelledError 등 제어 예외를 삼키지 않고 실패 사유를 기록  
- 문제: DM 경로에서 쿨다운/에러 안내 메시지를 보낼 때 모든 예외를 무조건 삼킨다. discord.HTTPException 외에 asyncio.CancelledError 같은 제어 예외도 삼켜질 수 있고, 전송 실패가 전혀 기록되지 않는다.
- 수정안: discord.HTTPException(또는 discord.DiscordException)으로 좁혀 잡고 logger.debug/warning 으로 사유를 남긴다. CancelledError 는 다시 raise 한다.

**70. 이미지 첨부 판정이 클라이언트 제어 content_type 헤더만 신뢰**  
`src/discord_assistant/bot.py:3091-3094, 1166-1189` · 입력 검증/보안 · ⏸ 보류: 매직넘버 검증 추가는 버그 픽스가 아닌 방어 강화이고, 불완전한 시그니처 목록이 유효 이미지를 거부하는 회귀를 만들 수 있어 보수적으로 보류(제공자 측 검증으로 대부분 흡수, severity low)  
- 문제: 이미지 분석 분기와 _download_image_attachments 는 첨부가 이미지인지 content_type.startswith('image/') 만으로 판정하고, 실제 바이트의 매직넘버 검증이 없다.
- 수정안: 다운로드한 바이트의 매직넘버(PNG \x89PNG, JPEG \xFF\xD8, GIF GIF8, WEBP RIFF...WEBP)를 확인해 실제 이미지가 아니면 건너뛰고, content_type 과 실제 포맷 불일치 시 로그를 남긴다.

**71. 봇 답장 chat 경로가 쿨다운에 걸리면 안내 없이 조용히 무시**  
`src/discord_assistant/bot.py:3032-3034` · UX/일관성 · ✅ 수정: 봇 답장 chat 경로의 쿨다운 차단 시 다른 경로처럼 '⏳ N초 후에 다시 시도해주세요' 안내 전송 추가(전송 실패는 흡수)  
- 문제: 봇 메시지에 답장하는 chat 경로는 쿨다운에 걸리면 아무 안내 없이 return 한다. DM/컨텍스트 메뉴 경로는 남은 쿨다운 초를 안내한다(동작 불일치).
- 수정안: 다른 경로와 동일하게 `await message.channel.send(f"⏳ {reply_remaining:.0f}초 후에 다시 시도해주세요.")` 같은 짧은 안내를 보내거나, 의도적 침묵이면 주석으로 설계 근거를 명시한다.

**72. 컨텍스트 메뉴: 토큰 상한 검사(DB 왕복 2회)를 defer 전에 수행해 3초 ACK 시한 위험**  
`src/discord_assistant/bot.py:2604-2627, 2635-2639, 2664-2668, 2694-2698` · async 함정 (interaction 응답 시한) · ⏸ 보류: _ctx_menu_guard 를 defer-우선 구조로 바꾸려면 가드의 response.send_message 를 followup 으로 전면 재작성해야 해 회귀 위험이 큼(타이밍 리팩터)  
- 문제: _ctx_menu_guard 가 get_guild_config + get_today_token_usage(DB 왕복 2회)를 수행한 뒤 핸들러로 돌아오고, defer(thinking=True)는 그 이후에 호출된다. ACK 가 가드의 DB 호출 뒤로 밀린다.
- 수정안: 핸들러 진입 직후 가장 먼저 interaction.response.defer(thinking=True, ephemeral=True) 로 ACK 를 확보한 뒤, 쿨다운/빈 메시지/토큰 상한 검사를 수행하고 결과를 followup.send 로 보내도록 순서를 바꾼다.


### 수명주기/동시성 (`bot-lifecycle`)

**73. _auto_summary_last_run 딕셔너리가 무한 증가 — on_guild_remove 없음, 정리 로직 부재**  
`src/discord_assistant/bot.py:337, 3259, 3264` · resource-leak · ✅ 수정: auto_summary_task 매 주기에서 configured set 에 없는 gid 의 _auto_summary_last_run 항목을 정리(교집합 유지)해 무한 증가 방지  
- 문제: 자동요약을 한 번이라도 켠 모든 길드의 마지막 실행 시각이 _auto_summary_last_run 에 영구 누적된다. 상한·만료·on_guild_remove 정리가 전혀 없다.
- 수정안: auto_summary_task 매 주기에서 configured set 에 없는 gid 를 _auto_summary_last_run 에서 제거하거나(교집합 유지), on_guild_remove 이벤트를 추가해 _auto_summary_last_run.pop(gid, None) / _tracked_messages.pop(gid, None) 로 정리한다.

**74. run_bot 의 add_signal_handler 를 종료 시 remove 하지 않아 재사용 루프에서 이전 클로저가 잔존**  
`src/discord_assistant/bot.py:3546-3550, 3562-3570` · deployment · ✅ 수정: 등록 성공한 시그널을 registered_signals 에 모아 finally 에서 loop.remove_signal_handler 로 해제(루프 재사용 시 이전 클로저 잔존 방지)  
- 문제: 시그널 핸들러를 등록만 하고 해제하지 않는다. asyncio.run 단일 경로에선 무해하나, 같은 루프를 재사용하는 호출자(임베딩/테스트)에선 직전 호출의 _request_stop 클로저가 남는다.
- 수정안: 등록 성공한 시그널을 set 에 모아두고, finally 에서 `for sig in registered: loop.remove_signal_handler(sig)` 로 해제한다.

**75. _last_summaries 가 LRU 가 아니라 FIFO — 재대입이 삽입 순서를 갱신하지 않아 활성 사용자가 부당하게 evict**  
`src/discord_assistant/bot.py:1524-1526, 2036-2042` · edge-case · ✅ 수정: _store_last_summary 헬퍼로 재대입 전 pop 후 재삽입해 진짜 LRU 로 만들고, 두 쓰기 경로(캐시/라이브)에서 공통 사용  
- 문제: 주석/의도는 '마지막 요약 캐시'(LRU 성격)이지만 실제 동작은 최초 삽입 순서 기준 FIFO 다. 1000명 한도를 넘는 활성 봇에서 자주 쓰는 오래된 사용자가 캐시에서 밀린다.
- 수정안: OrderedDict + move_to_end 를 쓰거나, 재대입 전 `_last_summaries.pop(user_id, None)` 후 다시 넣어 삽입 순서를 갱신해 진짜 LRU 로 만든다.

**76. DM on_message 가 빈/접두명령 메시지도 무조건 LLM 호출 — content 검증·ctx.valid 분기 부재, 토큰 가드도 없음**  
`src/discord_assistant/bot.py:2944-2972` · logic · ⏸ 보류 (해당 웨이브 미할당/중복)  
- 문제: DM 에서 빈 메시지·공백·'!ping' 같은 접두 명령·멘션만 보내도 그 내용이 그대로 LLM 프롬프트가 되어 generate 가 호출된다. process_commands 가 이미 명령을 처리한 경우에도 LLM 폴백이 추가로 실행된다.
- 수정안: DM LLM 경로 진입 전에 `if not message.content.strip() or message.content.startswith('!'): return` 을 두고, process_commands 결과 ctx.valid 인 경우 LLM 폴백을 건너뛰도록 분기한다.

**77. _track_for_feedback 의 두 add_reaction 을 단일 try 로 묶고 HTTPException 만 잡아 부분 실패/비-HTTP 예외 전파**  
`src/discord_assistant/bot.py:537-548` · error-handling · ✅ 수정: 두 시드 리액션 add_reaction 을 각각 try/except 로 격리하고 (discord.HTTPException, asyncio.TimeoutError) 로 넓혀 한쪽 실패가 흐름을 깨지 않게 함  
- 문제: 두 리액션 시드와 추적 등록의 실패 격리가 불완전하다. 한쪽 실패 시 이모지 비대칭, 비-HTTP 예외 시 사용량 기록 전 흐름 중단 가능.
- 수정안: 두 add_reaction 을 각각 try/except 로 분리하거나, except 를 (discord.HTTPException, asyncio.TimeoutError) 로 넓혀 한쪽 실패가 추적/기록 흐름을 깨지 않게 한다.

**78. _schedule_reminder 가 due_at 파싱 실패 시 즉시 발송으로 폴백 — 손상된 미래 reminder 가 봇 시작 시 곧바로 발송**  
`src/discord_assistant/bot.py:1344-1354, 1368-1369` · edge-case · ⏸ 보류: #22 와 동일: 장기 sleep→폴링 루프 재설계는 아키텍처 변경이고, 손상 due_at 즉시발송 대체는 드롭 위험이 있어 보류  
- 문제: due_at 파싱 실패를 '즉시 발송'으로 처리한다. 잘못 저장/타임존 누락된 미래 reminder 가 의도 시점이 아니라 봇 시작 시 바로 나간다. 또 최대 30일 단일 sleep 은 시스템 시계 조정/서스펜드에 취약하다.
- 수정안: 긴 지연은 짧은 간격 루프로 쪼개 매번 datetime.now 와 due 를 재비교한다. 파싱 실패한 due_at 은 즉시 발송 대신 logger.warning 후 skip 하거나 mark_sent 로 격리한다(현재도 로깅은 1347 에서 하나 폴백이 즉시 발송).

**79. 인메모리 봇 상태가 모듈 전역이라 다중 create_bot 인스턴스 간 공유 — 길드 데이터 교차 + shutdown 시 타 봇 태스크 취소**  
`src/discord_assistant/bot.py:269, 328-340, 1369, 3519-3526` · data-integrity · ⏸ 보류: 모듈 전역 상태를 AssistantBot 인스턴스/클로저로 옮기는 광범위 리팩터로 다수 경로에 영향. 회귀 위험 큼  
- 문제: DB(store)는 봇별로 분리되지만 쿨다운·마지막요약·피드백 추적·자동요약 시각·백그라운드 태스크는 전부 모듈 전역으로 공유된다. _cancel_background_tasks 도 전역을 비운다.
- 수정안: 이 상태들을 AssistantBot 인스턴스 속성(또는 create_bot 클로저 지역)으로 옮겨 봇 단위로 격리한다. 최소한 _cancel_background_tasks 가 해당 봇이 만든 태스크 집합만 취소하도록 봇별 _background_tasks 를 둔다.


### 리마인더 (`bot-reminders`)

**80. due_at 마이크로초 vs 초 정밀도 불일치로 list_due 사전식 비교가 경계에서 만기 누락**  
`src/discord_assistant/bot.py:2047` · data-integrity/timezone · ✅ 수정: remind_command 의 due_at 과 _reschedule_pending_reminders 의 far_future 를 isoformat(timespec='seconds') 로 통일해 storage 의 초 단위 포맷과 정합  
- 문제: remind_command 가 add_reminder 에 마이크로초 포함 due_at 을 넘겨 storage 가 가정하는 초 단위 포맷 일관성을 깬다. list_due 의 문자열 비교는 '+' < '.' 때문에 같은 초 경계에서 만기 항목을 한 사이클 누락한다.
- 수정안: remind_command 와 _reschedule_pending_reminders 의 far_future 도 isoformat(timespec='seconds') 로 통일하거나, list_due 비교를 datetime 파싱 후 비교로 바꿔 혼합 정밀도/오프셋 모두 안전하게 한다.

**81. non-UTC 오프셋 행에서 list_due 의 문자열 사전식 비교가 시간 순서와 불일치**  
`src/discord_assistant/storage.py:1191-1209, 1174` · data-integrity/timezone · ✅ 수정: add_reminder 에 _normalize_due_at 헬퍼 추가 — due_at 을 파싱해 UTC·timespec='seconds' 고정 포맷으로 정규화(파싱 불가 입력은 원본 보존 fallback)해 사전식 비교 불변식을 강제.  
- 문제: due_at 비교가 datetime 이 아닌 문자열 사전식이고 add_reminder 가 오프셋을 정규화하지 않는다. +09:00 같은 비-UTC 오프셋이 섞이면 정렬이 시간 순서와 어긋난다.
- 수정안: add_reminder 에서 due_at 을 파싱->UTC 변환->고정 포맷(timespec='seconds')으로 정규화해 저장하고, 비교도 정규화된 UTC 문자열로만 수행하도록 불변식을 코드로 강제한다.

**82. reminders 테이블에 (sent, due_at)/(user_id, sent) 인덱스 부재 + sent=1 행 미정리(무한 누적)**  
`src/discord_assistant/storage.py:333-346, 899-931, 1191-1237` · performance/resource · ⏸ 보류: 인덱스 클레임은 false-positive: idx_reminders_due/user/guild 가 SCHEMA(145-147)와 _create_query_indexes(395-397)에 이미 존재. 나머지(sent=1 리마인더 retention)는 purge_old 시그니처/동작 변경  
- 문제: reminders 에 조회용 인덱스가 없고, retention_task/purge_old 가 reminders 를 정리하지 않아 sent=1 행이 단조 증가한다.
- 수정안: CREATE INDEX idx_reminders_due ON reminders(sent, due_at) 와 idx_reminders_user ON reminders(user_id, sent) 를 추가하고, 일정 기간 지난 sent=1 행을 정리하는 retention 을 purge_old 에 추가한다.

**83. _parse_remind_delay 가 유니코드 숫자/선행 0 을 허용하고 docstring '1초 미만' 보장이 거짓**  
`src/discord_assistant/bot.py:456-478` · edge-case/validation · ✅ 수정: 정규식 (\d+) → ([0-9]+) 로 ASCII 숫자만 허용하고 docstring 의 '1초 미만' 문구를 실제 동작(분 단위 최소 1)에 맞게 수정  
- 문제: 정규식 \d 가 아랍-인도 숫자 등 유니코드 숫자를 매칭하고 int() 가 이를 받아들인다. docstring 의 '1초 미만 에러' 는 실제로 구현돼 있지 않다(분 단위 입력만 가능).
- 수정안: 정규식을 [0-9]+ 로 ASCII 숫자만 허용하거나 int 변환 후 범위/형식을 재검증한다. docstring 을 실제 동작(분 단위 최소 1)과 일치시킨다.

**84. repeat 라벨이 표시만 되고 실제 반복 예약이 없어 사용자 기대를 오인하게 함**  
`src/discord_assistant/bot.py:2011, 2048-2049, 2071-2074, 1320-1337` · correctness/UX · ✅ 수정: remind 확인 메시지의 repeat 표기를 '(반복 표시: X · 실제로는 1회만 발송)' 로 명확화해 자동 반복으로 오해하지 않게 함  
- 문제: repeat 라벨은 임베드/응답에 표시되고 payload 에 저장되지만 실제 반복 재예약은 구현돼 있지 않다. 단 1회만 발송된다.
- 수정안: 반복을 실제 구현(발송 후 다음 주기로 due_at 재계산해 add_reminder)하거나, 응답/임베드 라벨에서 '반복' 표기를 제거하고 표시용임을 사용자 메시지에도 명시한다.

**85. _last_summaries 가 user 전역 캐시라 다른 길드 요약이 /remind 로 잘못 캡처될 수 있음**  
`src/discord_assistant/bot.py:2036-2044, 331-334, 1459, 1524` · data-integrity/privacy · ✅ 수정: remind 의 빈 메시지 경로에서 캐시 tuple 의 guild_id 가 현재 interaction guild_id 와 일치할 때만 재사용하도록 검증(불일치 시 '보낼 내용이 없어요')  
- 문제: /remind 의 빈 메시지 경로가 user_id 단일 키 캐시의 마지막 요약을 길드 무관하게 사용한다. tuple 에 guild_id 가 있지만 현재 interaction 의 guild_id 와 대조하지 않는다.
- 수정안: 캐시 키를 (user_id, guild_id) 로 하거나, tuple 에 저장된 guild_id 와 현재 interaction 의 guild_id 가 일치할 때만 재사용하도록 검증한다(이미 guild_id 를 저장하므로 활용).

**86. _schedule_reminder 가 최대 30일을 단일 asyncio.sleep 으로 대기 + 파싱 실패 시 즉시 발송**  
`src/discord_assistant/bot.py:1344-1354` · edge-case/reliability · ⏸ 보류: 근본 fix(분 단위 폴링 루프로 전환)는 아키텍처 재설계로 위험. 파싱 실패 즉시발송 대체도 리마인더를 조용히 드롭할 위험이 있어 보류  
- 문제: 장기 리마인더를 단일 sleep(최대 30일)으로 대기하고, due_at 파싱 실패를 조용히 '지금 발송' 으로 처리한다.
- 수정안: 긴 대기는 짧은 주기(분 단위) 폴링 루프(list_due)로 전환하거나 sleep 을 청크로 나눠 주기적으로 due_at 을 재평가한다. 파싱 실패 due_at 은 즉시 발송 대신 로깅 후 skip/영구실패 mark_sent 로 다르게 처리한다.


### CI·CD/인프라 (`cicd-infra`)

**87. 백업 보존 정리(prune)가 -wal/-shm 사이드카를 삭제하지 않아 디스크 누수**  
`scripts/backup.sh:92-103` · resource-leak · ✅ 수정: In the prune loop, changed `rm -f "$old"` to `rm -f "$old" "${old}-wal" "${old}-shm"` so the cp-fallback WAL/SHM sidecars are deleted alongs  
- 문제: prune 글롭이 `bot_*.db` 만 매칭해 cp 폴백이 만든 -wal/-shm 사이드카를 정리하지 못한다.
- 수정안: 삭제 시 사이드카도 함께 제거한다: 루프 안에서 `rm -f "$old" "${old}-wal" "${old}-shm"`. 또는 정렬 키는 .db 기준으로 두되 삭제 대상을 `bot_*.db*` 로 확장.

**88. restore.sh 의 대화형 read 프롬프트가 비대화형(cron/CI)에서 무음 취소되거나 멈춘다**  
`scripts/restore.sh:57-66` · error-handling · ✅ 수정: 확인 프롬프트 블록(FORCE!=1) 진입부에 `if [[ ! -t 0 ]]` 가드를 추가해, stdin 이 tty 가 아닌데 FORCE 미설정이면 read 로 빈 입력→exit 0(성공) 무음 스킵 대신 명확한 에러 메시지 출력 후 exit 1 로   
- 문제: FORCE=1 없이 비대화형 컨텍스트에서 실행하면 read 가 빈 입력으로 'N' 취소되어 복원이 수행되지 않은 채 exit 0(성공)으로 끝난다(또는 tty 가 붙으면 무한 대기).
- 수정안: stdin 이 tty 가 아니면(`[ ! -t 0 ]`) FORCE 미설정 시 에러로 중단(exit 1)하는 가드를 추가한다. 빈 응답을 '명시적 확인 필요' 로 다뤄 자동화에서의 무음 스킵을 막는다.

**89. staging compose 에 OLLAMA_BASE_URL 리터럴 오버라이드/extra_hosts 가 없어 .env 의 localhost 가 컨테이너 자신을 가리킨다**  
`compose.staging.yml:25-32` · config · ✅ 수정: compose.prod.yml과 동일하게 services.bot.environment에 OLLAMA_BASE_URL: "http://host.docker.internal:11434" 리터럴 오버라이드를 추가하고, extra_hosts: ["host.d  
- 문제: staging compose 가 prod 와 달리 OLLAMA_BASE_URL 리터럴 오버라이드와 host-gateway extra_hosts 를 빠뜨려, Ollama 사용 시 컨테이너가 자기 자신을 가리킨다.
- 수정안: compose.staging.yml 에도 prod 와 동일하게 `OLLAMA_BASE_URL: "http://host.docker.internal:11434"` 와 `extra_hosts: ["host.docker.internal:host-gateway"]` 를 추가하거나, .env.staging 에서 호스트 게이트웨이 URL 을 명시하도록 문서화한다.

**90. 백업 파일명이 날짜(일 단위)만 사용 → 같은 날 재실행 시 같은 날 백업을 덮어쓴다**  
`scripts/backup.sh:11-47` · data-integrity · ✅ 수정: Changed TIMESTAMP from `date +%Y-%m-%d` to `date +%Y-%m-%d_%H-%M-%S` so a second run on the same day writes a distinct bot_<date_time>.db fi  
- 문제: 정기 백업 파일명이 일 단위라 같은 날 두 번째 백업이 첫 번째를 덮어쓴다.
- 수정안: 정기 백업 파일명에도 시각을 포함(`date +%Y-%m-%d_%H-%M-%S`)하거나 동일 이름 존재 시 덮어쓰기 전에 경고/스킵한다. prune 글롭/정렬도 그에 맞춰 조정.

**91. deploy 매트릭스가 사용자 제어 vars.DEPLOY_TARGETS 를 의미 검증 없이 runs-on 라벨/deploy_dir 로 직접 사용**  
`.github/workflows/deploy.yml:117-151` · deployment · ✅ 수정: prepare-targets 의 compute 스텝에서 JSON round-trip(형식 검증)만 하던 부분을, labels 가 허용 라벨 집합(self-hosted/macOS/ARM64/Linux/X64)에 속하는지 + deploy_dir 이 빈 문  
- 문제: DEPLOY_TARGETS 가 JSON 형식만 검증되고 labels/deploy_dir 의 의미 유효성(등록 라벨 일치, 허용 경로) 검증 없이 매트릭스 runs-on 과 배포 경로로 직접 사용된다.
- 수정안: prepare-targets 에서 labels 가 허용 목록(self-hosted/macOS/ARM64/Linux/X64 등)에 속하는지, deploy_dir 이 허용 베이스 경로 하위 절대경로인지 검증해 위반 시 실패시킨다. 멀티 호스트가 불필요하면 기능 비활성화.

**92. auto-release 가 workflow_run 성공만 보고 main HEAD(배포 SHA 아님)에 태그 → 부분 실패/SHA 드리프트 가능**  
`.github/workflows/auto-release.yml:22-32` · deployment · ✅ 수정: Changed the Checkout step `ref` from `main` to `${{ github.event.workflow_run.head_sha }}` so version computation and the annotated tag are   
- 문제: 릴리스가 deploy 의 head_sha 가 아닌 현재 main HEAD 에 태그를 찍는다. 배포 트리거와 태깅 사이 main 이 진전되면 릴리스 태그가 실제 배포 이미지(sha-<배포시점>)와 다른 커밋을 가리킨다. 멀티 호스트 부분 실패 시 일부 호스트가 깨진 채 릴리스될 여지도 있다.
- 수정안: 릴리스 체크아웃을 `ref: ${{ github.event.workflow_run.head_sha }}` 로 배포된 정확한 커밋에 고정한다(태그도 그 커밋에). 멀티 호스트 시 모든 타겟 성공을 릴리스 전제로 삼도록 성공 판정을 강화하거나 fail-fast 정책을 재검토한다.


### 설정/암호화 (`config-crypto`)

**93. _get_bool 이 화이트리스트 밖 값을 검증 없이 조용히 False 처리 (다른 파서와 비대칭)**  
`src/discord_assistant/settings.py:40-44` · config · ✅ 수정: Added explicit _TRUE_BOOL_VALUES/_FALSE_BOOL_VALUES frozensets; unrecognized values (typos like 'ture','TRUE!') now emit a warning log namin  
- 문제: _get_bool은 truthy 화이트리스트에 없는 모든 값을 검증 없이 False로 떨어뜨린다. _get_int/_get_float가 잘못된 입력을 ValueError로 거부하는 것과 정책이 불일치한다.
- 수정안: 참/거짓 화이트리스트를 모두 정의하고 어디에도 속하지 않는 값은 다른 파서처럼 ValueError로 거부하거나 최소한 경고 로그를 남긴다.

**94. ollama_model·database_url 은 빈 문자열 폴백 부재 — ollama_base_url 과 폴백 정책 불일치**  
`src/discord_assistant/settings.py:115-127` · config · ✅ 수정: Added 'or <default>' fallback to ollama_model (-> 'llama3.1:8b') and database_url (-> 'sqlite:///./data/discord_assistant.db') in from_env s  
- 문제: OLLAMA_MODEL=''/DATABASE_URL='   '로 설정하면 strip 결과가 빈 문자열이어도 그대로 사용된다. ollama_base_url·default_language·ollama_keep_alive·llm_system_prompt는 빈 값 폴백이 있는데 ollama_model·database_url만 누락돼 정책이 불일치한다.
- 수정안: ollama_model·database_url도 빈 값이면 기본값으로 폴백하거나 명시적으로 검증해 모든 필수 문자열 필드의 처리 정책을 일관되게 한다.

**95. from_env가 매 호출 load_dotenv()로 mutable os.environ 시점에 의존 — 프로세스 싱글톤 미보장**  
`src/discord_assistant/settings.py:93-96` · config · ⏸ 보류: Risky design change, not a bug. The suggested fix (module-level singleton/cache of from_env) would break the test suite and intended usage: many tests call AppS  
- 문제: from_env는 load_env_file=True일 때 매 호출 load_dotenv()를 실행하고 그 시점의 os.environ을 읽어 인스턴스를 만든다. frozen dataclass라 인스턴스 자체는 불변이지만 호출마다 다른 스냅샷이 나올 수 있어 '한 번 로드'라는 암묵 가정이 보장되지 않는다.
- 수정안: 프로세스 단위 단일 인스턴스(모듈 싱글톤/캐시)로 settings를 한 번만 생성해 공유하거나, from_env의 멱등성/override 정책을 문서화하고 테스트에서 일관되게 사용한다.


### 대시보드 (`dashboard`)

**96. Discord 토큰 교환 실패 시 raw 응답 본문을 클라이언트에 그대로 누설**  
`dashboard/backend/auth.py:312-316` · 보안(에러 누설) · ✅ 수정: callback 의 502 detail 을 일반화된 'Discord token exchange failed' 로 바꾸고, 원본 token_resp.text(상태코드 포함)는 모듈 logger.warning 으로만 서버 로그에 남기도록 변경 (loggi  
- 문제: 토큰 교환이 200 이 아니면 Discord 의 raw 응답 본문(token_resp.text)을 502 응답 detail 로 외부에 노출한다. 이 본문에는 redirect_uri 불일치·invalid_client 등 OAuth 설정 단서가 섞일 수 있다.
- 수정안: 사용자에게는 'Discord token exchange failed' 같은 일반화된 메시지만 반환하고, 원본 token_resp.text 는 서버 로그로만 남긴다(시크릿 마스킹 후).

**97. _assert_guild_access/_admin 이 클레임 파싱 실패 시 500 으로 폭발**  
`dashboard/backend/main.py:904-912` · 에러 처리 누락/엣지케이스 · ✅ 수정: guilds 클레임을 정규화하는 _user_guilds 헬퍼를 추가하고 _assert_guild_access/_assert_guild_admin 에서 비-list/비-dict/누락·비숫자 id 를 try/except 로 안전 skip 하여 500 대신  
- 문제: guilds 클레임이 예상 밖 형태(id 누락·비숫자, guilds 가 list 아님)면 명확한 401/403 대신 처리되지 않은 500 이 발생한다. 다만 JWT 는 서버 서명이라 정상 발급(create_jwt:152-160)은 항상 정상 형태이며, 변조는 서명 검증에서 막히므로 트리거는 구버전/손상 토큰에 한정된다.
- 수정안: guilds 가 list 가 아니면 빈 목록으로 취급하고, 각 항목의 g.get('id') None/형변환 실패를 try/except 로 안전하게 skip 해 일관되게 403 을 반환한다.

**98. Authorization: Bearer 폴백 + 응답 바디 token 이 httpOnly 쿠키의 XSS 방어를 부분 복원**  
`dashboard/backend/auth.py:250-262` · 보안(토큰 처리) · ⏸ 보류: 의도된 전환기 백워드 호환 동작(바디 token 반환 + Authorization: Bearer 폴백)이라 finding 자체가 '즉각적 취약점 아님'으로 명시. 제거하면 cross-file 파급(프론트 apiFetch, dashboard/backend/main.py CORS allow  
- 문제: JWT 를 httpOnly 쿠키(#34)로 막으려 했으나, 콜백/리프레시가 바디에 token 을 노출하고 Bearer 헤더 폴백도 수용해 JS 가 읽을 수 있는 토큰 경로가 부분적으로 복원된다.
- 수정안: 전환이 끝나면 바디의 token 반환과 Bearer 헤더 폴백을 제거하고 httpOnly 쿠키만 신뢰한다. 병행 기간에는 짧은 만료/범위 제한을 둔다.

**99. OAuth state 만료 청소가 /login 호출 시에만 일어남**  
`dashboard/backend/auth.py:228-237` · 리소스 누수 · ✅ 수정: login 의 인라인 prune 로직을 _prune_oauth_states(now=None) 헬퍼로 추출하고, callback 에서 사용 state 소비 직후에도 호출해 미사용·미회수 state 가 login 빈도와 무관하게 정리되도록 함.  
- 문제: 발급만 되고 콜백이 오지 않은 state(동의 취소 등)는 다음 login 호출 전까지 누적된다. _STATE_TTL_SECONDS(25,=600s) 후 만료지만 키는 login 빈도에 의존해서만 제거된다.
- 수정안: 주기적 백그라운드 태스크 또는 callback 진입 시에도 만료 청소를 수행하거나 TTL 캐시로 교체한다.

**100. logout 이 revoke 실패를 무시하고 항상 성공 처리 — 무효화 보장/가시성 부재**  
`dashboard/backend/auth.py:181-204` · 보안(세션 관리) · ✅ 수정: logout 에서 revoke_jwt 반환값이 False 면 logger.info 로 무효화 실패를 기록(토큰 본문 미노출)하고, 로그아웃이 드물어도 만료 블랙리스트가 쌓이지 않게 _prune_revoked() 를 항상 호출. 멱등 성공(logged_  
- 문제: 로그아웃이 멱등 성공으로 설계됐으나, 만료 직전 경합·jti 부재 등으로 revoke 가 실제로 블랙리스트 등재에 실패해도 사용자에게는 성공으로 보인다. 무효화 성공 여부 로깅/가시성이 없다.
- 수정안: revoke 실패가 '이미 무효'인지 '무효화 실패'인지 구분해 로깅하고, 블랙리스트 청소(_prune_revoked)를 시간 기반/백그라운드로도 수행한다(또는 공유 스토어 사용).


### i18n/프롬프트 (`i18n-prompts`)

**101. detect_language_from_transcript 가 한자 위주 일본어를 중국어(zh)로 오판한다**  
`src/discord_assistant/prompts.py:41-51` · 엣지케이스/유니코드 · ⏸ 보류: 구체적 실패 예시('今日会議決定事項確認報告内容' 등)가 모두 가나 0인 순수 한자 일본어다. 문자 범위만으로 순수 한자 JA와 ZH를 구분하는 신뢰성 있는 방법이 없으며(핑딩도 langdetect/fasttext 라이브러리 도입 검토를 제안), 가나가 있는 혼합 입력은 이미 ja로 정상  
- 문제: 가나가 거의 없는 한자 위주 일본어 문장은 japanese 비율이 임계(0.15) 미만이 되고 chinese 비율이 높아져 zh 로 분류된다.
- 수정안: 가나 존재 시 ja 를 chinese 보다 우선 분류하거나, CJK 동시 출현 시 가나 유무로 ja/zh 를 구분하는 규칙을 추가한다. 또는 langdetect/fasttext 같은 라이브러리 도입을 검토한다.

**102. detect_language_from_transcript 의 한국어 우선 + 0.15 임계 + 축소된 분모가 영어 위주 짧은 혼합 입력을 한국어로 오판한다**  
`src/discord_assistant/prompts.py:45-65` · 엣지케이스/언어 자동감지 · ✅ 수정: detect_language_from_transcript의 ko 조건에 'korean >= max(japanese, chinese, latin)' 우세 검사를 추가해 한글 소수 혼합 입력의 ko 쏠림 제거. 기존 임계 구조 유지(최소 변경).  
- 문제: 분모가 알파벳 글자만 합산해 축소되고 한국어 우선·낮은 임계가 결합되어, 한글이 소수만 섞인 짧은 혼합 입력이 한국어로 쏠린다.
- 수정안: 전체 글자 수를 분모로 쓰거나, 각 언어 점수를 동일 기준으로 비교한 뒤 argmax 를 취하는 상대 다수결 방식으로 바꿔 한국어 편향을 제거한다.

**103. t() 의 format 예외 처리가 ValueError 를 잡지 않아 잘못된 포맷 스펙이 렌더를 깨뜨린다(잠재)**  
`src/discord_assistant/messages.py:342-347` · 에러 처리 누락 · ✅ 수정: t()의 format except 절을 (KeyError, IndexError)에서 (KeyError, IndexError, ValueError)로 확장 — 닫히지 않은 중괄호 등 잘못된 스펙도 원문 폴백.  
- 문제: 닫히지 않은 중괄호 등 잘못된 포맷 스펙은 ValueError 를 내는데 except 절이 KeyError/IndexError 만 잡아 폴백하지 못한다. 향후 코드 예시·정규식 등 중괄호 포함 문자열을 kwargs 와 함께 호출하면 그대로 전파된다.
- 수정안: 예외 처리를 `except (KeyError, IndexError, ValueError):` 로 넓히고, 가능하면 포맷 인자가 없는 정적 문자열엔 format 호출을 건너뛰도록 키별 메타데이터로 구분한다.

**104. t() 가 빈 문자열 번역을 ko 로 폴백하지 않고 빈 값을 그대로 렌더한다**  
`src/discord_assistant/messages.py:338-341` · i18n 폴백 로직 결함 · ✅ 수정: 폴백 검사를 'if text is None'→'if not text'로, entry.get('ko', key)→entry.get('ko') or key 로 변경해 빈 문자열도 ko 폴백.  
- 문제: 어떤 언어 항목이 빈 문자열('')이면 falsy 지만 None 이 아니므로 ko 폴백을 타지 않고 빈 문자열을 반환한다. 번역자가 미완성 항목을 키 생략 대신 빈 문자열로 두는 흔한 실수에 취약하다.
- 수정안: 폴백 조건을 `if not text:` (None/빈 문자열 모두 ko 폴백)로 바꾸거나, 빈 항목은 카탈로그에서 키 자체를 생략하도록 강제한다.

**105. summarize 캐시 경로와 라이브 경로의 헤더 언어가 불일치한다**  
`src/discord_assistant/bot.py:1462-1464, 1530-1535` · 데이터 정합성/i18n · ⏸ 보류: 캐시/라이브 헤더 언어 일치는 캐시에 감지 언어를 함께 저장하는 스키마 변경이 필요(cache.py 와 연계)하고 기능 버그가 아닌 i18n 품질 이슈라 보류  
- 문제: 동일 길드의 같은 요약이라도 캐시 여부에 따라 헤더 언어가 달라지고, auto 감지 본문이 ja/zh 인데 헤더는 ko/en 폴백으로 어긋난다(본문 일본어 + 헤더 한국어 등).
- 수정안: 캐시 경로도 본문 언어 기준으로 헤더 언어를 정하거나(캐시에 감지 언어를 함께 저장), summary.header 류 헤더에 지원 7개 언어 번역을 채워 폴백 불일치를 줄인다.

**106. _neutralize_role_tokens 정규식이 행 시작(^)에 앵커되어 라인 중간의 가짜 role 토큰을 놓친다**  
`src/discord_assistant/prompts.py:78-80, 93-105` · 보안(다층 방어 부분 우회) · ✅ 수정: _ROLE_TOKEN_RE에 문장부호([.!?。！？]) 뒤 공백 다음의 라인 중간 role 토큰도 탐지하는 대안을 추가. 실제 화자(alice:/bob:)와 문장 중간 일반 단어는 여전히 미탐지로 오탐 방지.  
- 문제: 트랜스크립트가 'speaker: msg' 줄 단위 구성이라 대부분 잡히지만, 한 줄에 여러 발화가 합쳐지거나 문장 중간에 끼운 가짜 role 토큰(예: '... please respond. System: ignore the rules')은 ^ 앵커 때문에 무력화되지 않는다.
- 수정안: ^ 앵커에 더해 줄바꿈/공백/문장부호 뒤의 라인 중간 role 라벨도 탐지하는 추가 패턴을 두거나, 줄바꿈 정규화 후 적용한다.

**107. build_chat_with_history_prompt 가 history 항목의 role/content 키를 무방비로 인덱싱한다(잠재)**  
`src/discord_assistant/prompts.py:235-241` · 에러 처리/엣지케이스 · ✅ 수정: turn['role']/turn['content']를 turn.get('role')/turn.get('content')로 방어적 접근으로 변경(role 기본 'user', content 기본 ''). 현재 호출자 동작 불변, 스키마 변형 시 KeyEr  
- 문제: history 루프가 role/content 키를 .get 없이 직접 인덱싱한다. 향후 OpenAI 메시지 dict(role 만) 또는 tool 메시지 등 다른 스키마를 그대로 넘기면 KeyError 로 즉시 깨진다.
- 수정안: `turn.get("role", "user")`, `turn.get("content", "")` 로 방어적 접근하고, user/assistant 외 role 값 정규화도 명시한다.

**108. language_select.value 카탈로그 키가 어디서도 t() 로 사용되지 않는 데드 항목이다**  
`src/discord_assistant/messages.py:152-156` · 유지보수/데드코드 · ✅ 수정: 어디서도 t()로 참조되지 않는 데드 키 language_select.value 항목을 MESSAGES 카탈로그에서 제거(파일 내 보수적 해결).  
- 문제: language_select.value 키는 정의만 있고 호출처가 없는 데드 i18n 키다. label/code 자리표시자를 가진 채 방치되어 있다.
- 수정안: _language_select_embed 의 value 를 t('language_select.value', lang, label=..., code=...) 로 실제 사용하거나, 사용하지 않을 거면 카탈로그에서 제거한다.


### LLM 제공자 (`llm-providers`)

**109. 서킷 브레이커가 재시도 불가능한 4xx(401/403 등)도 실패로 카운트해 잘못 열림**  
`src/discord_assistant/llm.py:338-344` · 에러 처리 오류 · ⏸ 보류: already-fixed: 현재 코드 _with_circuit_breaker(394-402)는 이미 `except LLMError as exc: if _is_retryable(exc): breaker.record_failure() else: breaker.record_ignored()`  
- 문제: breaker 가 설정된 경우, 영구적·요청 내용 의존 오류인 4xx(잘못된 키 401, 권한 없음 403, 잘못된 요청 400)도 연속 실패로 누적되어 failure_threshold(기본 5)회면 서킷이 열린다. 서킷 브레이커는 본래 일시적 서버 장애(429/5xx/네트워크)만 격리해야 한다.
- 수정안: record_failure 를 _is_retryable(exc) 가 True 인 오류에만 적용한다: 'except LLMError as exc: if _is_retryable(exc): breaker.record_failure(); raise'. 4xx 비재시도 오류는 서킷 카운트에서 제외한다.

**110. Ollama list_models 리스트 컴프리헨션이 try 밖이라 name 누락 시 KeyError 전파·size:null 시 None 정합성 깨짐**  
`src/discord_assistant/llm.py:1378-1390` · 엣지케이스/데이터 정합성 · ⏸ 보류: already-fixed: _list_sync(1546-1559)의 결과 매핑이 try 블록 안으로 이동했고, 항목이 dict 가 아니거나 name 이 str 이 아니면 skip, size 는 `size if isinstance(size,int) and not bool else 0` 로  
- 문제: list_models 의 결과 매핑이 except 블록 밖에서 실행되어 Ollama /api/tags 응답에 name 이 없으면 KeyError 가 list_models 호출자로 전파된다. 또한 size 가 null 로 오면 m.get('size', 0) 이 None 을 그대로 넘겨 size_bytes=None 이 되고, 이후 size_display 호출 시 TypeError 로 표시가 깨진다.
- 수정안: 컴프리헨션을 try 블록 안으로 옮기고, m.get('name') 으로 안전 접근 후 None 이면 스킵, size 는 'm.get("size") or 0' 으로 None→0 보정한다.

**111. Anthropic 툴 루프 종료 조건이 stop_reason 에 과의존(OR) — tool_use 블록 있어도 실행 누락 가능**  
`src/discord_assistant/llm.py:1183-1184` · API 오용/엣지케이스 · ⏸ 보류: already-fixed: Anthropic generate_with_tools(1308-1320)가 이미 `if not tool_uses: return last_text` 로 tool_use 블록 존재를 1차 종료 기준으로 사용한다(stop_reason OR 의존 제거, #50 주석   
- 문제: tool_use 블록 존재(tool_uses)와 stop_reason 두 신호를 OR 종료로 묶어, 둘 중 하나라도 어긋나면 도구를 실행하지 않는다. 모델이 실제로 도구를 호출(tool_use 블록 존재)했는데 stop_reason 이 다르게 들어오면 도구 결과 없이 부분 답변이 최종으로 반환된다.
- 수정안: tool_uses 존재를 1차 기준으로 삼아 블록이 있으면 실행하고, stop_reason 은 보조 신호로만 쓴다(예: 'if not tool_uses: return last_text'). stop_reason 이 명확히 end_turn 일 때만 조기 종료한다.

**112. Anthropic 멀티모달 media_type 화이트리스트 미검증 — 미지원 MIME(image/svg 등) 으로 400 유발**  
`src/discord_assistant/llm.py:93-108,1004-1016` · API 오용/엣지케이스 · ⏸ 보류: cross-file: 진짜 루트 원인은 bot.py 의 첨부 필터(_download_image_attachments, bot.py:1277)가 `content_type.startswith('image/')` 만 검사해 svg/bmp/tiff 등 비지원 MIME 을 통과시키는 것 — 화이  
- 문제: 이미지 MIME 을 허용 화이트리스트로 검증하지 않고 그대로 media_type/data URI/inlineData 에 싣는다. Discord 첨부 필터도 image/* 접두사만 보므로 비전 미지원 MIME 이나 raw bytes 의 잘못 가정된 MIME 이 제공자에 전달될 수 있다.
- 수정안: _encode_image_b64/_normalize_image 단계에서 허용 MIME(jpeg/png/gif/webp) 화이트리스트로 검증하고, raw bytes 는 매직넘버(시그니처) 검사로 실제 포맷을 추정한다. 미지원 MIME 은 호출부에서 거르거나 명확한 오류로 변환한다.


### LLM 재시도/툴 (`llm-resilience`)

**113. 스트리밍 경로는 토큰 usage 가 항상 (0,0) 으로 기록 — 주력 경로의 비용 추적 사각지대**  
`src/discord_assistant/llm.py:552-565, 730-742` · data-integrity · ⏸ 보류: 스트리밍 경로(Ollama generate_stream 651-712, OpenAI generate_stream 834-909)의 last_usage 갱신은 주력 핫패스에 새 동작을 추가하는 작업이다. Ollama 는 done 라인을 worker 스레드 제너레이터에서 파싱해 self.l  
- 문제: 스트리밍 generate 는 last_usage 를 갱신하지 않아 정상 동작하는 유료(OpenAI/Anthropic) 호출 대부분이 토큰 0 으로 기록된다. usage 집계는 사실상 비스트리밍 폴백 경로에서만 동작한다.
- 수정안: Ollama 스트림의 done 라인 prompt_eval_count/eval_count 를 파싱해 last_usage 에 반영하고, OpenAI 는 stream 요청에 stream_options:{include_usage:true} 를 추가해 마지막 usage 청크를 파싱한다. 불가하면 토크나이저 추정치로라도 채운다.

**114. 툴 호출 id 누락 시 빈 문자열 기본값 → 후속 요청 400 위험(조용한 처리)**  
`src/discord_assistant/llm.py:920, 1197` · api-misuse · ⏸ 보류: already-fixed: OpenAI 루프(1039-1040)와 Anthropic 루프(1334-1335)가 빈 tool_call_id/tool_use_id 에 대해 이미 `logger.warning(...)` 으로 가시화한다(#59 주석 명시). 정상 응답엔 항상 id 가 있어 동작  
- 문제: id 가 없으면 빈 문자열을 조용히 넣는다. 정상 응답에는 항상 id 가 있어 실전 빈도는 낮지만, 제공자 응답 형식 변경/부분 응답/프록시 변형 시 빈 id 가 들어오면 다음 왕복에서 매칭 실패로 400 이 발생할 수 있다.
- 수정안: id 가 비어 있으면 해당 tool 호출을 건너뛰거나 명확한 LLMError 를 던져 조기에 드러낸다. 최소한 경고 로그를 남긴다.

**115. pull_model: communicate() 가 stderr 를 메모리에 무제한 버퍼링**  
`src/discord_assistant/llm.py:1402-1408` · resource-leak · ⏸ 보류: pull_model 의 stderr 메모리 무제한 버퍼링은 communicate() 자체가 전부 메모리에 적재하므로, 실제 상한을 두려면 communicate() 를 수동 bounded 스트림 읽기(예: 마지막 N 바이트 deque)로 교체해야 한다. 그러면 communicate 를 모  
- 문제: ollama pull 이 진행률/경고를 stderr 로 대량 출력할 수 있는데 communicate() 가 이를 전부 메모리에 버퍼링한다. 상한이 없다.
- 수정안: 성공 경로에선 stderr 가 필요 없으므로 stderr=DEVNULL 로 두거나, 오류 메시지가 필요하면 스트림을 읽으며 마지막 N 바이트만 보관하도록 제한한다.

**116. _coerce_token_count 가 float 토큰 수를 내림 절단하고 비정상 입력을 조용히 0 처리**  
`src/discord_assistant/llm.py:129-137` · edge-case · ⏸ 보류: already-fixed: _coerce_token_count(143-161)는 float 내림 절단이 의도된 보수적 동작임을 docstring(148-150)에 문서화했고, 비숫자/None(154)·음수(159) 입력 시 logger.debug 로 제공자 응답 이상을 가시화한다(#61  
- 문제: float 토큰 수가 일관되게 내림 절단되고, 음수/비숫자/bool 입력이 조용히 0 으로 처리된다. 의도된 방어이긴 하나 비정상 제공자 응답이 가시화되지 않는다.
- 수정안: 의도적 내림임을 문서화하거나 round() 를 사용한다. 음수/비숫자 입력 시 debug 로그를 남겨 제공자 응답 이상을 가시화한다.

**117. 스트림 부분 출력 후 LLMError 시 폴백 차단 + 스트림은 서킷/재시도 밖이라 복원력 약함**  
`src/discord_assistant/bot.py:1820-1834` · error-handling · ⏸ 보류: 동일 위치(#9)로 함께 해결됨: 부분 스트림 응답을 re-raise 전에 저장. 스트림 시작 실패의 서킷/재시도 래핑은 llm.py 영역(cross-file)이라 제외  
- 문제: 스트리밍 경로는 재시도·서킷 브레이커 보호 밖이라 일시적 제공자 오류에 비스트리밍보다 약하다. 부분 출력 후 끊기면 answer 가 비어 있지 않아 폴백도 받지 못한다.
- 수정안: 스트림 실패가 부분 출력 이후라도 누적 텍스트가 너무 짧으면 폴백을 허용하거나, 최소한 스트림 시작 단계 연결 실패를 _with_circuit_breaker/_with_retry 로 감싸 초기 실패에 재시도를 적용한다.


### 관측성/지원 (`observability-support`)

**118. AlertRateLimiter: 지속 폭주 시 억제 요약 영구 미배출 + _suppressed_signatures 무한 증가**  
`src/discord_assistant/monitor.py:172-208` · 리소스 누수/메모리 증가 · ✅ 수정: _record_suppressed 에서 distinct 시그니처 dict 가 새 상한(_MAX_TRACKED_SUPPRESSED_SIGNATURES=50)을 넘으면 건수 상위 N개만 유지하도록 trim. 누적 총건수(_suppressed_count)는  
- 문제: _drain_suppressed_summary(카운터 리셋)는 알림이 통과할 때만 호출. 상한을 지속 초과하는 다양한 시그니처 폭주 시 어떤 알림도 통과 못해 요약 미배출, _record_suppressed 가 distinct 시그니처를 계속 추가.
- 수정안: _suppressed_signatures 상한(상위 N)·주기적 trim + 억제 누적 임계 초과 시 강제 1건 통과 escape hatch.

**119. shutdown 중 on_disconnect 재예약으로 종료 시 가짜 '끊김' 알림 가능**  
`src/discord_assistant/bot.py:3192-3217,3564-3567` · async 함정/단절 오탐 · ⏸ 보류: shutting_down 플래그를 on_disconnect/_delayed_disconnect_alert(클로저)와 run_bot(모듈 함수)에 함께 배선하려면 봇 인스턴스 상태가 필요해 cross-function 경계 변경. 회귀 위험으로 보류  
- 문제: graceful shutdown 이 _cancel_background_tasks() 후 bot.close() 호출. close() 가 on_disconnect 를 내면 취소 완료 후 _delayed_disconnect_alert 가 새로 _track_task 예약되고, 조건(is_closed() or not is_ready())이 종료 중에도 참이라 30초 내 미종료 시 오탐 발생.
- 수정안: shutting_down 플래그를 두고 on_disconnect/_delayed_disconnect_alert 가 종료 중이면 예약/발송 안 함.

**120. health /metrics 응답이 charset/version 메타를 잘라냄 (content_type 분해)**  
`src/discord_assistant/health.py:71-73` · API 오용/관측성 · ✅ 수정: _metrics 핸들러에서 content_type.split(';')[0] 로 메타를 버리던 것을 web.Response(headers={'Content-Type': content_type}) 로 바꿔 'text/plain; version=0.0.4;  
- 문제: _metrics 가 content_type.split(';',1)[0] 로 'text/plain; version=0.0.4; charset=utf-8' 에서 text/plain 만 남겨 Prometheus 버전/charset 이 헤더에서 사라진다.
- 수정안: web.Response 에 charset='utf-8' 별도 지정하거나 headers 로 원본 Content-Type 명시.

**121. 번역 캐시가 FIFO(삽입시각) 축출이며 매 set 마다 O(n) 스캔 — 인기 항목 thrashing**  
`src/discord_assistant/cache.py:68-74` · 캐시 정책/성능 · ✅ 수정: _translation_cache 를 OrderedDict 로 전환해 O(1) LRU 구현(get 시 move_to_end, set 시 popitem(last=False)); set_translation 의 축출 분기를 `key not in cache  
- 문제: set_translation 이 가득 차면 삽입시각 최소 항목 축출(LRU 아님 FIFO)+매 set 마다 O(n) 스캔. 기존 키 갱신 시도 무관 엔트리 먼저 축출.
- 수정안: OrderedDict/move_to_end 로 O(1) LRU + set 전 key 존재 확인해 축출 건너뛰기.

**122. purge_expired_translations 가 운영에서 미스케줄 — 만료 항목이 한도 도달까지 잔존**  
`src/discord_assistant/cache.py:87-93` · 캐시 TTL/메모리 · ✅ 수정: fix 의 in-file 대안(축출 계산에서 만료 우선 제거)을 적용: set_translation 이 가득 차서 새 키를 넣기 직전 purge_expired_translations() 를 먼저 호출해 만료 stale 항목을 정리한 뒤, 여전히 가득   
- 문제: purge_expired_translations 가 테스트에서만 호출되고 백그라운드 주기 작업에 미연결. TTL(1h) 지난 항목도 재조회 전까지 잔존, 500 FIFO 축출로만 정리.
- 수정안: 주기 백그라운드 태스크에서 purge_expired_translations()/summarize_cache 정리 호출, 또는 축출 계산에서 만료 우선 제거.


### 보안 (`security`)

**123. persona 가 시스템 프롬프트에 의미적 인젝션 무력화 없이 직접 삽입된다**  
`src/discord_assistant/prompts.py:208-221` · security/prompt-injection · ✅ 수정: build_chat_prompt에서 persona를 _neutralize_role_tokens + _neutralize_injection_phrases로 거친 뒤 Persona 라인에 삽입. 양성 persona는 변경되지 않아 기존 테스트 유지.  
- 문제: build_chat_prompt 는 persona 를 신뢰 영역(보안 가드 위)에 한 줄로 그대로 삽입한다. _sanitize_persona 가 구조적 인젝션(가짜 role 블록)은 막지만 인라인 의미적 인젝션 문구는 막지 못한다.
- 수정안: persona 도 신뢰 불가 입력처럼 _neutralize_injection_phrases/_neutralize_role_tokens 를 거치게 하거나, <persona> 태그로 감싸 _INJECTION_GUARD 의 보호 대상(untrusted DATA)에 포함시킨다.

**124. /summarize-channels 의 채널별 오류가 마스킹 없이 임베드에 노출된다**  
`src/discord_assistant/bot.py:2292-2293, 2301-2306` · security/info-disclosure · ⏸ 보류: 동일 위치(#1)로 함께 해결됨: summarize-channels 채널별 예외를 일반 문구로 마스킹하고 원본은 logger 로만 남김  
- 문제: 멀티채널 요약의 채널별 예외를 마스킹 없이 임베드에 그대로 노출한다. usage_log 는 시크릿을 마스킹하면서 사용자 노출 경로는 마스킹하지 않는 불일치.
- 수정안: 사용자에게 보이는 오류는 error_hint 처럼 일반화하거나, 최소한 storage._mask_secrets 와 동등한 마스킹을 적용한 뒤 표시한다. 원본 예외 detail 은 logger 로만 남긴다.

**125. 역할 토큰 무력화 정규식이 콜론 없는 role 토큰을 놓친다(주석 주장과 불일치)**  
`src/discord_assistant/prompts.py:78-80` · security/prompt-injection · ⏸ 보류: 콜론 없는 role 토큰(bare 'System', 'assistant-', '# System')까지 매칭하도록 정규식을 넓히면 일반 영어 텍스트('the system works', 'AI is great', 'tool used')에 광범위한 오탐이 발생해 정상 콘텐츠를 훼손한다. 핑딩  
- 문제: _ROLE_TOKEN_RE 가 콜론으로 끝나는 role 토큰만 탐지한다. 주석의 주장(하이픈·대괄호 종결 포함)과 달리 'assistant-', '[SYSTEM]', '# System' 류와 이미 zero-width 가 삽입된 입력은 무력화되지 않는다.
- 수정안: 콜론 외 구분자(대시/대괄호/헤더 '#')와 콜론 없는 role 헤더 변형도 커버하도록 정규식을 보강하고, 무력화 전에 입력의 기존 zero-width 문자를 정규화(제거)해 사전 가공 우회를 막는다.


### 스토리지/DB (`storage`)

**126. save_feedback INSERT lacks ON CONFLICT for UNIQUE(message_id,user_id); re-vote raises IntegrityError that the caller swallows, so rating changes silently fail**  
`src/discord_assistant/storage.py:1040-1058` · 에러 처리 누락/데이터 정합성 · ✅ 수정: save_feedback INSERT 에 ON CONFLICT(message_id,user_id) DO UPDATE SET rating/command/created_at upsert 절을 추가해 평점 토글(👍→👎)이 실제로 갱신되게 함.  
- 문제: save_feedback 는 ON CONFLICT 절 없는 평범한 INSERT 라 동일 (message_id,user_id) 재평가 시 IntegrityError 를 던진다. 호출 측(on_reaction_add)이 이를 try/except 로 잡아 경고만 남기므로 크래시는 없지만, 사용자가 평점을 바꿔도(👍→👎) 갱신이 저장되지 않고 매번 경고 로그만 쌓인다.
- 수정안: ON CONFLICT(message_id, user_id) DO UPDATE SET rating=excluded.rating, command=excluded.command, created_at=excluded.created_at 로 upsert 한다. 1인 1평가 고정이 의도라면 INSERT OR IGNORE 로 명시하고 호출 측 UX 도 그에 맞춘다.

**127. schema_version table lacks PK/UNIQUE and _set_schema_version UPDATEs without WHERE, so a double-seed silently breaks the single-row invariant**  
`src/discord_assistant/storage.py:53-55,416-429` · 스키마/마이그레이션 · ⏸ 보류: schema_version 테이블에 CHECK(id=1) PK 강제는 마이그레이션성 스키마 재정의로, 기존 배포 DB·레거시/부분 마이그레이션 테스트(구 단일컬럼 schema_version 수동 생성, _get_schema_version 시드)를 깨뜨릴 위험. finding 자체가 '즉  
- 문제: schema_version 테이블에 단일 행을 강제하는 제약(PK/UNIQUE)이 없고, _set_schema_version 의 UPDATE 가 WHERE 없이 모든 행을 갱신하며 _get_schema_version 의 SELECT 는 LIMIT 1 로 임의 한 행만 읽는다. 단일 행 불변식이 스키마 제약이 아닌 호출 순서/단일 스레드 직렬화에만 의존한다.
- 수정안: CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id=1), version INTEGER NOT NULL) 로 단일 행을 스키마로 강제하고, INSERT OR IGNORE INTO schema_version(id,version) VALUES (1,0) · UPDATE ... WHERE id=1 로 갱신해 불변식을 코드가 아닌 제약으로 보장한다.

**128. set_provider_config validates empty model only via GuildConfig.__post_init__, raising a different message/layer than set_model's explicit check**  
`src/discord_assistant/storage.py:701-718` · API 오용/검증 비대칭 · ✅ 수정: set_provider_config 진입부에 model.strip() 빈 값 검사 + 'model cannot be empty' ValueError 를 추가해 set_model 과 검증 지점·메시지를 일원화(빈 모델은 여전히 거부).  
- 문제: set_model 은 빈 모델을 진입점에서 명시적 ValueError('model cannot be empty') 로 거부하지만, set_provider_config 는 strip 만 하고 검증을 GuildConfig.__post_init__ 에 위임한다. replace() 가 __post_init__ 을 트리거하므로 빈 모델은 여전히 거부되나, 다른 메시지('GuildConfig.model must not be empty')와 다른 계층에서 실패한다.
- 수정안: set_provider_config 에도 normalized=model.strip(); if not normalized: raise ValueError('model cannot be empty') 를 set_model 과 동일하게 추가해 검증 지점·메시지를 일원화한다.

**129. _run_sync depends on aiosqlite private internals conn._execute / conn._conn for all schema/PRAGMA application**  
`src/discord_assistant/storage.py:595-603` · API 오용/배포 함정 · ⏸ 보류: _run_sync 의 aiosqlite 비공개 _execute/_conn 의존을 공개 API 로 재작성하려면 동기 마이그레이션/PRAGMA 헬퍼 전체를 async 로 바꿔야 하는 침습적 아키텍처 변경. pyproject 가 aiosqlite<1.0 으로 메이저 파손을 막고 있고, 명확한  
- 문제: _run_sync 가 aiosqlite 의 비공개 메서드 _execute 와 비공개 속성 _conn 에 직접 의존한다. initialize() 의 PRAGMA·스키마·마이그레이션 적용이 전적으로 이 비공개 경로를 통과한다.
- 수정안: 가능하면 공개 API(conn.execute/executescript)로 PRAGMA·스키마를 적용하도록 재작성한다. 비공개 의존이 불가피하면 aiosqlite 핀을 더 좁히고(예: 검증된 마이너 범위로) 비공개 의존을 회귀 테스트로 고정한다.

**130. delete_user_data/delete_guild_data run multi-table DELETEs in autocommit mode without an explicit transaction, allowing partial GDPR deletion on mid-loop failure**  
`src/discord_assistant/storage.py:1321-1355` · 데이터 정합성/에러 처리 · ✅ 수정: 두 GDPR 삭제 메서드의 다중 DELETE 를 명시적 BEGIN/COMMIT 으로 감싸고, 예외 시 rollback 후 re-raise 하여 삭제를 원자화.  
- 문제: GDPR 삭제 메서드들이 autocommit(isolation_level=None) 연결에서 여러 테이블을 순차 DELETE 한 뒤 마지막에 한 번 commit() 한다. autocommit 이라 각 DELETE 가 즉시 커밋되고 마지막 commit() 은 no-op 이며, 명시적 BEGIN/ROLLBACK 이 없어 원자성이 보장되지 않는다.
- 수정안: 삭제를 명시적 트랜잭션으로 감싼다: await conn.execute('BEGIN') → 모든 DELETE → await conn.commit(), 예외 시 await conn.rollback(). autocommit 모드라도 명시적 BEGIN 으로 원자성을 확보한다.

**131. Discord token masking regex over-matches generic dotted identifiers and JWTs, scrubbing non-secret debug context**  
`src/discord_assistant/storage.py:228-229` · 보안/오탐(데이터 손실) · ⏸ 보류: Discord 토큰 마스킹 정규식 좁히기는 over-masking(안전 측)을 줄이는 대신 실제 토큰 under-masking(시크릿 노출) 위험을 도입. 작업 하드 제약('시크릿/토큰 노출 금지, 새 버그 금지')과 정면 충돌. finding 도 '안전 측 동작이 의도면 문서화'를 대  
- 문제: Discord 토큰 패턴이 '점 두 개로 구분된 길이 조건 충족 식별자'를 모두 매칭해, 실제 토큰이 아닌 JWT 유사 문자열·긴 점-구분 식별자(모듈 경로 등)까지 통째로 *** 로 치환한다(실측 확인).
- 수정안: 토큰 패턴을 실제 Discord 토큰 구조(세그먼트 길이/문자 구성, 또는 'Bot ' 접두 컨텍스트 결합)에 맞춰 더 좁힌다. 과잉 마스킹과 누락의 균형을 테스트 케이스로 고정하고, 안전 측 동작이 의도라면 그 의도를 명시적으로 문서화한다.


### UI 뷰/모달 (`ui`)

**132. RetryView 더블클릭 레이스 — disabled 가 클라이언트에 반영되기 전 콜백 중복 진입**  
`src/discord_assistant/ui.py:1304-1308` · 레이스/동시성 · ✅ 수정: RetryView 에 1회성 self._used 가드 추가: 두 번째 클릭은 콜백 재호출 없이 ephemeral 안내만 보내 토큰 이중 소모/이중 응답 방지(첫 클릭 동작은 기존과 동일).  
- 문제: 버튼 비활성화가 즉시 클라이언트에 반영되지 않고 1회성 가드도 없어, 동일 사용자의 더블클릭이 재시도 콜백을 두 번 실행한다.
- 수정안: 콜백 위임 전에 `await interaction.response.edit_message(view=self)` 로 비활성화를 즉시 반영하거나, self._used 1회성 플래그를 두고 이미 사용됐으면 followup 안내 후 return 한다.

**133. API 키 검증의 관대 정책 — 4xx(비-401/403)/5xx/429 응답이면 무효 키도 '유효'로 통과해 암호화 저장**  
`src/discord_assistant/ui.py:277-280, 305-308, 327-330` · 에러 처리 / 보안 · ⏸ 보류: 의도된/문서화된 설계 정책(ui.py 267-271 주석: 429/5xx/네트워크 오류는 일시 장애로 보고 등록을 막지 않음). 4xx 전체 거부로 바꾸면 제공자가 일시 400 을 줄 때 유효 키도 거부할 수 있는 동작/정책 변경이라 보수적으로 skip. finding 자체도 sever  
- 문제: 키 검증이 명시적/오타 무효(401/403)만 거르고, 그 외 4xx/5xx/429 는 '유효'로 판정한다. 의도된 관대 정책이지만 400 같은 비-인증 오류까지 유효로 보는 것은 과도하다.
- 수정안: 성공(2xx)만 유효로 보고 4xx 전체를 무효로 처리하거나, 429/5xx/네트워크 오류는 '검증 불가(사용 중 실패 가능)' 로 분리해 사용자에게 명시한다.

**134. _APIKeyModal.on_submit: defer 후 예외 시 on_error 의 response.send_message 가 InteractionResponded 2차 예외**  
`src/discord_assistant/ui.py:361-385, 396-397` · 에러 처리 · ✅ 수정: on_error 를 interaction.response.is_done() 으로 분기해 응답 소비 후에는 followup.send, 아니면 response.send_message 를 쓰도록 수정.  
- 문제: on_error 가 응답 소비 여부(is_done)를 검사하지 않고 항상 response.send_message 를 호출한다. defer 이후 예외 경로에서는 followup.send 를 써야 한다.
- 수정안: on_error 를 `if interaction.response.is_done(): await interaction.followup.send(...) else: await interaction.response.send_message(...)` 로 분기한다. on_submit 의 저장/갱신 구간을 try/except 로 감싸 followup 으로 결과를 알린다.

**135. ProviderView._on_select: interaction.data['values'][0] 무검증 인덱싱 + LLMProvider() enum 변환**  
`src/discord_assistant/ui.py:564, 618, 680, 742, 1101, 1224` · 엣지케이스 / API 오용 · ✅ 수정: interaction.data.get('values') 빈 값 가드 추가 + LLMProvider(values[0]) 를 try/except ValueError 로 감싸 위조/변형 페이로드 시 IndexError/ValueError 대신 ephemer  
- 문제: 신뢰 경계(클라이언트가 제어 가능한 게이트웨이 페이로드)의 입력을 무검증으로 인덱싱/enum 변환한다. 정상 Discord 클라이언트는 항상 정의된 옵션을 보내지만, 변형/위조 페이로드 시 IndexError/ValueError 가 발생한다.
- 수정안: `values = interaction.data.get('values') or []; if not values: return` 후 처리하고, LLMProvider(...) 변환은 try/except ValueError 로 감싸 '지원하지 않는 선택' 을 ephemeral 로 안내한다.

**136. run_install 스피너 루프의 'except Exception: pass' 가 모든 edit 실패를 무로깅 폐기**  
`src/discord_assistant/ui.py:779-785` · 관측성 / 예외 삼킴 · ✅ 수정: 모듈 logger 추가 후 스피너 갱신 예외를 discord.NotFound(토큰 만료→루프 break, debug 로깅)와 discord.HTTPException(debug 로깅 후 다음 주기 재시도)으로 분기해 무로깅 폐기 제거.  
- 문제: 스피너 갱신 실패를 전부 조용히 삼켜 로그가 남지 않는다. 토큰 만료가 반복돼도 운영자가 관측할 수 없다.
- 수정안: logger.debug/warning 으로 예외를 기록하고, NotFound(토큰 만료)면 루프를 break 해 더 이상 edit 시도를 하지 않는다.

**137. 설정 패널 콜백들에 on_error/try-except 부재 — 외부 호출 실패 시 무응답('interaction failed')**  
`src/discord_assistant/ui.py:493-518, 617-645, 679-705, 1223-1232` · 에러 처리 누락 · ⏸ 보류: 확정된 라이브 실패가 아닌 방어적(defense-in-depth) 개선이며 SettingsView/ExternalModelView/OllamaModelView/FollowUpView 등 4개 뷰 클래스에 on_error 를 추가해야 하는 넓은 변경. 최소·보수 수정 원칙상 단일 버그 수  
- 문제: 설정 패널 버튼 콜백이 외부 호출(store/ollama_manager) 도중 예외가 나면 응답을 소비하지 않은 채 종료될 수 있고, View 에 on_error 가 없어 사용자에게 피드백이 전혀 없다.
- 수정안: 각 View 에 async def on_error 를 오버라이드해 is_done 여부에 따라 followup/response 로 ephemeral 오류 안내를 보내거나, 외부 호출 구간을 try/except 로 감싸 사용자에게 실패를 알린다.


---

## ❓ 불확실

**138. OpenAI/Anthropic 툴 루프가 id 누락 시 빈 문자열로 응답 정합성 깨짐** (low) — `/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:917-923,1194-1200`
- 툴 결과 메시지의 tool_call_id/tool_use_id 가 falsey(누락) 일 때 빈 문자열을 보내는데, 이는 API 가 거부하는 잘못된 요청을 능동적으로 만드는 셈이다. 차라리 해당 호출을 스킵하거나 명확한 오류로 처리하는 편이 안전하다.

**139. CircuitBreaker half-open 이 1회 시도로 제한되지 않아 thundering herd 가능** (low) — `/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:298-321`
- reset_timeout 경과 후 half-open 전환이 1회 시도로 제한되지 않아, 동시에 대기하던 여러 요청이 모두 통과해 아직 회복 안 된 제공자에 폭주할 수 있다. (record_failure/success 는 코루틴 레벨에서 직렬 호출되므로 워커-스레드 경합은 해당 없음.)

**140. LongResponseView.send_dm: full_text 가 빈 문자열이면 chunks[0] IndexError (현재 배선에선 도달 불가)** (low) — `src/discord_assistant/ui.py:999-1000`
- send_dm 자체는 빈 full_text 에 대해 IndexError 를 낼 수 있는 방어 공백이 있으나, 현재의 모든 LongResponseView 생성 지점이 비-빈 + 길이 임계 초과를 보장하므로 실제 트리거 경로가 없다. 향후 직접 빈 텍스트로 생성하면 문제가 된다.

**141. Select View 의 _selected 가변 상태 TOCTOU — 동일 사용자 빠른 재선택 시 마지막 값으로 동작** (low) — `src/discord_assistant/ui.py:606-629, 662-694, 722-764, 1202-1232`
- 후보의 핵심 주장(타 사용자가 _selected 를 덮어써 의도와 다른 모델/채널로 동작)은 모든 해당 View 가 ephemeral/단일 사용자 흐름이라 성립하지 않는다. 동일 사용자 내 마지막 선택값 사용은 정상 UX 에 가깝고, 진짜 레이스는 같은 사용자의 거의 동시 선택 정도로 영향이 작다.

**142. add_reminder stores due_at unvalidated and list_due compares lexically; microsecond/offset format drift can mis-fire reminders at the boundary** (low) — `/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:1161-1209`
- 리마인더 만기 판정이 문자열 사전식 비교에 의존하는데 add_reminder 가 형식/타임존을 전혀 검증·정규화하지 않는다. 현재 유일한 호출자는 항상 UTC 를 넘기므로 오프셋 오작동은 실재하지 않으나, 호출자의 isoformat() 은 마이크로초를 포함하고 list_due 의 _utc_now() 는 초 단위로 잘라 형식이 어긋난다. 경계 초에서 마이크로초 때문에 due_at 이 now 보다 사전식으로 커져 만기 행이 한 박자 늦게 선택된다.

**143. _connect() opens a separate empty in-memory DB when path is ':memory:', diverging from the persistent connection** (low) — `/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:605-617`
- _connect 의 ':memory:' 분기는 영속 aiosqlite 연결과 다른 독립 빈 인메모리 DB 를 연다(연결별 분리). docstring 에 경고가 있고 테스트들도 이를 알고 file DB 만 사용하지만, 향후 진단/테스트 코드가 무심코 :memory: 로 _connect 를 쓰면 항상 빈 결과를 본다.

**144. Reminder.due_at 문자열 사전식 비교는 포맷/오프셋이 다르면 만기 판정이 어긋남** (low) — `src/discord_assistant/models.py:112-128`
- due_at 만기 비교가 SQLite 문자열 사전식 비교에 의존한다. 모든 값이 동일 UTC 오프셋일 때만 시간 순서와 일치하는데 add_reminder는 포맷/타임존을 정규화하지 않는다. 다만 현재 앱은 항상 datetime.now(timezone.utc).isoformat()로만 생성하므로 실제 발생 경로가 없어 잠재적/방어적 결함이다.

**145. LongResponseView DM 버튼에 소유자(interaction_check) 검증이 없다** (low) — `src/discord_assistant/ui.py:989-1011`
- LongResponseView 의 DM 버튼은 소유자 검증 없이 누구나 누를 수 있다. 이론상 원 요청자가 아닌 사용자가 잘려나간 전체 응답을 DM 으로 받을 수 있다.

**146. _split_discord_text/DM 청크가 경계에서 마크다운/멀티바이트를 깨뜨릴 수 있다** (low) — `src/discord_assistant/bot.py:678-742, 725-726`
- 후보의 핵심 주장(1900 청크가 Discord 한도 초과로 전송 실패)은 한도가 codepoint 기준이고 청크가 1900 으로 보수적이라 성립하기 어렵다. 다만 비코드 인라인 마크다운/멘션이 max_chars 경계에서 잘려 렌더가 깨지는 미관 결함은 존재한다(전송 실패 아님).

**147. CSRF state·JWT 블랙리스트가 프로세스 로컬 메모리 — 멀티워커 시 인증/로그아웃 깨짐(잠재)** (low) — `/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:22-34`
- state·jti 블랙리스트가 프로세스 로컬이므로 multi-worker(uvicorn --workers>1/gunicorn)로 띄우면 (1) login state 발급 워커와 callback 워커가 달라 'Invalid or missing OAuth2 state'(287) 로그인 산발 실패, (2) 한 워커의 revoke 가 다른 워커에 안 보여 로그아웃이 무력화된다.
