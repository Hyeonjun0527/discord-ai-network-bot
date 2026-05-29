# 함정 감사 보고서 — discord-assistant

> 14개 영역 병렬 finder → 독립 verifier 적대적 검증(거짓양성 제거). verdict=confirmed 만 수록.


## 요약

- **확정 137개** · 🔴 critical 0 · 🟠 high 20 · 🟡 medium 39 · ⚪ low 78
- ❓ 불확실(추가 조사) 10개


### 영역별 확정 수

- 봇 슬래시 명령 (`bot-commands`): 11
- 리마인더 (`bot-reminders`): 11
- 이벤트 핸들러 (`bot-events`): 11
- LLM 재시도/툴 (`llm-resilience`): 11
- i18n/프롬프트 (`i18n-prompts`): 11
- 관측성/지원 (`observability-support`): 11
- 수명주기/동시성 (`bot-lifecycle`): 10
- CI·CD/인프라 (`cicd-infra`): 10
- UI 뷰/모달 (`ui`): 9
- 설정/암호화 (`config-crypto`): 9
- 대시보드 (`dashboard`): 9
- LLM 제공자 (`llm-providers`): 8
- 스토리지/DB (`storage`): 8
- 보안 (`security`): 8


---

## 🟠 HIGH (20)


### 이벤트 핸들러 (`bot-events`)

**1. @멘션 응답 경로에 쿨다운 검사가 전혀 없음 (LLM 비용 폭증/스팸)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3069-3171` · 리소스/비용 남용 (rate-limit 누락)  
- 문제: on_message 의 @멘션 처리 경로는 다른 모든 LLM 진입점(슬래시 명령/DM/답장/리액션/컨텍스트 메뉴)이 호출하는 _check_cooldown 을 한 번도 호출하지 않는다. 멘션마다 곧바로 _enforce_token_budget 후 _collect_transcript + llm.generate 가 돌고, 이미지 첨부 시에는 비전 분석(3097-3122)까지 무제한으로 실행된다.
- 영향: 한 사용자가 봇을 연속 멘션하면 가장 비싼 경로(트랜스크립트 수집 + 비전 분석)가 쿨다운 없이 반복 호출된다. 서버의 daily_token_budget 이 None(무제한, _enforce_token_budget 1031에서 None이면 즉시 return)이면 방어막이 전혀 없어 외부 제공자(OpenAI/Anthropic) API 비용 폭증과 레이트리밋 소진으로 이어진다.
- 수정: 멘션 경로 진입부(3072 `started = perf_counter()` 직후, guild_id/user_id 확정 후)에서 다른 경로와 동일하게 `remaining = _check_cooldown(guild_id, user_id)` 를 호출하고, None 이 아니면 짧은 안내(또는 조용히 return) 후 종료한다.

**2. 리액션·멘션·답장 경로가 allowed_role_id(명령 사용 권한 역할)를 우회**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3032-3067, 3082-3171, 3357-3420` · 보안/접근제어 (권한 우회)  
- 문제: 슬래시 명령 /summarize(1441)·/ask(1614)는 `if not _has_allowed_role(interaction, config.allowed_role_id)` 로 사용 권한 역할을 강제하지만, @멘션 ask/summarize·봇 답장 chat·리액션(📝/🌐) 경로는 이 검사를 전혀 하지 않는다.
- 영향: 관리자가 /config command_role 로 봇 사용을 특정 역할로 제한했더라도, 권한 없는 누구나 메시지에 📝/🌐 리액션을 달거나 봇을 멘션/답장하는 것만으로 동일한 LLM 기능(요약·번역·질문)을 실행해 토큰·비용을 소비할 수 있다. 의도된 접근 제어가 사실상 무력화된다.
- 수정: 세 경로 모두 LLM 호출 전 사용자 역할을 검사한다. 멘션/답장은 message.author.roles 를, 리액션은 payload.member(또는 guild.get_member(payload.user_id)).roles 를 사용해 allowed_role_id 를 확인하고, 권한이 없으면 조용히 return 한다.


### 수명주기/동시성 (`bot-lifecycle`)

**3. 토큰 일일 상한 검사가 check-then-act 레이스 — 동시 요청이 상한을 크게 초과 가능**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1033-1034, 1512-1514, 1557-1561` · race-condition  
- 문제: 토큰 일일 상한이 read-modify-write 가 아니라 read-then-(LLM호출)-then-write 구조다. 검사 시점과 기록 시점 사이에 가장 긴 await(LLM 호출)가 끼어 있어, 동시에 들어온 여러 명령이 전부 '아직 상한 미만'으로 읽고 통과한다. 예약(reservation)이나 락이 없다.
- 영향: 서버 관리자가 설정한 일일 토큰 상한(과금/비용 보호 장치, #19)이 동시 요청 폭주 시 의도한 한도를 크게 초과할 수 있다. OpenAI/Anthropic/Gemini 유료 키 사용 시 예상치 못한 비용이 발생한다. 단, 단일 프로세스 단일 길드에서 평상시 동시성이 낮으면 초과 폭은 동시 in-flight 호출 수에 한정되므로 critical 이 아닌 high 로 본다.
- 수정: 길드별 asyncio.Lock 으로 _enforce_token_budget 검사~_record_usage 기록 구간을 직렬화하거나(가장 단순), 호출 직전에 예상 토큰을 선차감(reservation)해 log_usage 에 먼저 기록한 뒤 실제값으로 보정한다. 더 견고하게는 get_today_token_usage 를 DB 레벨 조건부 UPSERT 로 원자화한다.


### 리마인더 (`bot-reminders`)

**4. 취소(/reminders cancel)가 라이브 sleep 태스크를 멈추지 못해 알림이 그대로 발송됨**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2090-2102, 1320-1337, 2061` · correctness/concurrency  
- 문제: /reminders cancel:<ID> 는 DB 행만 지우고, /remind 가 만든 in-memory sleep 태스크를 취소하지 않는다. _deliver_reminder 는 발송 직전 DB 상태(행 삭제/sent)를 재확인하지 않아 취소된 알림도 그대로 보낸다.
- 영향: 사용자가 취소했고 봇이 '취소했어요' 라고 응답까지 했는데, 봇이 재시작되지 않는 한 due 시각에 그대로 DM 알림이 발송된다. 사용자 신뢰를 깨는 명백한 기능 결함.
- 수정: reminder_id->Task dict 를 유지해 취소/발송 완료 시 해당 태스크를 cancel + 제거한다. 추가 방어로 _deliver_reminder 시작부에서 store 로 행이 아직 존재하고 sent=0 인지 재확인 후 전송한다(취소/중복 동시 방어).

**5. on_ready 재발화(재연결)마다 reschedule 무가드 실행 → 미발송 리마인더 중복 재예약(중복 DM)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2896-2907, 1356-1371` · concurrency/correctness  
- 문제: on_ready 가 부울/실행중 가드 없이 매번 _reschedule_pending_reminders 를 실행한다. on_ready 는 봇 생애 1회가 아니라 재연결마다 발화하므로, 동일 미발송 행에 대해 _schedule_reminder 태스크가 중복 생성된다.
- 영향: 재연결이 잦으면 같은 reminder 에 대해 sleep 태스크가 N개 쌓이고, 각각 mark_sent 전에 user.send 를 호출해 같은 알림이 여러 번 DM 으로 도착한다. 만기 임박/지난 항목은 즉시 중복 전송된다.
- 수정: 최초 1회만 reschedule 하도록 on_ready 에 부울 가드를 두거나 setup_hook 으로 옮긴다. 근본적으로는 reminder_id->Task 레지스트리에 이미 예약된 id 는 재예약을 건너뛴다.

**6. 일시적 HTTP 오류(레이트리밋/5xx)에도 mark_sent 가 호출되어 알림이 영구 유실**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1334-1337` · error-handling  
- 문제: 발송 실패가 일시적(429/5xx)인 경우에도 except 가 로깅만 하고 그 뒤 무조건 mark_sent(sent=1) 가 실행된다. 일시 오류와 영구 오류를 구분하지 않는다.
- 영향: Discord 가 잠깐 5xx/429 를 던지면 실제로 전달되지 않았는데도 발송 완료로 표시되어, 재시작 시 재예약 대상(list_due)에서 빠진다. 알림이 조용히 영구 유실된다.
- 수정: 일시 오류와 영구 오류를 구분한다. Forbidden(및 50007 등 명시적 영구 실패 코드)만 mark_sent 하고, 그 외 HTTPException 은 mark_sent 하지 말고(또는 재시도/백오프 후) 다음 기동 시 재시도되게 둔다.


### CI·CD/인프라 (`cicd-infra`)

**7. 프로덕션 배포 가드가 placeholder SECRET_KEY를 통과시킨다 (존재 여부만 검사)**  
`/Users/osuma/coding_stuffs/discord-assitant/.github/workflows/deploy.yml:192-195` · security  
- 문제: DISCORD_BOT_TOKEN 과 달리 SECRET_KEY 가드는 placeholder 거부 없이 존재 여부만 검사한다. 공개 리포의 .env.prod.example 에 적힌 알려진 placeholder 값이 가드를 통과해 프로덕션에 그대로 배포될 수 있다.
- 영향: 공개 example 에 명시된 약한·공지된 SECRET_KEY 로 봇이 기동될 수 있다. crypto._fernet_key 는 salt 없는 sha256 한 번이라 SECRET_KEY 만 알면 모든 길드의 OpenAI/Anthropic API 키가 사실상 평문 수준으로 복호화 가능하다. 사후 교체는 rekey_api_keys.py 마이그레이션을 강제한다.
- 수정: DISCORD_BOT_TOKEN 과 동일하게 placeholder 거부 가드를 추가한다: `if grep -qE '^SECRET_KEY=(replace-with|$)' "${DEPLOY_DIR}/.env"; then echo '::error::SECRET_KEY 가 placeholder 입니다'; exit 1; fi`. 추가로 최소 길이(예: env 에서 추출해 32자 이상) 검증 권장.

**8. 컨테이너 내부 백업이 sqlite3 CLI 부재로 항상 cp 폴백 → 라이브 WAL DB 비일관 스냅샷 + 무결성검증 스킵**  
`/Users/osuma/coding_stuffs/discord-assitant/scripts/backup.sh:37-60` · data-integrity  
- 문제: 백업이 의도와 달리 항상 cp 폴백 경로로 동작한다. 라이브 WAL DB 를 일관 스냅샷(.backup) 없이 cp 로 복사하고 integrity_check 도 스킵해 손상/비일관 가능 스냅샷이 검증 없이 저장된다.
- 영향: 매일 03:00 cron 백업이 조용히 cp 폴백으로 동작하며, 봇이 쓰는 도중의 비일관 가능 스냅샷을 무결성 검증 없이 만든다. cp 가 -wal/-shm 도 함께 복사(line 44-45)하지만 메인 .db 와 사이드카가 서로 다른 순간의 상태일 수 있어(원자적 스냅샷 아님) 복구 시점에 백업이 깨져 있을 위험이 있다.
- 수정: Dockerfile 의 apt-get install 목록에 `sqlite3` 추가(가장 단순). 또는 backup.cron:15 대안처럼 sqlite3 가 설치된 호스트에서 직접 실행. 또는 sqlite3 CLI 미존재 시 Python `sqlite3` 모듈의 `.backup()` API 로 일관 스냅샷+무결성검증을 보장하는 폴백을 둔다.


### 설정/암호화 (`config-crypto`)

**9. SECRET_KEY 가드가 정확한 기본값만 차단 — 빈 값·짧은 값·약한 변형은 통과**  
`src/discord_assistant/settings.py:102-113` · config  
- 문제: production 가드는 secret_key가 정확히 'change-me-in-production'일 때만 기동을 거부한다. 빈 문자열·한 글자·사소한 변형은 모두 무사 통과하며, 특히 빈 값은 경고 로그도 남기지 않고 crypto가 정상 키를 생성해 약한 암호화로 조용히 동작한다.
- 영향: 운영자가 SECRET_KEY를 비워두거나 사소한 값으로 둬도 봇이 기동돼 모든 길드 API 키가 사실상 공개에 가까운 키로 암호화된다. 기본값 차단이라는 보안 가드 목적이 우회된다.
- 수정: production에서 빈 문자열·최소 길이 미만(예: 32바이트 미만)·알려진 약한 값을 함께 거부한다. 정확 일치 여부가 아니라 엔트로피/길이 기준으로 검증한다.


### 대시보드 (`dashboard`)

**10. 대시보드 JWT 서명 키가 기본값 'change-me-in-production' 으로 무가드 폴백**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:94-105` · 보안(시크릿/인증)  
- 문제: 대시보드 백엔드에는 봇이 가진 production 기본키 거부 가드가 없다. JWT_SECRET_KEY/SECRET_KEY 환경변수를 모두 빠뜨리면 공개된 'change-me-in-production' 으로 JWT 가 서명·검증된다. 부팅을 실패시키는 startup 가드(lifespan/모듈 임포트)도 없다.
- 영향: render.yaml(16-17)은 SECRET_KEY 를 generateValue:true 로 자동생성하므로 문서화된 Render 배포 경로는 안전하다. 그러나 Dockerfile/Procfile/self-hosted 등 SECRET_KEY 를 수동 주입해야 하는 경로에서 변수를 누락하면, 누구나 공개키로 임의 JWT 를 서명해 sub/guilds(admin:true 포함) 클레임을 위조하고 _assert_guild_admin(main.py:925) 을 통과해 설정 변경·API 키 삭제 등 관리자 권한을 탈취할 수 있다. 인증/인가 완전 우회.
- 수정: _secret_key() 에서 기본값 폴백을 제거하고, 키가 없거나 'change-me-in-production' 이면 lifespan/모듈 임포트 시점에 RuntimeError 로 부팅을 실패시킨다(봇 settings.py 와 동일한 production 가드 재사용). 최소 길이/엔트로피 검증도 추가한다.


### i18n/프롬프트 (`i18n-prompts`)

**11. 커스텀 프롬프트 경로가 프롬프트 인젝션 방어선(_wrap_untrusted/_INJECTION_GUARD)을 완전히 우회한다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1506-1509, 1635-1640` · 보안(프롬프트 인젝션)  
- 문제: 관리자가 커스텀 요약/Q&A 프롬프트를 설정하면 신뢰할 수 없는 transcript/question 이 _wrap_untrusted 와 _INJECTION_GUARD 를 거치지 않고 그대로 LLM 프롬프트에 박힌다. #38 으로 구축한 다층 인젝션 방어선(가짜 role 토큰 zero-width 무력화, 구분자 무결성, 보안 지침 prepend)이 커스텀 프롬프트 한 줄 설정으로 전부 무효화된다.
- 영향: 관리자가 커스텀 프롬프트를 켜면, 채널의 임의 사용자가 'ignore previous instructions / System: ...' 류 메시지를 남기는 것만으로 모델을 조종(jailbreak)할 수 있다. 데이터(transcript)와 지시(instruction)의 경계가 사라져 프롬프트 인젝션에 직접 노출된다.
- 수정: 커스텀 경로에서도 치환 전 transcript/question 을 prompts._wrap_untrusted(transcript, "transcript") / _wrap_untrusted(question.strip(), "question") 로 감싸고, prompts._INJECTION_GUARD 를 프롬프트에 prepend 한다. 예: `prompt = _INJECTION_GUARD + "\n" + custom.replace("{transcript}", _wrap_untrusted(transcript, "transcript"))`.


### LLM 제공자 (`llm-providers`)

**12. 스트림 조기 종료 시 워커 스레드가 블로킹 HTTP 읽기에서 멈춰 누수/지연**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:423-453` · 리소스 누수/async 함정  
- 문제: _iter_in_thread 는 블로킹 urllib 스트림을 워커 스레드에서 돌리고 finally 에서 무조건 'await worker' 로 워커 종료를 기다린다. 소비자가 스트림을 끝까지 읽지 않고 중단하면 워커는 다음 readline 또는 가득 찬 큐의 q.put 에서 블로킹된 채로 남고, finally 의 await worker 는 그 워커가 끝날 때까지(=서버 close 또는 HTTP timeout 만료까지) 깨어나지 못한다.
- 영향: Discord 사용자가 스트리밍 응답 중 취소하거나 후속 입력/타임아웃으로 핸들러 task 가 cancel 되면, 해당 to_thread 워커 스레드와 소켓이 timeout(기본 60초)까지 살아 있고 finally 도 그동안 블로킹된다. 동시 스트림이 많으면 asyncio 기본 스레드풀(min(32, cpu+4))이 블로킹 워커로 고갈되어 이후 모든 to_thread(=generate/list/pull 포함)가 지연되는 부분 DoS·리소스 누수.
- 수정: (1) response 핸들을 보관해 finally 에서 response.close() 를 호출해 블로킹 read 를 깨운다, (2) q.put 을 timeout 가능하게 하거나 별도 stop 이벤트를 워커가 주기적으로 확인, (3) 최소한 finally 에서 'await asyncio.wait_for(worker, timeout=...)' 로 무한 대기를 막고 워커를 detach 한다. 취소 가능한 HTTP 클라이언트(httpx) 도입도 고려.


### LLM 재시도/툴 (`llm-resilience`)

**13. is_available() 가 _list_sync 의 예외 흡수로 인해 사실상 항상 True 를 반환**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1410-1415` · error-handling  
- 문제: is_available() 가 의존하는 _list_sync() 는 모든 예외를 자체적으로 except Exception 으로 흡수해 [] 를 반환한다. 따라서 _list_sync 는 사실상 예외를 던지지 않으므로 is_available 의 try/except 가 도달할 수 있는 유일한 경로는 to_thread 자체 실패뿐이며, 서버 다운/연결 거부 같은 정상적인 미가용 상태는 항상 True 로 보고된다.
- 영향: Ollama 미가용 상태(serve 미실행, 연결 거부)를 가용으로 오판한다. 프리플라이트 체크/제공자 선택 가드가 is_available 에 의존하면 실제로는 닿을 수 없는 Ollama 로 요청을 보내 사용자에게 실패가 노출되거나 폴백으로 전환되지 않는다. 단, 빈 모델 목록과 연결 실패를 구분하지 못하는 점이 핵심 원인이다.
- 수정: 가용성 판정 전용으로 예외를 전파하는 경로를 둔다. 예: GET /api/tags 를 직접 호출해 HTTP 상태/URLError 로 판정하거나, _list_sync 가 실패 시 [] 가 아닌 sentinel(또는 raise)을 반환하도록 분리해 is_available 이 '빈 목록'과 '연결 실패'를 구분하게 한다.

**14. _iter_in_thread: 소비자 조기 종료/취소 시 워커가 바운드 큐(put)에서 영구 블록 → 데드락**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:432-453` · concurrency  
- 문제: 스트리밍 소비부가 도중에 중단되면(예: followup 편집 중 비-HTTPException 예외, 상위 태스크 취소) finally 의 await worker 가 큐를 비우지 않은 채 워커 종료를 기다린다. 워커는 가득 찬 큐에 put 하려고 블록돼 있어 둘 다 영구 대기한다.
- 영향: bot.py _stream_to_interaction 의 async-for 가 예외/취소로 끊기고 누적 청크가 큐 용량(64)을 채운 상태면, 워커 스레드와 이를 await 하는 코루틴이 영구 정지한다. 스레드 누수 + 코루틴 hang 으로 봇이 점진적으로 스레드풀/메모리를 소진한다. 다만 청크 생산 속도가 소비보다 느리면(전형적 LLM 스트림) 큐가 64까지 차는 경우가 드물어 실전 트리거 빈도는 낮다.
- 수정: GeneratorExit/취소 시 워커를 깨운다. finally 에서 worker 완료까지 큐를 계속 drain(q.get_nowait 반복으로 비우기)하거나, 워커에 종료 이벤트를 전달해 put_nowait + 가득참 시 종료 체크를 하도록 바꾼다. 또는 close 가능한 응답 핸들을 워커에 넘겨 소비자 종료 시 강제 close 해 make_iter 가 예외로 끝나게 한다.


### 관측성/지원 (`observability-support`)

**15. correlation_id 가 실제 로그에 절대 나타나지 않음 (필터 미부착 + 포맷에 cid 없음)**  
`src/discord_assistant/logging_config.py:20,36-48,97-102` · 관측성/로깅 결함  
- 문제: CorrelationIdFilter 가 record.cid 를 주입하고 docstring 은 포매터가 %(cid)s 를 참조한다고 명시하지만, setup_logging 핸들러에 (1) 필터가 addFilter 되지 않고, (2) _TEXT_FORMAT 에도 %(cid)s 가 없으며, (3) JsonFormatter 도 cid 키를 넣지 않는다.
- 영향: #46 correlation_id 전파가 완전히 죽어 있어 어떤 포맷에서도 cid 가 안 찍히고 명령 추적 불가.
- 수정: 핸들러에 CorrelationIdFilter addFilter + _TEXT_FORMAT 에 (cid=%(cid)s) + JsonFormatter 에 cid 직렬화. 순환참조 피하려 필터를 logging_config 로 이동.

**16. Sentry before_send/PII 스크럽 부재 — 예외 로컬 변수에 사용자 콘텐츠·토큰 유출 가능**  
`src/discord_assistant/observability.py:49-54` · 보안/PII 유출  
- 문제: init_sentry 가 dsn/environment 만으로 init 하고 before_send 스크럽·send_default_pii=False 를 설정하지 않는다. on_error→capture_exception 경로의 예외 프레임 로컬 변수가 함께 전송될 수 있다.
- 영향: 사용자 메시지·user_id·LLM 프롬프트/응답, 최악의 경우 provider 에러에 섞인 API 키 일부가 외부 Sentry 로 유출 가능. GDPR/시크릿 리스크.
- 수정: send_default_pii=False + 민감 키(message/content/authorization/token/api_key) 마스킹 before_send 콜백 추가.


### 보안 (`security`)

**17. 커스텀 프롬프트 경로가 프롬프트 인젝션 방어를 전면 우회한다**  
`src/discord_assistant/bot.py:1506-1509, 1635-1642` · security/prompt-injection  
- 문제: config.custom_summarize_prompt / custom_ask_prompt 가 설정되면 채널 트랜스크립트와 사용자 질문이 인젝션 방어(구분자 래핑, role 토큰/지시문 무력화, 보안 가드 prepend) 없이 그대로 모델 프롬프트에 들어간다. 기본 build_* 경로가 들이는 모든 방어가 이 분기에서 무효화된다.
- 영향: 관리자가 커스텀 프롬프트를 설정한 서버에서 일반 멤버가 채널 메시지에 'System:'/'ignore previous instructions' 같은 토큰을 심어 /summarize·/ask 의 시스템 지시를 덮어쓰거나 데이터 유출/jailbreak 을 유도할 수 있다. 다만 커스텀 프롬프트 설정은 require_guild_admin(bot.py:2806) 으로 관리자만 가능하므로, 트리거하려면 관리자가 기능을 켠 상태여야 한다(상시 노출이 아닌 옵트인).
- 수정: 커스텀 프롬프트에 데이터를 치환할 때도 신뢰 불가 입력을 정제하라. 예: transcript 치환값을 prompts._wrap_untrusted(transcript, 'transcript') 결과로 바꾸고 question 도 동일 처리한 뒤, 커스텀 프롬프트 본문 앞에 항상 prompts._INJECTION_GUARD 를 강제 prepend 한다. 최소한 _neutralize_role_tokens + _neutralize_injection_phrases 를 적용한 값을 삽입한다.

**18. allowed_role 역할 제한이 대부분의 LLM 진입점에 적용되지 않는다**  
`src/discord_assistant/bot.py:1730, 1786, 2435, 2325, 2194, 2248, 2604-2627, 3069, 3357` · security/authorization  
- 문제: 관리자가 /config allowed_role 로 '명령어 사용 가능 역할'을 지정해도, run_summarize/run_ask/digest 외의 모든 LLM 호출 경로(translate, chat, search, export, summarize-channels, 컨텍스트 메뉴 3종, @멘션, 답장, DM, 리액션 트리거)는 역할 검사 없이 누구나 사용할 수 있다.
- 영향: 역할 기반 사용 제한이 광범위하게 우회된다. 제한 대상 사용자가 /chat·/translate·@멘션·리액션 등으로 동일한 LLM 자원(토큰/비용)을 그대로 소비할 수 있어 접근 통제·비용 통제가 실질적으로 무력화된다.
- 수정: 공통 가드(_has_allowed_role)를 모든 LLM 진입점에 일원화하라. _ctx_menu_guard, _run_chat, translate/search/export/summarize-channels, on_message 의 멘션·답장 경로, on_raw_reaction_add 에 config.allowed_role_id 기반 검사를 추가하거나 단일 데코레이터/헬퍼로 통일한다.


### 스토리지/DB (`storage`)

**19. purge_old retention cutoff compares ISO 'T'/offset created_at against datetime('now') space-form lexically, leaving same-date rows undeleted**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:916-929` · 데이터 정합성/시간 비교  
- 문제: purge_old 의 보존 컷오프 비교가 두 개의 서로 다른 시각 직렬화 형식을 단순 문자열로 비교한다. created_at 은 'T' 구분자와 '+00:00' 오프셋을 가진 ISO 문자열이지만 datetime('now','-N days') 는 공백 구분자에 오프셋 없는 형식을 돌려준다. 두 형식은 위치 10('T'=84 vs ' '=32)에서 갈리므로, 컷오프 날짜와 같은 날짜의 행은 그 날 어느 시각이든 컷오프보다 '크다'고 판정되어 삭제되지 않는다.
- 영향: 보존 정책을 넘긴 usage_log/chat_history 행 중 최대 약 하루치(컷오프 날짜에 걸친 모든 행)가 삭제되지 않고 남는다. 실행 시각에 따라 보존 결과가 달라져 비결정적이며, PII/대화 데이터가 의도보다 오래 잔존해 컴플라이언스 위험과 DB 비대화를 유발한다. retention_task(bot.py:3307) 가 이 메서드만 호출하므로 이 결함이 운영에서 그대로 노출된다.
- 수정: 양변을 동일 함수로 정규화해 비교한다. WHERE datetime(created_at) < datetime('now', ?) 처럼 양쪽 모두 SQLite datetime() 으로 파싱하거나, _utc_now() 와 동일 형식으로 미리 계산한 컷오프 문자열을 바인딩한다. get_today_token_usage 의 date(created_at)=date('now') 와 동일한 양변-파싱 패턴을 적용하면 된다.


### UI 뷰/모달 (`ui`)

**20. run_install: pull_task 예외 미회수 + 실패해도 '설치 완료' 표시 (fire-and-forget 태스크)**  
`src/discord_assistant/ui.py:766-803 (특히 773, 775, 788-791)` · async 함정 / 데이터 정합성  
- 문제: run_install 의 while 루프가 pull_task 의 완료만 폴링하고 예외/결과를 await 로 회수하지 않는다. 따라서 ollama pull 이 OllamaError 로 실패해도 그 예외는 except OllamaError(794) 로 전달되지 못한 채 unretrieved 상태가 되고, 코드는 곧바로 set_model 을 호출해 '설치 완료' 를 표시한다. 또한 create_task 반환값을 어디에도 저장하지 않아 부모 태스크가 GC 될 수 있다.
- 영향: 1) ollama pull 실패(디스크 부족/네트워크/잘못된 모델명/ollama 미설치) 시 사용자에게 '✅ 설치 완료' 로 잘못 표시되고, 실제로는 모델이 없는 상태로 set_model 되어 이후 모든 AI 요청이 OllamaError 로 실패한다(데이터 정합성 위반). 2) 'Task exception was never retrieved' 경고가 로그를 오염시키고, fire-and-forget 태스크가 GC 되면 설치가 조용히 중단될 수 있다.
- 수정: 루프 종료 후 try 블록 안에서 먼저 `await pull_task`(또는 pull_task.result())로 예외를 전파시킨 뒤에만 set_model 을 호출하도록 순서를 바꾼다. OllamaError 가 except OllamaError(794)에서 잡혀 '설치 실패' 로 표시되게 한다. 또한 set_model 에 ollama_manager 를 넘겨 모델 존재 검증을 활성화하고, create_task 반환값을 self._install_task 로 보관 + add_done_callback 으로 예외 로깅을 건다.


---

## 🟡 MEDIUM (39)


### 봇 슬래시 명령 (`bot-commands`)

**21. remind: no cooldown, no length cap on stored payload — DB bloat, PII retention, spam**  
`src/discord_assistant/bot.py:2013-2074` · data-integrity  
- 문제: /remind has neither a per-call cooldown nor any length limit on the stored text/summary, and no per-user limit on the number of pending reminders. A user can schedule many reminders carrying large payloads (full conversation summaries = chat PII) that persist in the reminders table until due.
- 영향: Unbounded reminders table growth and long-lived plaintext retention of conversation summaries (PII) until due_at. Absence of cooldown allows spam-style mass scheduling (resource exhaustion).
- 수정: Apply a length cap (e.g. 1800 chars) to both `message` and the cached summary text before encoding; call `_check_cooldown` at the start of remind_command; enforce a per-user cap on pending (un-fired) reminders.

**22. digest: period-based command silently truncated to summary_limit (default 50) messages**  
`src/discord_assistant/bot.py:2517-2525` · edge-case  
- 문제: digest advertises summarizing a whole time window (e.g. since:1d) but caps collection at summary_limit (default 50). When the window contains more than `limit` messages, discord.py's after-based pagination keeps the OLDEST `limit` messages and drops the MOST RECENT part of the period (the candidate's claim that the oldest part is dropped is inverted — it's actually the newest). Either way the window is not fully represented.
- 영향: On active channels, /digest since:1d summarizes only the first ~50 messages of the period and omits the most recent activity, producing a partial/misleading 'key points / decisions / actions' summary while the user trusts the full period was covered.
- 수정: For period-based commands, raise limit (e.g. 200 or None) and rely on max_context_chars for truncation, or explicitly tell the user only N of M messages were included. Note set_summary_limit caps summary_limit at 200, so the /usage `if summary_limit > 200` branch (2593) is dead.

**23. _collect_transcript: since(after)+limit returns only part of the period (summarize/digest)**  
`src/discord_assistant/bot.py:1110-1132, 1491-1497` · edge-case  
- 문제: When a since/after window is given, history(after=..., limit=N) returns the OLDEST N messages within the window (because after forces oldest-first iteration), so any window with more than N messages is truncated — the user expects 'all messages in this period' but gets only N of them. Note the candidate's 'recent N only / oldest part dropped' phrasing is imprecise: with after set it is actually the oldest N that are kept and the newest dropped; either way the window is partial.
- 영향: /summarize since:Xd and /digest under-represent active periods, summarizing only N messages of the window; on busy channels the omission is large and summary reliability drops while users assume full coverage.
- 수정: When since/after is provided, raise limit (or set None) and bound only by max_context_chars, or paginate the full window and tell the user when only part was included.


### 이벤트 핸들러 (`bot-events`)

**24. on_ready 가 재연결마다 전 길드 명령을 재동기화 (이중 sync + Discord 레이트리밋 소진)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2897-2903` · 설정/배포 함정 (API 레이트리밋)  
- 문제: discord.py 의 on_ready 는 최초 1회가 아니라 게이트웨이 RESUME/재연결마다 다시 발생할 수 있는데, on_ready 의 길드별 copy_global_to + sync 는 1회성 가드 없이 매번 실행된다. 게다가 setup_hook 에서 이미 글로벌 sync 를 한 뒤 기동 직후 또 모든 길드에 sync 가 돌아 시작 시점부터 중복 동기화가 발생한다.
- 영향: 게이트웨이가 자주 재연결되는 환경(네트워크 불안정/Discord 리밸런싱)에서 봇이 붙어 있는 모든 길드에 대해 명령 동기화 PUT 이 매 on_ready 마다 반복된다. 길드별 명령 동기화는 강한 레이트리밋(일일 한도) 대상이라, 다수 길드 봇은 동기화 차단/기동 지연이 발생할 수 있다.
- 수정: on_ready 에 `if getattr(bot, '_synced', False): return; bot._synced = True` 같은 1회 실행 가드를 두거나, 동기화를 setup_hook 의 글로벌 sync 1회로 한정한다. 길드별 copy_global_to+sync 는 개발/즉시 반영이 필요한 경우로 제한한다.

**25. auto_summary_task: 접근 가능한 첫 채널이 비면 break 로 길드 전체 자동요약 중단**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3271-3291` · 엣지케이스/로직 함정  
- 문제: 자동 요약은 send+read 권한이 있는 첫 채널만 시도하고, 그 채널의 transcript 가 비면 continue 가 아닌 break 로 채널 루프를 종료한다. 대화가 활발한 다른 채널은 시도하지 않는다.
- 영향: 권한 순서상 첫 번째로 잡히는 채널이 #규칙/#공지처럼 대화가 거의 없는 채널이면, 그 채널이 비어 있는 한 해당 길드의 자동 요약이 매 주기 아무것도 하지 않는다(다른 활발한 채널 무시). 3264 에서 last_run 을 먼저 갱신하므로 빈 채널이라도 한 주기를 소모한다. 사용자는 자동 요약이 조용히 동작하지 않는다고 느낀다.
- 수정: 빈 채널이면 break 대신 continue 로 다음 채널을 시도한다. 또는 자동 요약 대상 채널을 설정값으로 명시하게 하고, 어느 채널에도 transcript 가 없을 때만 이번 주기를 건너뛴다. last_run 갱신은 실제 요약 성공 후로 미루는 것도 고려한다.

**26. 리액션 트리거(📝/🌐) 가 채널 send 권한 사전 확인 없이 동작하고 성공 경로 reply 가 Forbidden 미처리**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3375-3420` · 권한/에러 처리 (사전 권한 확인 누락)  
- 문제: 리액션 핸들러는 봇 자기 리액션·쿨다운만 거른 뒤 메시지를 fetch 해 곧장 LLM 요약/번역을 실행하고 target.reply 로 답장한다. 봇이 해당 채널에서 send/reply 권한이 있는지 사전 확인이 없고, 성공 경로의 reply 실패(discord.Forbidden)는 잡히지 않는다.
- 영향: 봇이 메시지는 읽되 보낼 수 없는 채널에서 누군가 📝/🌐 를 달면, 이미 비용이 발생한 LLM 호출 후 reply 단계의 discord.Forbidden 이 성공 경로에서 새어나가 on_error → 개발자 DM 알림까지 발생한다(노이즈). 또한 권한 검증 부재로 봇이 응답할 수 없는 채널에서도 LLM 토큰만 소모된다.
- 수정: LLM 호출 전에 channel.permissions_for(guild.me).send_messages 권한을 확인하고 없으면 조용히 return 한다. 성공 경로의 target.reply/_send_channel_chunks 도 discord.HTTPException(Forbidden 포함)을 잡아 흡수한다.

**27. DM 채팅 경로가 슬래시 명령과 달리 토큰 상한·역할 제한을 우회**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2946-2999` · 리소스/비용 남용  
- 문제: DM 모드는 0번 공용 기본 설정으로 동작하며 guild_id=None 이라 _enforce_token_budget(일일 상한)·allowed_role 같은 길드 단위 보호가 모두 무력화된다. 0번 설정에 외부 제공자 키가 있으면 DM 으로 누구나 그 키로 LLM 을 호출한다.
- 영향: 봇과 DM 할 수 있는 임의의 사용자가(서버 멤버 자격과 무관하게) 길드 토큰 상한·역할 제한을 받지 않고 0번 설정의 외부 API 키로 LLM 을 호출할 수 있다. per-user DM 쿨다운(10초)만으로는 비용/남용 통제가 약하고, 0번 설정에 외부 제공자 키가 있으면 비용이 누구에게나 노출된다.
- 수정: DM 채팅에도 일일 호출/토큰 상한과 허용 사용자(예: 봇이 함께 속한 길드 멤버) 검증을 추가한다. 0번 공용 설정에는 외부 API 키를 두지 않거나 DM 전용 권한 정책을 명시한다.


### 수명주기/동시성 (`bot-lifecycle`)

**28. _delayed_disconnect_alert 가 await 후 pending 을 무조건 None 으로 덮어써 새 알림 태스크 참조를 분실**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3198-3199, 3201-3206, 3215-3217` · race-condition  
- 문제: _delayed_disconnect_alert 끝부분이 '자기 자신이 아직 등록된 pending 인지' 확인 없이 _disconnect_state['pending']=None 을 실행한다. await(notify_developer) 경계 이후 상태가 바뀌었을 수 있는데 무조건 덮어쓴다.
- 영향: 재연결이 잦은 환경에서 취소되지 못한 끊김 알림 태스크가 살아남아 개발자에게 오탐 DM(#54 가 막으려던 도배)을 보낼 수 있다. 추적이 끊긴 태스크는 _cancel_pending_disconnect_alert 로 정리되지 않는다. 발생하려면 특정 인터리빙이 필요해 medium.
- 수정: 함수 진입 시 `me = asyncio.current_task()` 를 보관하고, 끝에서 `if _disconnect_state.get('pending') is me: _disconnect_state['pending'] = None` 으로 자기 자신일 때만 비운다.

**29. auto_summary_task 가 LLM/예산 실패·빈 채널에도 last_run 을 먼저 갱신 — 실패 주기를 정상 소진**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3264, 3280-3281, 3285-3291` · error-handling  
- 문제: 성공 여부와 무관하게 주기 시작 시점에 last_run 을 갱신한다. 한 번의 일시적 실패가 그 interval 전체를 조용히 소진시킨다.
- 영향: 일시적 LLM 오류·토큰 상한 도달 시 그 주기의 자동요약이 사용자에게 아무 표시 없이 통째로 유실된다. 실패가 매 주기 반복되면 자동요약이 사실상 동작하지 않을 수 있다.
- 수정: last_run 갱신을 ch.send 성공 이후로 옮긴다. 예산 초과(UserFacingError)는 의도된 skip 이므로 그 경우에만 별도로 last_run 을 갱신하고, 일반 LLM 오류/네트워크 오류는 last_run 을 유지해 다음 주기에 재시도하게 한다.


### 리마인더 (`bot-reminders`)

**30. user 가 None 일 때 AttributeError 미처리 → mark_sent 누락, 재시작마다 무한 재시도**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1328-1337` · error-handling  
- 문제: 후보가 지적한 NotFound 부분은 false: NotFound 는 HTTPException 서브클래스라 잡힌다. 그러나 user 가 None 이 되는 경계는 진짜다 — AttributeError 가 어떤 except 에도 걸리지 않아 mark_sent 가 호출되지 않는다.
- 영향: user==None 시 행이 영원히 미발송으로 남고, 매 봇 재시작 reschedule 마다 같은 행을 다시 시도하다 같은 예외로 실패하는 좀비 행이 된다(로그 스팸 + 정리되지 않음). 백그라운드 태스크 예외는 _on_task_done 이 로깅만 한다.
- 수정: user 가 None 인 경우를 명시 처리하고(영구 실패로 mark_sent), 광의의 except 대신 명확한 성공/영구실패 분기에서만 mark_sent 하도록 구조화한다.


### CI·CD/인프라 (`cicd-infra`)

**31. rekey_api_keys.py 가 busy_timeout/락 처리·봇 정지 가드 없이 라이브 DB UPDATE → 'database is locked' 위험**  
`/Users/osuma/coding_stuffs/discord-assitant/scripts/rekey_api_keys.py:106-139` · concurrency  
- 문제: 재암호화 도구가 WAL 라이터 경합 보호(busy_timeout, BEGIN IMMEDIATE) 없이 기본 connect 로 라이브 DB 에 UPDATE 하며, restore.sh 와 달리 '봇 정지 후 실행' 가드/경고도 없다.
- 영향: 봇 실행 중 SECRET_KEY 교체 마이그레이션을 돌리면 쓰기 경합으로 `sqlite3.OperationalError: database is locked` 가 발생해 일부 길드만 재암호화되고 중단될 수 있다. commit 은 끝에서 1회라 락이 commit 전에 터지면 전체 롤백되지만, with 블록이 락 예외로 빠져나가면(미커밋) 또는 봇이 동시에 같은 행을 덮어쓰면 부분/불일치 상태가 생긴다. 봇 정지 후 실행이 안내되지 않아 운영자가 라이브에서 돌리기 쉽다.
- 수정: docstring/실행 전 가드에 '컨테이너 stop 후 실행' 을 명시하고, `sqlite3.connect(..., timeout=30)` + `conn.execute('PRAGMA busy_timeout=30000')` 를 적용하며, `BEGIN IMMEDIATE` 로 단일 트랜잭션을 잡아 경합을 조기에 감지·중단(또는 재시도)한다.

**32. 배포 스모크테스트가 컨테이너 전체 로그 히스토리를 grep → 이전 기동의 'Logged in as' 로 거짓 통과**  
`/Users/osuma/coding_stuffs/discord-assitant/.github/workflows/deploy.yml:252-266` · deployment  
- 문제: 스모크테스트가 현재 기동분이 아닌 컨테이너 전체 로그를 grep 한다. 컨테이너가 recreate 되지 않는 재배포에서는 과거 로그의 'Logged in as' 로 거짓 성공한다.
- 영향: 새(또는 재기동된) 봇이 로그인 직전 크래시했더라도 과거 로그의 'Logged in as' 때문에 스모크테스트가 거짓 통과한다. 그러면 line 273 의 if: failure() 자동 롤백(#68)이 트리거되지 않아 고장난 배포가 운영에 남을 수 있다.
- 수정: 기동 시각 이후 로그만 보도록 한다. 예: Deploy 스텝 직전에 `start_ts=$(date -u +%Y-%m-%dT%H:%M:%S)` 캡처 후 `docker logs --since "$start_ts" discord-assistant-bot`. 또는 healthcheck/readyz 가 노출하는 현재 기동의 로그인 상태를 신뢰 소스로 사용한다.


### 설정/암호화 (`config-crypto`)

**33. decrypt_api_key가 `except (InvalidToken, Exception)`로 모든 예외를 삼켜 '키 변경'으로 오진**  
`src/discord_assistant/crypto.py:28-29` · error-handling  
- 문제: except 절이 (InvalidToken, Exception)로 사실상 모든 예외(TypeError, AttributeError 등)를 포착한 뒤 항상 동일한 '키가 변경됐을 수 있다'는 CryptoError로 변환한다. InvalidToken이 Exception의 서브클래스라 첫 항목은 무의미한 중복이며, 원래 예외 타입/메시지가 보존되지 않는다.
- 영향: 운영 중 진짜 원인(타입 오류, 데이터 손상, 라이브러리 결함)이 '키가 바뀐 것 같다'는 잘못된 진단으로 은폐돼 디버깅이 어려워진다. 사용자에게도 틀린 원인이 안내되어(bot.py:972-973) 불필요한 키 재설정을 유도한다. 다만 from exc로 체이닝은 보존되고 호출부가 CryptoError만 처리하므로 정정/제어 흐름 자체는 안전 — high가 아니라 medium.
- 수정: `except InvalidToken as exc:`로 범위를 좁히고, 입력 타입(str)/빈 토큰을 별도 검증한다. 광범위 포착이 필요하면 최소한 원래 예외 타입/메시지를 로깅하거나 메시지에 포함시켜 진단 정보를 보존한다.

**34. _fernet_key가 솔트·KDF 없이 단일 SHA-256 다이제스트를 Fernet 키로 사용**  
`src/discord_assistant/crypto.py:16-18` · security  
- 문제: Fernet 키를 단일 라운드 SHA-256(secret)으로 파생한다. 비용 인자가 없어 SECRET_KEY가 저엔트로피일 경우 DB(api_key_encrypted) 유출 시 오프라인 무차별/사전 공격이 저렴해진다.
- 영향: SQLite 파일이 유출되고 SECRET_KEY가 약하면 저장된 OpenAI/Anthropic/Gemini API 키(타사 과금 자원)가 비교적 쉽게 복호화될 수 있다. 보호 강도가 전적으로 SECRET_KEY 품질에만 의존한다.
- 수정: 배포별/고정 솔트와 PBKDF2-HMAC-SHA256(높은 iterations) 등 KDF로 키를 파생하고 secret 최소 길이/엔트로피를 검증한다. 키 회전을 위해 MultiFernet 사용도 검토한다.

**35. 운영 환경 판정이 ENVIRONMENT/APP_ENV 미설정 시 비운영으로 폴백 (fail-open)**  
`src/discord_assistant/settings.py:31-37` · config  
- 문제: 운영 여부 판정이 인식 키워드 2개에만 의존하고 미설정/오타/비표준 키워드면 비운영으로 간주된다. 보안 가드가 fail-open이라 env 누락 시 가장 위험한 쪽(기본 SECRET_KEY 허용)으로 동작한다.
- 영향: 실제 운영 배포에서 env 변수 설정을 누락하면 가드가 작동하지 않아 기본 SECRET_KEY로 봇이 기동될 수 있다. 보안 가드의 신뢰성이 운영자 환경 변수 설정에만 의존한다.
- 수정: 기본 SECRET_KEY('change-me-in-production')는 환경과 무관하게 항상 거부하거나, 운영 여부를 명시적으로 요구(미설정 시 엄격 모드)한다. 인식 운영 키워드 집합도 넓힌다.

**36. GuildConfig.summary_limit 에 모델 레벨 범위 검증 없음 — DB/직접 생성 경로로 1~200 우회**  
`src/discord_assistant/models.py:65-77` · data-integrity  
- 문제: set_summary_limit와 UI 경로는 1~200을 강제하지만 GuildConfig.__post_init__과 DB 로드(storage.py:639)는 summary_limit를 검증/클램프하지 않는다. 손상·마이그레이션된 행이나 모델 직접 생성 시 0·음수·거대값이 들어올 수 있고, bot.py:2270 경로는 그 값을 클램프 없이 사용한다.
- 영향: 손상된 DB 행이 들어오면 다중 채널 요약 경로(bot.py:2270)에서 비정상 limit로 메시지를 가져오려 시도해 빈 결과 또는 과도한 메모리/컨텍스트 사용으로 이어질 수 있다. 대부분 경로는 _effective_limit가 방어하므로 영향은 부분적 — high가 아니라 medium. 검증 책임이 모델이 아닌 호출부에 분산돼 일관성이 깨진다.
- 수정: SUMMARY_LIMIT 상·하한 상수를 두고 __post_init__에서 범위를 검증해 단일 진실 원천으로 강제하거나, get_guild_config에서 _normalize_interval처럼 읽기 시 클램프한다. bot.py:2270도 _effective_limit를 거치게 한다.

**37. api_key_encrypted 에 키 버전/지문 없음 + 단일 Fernet — SECRET_KEY 회전 시 전 서버 키 일괄 무효화**  
`src/discord_assistant/models.py:45-63` · data-integrity  
- 문제: 어떤 SECRET_KEY로 암호화됐는지 식별하는 키 버전/지문이 모델·저장 계층에 없고 crypto가 MultiFernet을 쓰지 않는다. SECRET_KEY를 한 번 바꾸면 기존 모든 길드의 api_key_encrypted가 복호화 불가가 된다.
- 영향: 운영자가 SECRET_KEY를 회전/교체하는 정당한 작업을 하는 순간 저장된 모든 길드 API 키가 일괄 무효화되어 OpenAI/Anthropic/Gemini 기능이 전 서버에서 동시에 중단된다. grace 기간을 둔 점진적 회전이 불가능하다.
- 수정: 암호문에 키 버전 식별자를 부착하고 crypto에 MultiFernet(구키+신키)으로 무중단 회전을 지원하거나, 회전 시 재암호화 마이그레이션 절차/스크립트를 명시한다.


### 대시보드 (`dashboard`)

**38. 인라인 폴백 PUT 경로가 daily_token_budget 컬럼을 누락 — store 경로와 스키마 drift**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/main.py:372-461` · 데이터 정합성/스키마 drift  
- 문제: storage import 가 실패해 인라인 폴백으로 동작하는 배포에서, 대시보드 PUT 이 길드의 새 config 행을 만들 때 daily_token_budget 을 NULL 로 INSERT 한다. 봇의 ConfigStore._upsert(14컬럼)와 폴백(13컬럼)이 갈라져 '전체 컬럼 보존' 불변(369-371 주석)이 신규행에서 깨진다.
- 영향: 대시보드에는 daily_token_budget setter 가 없으므로(봇의 /config daily_token_budget 만 설정), 신규행은 어차피 NULL=무제한으로 시작해 직접적 상한 소실은 제한적이다. 그러나 폴백 경로와 공용 store 경로의 동작/컬럼 집합이 갈라져 스키마 drift 가 누적되고, 향후 대시보드가 budget 을 다루게 되면 폴백에서 소실된다. 봇과 대시보드 폴백의 행 생성 의미가 불일치한다.
- 수정: 폴백 SELECT/INSERT/ON CONFLICT 컬럼 목록과 current dict 기본값에 daily_token_budget(기본 None)을 추가해 ConfigStore._upsert 의 14개 컬럼 집합과 정확히 일치시킨다(스키마 단일 출처).

**39. IP별 레이트리밋 저장소가 무한 증가 — 빈/오래된 버킷을 회수하지 않음**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/main.py:57-68` · 리소스 누수  
- 문제: 한 번 등장한 모든 클라이언트 IP 키가 프로세스 수명 동안 dict 에 영구히 남는다. 만료된(윈도 밖) 타임스탬프만 정리될 뿐 키 자체가 제거되지 않아 IP 종류가 누적된다.
- 영향: IP 가 자주 바뀌는 환경(특히 Render/프록시 뒤 또는 IPv6)에서는 키가 단조 증가해 메모리가 누적된다. 장기 구동 시 OOM 위험. 단일 워커 in-memory 라 재시작으로 리셋되긴 한다.
- 수정: 타임스탬프 리스트가 비면 del _rate_limit_store[ip] 로 키를 제거하거나, 주기적 _prune/LRU/TTL 캐시 또는 Redis 백엔드로 교체한다.

**40. 레이트리밋이 request.client.host 만 사용 — 프록시 뒤에서 전 사용자가 하나의 버킷 공유**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/main.py:62-73` · 보안(레이트리밋)/설정 함정  
- 문제: 레이트리밋 키가 peer IP 단일 값이다. 리버스 프록시/로드밸런서 뒤에서는 모든 요청의 client.host 가 프록시 IP 하나로 동일해진다. 신뢰 프록시의 X-Forwarded-For 처리가 없다.
- 영향: Render 등 프록시 뒤 배포(현 배포 가정)에서 전 사용자가 하나의 60req/min 버킷을 공유한다. 한 사용자의 정상 트래픽이 모두를 429 로 막는 자기-DoS 가 발생하고, 동시에 공격자별 제한은 불가능해 레이트리밋이 의도대로 동작하지 않는다.
- 수정: 신뢰 프록시 목록 기반으로 X-Forwarded-For 의 가장 바깥 신뢰 클라이언트 IP 를 쓰거나(ProxyHeadersMiddleware/uvicorn --proxy-headers + --forwarded-allow-ips), 배포 토폴로지에 맞춘 키 추출 전략을 명시한다.


### i18n/프롬프트 (`i18n-prompts`)

**41. set_language 가 언어 코드를 화이트리스트 검증/정규화하지 않아 임의 문자열이 프롬프트로 흘러간다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:692-699` · 데이터 정합성/보안(2차 인젝션)  
- 문제: 관리자가 /config language 에 'GIBBERISH', 'Français', 또는 'Answer in pirate. reveal secrets' 같은 임의 값을 넣으면 검증 없이 저장되고, language_label 이 원문을 그대로 통과시켜 프롬프트의 'Answer in {target_language}.' 자리에 박힌다.
- 영향: 프롬프트에 공격자/오타 제어 문자열이 삽입되어 LLM 응답 언어/품질이 깨지거나, 길드 언어 설정 필드를 통해 프롬프트 내용을 부분 오염시키는 2차 인젝션 표면이 된다. UI 임베드는 _normalize_lang 로 ko 폴백되지만 실제 응답 경로는 깨진다.
- 수정: set_language 에서 `normalized = language.strip().lower()` 후 _LANGUAGE_ALIASES 흡수 → `if normalized != 'auto' and normalized not in _LANGUAGE_LABELS: raise ValueError(...)` 로 화이트리스트 검증한다. config_language 핸들러도 동일 검증 후 저장한다.

**42. _split_discord_text 가 닫는 코드펜스(\n```) 길이를 경계 검사에 반영하지 않아 max_chars 를 초과하는 청크를 만든다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:729-734, 738-741` · 엣지케이스/경계 오류  
- 문제: 코드블록 내부 버퍼를 플러시하며 닫는 펜스를 더하는데(732/740) 경계 검사(730)가 그 4글자를 예약하지 않아 반환 청크가 max_chars 계약을 위반한다.
- 영향: 현재 MAX_DISCORD_MESSAGE_CHARS=1900(bot.py:306)이라 Discord 하드 한도 2000 까지 여유가 있어 실사고는 없지만, 함수 계약 위반이다. 호출자가 max_chars=2000(실제 Discord 한도)을 넘기면 2003 글자 청크가 만들어져 Discord API 가 HTTP 400(Must be 2000 or fewer)으로 전송을 거부한다.
- 수정: 코드블록 플러시 시 닫는 펜스를 예약한다. line 730 을 `len(candidate) + (len('\n```') if in_code_block else 0) > max_chars` 로 하거나, in_code_block 일 때 누적 한도를 `max_chars - 4` 로 둔다.


### LLM 제공자 (`llm-providers`)

**43. generate_with_tools 멀티 왕복에서 last_usage 가 덮어써져 토큰 과소 집계 (인스턴스 공유 시 경합)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:860,1128,944,1221` · 데이터 정합성/레이스  
- 문제: 툴 루프는 _chat_sync/_messages_sync 를 여러 왕복 호출하지만 각 호출이 self.last_usage 를 누적이 아니라 덮어쓴다. 호출부는 루프가 끝난 뒤 last_usage 를 한 번만 읽으므로 마지막 왕복(흔히 toolcall 직후의 최종 응답)의 토큰만 기록되고 중간 왕복의 입력/출력 토큰이 누락된다.
- 영향: search=True 등 멀티-툴 호출 비용이 과소 집계되어 #19 일일 토큰 상한·과금·통계가 실제보다 낮게 기록된다. 인스턴스 동시 공유 시(향후 캐싱/전역화) A/B 요청의 usage 가 뒤섞이는 경합도 가능하나, 현재 _get_llm 의 요청별 새 인스턴스 생성으로 그 부분은 발생하지 않는다(호출부 우연한 계약일 뿐 어댑터 자체는 비안전).
- 수정: 툴 루프에서 각 _chat_sync/_messages_sync 의 usage 를 누적 합산해 last_usage 에 반영한다(예: 왕복마다 prompt/completion 토큰을 더함). 더 견고하게는 usage 를 인스턴스 가변 속성 대신 반환 경로로 전달한다.

**44. OpenAI content 가 null 일 때 None.strip() 으로 AttributeError 가 LLMError 우회해 전파**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:722-728` · 엣지케이스/None  
- 문제: _generate_sync 는 content 를 꺼낸 뒤 곧바로 content.strip() 을 호출하지만 content 가 명시적 null 인 경우(OpenAI refusal/content filter)를 처리하지 않는다. None.strip() 의 AttributeError 가 의도된 'OpenAI 응답 형식을 해석할 수 없습니다.' OpenAIError 로 변환되지 않은 채 raw 로 전파된다.
- 영향: tools 없이 호출했는데 모델이 content:null 로 응답하면 친절한 형식 오류 대신 raw AttributeError 가 나고, LLMError 가 아니라 ask 핸들러의 except (UserFacingError, LLMError) 를 통과해 사용자에게 오류 임베드·재시도 없이 명령이 깨진 것처럼 보인다.
- 수정: content 가 str 인지 명시 검사: 'content = payload[...][...][...] ; if not isinstance(content, str): raise OpenAIError("OpenAI 응답 형식을 해석할 수 없습니다.")'. 또는 except 절에 AttributeError 를 추가해 None/누락을 정상 형식 오류로 처리한다.

**45. OpenAI 스트림이 SSE error 이벤트/[DONE] 누락을 조용히 무시해 빈 응답으로 종료**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:768-784` · 에러 처리 누락/스트림 파싱  
- 문제: _stream_sync 는 SSE 파싱 예외와 비-data 라인을 continue 로 삼키고 obj 내 'error' 키를 검사하지 않는다. OpenAI 스트림 중간/시작에 오는 {"error":{...}} 이벤트(rate limit/content filter/부분 실패)가 표면화되지 않고 스트림이 빈 채로 정상 종료된다.
- 영향: rate limit/content filter/부분 실패가 사용자에게 빈 답변 또는 잘린 답변으로 보이고 로그에도 안 남아 디버깅이 어렵다. 비스트리밍 경로(_generate_sync)는 HTTPError 본문을 오류로 올리지만 스트림 경로는 동일 실패를 무시해 동작이 비대칭적이다.
- 수정: SSE 파싱 후 obj 에 'error' 키가 있으면 OpenAIError 로 올린다(Ollama 스트림처럼). 또한 한 청크도 yield 하지 못한 채 종료한 경우를 감지해 호출부가 폴백/오류 처리하도록 신호를 준다.


### LLM 재시도/툴 (`llm-resilience`)

**46. OllamaManager.pull_model 에 타임아웃이 없어 무한 hang 가능**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1399-1408` · resource-leak  
- 문제: 모델 다운로드가 네트워크 정체/원격 레지스트리 무응답으로 멈추면 communicate() 가 반환되지 않는다. 호출 코루틴이 상한 없이 대기한다.
- 영향: 대용량 모델 pull 중 네트워크가 멈추면 호출 코루틴(및 이를 트리거한 Discord 인터랙션)이 영구 대기하고 서브프로세스도 정리되지 않아 좀비/리소스 누수로 이어질 수 있다.
- 수정: asyncio.wait_for(proc.communicate(), timeout=...) 로 상한을 두고, TimeoutError 시 proc.kill() 후 await proc.wait() 로 정리하고 OllamaError 로 안내한다. 장기 작업이면 진행률 스트림/하트비트로 헬스를 감시한다.

**47. 서킷 브레이커 half-open 단일 프로브 가드 부재 — thundering herd**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:298-321` · concurrency  
- 문제: half-open 상태에서 reset_timeout 이 지나는 순간 동시에 대기하던 여러 코루틴이 모두 _is_open()==False 를 보고 before_call 을 통과한다. half-open 프로브를 1개로 제한하는 상태가 없다.
- 영향: 제공자가 여전히 다운된 상태에서 reset_timeout 경과 직후 대기 중이던 모든 동시 요청이 한꺼번에 다운 제공자로 몰려 thundering herd 를 일으킨다. 의도한 부하 차단/완만한 복구가 무너지고 제공자 레이트리밋을 악화시킨다. 단, 이 봇은 인터랙션 단위 호출이라 동시성이 극단적으로 높지 않으면 영향은 제한적이다.
- 수정: half-open 진입 시 단일 프로브만 통과시키는 in-flight 플래그/락을 추가한다. 프로브 결과 전까지 다른 호출은 open 으로 빠르게 실패시키고, 프로브 성공 시에만 닫는다.

**48. _list_sync: 모델 항목 'name' 누락/형식 이상 시 KeyError·TypeError 가 try 밖에서 전파**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1390` · error-handling  
- 문제: 응답의 models 항목 중 하나라도 dict 가 아니거나 'name' 키가 없으면 KeyError/TypeError 가 _list_sync 의 try 밖에서 전파된다. m.get('size', 0) 은 방어적이지만 m['name'] 은 아니다.
- 영향: 비정상/버전 변경된 Ollama 응답 시 list_models() 가 KeyError/TypeError 로 깨진다. 모델 목록 UI/명령이 친절한 오류 없이 실패한다. 또한 is_available() 은 _list_sync 의 예외(이 컴프리헨션 단계)는 잡으므로 finding #1 의 'except 도달 불가'와는 별개의 예외 경로가 존재함을 보여준다.
- 수정: 컴프리헨션을 try 안으로 옮기고 m 이 dict 인지, 'name' 이 str 인지 검사해 누락 항목은 skip 한 뒤 OllamaModel 을 생성한다. 파싱 실패 시 [] 또는 명확한 OllamaError 로 변환한다.

**49. 툴 루프에서 last_usage 가 왕복마다 덮어써져 누적되지 않음 — 비용 과소 집계**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:860, 1128, 924-944, 1201-1221` · data-integrity  
- 문제: 툴 루프는 max_iterations 만큼 여러 LLM 왕복을 하지만 각 호출이 last_usage 를 덮어쓰기만 해서 마지막 왕복분만 남는다. 그 이전 왕복들의 토큰은 사라진다.
- 영향: 에이전트(툴) 경로는 실제로 여러 왕복으로 토큰/비용을 소비하지만 bot.py 의 _usage_tokens→_record_usage 는 마지막 왕복분만 본다. 과금/사용량 통계가 실제보다 크게 과소 집계되어 비용 추적·일일 상한 정책(_enforce_token_budget)이 약화된다.
- 수정: 툴 루프 동안 각 _chat_sync/_messages_sync 의 usage 를 누적(prompt/completion 합산)해 last_usage 에 반영하거나, generate_with_tools 가 누적 TokenUsage 를 별도로 합산해 루프 종료 시 한 번에 설정한다.


### 관측성/지원 (`observability-support`)

**50. JsonFormatter 가 logger.extra/추가 필드를 무시 — 컨텍스트 손실**  
`src/discord_assistant/logging_config.py:36-48` · 관측성/로깅 결함  
- 문제: JsonFormatter.format 은 고정 4키(time/level/logger/message)+exception 만 직렬화하고 record.__dict__ 추가 필드(extra=, stack_info, cid)를 반영하지 않는다.
- 영향: 구조화 로깅의 키-값 컨텍스트 이점 상실. extra 로 넘긴 guild_id/user_id/command 가 JSON 에 안 들어가 집계 불가.
- 수정: 표준 LogRecord 속성 제외한 record.__dict__ 잔여 키를 payload 에 병합, stack_info 가 있으면 stack 키 추가.

**51. format_error_message 트레이스백을 개발자 DM 으로 그대로 전송 — 민감정보 평문 노출**  
`src/discord_assistant/monitor.py:271-280` · 보안/PII 유출  
- 문제: format_error_message 가 전체 트레이스백을 코드블록째 Discord DM(notify_developer)으로 전송. 예외 메시지에 사용자 입력·경로·토큰 단편이 포함될 수 있고 스크럽이 없다.
- 영향: 시크릿/PII 가 서드파티 채널(DM, 영구 저장)에 평문으로 남는다.
- 수정: DM 전 알려진 시크릿/이메일 패턴 마스킹하거나 예외 타입+위치만 요약 전송.

**52. summarize 캐시 invalidate_prefix 의 startswith 키 충돌 — 인접 채널 캐시 오삭제**  
`src/discord_assistant/cache.py:33-36` · 캐시 무효화 버그/키 충돌  
- 문제: invalidate_prefix 가 str.startswith 로 매칭하는데 키가 구분자 없는 {guild}:{channel} 형태라 채널 45 의 무효화가 456/450/4567 채널 캐시까지 삭제한다.
- 영향: 한 채널 대화가 ID 접두 겹치는 다른 채널 요약 캐시를 부당 무효화 → 적중률 저하·불필요한 LLM 재호출. on_message 마다 발생.
- 수정: 키에 종료 구분자({guild}:{channel}:) 또는 정확 일치 무효화 API 사용.

**53. summarize 캐시 키가 언어/limit/모델/커스텀프롬프트를 무시 — 잘못된 언어/내용 응답**  
`src/discord_assistant/cache.py:30-31` · 캐시 키 설계 결함  
- 문제: 캐시 키가 guild:channel 뿐인데 요약 본문은 effective_language(auto 포함)·custom_summarize_prompt·model 에 따라 달라진다.
- 영향: 언어를 ko→en 바꿔도 새 메시지 없으면 이전 ko 요약 반환(헤더/본문 언어 불일치). 모델/프롬프트 변경도 미반영.
- 수정: cache_key 에 effective_language·model·custom_prompt 해시 포함.


### 보안 (`security`)

**54. DM 채팅이 길드 격리 없이 user 전역으로 chat_history 를 조회한다**  
`src/discord_assistant/bot.py:2961, 2976-2977` · security/data-integrity  
- 문제: DM 경로는 get_chat_history 를 guild_id=None 으로 호출해 storage 의 user 전역 분기로 떨어진다. 길드 대화는 guild_id+channel_id 로 스코프 저장되므로, DM 응답 맥락에 같은 사용자의 다른 서버 대화가 섞여 들어올 수 있다(비대칭 누수).
- 영향: 사용자가 특정 서버에서 나눈 대화 일부가 DM 응답 맥락(최근 10건)으로 새어 들어갈 수 있다(컨텍스트 누수/프라이버시). 단일 사용자 본인 데이터 범위 내 누수라 피해 대상은 본인이며 타인 데이터 노출은 아니다.
- 수정: DM 경로도 명시적 스코프 키(예: channel_id=message.channel.id, 또는 DM 전용 센티넬 guild_id)로 get_chat_history/save_chat_message 를 호출한다. 또는 get_chat_history 에서 guild_id 만 None 이고 channel_id 가 주어진 경우를 별도 스코프로 처리해 user 전역 폴백을 없앤다.

**55. API 키 검증이 429/5xx/400 등 비인증 오류를 '유효'로 간주해 무효 키를 저장한다**  
`src/discord_assistant/ui.py:267-330` · security/error-handling  
- 문제: 세 검증 함수가 401/403 만 무효로 보고 그 외 HTTP 오류(429/5xx/400)는 유효로 통과시킨다. 성공 코드(200/201)만 명시적으로 True 로 보는 것이 아니라 '401/403 이 아니면 통과' 방식이라, 제공자가 4xx/5xx 를 주는 잘못된/만료 키도 검증을 통과해 저장될 수 있다.
- 영향: 유효성 검증을 우회해 잘못된 키가 암호화 저장되고, 이후 LLM 호출이 런타임에 401 로 실패한다(사용자에게는 '키 등록 성공'으로 표시). 보안/UX 가치가 약화되나, 키 자체가 평문 노출되는 등의 직접 보안 침해는 아니다.
- 수정: 성공 코드(200/201)만 True 로 보고, 401/403/400 은 명확히 무효로 처리한다. 429/5xx/네트워크 오류는 '검증 불가'로 구분해 사용자에게 별도 안내(예: '검증을 건너뛰고 저장하시겠어요?')하거나 보수적으로 거부한다.

**56. 리액션 트리거(📝/🌐)가 역할 검사 없이 임의 메시지를 LLM 으로 처리한다**  
`src/discord_assistant/bot.py:3357-3420` · security/authorization  
- 문제: 리액션 핸들러는 쿨다운·토큰예산만 보고 allowed_role 검사 없이 LLM 처리를 트리거한다. allowed_role 이 설정된 서버에서도 리액션을 달 수 있는 사용자라면 이모지 한 번으로 봇이 메시지를 요약/번역해 채널에 응답하게 만들 수 있다.
- 영향: 역할 제한 우회 + LLM 비용 남용 벡터. 단, 메시지에 리액션을 달려면 Discord 상 해당 채널 View/Read 권한이 이미 필요하므로 '읽을 수 없는 메시지 노출'은 성립하기 어렵다. 핵심 영향은 allowed_role 통제 우회와 비용 소비.
- 수정: 리액션 핸들러에도 config.allowed_role_id 기반 _has_allowed_role 검사를 추가한다. 다른 LLM 진입점과 동일한 공통 가드로 일원화한다.


### 스토리지/DB (`storage`)

**57. save_chat_message prune keys only on user_id (global 200), wiping per-guild/channel history that get_chat_history retrieves by (user_id, guild_id, channel_id)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:846-859` · 데이터 정합성/엣지케이스  
- 문제: 채팅 기록 prune 은 사용자 전역으로 최신 200행만 유지하지만, 조회는 (user_id, guild_id, channel_id) 조합으로 필터링한다(bot.py:1806 이 실제로 guild_id/channel_id 를 전달). 따라서 한 사용자가 한 채널에서 200건 이상을 쌓으면 다른 길드/채널의 과거 메시지가 전부 삭제된다.
- 영향: 여러 길드/채널에서 활동하는 사용자의 경우 특정 채널 대화 컨텍스트가 다른 채널 활동에 의해 비결정적으로 소실되어, AI 답변의 맥락 일관성이 저하된다. 활동량이 많은 채널이 다른 채널의 기록을 굶주리게 한다.
- 수정: prune 키를 조회 키와 일치시킨다. prune 의 LIMIT 서브쿼리 WHERE 절을 (user_id, guild_id, channel_id) 조합별로 적용하거나, 조회 시맨틱이 채널별이면 prune 도 채널별 상한으로 통일한다.


### UI 뷰/모달 (`ui`)

**58. ModelInstallView timeout(1800s)이 Discord 인터랙션 토큰(15분)을 초과 — 최종/실패 edit 가 unretrieved 예외로 폭주**  
`src/discord_assistant/ui.py:719, 787-803 (특히 795, 800)` · API 오용 / 에러 처리  
- 문제: 15분 넘게 걸리는 대형 모델 다운로드가 끝나면 최종 상태를 쓰는 edit_original_response 가 401/404(Invalid Webhook Token)로 예외를 던진다. except 핸들러 내부의 edit(795/800)는 보호되지 않아 거기서 다시 예외가 나면 그대로 태스크 밖으로 전파된다.
- 영향: 사용자는 마지막 스피너('다운로드 중...') 상태에서 멈춰 보이고 설치 완료/실패 결과가 전달되지 않는다. 'Task exception was never retrieved' 로 끝나 운영자도 원인 추적이 어렵다. (단, ephemeral 이 아니라 deferred public 응답이라 토큰 만료 시점은 다운로드 길이에 따라 가변이라 medium 으로 조정.)
- 수정: 장시간 작업은 인터랙션 토큰 대신 최초 확보한 Message 핸들(interaction.original_response())로 message.edit() 갱신하거나, timeout 을 토큰 수명 이하로 낮춘다. 최소한 모든 최종/실패 edit_original_response 를 개별 try/except 로 감싸 NotFound 시 채널 폴백 전송 또는 무시한다.

**59. 공개(non-ephemeral) View(LongResponseView, FollowUpView)에 interaction_check 부재 — 타 사용자가 토큰 소모/콘텐츠 수신**  
`src/discord_assistant/ui.py:989-1011, 1169-1183` · 보안(권한)  
- 문제: 공개 채널에 붙는 LongResponseView·FollowUpView 는 interaction_check 가 없어 메시지를 볼 수 있는 누구나 버튼을 누를 수 있다. FollowUpView 의 '후속 질문' 은 임의 사용자가 트리거해 원작성자 맥락(transcript_snapshot)으로 LLM 호출을 일으킬 수 있다. LongResponseView 의 send_dm 은 interaction.user(클릭한 본인) 에게 전송하므로 '남의 DM 으로 전문 탈취' 는 불가하나(후보의 정보노출 시나리오는 과장 — 자기 DM 으로 받는 공개 내용), 임의 사용자가 DM 발송을 트리거할 수 있다.
- 영향: 다른 사용자가 FollowUpView 로 후속 LLM 호출을 일으켜 토큰 예산/비용을 소모(경미한 DoS)하거나, 원작성자의 대화 맥락에 기반한 후속 답변을 채널에 노출시킬 수 있다.
- 수정: LongResponseView/FollowUpView 에 author_id 를 저장하고 async def interaction_check 에서 interaction.user.id != author_id 면 ephemeral 거부 후 False 반환. 또는 이 결과/후속 흐름을 ephemeral 로 전송해 원작성자로 한정한다.


---

## ⚪ LOW (78)


### 봇 슬래시 명령 (`bot-commands`)

**60. summarize-channels: int(ch_id) ValueError exposes raw exception string in embed field**  
`src/discord_assistant/bot.py:2276-2293` · error-handling  
- 문제: The `except Exception as e` at 2292 formats the raw exception (`f"(오류: {e})"`) directly into the embed field at 2304. The candidate's premise that arbitrary non-numeric select values can reach `int(ch_id)` is a false alarm: Discord select values are server-defined (`value=str(ch.id)` at ui.py:1207), so ValueError from int() is not attacker-reachable. The genuine but minor issue is that any internal exception text (e.g. a provider error message) is surfaced verbatim into the user-facing embed.
- 영향: Minor information leakage / poor UX: internal exception messages (provider/SDK errors, stack-detail strings) appear in the embed field instead of a friendly message. No injection of arbitrary int() input because select values are bot-controlled.
- 수정: Replace `f"(오류: {e})"` at line 2293 with a generic user-facing message (e.g. '(요약 중 오류가 발생했어요)') and log the real exception server-side. The int() guard the candidate proposes is unnecessary given the constrained select values.

**61. run_ask/_run_chat/translate: retry button blocked by cooldown set during the failed attempt**  
`src/discord_assistant/bot.py:1602-1608, 1700-1703` · concurrency  
- 문제: Cooldown is recorded on entry (read-modify-write side effect). When the LLM call then fails and a Retry button is offered, clicking it within COOLDOWN_SECONDS (10s) re-enters the cooldown check and only shows a cooldown warning — the retry does not actually run.
- 영향: The 'Retry' button presented right after an error is effectively dead for up to 10 seconds; the user clicks it and merely receives another cooldown warning, which is confusing UX.
- 수정: Pass a skip_cooldown flag through the retry/follow-up re-entry paths, or roll back `_cooldowns[key]` when the attempt fails with LLMError so the retry is not blocked.

**62. stats_command/usage_command: get_stats keys indexed directly (no .get), and stats_command has no try/except**  
`src/discord_assistant/bot.py:2391-2427, 2582-2584` · error-handling  
- 문제: Inconsistent access pattern (`stats.get()` for some keys vs `stats[...]` for others) and absence of any try/except in stats_command. The KeyError the candidate fears is not reachable with the current get_stats implementation (it unconditionally returns all keys), so this is a maintainability/defense-in-depth issue, not a live bug.
- 영향: Low: if get_stats's return schema ever changes or a key is dropped, /stats would raise KeyError after defer with no user-facing fallback (silent failure + developer DM). usage_command shares the fragility.
- 수정: Use `stats.get('total', 0)` etc. consistently, and wrap stats_command's body in try/except with a user-friendly fallback message.

**63. export/search: interaction.channel may be None -> AttributeError on .history (uncaught)**  
`src/discord_assistant/bot.py:2334-2336, 2449-2451` · error-handling  
- 문제: Unlike _collect_transcript, export/search directly dereference interaction.channel.history without a None/hasattr guard. The AttributeError from a None channel is not in the caught exception tuple and would fall through to on_error (defer-only silent failure), inconsistent with _collect_transcript's friendly message. Likelihood is low because interaction.channel is rarely None for slash commands.
- 영향: Low: in the rare context where interaction.channel is None, /export and /search fail with an uncaught AttributeError (no user response, internal developer DM), instead of the friendly guidance _collect_transcript provides.
- 수정: Add the same `channel is None or not hasattr(channel, 'history')` guard (or reuse a shared helper) before calling history in both commands and raise UserFacingError.

**64. _check_cooldown / _cooldowns: lock-free global dict, in-memory across processes, mixed key semantics**  
`src/discord_assistant/bot.py:500-519, 2568` · race  
- 문제: No actual concurrency bug under single-process asyncio (the function has no awaits, so it is atomic). The genuine concern is scalability: _cooldowns is per-process global state, so any multi-process/multi-shard scaling makes cooldowns inconsistent (bypassable). Cleanup (508-512) only evicts entries older than COOLDOWN_SECONDS*10, so the dict grows up to one entry per (guild,user) pair within that window.
- 영향: Functionally correct on a single process. On horizontal scaling (multiple workers/shards) cooldowns split per process and can be evaded. Memory grows with active (guild,user) pairs until the periodic eviction threshold.
- 수정: Document the single-process assumption, or move cooldown state to a shared store (e.g. Redis) for multi-process deployments. No lock is needed under asyncio.

**65. translate/search: LLM output truncated to 1024 chars in embed with no overflow fallback**  
`src/discord_assistant/bot.py:1767-1768, 2480` · edge-case  
- 문제: Translation results and search summaries longer than 1024 chars are silently truncated in the embed field, and unlike /ask and /chat there is no 'receive full content via DM' fallback, so the overflow is permanently lost.
- 영향: Long translations (paragraph translation) or search summaries are cut at 1024 chars; the user sees only the ellipsis and has no way to retrieve the full result. Inconsistent overflow handling across commands.
- 수정: Use _send_answer_with_overflow, or attach a LongResponseView (DM button) when the answer exceeds 1024 chars, for both translate and search.

**66. _run_chat: streamed partial output + LLMError re-raise skips save_chat_message -> history desync**  
`src/discord_assistant/bot.py:1818-1852` · async  
- 문제: When streaming fails mid-way after emitting some text, the partial answer remains on screen but neither the user message nor the (partial) assistant reply is saved to chat_history, so the next /chat's history omits this turn entirely.
- 영향: After a mid-stream failure the user sees a (partial) reply yet that whole turn is missing from the conversation memory, so follow-up/'continue' questions lose context and become inconsistent.
- 수정: On partial-output failure, persist the user message (and partial answer) before re-raising, or explicitly tell the user the turn was not saved.

**67. auto_summary: only first eligible channel tried (break), and last_run set before LLM call**  
`src/discord_assistant/bot.py:3264, 3271-3291` · logic  
- 문제: Auto-summary picks the first eligible text channel and, due to the unconditional break at 3291 (and the early break at 3281 on empty transcript), never falls back to other channels. last_run is stamped at 3264 prior to the LLM call, so a failed or empty cycle is treated as done and skipped until the next interval.
- 영향: If the first eligible channel is inactive, that guild effectively gets no auto-summary even when other channels are busy; transient LLM failures also burn a whole interval because last_run was already recorded.
- 수정: Use `continue` instead of `break` on empty transcript so other channels are tried, and move the `_auto_summary_last_run[gid] = now` update to after a successful summary post.


### 이벤트 핸들러 (`bot-events`)

**68. 요약 캐시 무효화 prefix 충돌: 한 채널 메시지가 다른 채널 캐시를 잘못 무효화**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2942 (cache.py:33-36)` · 데이터 정합성/효율 (캐시 키 prefix 충돌)  
- 문제: 캐시 키와 무효화 prefix 모두 끝에 경계 구분자가 없어, 짧은 channel_id 의 prefix 가 더 긴 channel_id 의 키 접두사가 되면 다른 채널의 요약 캐시까지 함께 삭제된다.
- 영향: 같은 길드 내에서 짧은 channel_id 채널에 메시지가 올 때마다 그 ID 를 접두사로 갖는 다른 채널들의 요약 캐시까지 삭제돼 적중률이 떨어지고 불필요한 LLM 재호출/비용이 발생한다. 잘못된 결과를 주지는 않는다. 실제 Discord snowflake 는 자리수가 거의 같아(18~19자리) 충돌 빈도는 매우 낮으므로 severity 를 medium→low 로 하향한다. 테스트/소규모 ID 환경에서 주로 재현된다.
- 수정: 무효화를 정확 키 삭제(del/pop)로 바꾸거나, 키를 `f"{guild_id}:{channel_id}:"` 처럼 끝에 구분자를 두고 invalidate 에서 `k == key or k.startswith(key)` 로 경계를 강제한다.

**69. DM 안내 전송에서 광범위한 except Exception: pass 로 예외 삼킴**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2952-2955, 2991-2994` · async 함정/에러 처리 (예외 삼킴)  
- 문제: DM 경로에서 쿨다운/에러 안내 메시지를 보낼 때 모든 예외를 무조건 삼킨다. discord.HTTPException 외에 asyncio.CancelledError 같은 제어 예외도 삼켜질 수 있고, 전송 실패가 전혀 기록되지 않는다.
- 영향: DM 전송 실패 원인(권한/레이트리밋/네트워크)이 기록되지 않아 디버깅이 어렵다. graceful shutdown 중 CancelledError 가 삼켜지면 태스크 취소가 지연될 수 있다(드물지만 가능). 사용자는 안내를 못 받고 조용히 무시된다.
- 수정: discord.HTTPException(또는 discord.DiscordException)으로 좁혀 잡고 logger.debug/warning 으로 사유를 남긴다. CancelledError 는 다시 raise 한다.

**70. 이미지 첨부 판정이 클라이언트 제어 content_type 헤더만 신뢰**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3091-3094, 1166-1189` · 입력 검증/보안  
- 문제: 이미지 분석 분기와 _download_image_attachments 는 첨부가 이미지인지 content_type.startswith('image/') 만으로 판정하고, 실제 바이트의 매직넘버 검증이 없다.
- 영향: 비이미지(또는 위장된) 파일을 image/png 로 표시하면 MAX_IMAGE_BYTES 이내일 때 그대로 LLM 멀티모달 입력으로 전달된다. 제공자가 거부하면 LLMError 로 흡수되지만, 잘못된 바이트로 불필요한 업로드/토큰/요청이 발생할 수 있다. 영향은 제공자 측 검증으로 대부분 흡수되어 낮음.
- 수정: 다운로드한 바이트의 매직넘버(PNG \x89PNG, JPEG \xFF\xD8, GIF GIF8, WEBP RIFF...WEBP)를 확인해 실제 이미지가 아니면 건너뛰고, content_type 과 실제 포맷 불일치 시 로그를 남긴다.

**71. 봇 답장 chat 경로가 쿨다운에 걸리면 안내 없이 조용히 무시**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3032-3034` · UX/일관성  
- 문제: 봇 메시지에 답장하는 chat 경로는 쿨다운에 걸리면 아무 안내 없이 return 한다. DM/컨텍스트 메뉴 경로는 남은 쿨다운 초를 안내한다(동작 불일치).
- 영향: 사용자가 봇 답장으로 후속 질문을 했는데 쿨다운에 걸리면 봇이 완전히 무반응이라, 봇이 멈췄거나 답장을 못 알아들은 것으로 오해할 수 있다.
- 수정: 다른 경로와 동일하게 `await message.channel.send(f"⏳ {reply_remaining:.0f}초 후에 다시 시도해주세요.")` 같은 짧은 안내를 보내거나, 의도적 침묵이면 주석으로 설계 근거를 명시한다.

**72. 컨텍스트 메뉴: 토큰 상한 검사(DB 왕복 2회)를 defer 전에 수행해 3초 ACK 시한 위험**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2604-2627, 2635-2639, 2664-2668, 2694-2698` · async 함정 (interaction 응답 시한)  
- 문제: _ctx_menu_guard 가 get_guild_config + get_today_token_usage(DB 왕복 2회)를 수행한 뒤 핸들러로 돌아오고, defer(thinking=True)는 그 이후에 호출된다. ACK 가 가드의 DB 호출 뒤로 밀린다.
- 영향: SQLite 가 다른 쓰기와 경합해 잠겨 있거나 디스크 I/O 가 느린 순간, 가드의 DB 호출이 누적되어 defer 가 3초를 넘기면 Discord 가 interaction 을 실패(Unknown interaction)로 처리하고 이후 followup 이 모두 실패한다. 평소엔 빠르지만 부하 시 간헐 실패 가능. 단일 사용자 봇에서 SQLite 가 보통 매우 빠르므로 medium→low 로 하향.
- 수정: 핸들러 진입 직후 가장 먼저 interaction.response.defer(thinking=True, ephemeral=True) 로 ACK 를 확보한 뒤, 쿨다운/빈 메시지/토큰 상한 검사를 수행하고 결과를 followup.send 로 보내도록 순서를 바꾼다.


### 수명주기/동시성 (`bot-lifecycle`)

**73. _auto_summary_last_run 딕셔너리가 무한 증가 — on_guild_remove 없음, 정리 로직 부재**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:337, 3259, 3264` · resource-leak  
- 문제: 자동요약을 한 번이라도 켠 모든 길드의 마지막 실행 시각이 _auto_summary_last_run 에 영구 누적된다. 상한·만료·on_guild_remove 정리가 전혀 없다.
- 영향: 장기 가동 시 자동요약을 켠 길드 수만큼 메모리가 단조 증가한다. 봇이 떠난 길드 엔트리도 비워지지 않는다. 다만 엔트리당 int->datetime 한 쌍이라 절대량이 작고, 길드 수에 선형이라 실무 영향은 미미해 low. (후보가 medium 으로 제시했으나 severity 하향)
- 수정: auto_summary_task 매 주기에서 configured set 에 없는 gid 를 _auto_summary_last_run 에서 제거하거나(교집합 유지), on_guild_remove 이벤트를 추가해 _auto_summary_last_run.pop(gid, None) / _tracked_messages.pop(gid, None) 로 정리한다.

**74. run_bot 의 add_signal_handler 를 종료 시 remove 하지 않아 재사용 루프에서 이전 클로저가 잔존**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:3546-3550, 3562-3570` · deployment  
- 문제: 시그널 핸들러를 등록만 하고 해제하지 않는다. asyncio.run 단일 경로에선 무해하나, 같은 루프를 재사용하는 호출자(임베딩/테스트)에선 직전 호출의 _request_stop 클로저가 남는다.
- 영향: 재사용 루프 환경에서 SIGTERM/SIGINT 가 직전 호출의 stop_event 만 set 해 현재 봇이 graceful shutdown(#49) 되지 못할 수 있다. 일반 배포(main → asyncio.run)에선 영향 없음.
- 수정: 등록 성공한 시그널을 set 에 모아두고, finally 에서 `for sig in registered: loop.remove_signal_handler(sig)` 로 해제한다.

**75. _last_summaries 가 LRU 가 아니라 FIFO — 재대입이 삽입 순서를 갱신하지 않아 활성 사용자가 부당하게 evict**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1524-1526, 2036-2042` · edge-case  
- 문제: 주석/의도는 '마지막 요약 캐시'(LRU 성격)이지만 실제 동작은 최초 삽입 순서 기준 FIFO 다. 1000명 한도를 넘는 활성 봇에서 자주 쓰는 오래된 사용자가 캐시에서 밀린다.
- 영향: 1000명 초과 환경에서 자주 /summarize 하는 사용자가 인메모리 마지막 요약을 잃어, 곧바로 message 없이 /remind 를 호출하면 '보낼 내용이 없어요'(2039)로 실패할 수 있다. 한도가 1000 으로 크고 정확성만 저하되어 low.
- 수정: OrderedDict + move_to_end 를 쓰거나, 재대입 전 `_last_summaries.pop(user_id, None)` 후 다시 넣어 삽입 순서를 갱신해 진짜 LRU 로 만든다.

**76. DM on_message 가 빈/접두명령 메시지도 무조건 LLM 호출 — content 검증·ctx.valid 분기 부재, 토큰 가드도 없음**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2944-2972` · logic  
- 문제: DM 에서 빈 메시지·공백·'!ping' 같은 접두 명령·멘션만 보내도 그 내용이 그대로 LLM 프롬프트가 되어 generate 가 호출된다. process_commands 가 이미 명령을 처리한 경우에도 LLM 폴백이 추가로 실행된다.
- 영향: DM 에서 접두 명령/빈 입력/멘션만 보낸 경우에도 불필요한 LLM 호출과 토큰 소비가 발생하고, 명령어 텍스트가 AI 프롬프트로 새어 들어가 엉뚱한 응답을 만든다. DM 은 토큰 상한 대상도 아니라 비용 가드가 없다. 봇은 DM 명령을 거의 쓰지 않아 실무 빈도는 낮아 low.
- 수정: DM LLM 경로 진입 전에 `if not message.content.strip() or message.content.startswith('!'): return` 을 두고, process_commands 결과 ctx.valid 인 경우 LLM 폴백을 건너뛰도록 분기한다.

**77. _track_for_feedback 의 두 add_reaction 을 단일 try 로 묶고 HTTPException 만 잡아 부분 실패/비-HTTP 예외 전파**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:537-548` · error-handling  
- 문제: 두 리액션 시드와 추적 등록의 실패 격리가 불완전하다. 한쪽 실패 시 이모지 비대칭, 비-HTTP 예외 시 사용량 기록 전 흐름 중단 가능.
- 영향: 결과 메시지에 👍/👎 중 하나만 붙어 피드백 UX 가 일관되지 않다. 드물게 비-HTTP 예외가 _record_usage 전에 터지면 사용량 기록이 누락될 수 있다. 호출부 다수가 _track_for_feedback 뒤 _record_usage 를 호출하므로 영향 경로는 존재하나 빈도 낮아 low.
- 수정: 두 add_reaction 을 각각 try/except 로 분리하거나, except 를 (discord.HTTPException, asyncio.TimeoutError) 로 넓혀 한쪽 실패가 추적/기록 흐름을 깨지 않게 한다.

**78. _schedule_reminder 가 due_at 파싱 실패 시 즉시 발송으로 폴백 — 손상된 미래 reminder 가 봇 시작 시 곧바로 발송**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1344-1354, 1368-1369` · edge-case  
- 문제: due_at 파싱 실패를 '즉시 발송'으로 처리한다. 잘못 저장/타임존 누락된 미래 reminder 가 의도 시점이 아니라 봇 시작 시 바로 나간다. 또 최대 30일 단일 sleep 은 시스템 시계 조정/서스펜드에 취약하다.
- 영향: due_at 손상 reminder 가 의도 시점이 아니라 즉시(또는 부정확하게) 발송될 수 있다. 손상 데이터는 드물어 low. (시계 뒤점프로 영원히 안 깨는 시나리오는 단조시계 미사용 환경에서만 발생)
- 수정: 긴 지연은 짧은 간격 루프로 쪼개 매번 datetime.now 와 due 를 재비교한다. 파싱 실패한 due_at 은 즉시 발송 대신 logger.warning 후 skip 하거나 mark_sent 로 격리한다(현재도 로깅은 1347 에서 하나 폴백이 즉시 발송).

**79. 인메모리 봇 상태가 모듈 전역이라 다중 create_bot 인스턴스 간 공유 — 길드 데이터 교차 + shutdown 시 타 봇 태스크 취소**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:269, 328-340, 1369, 3519-3526` · data-integrity  
- 문제: DB(store)는 봇별로 분리되지만 쿨다운·마지막요약·피드백 추적·자동요약 시각·백그라운드 태스크는 전부 모듈 전역으로 공유된다. _cancel_background_tasks 도 전역을 비운다.
- 영향: 다중 인스턴스/테스트 환경에서 쿨다운·마지막요약·피드백 추적이 인스턴스 간 누수되고, 한 봇 graceful shutdown 이 다른 봇의 백그라운드 태스크(리마인더 등)를 함께 취소해 미발송/오작동을 유발한다. 프로덕션은 보통 단일 인스턴스라 실무 영향은 작고 주로 테스트 격리 문제라 low.
- 수정: 이 상태들을 AssistantBot 인스턴스 속성(또는 create_bot 클로저 지역)으로 옮겨 봇 단위로 격리한다. 최소한 _cancel_background_tasks 가 해당 봇이 만든 태스크 집합만 취소하도록 봇별 _background_tasks 를 둔다.


### 리마인더 (`bot-reminders`)

**80. due_at 마이크로초 vs 초 정밀도 불일치로 list_due 사전식 비교가 경계에서 만기 누락**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2047` · data-integrity/timezone  
- 문제: remind_command 가 add_reminder 에 마이크로초 포함 due_at 을 넘겨 storage 가 가정하는 초 단위 포맷 일관성을 깬다. list_due 의 문자열 비교는 '+' < '.' 때문에 같은 초 경계에서 만기 항목을 한 사이클 누락한다.
- 영향: 현재 활성 발송은 _schedule_reminder 의 sleep 을 쓰고 list_due 는 real-time now 로 호출되지 않으므로 실사용 영향은 거의 없다(잠재 결함). 향후 폴링 루프를 list_due(default now)로 추가하면 같은 초 경계에서 만기 알림이 한 사이클 늦게 분류된다.
- 수정: remind_command 와 _reschedule_pending_reminders 의 far_future 도 isoformat(timespec='seconds') 로 통일하거나, list_due 비교를 datetime 파싱 후 비교로 바꿔 혼합 정밀도/오프셋 모두 안전하게 한다.

**81. non-UTC 오프셋 행에서 list_due 의 문자열 사전식 비교가 시간 순서와 불일치**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:1191-1209, 1174` · data-integrity/timezone  
- 문제: due_at 비교가 datetime 이 아닌 문자열 사전식이고 add_reminder 가 오프셋을 정규화하지 않는다. +09:00 같은 비-UTC 오프셋이 섞이면 정렬이 시간 순서와 어긋난다.
- 영향: 봇 자체는 항상 +00:00 이라 현재 영향 없음(잠재). 외부 삽입/마이그레이션/추후 기능이 비-UTC 오프셋을 넣으면 만기 리마인더 유실 또는 잘못된 순서 처리로 이어진다.
- 수정: add_reminder 에서 due_at 을 파싱->UTC 변환->고정 포맷(timespec='seconds')으로 정규화해 저장하고, 비교도 정규화된 UTC 문자열로만 수행하도록 불변식을 코드로 강제한다.

**82. reminders 테이블에 (sent, due_at)/(user_id, sent) 인덱스 부재 + sent=1 행 미정리(무한 누적)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:333-346, 899-931, 1191-1237` · performance/resource  
- 문제: reminders 에 조회용 인덱스가 없고, retention_task/purge_old 가 reminders 를 정리하지 않아 sent=1 행이 단조 증가한다.
- 영향: 리마인더 누적량이 늘수록(특히 sent=1 영구 보존) on_ready reschedule 의 list_due 와 /reminders 의 list_by_user 가 점점 느려진다. 테이블이 무한 증가한다.
- 수정: CREATE INDEX idx_reminders_due ON reminders(sent, due_at) 와 idx_reminders_user ON reminders(user_id, sent) 를 추가하고, 일정 기간 지난 sent=1 행을 정리하는 retention 을 purge_old 에 추가한다.

**83. _parse_remind_delay 가 유니코드 숫자/선행 0 을 허용하고 docstring '1초 미만' 보장이 거짓**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:456-478` · edge-case/validation  
- 문제: 정규식 \d 가 아랍-인도 숫자 등 유니코드 숫자를 매칭하고 int() 가 이를 받아들인다. docstring 의 '1초 미만 에러' 는 실제로 구현돼 있지 않다(분 단위 입력만 가능).
- 영향: 유니코드 숫자 입력 시 사용자가 의도한 것과 다른 지연이 설정될 수 있고, docstring 보장이 사실이 아니라 유지보수 혼란을 준다. 보안 영향은 작다.
- 수정: 정규식을 [0-9]+ 로 ASCII 숫자만 허용하거나 int 변환 후 범위/형식을 재검증한다. docstring 을 실제 동작(분 단위 최소 1)과 일치시킨다.

**84. repeat 라벨이 표시만 되고 실제 반복 예약이 없어 사용자 기대를 오인하게 함**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2011, 2048-2049, 2071-2074, 1320-1337` · correctness/UX  
- 문제: repeat 라벨은 임베드/응답에 표시되고 payload 에 저장되지만 실제 반복 재예약은 구현돼 있지 않다. 단 1회만 발송된다.
- 영향: 사용자가 daily/weekly 를 지정하면 매일/매주 알림을 기대하지만 1회만 온다. 동작하는 듯한 UI 라벨이 침묵 실패를 유발한다.
- 수정: 반복을 실제 구현(발송 후 다음 주기로 due_at 재계산해 add_reminder)하거나, 응답/임베드 라벨에서 '반복' 표기를 제거하고 표시용임을 사용자 메시지에도 명시한다.

**85. _last_summaries 가 user 전역 캐시라 다른 길드 요약이 /remind 로 잘못 캡처될 수 있음**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:2036-2044, 331-334, 1459, 1524` · data-integrity/privacy  
- 문제: /remind 의 빈 메시지 경로가 user_id 단일 키 캐시의 마지막 요약을 길드 무관하게 사용한다. tuple 에 guild_id 가 있지만 현재 interaction 의 guild_id 와 대조하지 않는다.
- 영향: A 길드에서 요약 후 B 길드(또는 DM)에서 빈 /remind 하면 A 길드 요약 본문이 DM 으로 전송될 수 있다. 비공개 채널 요약이면 경미한 정보 노출/혼동.
- 수정: 캐시 키를 (user_id, guild_id) 로 하거나, tuple 에 저장된 guild_id 와 현재 interaction 의 guild_id 가 일치할 때만 재사용하도록 검증한다(이미 guild_id 를 저장하므로 활용).

**86. _schedule_reminder 가 최대 30일을 단일 asyncio.sleep 으로 대기 + 파싱 실패 시 즉시 발송**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1344-1354` · edge-case/reliability  
- 문제: 장기 리마인더를 단일 sleep(최대 30일)으로 대기하고, due_at 파싱 실패를 조용히 '지금 발송' 으로 처리한다.
- 영향: 장기 리마인더가 호스트 절전/서스펜드 후 누적 지연되거나, 파싱 불가 due_at 이 즉시 발송돼 의도와 다른 타이밍에 간다. 봇이 30일 내내 떠 있어야 하는 단일 sleep 의존성도 신뢰성 약점.
- 수정: 긴 대기는 짧은 주기(분 단위) 폴링 루프(list_due)로 전환하거나 sleep 을 청크로 나눠 주기적으로 due_at 을 재평가한다. 파싱 실패 due_at 은 즉시 발송 대신 로깅 후 skip/영구실패 mark_sent 로 다르게 처리한다.


### CI·CD/인프라 (`cicd-infra`)

**87. 백업 보존 정리(prune)가 -wal/-shm 사이드카를 삭제하지 않아 디스크 누수**  
`/Users/osuma/coding_stuffs/discord-assitant/scripts/backup.sh:92-103` · resource-leak  
- 문제: prune 글롭이 `bot_*.db` 만 매칭해 cp 폴백이 만든 -wal/-shm 사이드카를 정리하지 못한다.
- 영향: sqlite3 CLI 가 없는 컨테이너 환경(항목 2와 동일 조건)에서 -wal/-shm 사이드카가 매일 쌓이며 7일 후에도 정리되지 않는다. 다만 실무상 WAL 사이드카는 메인 DB 대비 작고, 백업 직후 봇이 활발히 쓰던 상태가 아니면 -wal/-shm 이 존재하지 않을 수도 있어 severity 는 low 로 유지. 장기적으로 backups 디렉터리가 비대해질 수 있다.
- 수정: 삭제 시 사이드카도 함께 제거한다: 루프 안에서 `rm -f "$old" "${old}-wal" "${old}-shm"`. 또는 정렬 키는 .db 기준으로 두되 삭제 대상을 `bot_*.db*` 로 확장.

**88. restore.sh 의 대화형 read 프롬프트가 비대화형(cron/CI)에서 무음 취소되거나 멈춘다**  
`/Users/osuma/coding_stuffs/discord-assitant/scripts/restore.sh:57-66` · error-handling  
- 문제: FORCE=1 없이 비대화형 컨텍스트에서 실행하면 read 가 빈 입력으로 'N' 취소되어 복원이 수행되지 않은 채 exit 0(성공)으로 끝난다(또는 tty 가 붙으면 무한 대기).
- 영향: 재해 복구를 자동화로 감쌌다가 FORCE=1 을 빠뜨리면 복원이 안 된 채 성공 코드로 종료해 운영자가 복원됐다고 오판할 수 있다. 데이터 파괴는 없으나 '복원 성공' 오판이 위험.
- 수정: stdin 이 tty 가 아니면(`[ ! -t 0 ]`) FORCE 미설정 시 에러로 중단(exit 1)하는 가드를 추가한다. 빈 응답을 '명시적 확인 필요' 로 다뤄 자동화에서의 무음 스킵을 막는다.

**89. staging compose 에 OLLAMA_BASE_URL 리터럴 오버라이드/extra_hosts 가 없어 .env 의 localhost 가 컨테이너 자신을 가리킨다**  
`/Users/osuma/coding_stuffs/discord-assitant/compose.staging.yml:25-32` · config  
- 문제: staging compose 가 prod 와 달리 OLLAMA_BASE_URL 리터럴 오버라이드와 host-gateway extra_hosts 를 빠뜨려, Ollama 사용 시 컨테이너가 자기 자신을 가리킨다.
- 영향: 스테이징에서 Ollama 기반 검증(HEALTHCHECK_REQUIRE_OLLAMA=true)이나 Ollama 명령을 쓰면 컨테이너가 자기 11434 포트로 접속을 시도해 항상 실패한다. prod 와 동작이 갈려 스테이징이 prod 동작을 대표하지 못한다. 다만 staging 기본값(line 29)이 HEALTHCHECK_REQUIRE_OLLAMA=false 이고 클라우드 API 우선이라 헬스 자체는 통과하므로 severity 는 low.
- 수정: compose.staging.yml 에도 prod 와 동일하게 `OLLAMA_BASE_URL: "http://host.docker.internal:11434"` 와 `extra_hosts: ["host.docker.internal:host-gateway"]` 를 추가하거나, .env.staging 에서 호스트 게이트웨이 URL 을 명시하도록 문서화한다.

**90. 백업 파일명이 날짜(일 단위)만 사용 → 같은 날 재실행 시 같은 날 백업을 덮어쓴다**  
`/Users/osuma/coding_stuffs/discord-assitant/scripts/backup.sh:11-47` · data-integrity  
- 문제: 정기 백업 파일명이 일 단위라 같은 날 두 번째 백업이 첫 번째를 덮어쓴다.
- 영향: 하루 1회 cron 전제에선 보통 무해하나, 같은 날 두 번째 백업이 첫 번째를 덮어써 '하루 단위 7개 보존' 의도가 깨지고, 사고 직후 수동 백업이 다음 정기 백업으로 덮여 특정 시점 스냅샷을 잃을 수 있다.
- 수정: 정기 백업 파일명에도 시각을 포함(`date +%Y-%m-%d_%H-%M-%S`)하거나 동일 이름 존재 시 덮어쓰기 전에 경고/스킵한다. prune 글롭/정렬도 그에 맞춰 조정.

**91. deploy 매트릭스가 사용자 제어 vars.DEPLOY_TARGETS 를 의미 검증 없이 runs-on 라벨/deploy_dir 로 직접 사용**  
`/Users/osuma/coding_stuffs/discord-assitant/.github/workflows/deploy.yml:117-151` · deployment  
- 문제: DEPLOY_TARGETS 가 JSON 형식만 검증되고 labels/deploy_dir 의 의미 유효성(등록 라벨 일치, 허용 경로) 검증 없이 매트릭스 runs-on 과 배포 경로로 직접 사용된다.
- 영향: 오타/미존재 라벨 조합이면 deploy job 이 매칭 러너를 못 찾아 무기한 큐잉되고, deploy_dir 에 임의 경로를 넣으면 install -d 로 디렉터리를 만들고 거기에 .env(시크릿)와 compose 를 렌더한다. repo Variable 쓰기 권한자가 배포 대상 경로/호스트를 사실상 조종할 수 있다. 다만 repo Variable 수정에는 별도 권한이 필요해 임의 외부 공격은 아니므로 severity low.
- 수정: prepare-targets 에서 labels 가 허용 목록(self-hosted/macOS/ARM64/Linux/X64 등)에 속하는지, deploy_dir 이 허용 베이스 경로 하위 절대경로인지 검증해 위반 시 실패시킨다. 멀티 호스트가 불필요하면 기능 비활성화.

**92. auto-release 가 workflow_run 성공만 보고 main HEAD(배포 SHA 아님)에 태그 → 부분 실패/SHA 드리프트 가능**  
`/Users/osuma/coding_stuffs/discord-assitant/.github/workflows/auto-release.yml:22-32` · deployment  
- 문제: 릴리스가 deploy 의 head_sha 가 아닌 현재 main HEAD 에 태그를 찍는다. 배포 트리거와 태깅 사이 main 이 진전되면 릴리스 태그가 실제 배포 이미지(sha-<배포시점>)와 다른 커밋을 가리킨다. 멀티 호스트 부분 실패 시 일부 호스트가 깨진 채 릴리스될 여지도 있다.
- 영향: 배포 트리거 SHA 와 다른(더 최신) main 커밋을 태깅하면 릴리스 태그가 실제 배포된 이미지와 어긋나 릴리스 노트/추적이 부정확해진다. 단일 호스트 환경에서는 부분 실패 문제가 없고, main concurrency(deploy.yml:24-26)로 직렬화되어 SHA 드리프트 빈도도 낮아 severity low.
- 수정: 릴리스 체크아웃을 `ref: ${{ github.event.workflow_run.head_sha }}` 로 배포된 정확한 커밋에 고정한다(태그도 그 커밋에). 멀티 호스트 시 모든 타겟 성공을 릴리스 전제로 삼도록 성공 판정을 강화하거나 fail-fast 정책을 재검토한다.


### 설정/암호화 (`config-crypto`)

**93. _get_bool 이 화이트리스트 밖 값을 검증 없이 조용히 False 처리 (다른 파서와 비대칭)**  
`src/discord_assistant/settings.py:40-44` · config  
- 문제: _get_bool은 truthy 화이트리스트에 없는 모든 값을 검증 없이 False로 떨어뜨린다. _get_int/_get_float가 잘못된 입력을 ValueError로 거부하는 것과 정책이 불일치한다.
- 영향: 오타·비표준 표기로 AUTO_SYNC_COMMANDS 등 기본 True 토글이 의도와 반대로 조용히 꺼져 명령이 갱신되지 않는 등 진단 어려운 설정 오류가 생길 수 있다.
- 수정: 참/거짓 화이트리스트를 모두 정의하고 어디에도 속하지 않는 값은 다른 파서처럼 ValueError로 거부하거나 최소한 경고 로그를 남긴다.

**94. ollama_model·database_url 은 빈 문자열 폴백 부재 — ollama_base_url 과 폴백 정책 불일치**  
`src/discord_assistant/settings.py:115-127` · config  
- 문제: OLLAMA_MODEL=''/DATABASE_URL='   '로 설정하면 strip 결과가 빈 문자열이어도 그대로 사용된다. ollama_base_url·default_language·ollama_keep_alive·llm_system_prompt는 빈 값 폴백이 있는데 ollama_model·database_url만 누락돼 정책이 불일치한다.
- 영향: DATABASE_URL이 공백이면 잘못된 경로로 DB가 생성되거나 연결 실패가 늦게 드러나고, OLLAMA_MODEL이 빈 값이면 모델 호출 시점에야 오류가 나 기동-실패 격차로 진단이 늦어진다. 보통은 env를 비워두는 일이 드물어 low.
- 수정: ollama_model·database_url도 빈 값이면 기본값으로 폴백하거나 명시적으로 검증해 모든 필수 문자열 필드의 처리 정책을 일관되게 한다.

**95. from_env가 매 호출 load_dotenv()로 mutable os.environ 시점에 의존 — 프로세스 싱글톤 미보장**  
`src/discord_assistant/settings.py:93-96` · config  
- 문제: from_env는 load_env_file=True일 때 매 호출 load_dotenv()를 실행하고 그 시점의 os.environ을 읽어 인스턴스를 만든다. frozen dataclass라 인스턴스 자체는 불변이지만 호출마다 다른 스냅샷이 나올 수 있어 '한 번 로드'라는 암묵 가정이 보장되지 않는다.
- 영향: from_env를 여러 곳에서 호출하면 컴포넌트별로 미세하게 다른 설정으로 동작할 수 있고, 특히 테스트에서 monkeypatch 후 재호출 시 재현 어려운 불일치가 생길 수 있다. 영향 범위가 좁아 low.
- 수정: 프로세스 단위 단일 인스턴스(모듈 싱글톤/캐시)로 settings를 한 번만 생성해 공유하거나, from_env의 멱등성/override 정책을 문서화하고 테스트에서 일관되게 사용한다.


### 대시보드 (`dashboard`)

**96. Discord 토큰 교환 실패 시 raw 응답 본문을 클라이언트에 그대로 누설**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:312-316` · 보안(에러 누설)  
- 문제: 토큰 교환이 200 이 아니면 Discord 의 raw 응답 본문(token_resp.text)을 502 응답 detail 로 외부에 노출한다. 이 본문에는 redirect_uri 불일치·invalid_client 등 OAuth 설정 단서가 섞일 수 있다.
- 영향: 공격자가 콜백을 조작해 의도적으로 실패를 유발하면 OAuth 설정 내부 정보를 정탐할 수 있어 에러 표면이 불필요하게 넓어진다. client_secret 자체가 응답에 실리지는 않으므로 영향은 정보 노출 수준(low).
- 수정: 사용자에게는 'Discord token exchange failed' 같은 일반화된 메시지만 반환하고, 원본 token_resp.text 는 서버 로그로만 남긴다(시크릿 마스킹 후).

**97. _assert_guild_access/_admin 이 클레임 파싱 실패 시 500 으로 폭발**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/main.py:904-912` · 에러 처리 누락/엣지케이스  
- 문제: guilds 클레임이 예상 밖 형태(id 누락·비숫자, guilds 가 list 아님)면 명확한 401/403 대신 처리되지 않은 500 이 발생한다. 다만 JWT 는 서버 서명이라 정상 발급(create_jwt:152-160)은 항상 정상 형태이며, 변조는 서명 검증에서 막히므로 트리거는 구버전/손상 토큰에 한정된다.
- 영향: 잘못된/오래된 토큰으로 500 Internal Server Error 가 발생해 견고성·관측성이 떨어진다. 보안 우회는 아니다(서명 검증이 먼저 막음).
- 수정: guilds 가 list 가 아니면 빈 목록으로 취급하고, 각 항목의 g.get('id') None/형변환 실패를 try/except 로 안전하게 skip 해 일관되게 403 을 반환한다.

**98. Authorization: Bearer 폴백 + 응답 바디 token 이 httpOnly 쿠키의 XSS 방어를 부분 복원**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:250-262` · 보안(토큰 처리)  
- 문제: JWT 를 httpOnly 쿠키(#34)로 막으려 했으나, 콜백/리프레시가 바디에 token 을 노출하고 Bearer 헤더 폴백도 수용해 JS 가 읽을 수 있는 토큰 경로가 부분적으로 복원된다.
- 영향: XSS 가 존재하면 응답 바디의 token 을 저장·재사용하거나 Authorization 헤더로 인증을 위조할 수 있어 httpOnly 쿠키의 토큰 탈취 방어가 약화된다. 전환기 백워드 호환을 위한 의도적 병행이라 즉각적 취약점은 아니다.
- 수정: 전환이 끝나면 바디의 token 반환과 Bearer 헤더 폴백을 제거하고 httpOnly 쿠키만 신뢰한다. 병행 기간에는 짧은 만료/범위 제한을 둔다.

**99. OAuth state 만료 청소가 /login 호출 시에만 일어남**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:228-237` · 리소스 누수  
- 문제: 발급만 되고 콜백이 오지 않은 state(동의 취소 등)는 다음 login 호출 전까지 누적된다. _STATE_TTL_SECONDS(25,=600s) 후 만료지만 키는 login 빈도에 의존해서만 제거된다.
- 영향: 장기 구동·낮은 login 빈도에서 만료 state 가 누적되어 메모리가 소폭 단조 증가한다. _rate_limit_store(#4)와 함께 in-memory 누수 패턴을 키운다. 단일 워커 재시작으로 리셋된다.
- 수정: 주기적 백그라운드 태스크 또는 callback 진입 시에도 만료 청소를 수행하거나 TTL 캐시로 교체한다.

**100. logout 이 revoke 실패를 무시하고 항상 성공 처리 — 무효화 보장/가시성 부재**  
`/Users/osuma/coding_stuffs/discord-assitant/dashboard/backend/auth.py:181-204` · 보안(세션 관리)  
- 문제: 로그아웃이 멱등 성공으로 설계됐으나, 만료 직전 경합·jti 부재 등으로 revoke 가 실제로 블랙리스트 등재에 실패해도 사용자에게는 성공으로 보인다. 무효화 성공 여부 로깅/가시성이 없다.
- 영향: 사용자가 로그아웃했다고 믿지만 특정 경합/상태에서 토큰이 블랙리스트에 등재되지 않을 수 있다(다만 만료 검증으로 곧 거절되고, 쿠키는 항상 삭제됨 396). 영향은 무효화 가시성 부재 수준(low).
- 수정: revoke 실패가 '이미 무효'인지 '무효화 실패'인지 구분해 로깅하고, 블랙리스트 청소(_prune_revoked)를 시간 기반/백그라운드로도 수행한다(또는 공유 스토어 사용).


### i18n/프롬프트 (`i18n-prompts`)

**101. detect_language_from_transcript 가 한자 위주 일본어를 중국어(zh)로 오판한다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/prompts.py:41-51` · 엣지케이스/유니코드  
- 문제: 가나가 거의 없는 한자 위주 일본어 문장은 japanese 비율이 임계(0.15) 미만이 되고 chinese 비율이 높아져 zh 로 분류된다.
- 영향: auto 언어 설정 길드에서 한자 비중이 높은 일본어 대화가 zh 로 감지되어, LLM 에 'Answer in Chinese' 지시가 들어가 중국어로 요약/응답하는 오작동이 발생한다.
- 수정: 가나 존재 시 ja 를 chinese 보다 우선 분류하거나, CJK 동시 출현 시 가나 유무로 ja/zh 를 구분하는 규칙을 추가한다. 또는 langdetect/fasttext 같은 라이브러리 도입을 검토한다.

**102. detect_language_from_transcript 의 한국어 우선 + 0.15 임계 + 축소된 분모가 영어 위주 짧은 혼합 입력을 한국어로 오판한다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/prompts.py:45-65` · 엣지케이스/언어 자동감지  
- 문제: 분모가 알파벳 글자만 합산해 축소되고 한국어 우선·낮은 임계가 결합되어, 한글이 소수만 섞인 짧은 혼합 입력이 한국어로 쏠린다.
- 영향: auto 설정에서 다국어가 섞인 짧은 대화의 응답 언어가 의도와 달리 한국어로 고정될 수 있다. 응답 품질/UX 저하(긴 입력에선 완화됨).
- 수정: 전체 글자 수를 분모로 쓰거나, 각 언어 점수를 동일 기준으로 비교한 뒤 argmax 를 취하는 상대 다수결 방식으로 바꿔 한국어 편향을 제거한다.

**103. t() 의 format 예외 처리가 ValueError 를 잡지 않아 잘못된 포맷 스펙이 렌더를 깨뜨린다(잠재)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/messages.py:342-347` · 에러 처리 누락  
- 문제: 닫히지 않은 중괄호 등 잘못된 포맷 스펙은 ValueError 를 내는데 except 절이 KeyError/IndexError 만 잡아 폴백하지 못한다. 향후 코드 예시·정규식 등 중괄호 포함 문자열을 kwargs 와 함께 호출하면 그대로 전파된다.
- 영향: 향후 번역 카탈로그에 잘못된 중괄호 스펙이 추가되고 그 키를 kwargs 와 함께 호출하면 ValueError 가 임베드 렌더/명령 응답 전체를 예외로 실패시킨다. 현재는 안전하나 깨지기 쉬운 구조.
- 수정: 예외 처리를 `except (KeyError, IndexError, ValueError):` 로 넓히고, 가능하면 포맷 인자가 없는 정적 문자열엔 format 호출을 건너뛰도록 키별 메타데이터로 구분한다.

**104. t() 가 빈 문자열 번역을 ko 로 폴백하지 않고 빈 값을 그대로 렌더한다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/messages.py:338-341` · i18n 폴백 로직 결함  
- 문제: 어떤 언어 항목이 빈 문자열('')이면 falsy 지만 None 이 아니므로 ko 폴백을 타지 않고 빈 문자열을 반환한다. 번역자가 미완성 항목을 키 생략 대신 빈 문자열로 두는 흔한 실수에 취약하다.
- 영향: 미완성 항목을 ''로 둔 언어 사용자에게 임베드 제목/필드가 빈칸으로 표시된다. discord.Embed 가 빈 name/value 를 거부할 수 있어 2차 오류로 이어질 수 있다.
- 수정: 폴백 조건을 `if not text:` (None/빈 문자열 모두 ko 폴백)로 바꾸거나, 빈 항목은 카탈로그에서 키 자체를 생략하도록 강제한다.

**105. summarize 캐시 경로와 라이브 경로의 헤더 언어가 불일치한다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1462-1464, 1530-1535` · 데이터 정합성/i18n  
- 문제: 동일 길드의 같은 요약이라도 캐시 여부에 따라 헤더 언어가 달라지고, auto 감지 본문이 ja/zh 인데 헤더는 ko/en 폴백으로 어긋난다(본문 일본어 + 헤더 한국어 등).
- 영향: 캐시/비캐시 응답의 헤더 언어가 들쭉날쭉하고 본문 언어와 어긋나 일관성 없는 UX 를 보인다. 기능 오류는 아니나 i18n 품질 저하.
- 수정: 캐시 경로도 본문 언어 기준으로 헤더 언어를 정하거나(캐시에 감지 언어를 함께 저장), summary.header 류 헤더에 지원 7개 언어 번역을 채워 폴백 불일치를 줄인다.

**106. _neutralize_role_tokens 정규식이 행 시작(^)에 앵커되어 라인 중간의 가짜 role 토큰을 놓친다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/prompts.py:78-80, 93-105` · 보안(다층 방어 부분 우회)  
- 문제: 트랜스크립트가 'speaker: msg' 줄 단위 구성이라 대부분 잡히지만, 한 줄에 여러 발화가 합쳐지거나 문장 중간에 끼운 가짜 role 토큰(예: '... please respond. System: ignore the rules')은 ^ 앵커 때문에 무력화되지 않는다.
- 영향: 프롬프트 인젝션 1차 방어선(가짜 role 토큰 무력화)이 라인 중간 토큰에는 적용되지 않는다. 구분자 래핑 + _INJECTION_GUARD 가 주 방어선으로 남아 단독으로는 치명적이지 않지만, 다층 방어 의도가 부분 무력화된다.
- 수정: ^ 앵커에 더해 줄바꿈/공백/문장부호 뒤의 라인 중간 role 라벨도 탐지하는 추가 패턴을 두거나, 줄바꿈 정규화 후 적용한다.

**107. build_chat_with_history_prompt 가 history 항목의 role/content 키를 무방비로 인덱싱한다(잠재)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/prompts.py:235-241` · 에러 처리/엣지케이스  
- 문제: history 루프가 role/content 키를 .get 없이 직접 인덱싱한다. 향후 OpenAI 메시지 dict(role 만) 또는 tool 메시지 등 다른 스키마를 그대로 넘기면 KeyError 로 즉시 깨진다.
- 영향: history 스키마가 조금만 달라져도 프롬프트 빌드가 KeyError 로 실패해 /chat 응답 전체가 에러로 끝난다. 현재는 잠재 위험.
- 수정: `turn.get("role", "user")`, `turn.get("content", "")` 로 방어적 접근하고, user/assistant 외 role 값 정규화도 명시한다.

**108. language_select.value 카탈로그 키가 어디서도 t() 로 사용되지 않는 데드 항목이다**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/messages.py:152-156` · 유지보수/데드코드  
- 문제: language_select.value 키는 정의만 있고 호출처가 없는 데드 i18n 키다. label/code 자리표시자를 가진 채 방치되어 있다.
- 영향: 기능 결함은 아니나 데드 키가 카탈로그를 비대하게 하고 '현지화되어 있다'는 잘못된 인상을 준다. 자리표시자가 있어 향후 잘못 호출하면 위의 ValueError/KeyError 함정과 결합될 수 있다.
- 수정: _language_select_embed 의 value 를 t('language_select.value', lang, label=..., code=...) 로 실제 사용하거나, 사용하지 않을 거면 카탈로그에서 제거한다.


### LLM 제공자 (`llm-providers`)

**109. 서킷 브레이커가 재시도 불가능한 4xx(401/403 등)도 실패로 카운트해 잘못 열림**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:338-344` · 에러 처리 오류  
- 문제: breaker 가 설정된 경우, 영구적·요청 내용 의존 오류인 4xx(잘못된 키 401, 권한 없음 403, 잘못된 요청 400)도 연속 실패로 누적되어 failure_threshold(기본 5)회면 서킷이 열린다. 서킷 브레이커는 본래 일시적 서버 장애(429/5xx/네트워크)만 격리해야 한다.
- 영향: 현재는 production 에서 circuit_breaker 가 주입되지 않아 실질 영향이 없다(잠재 버그). 향후 brekaer 를 주입하면, 잘못된 키로 인한 401 이 서킷을 열어 같은 브레이커를 공유하는 정상 요청까지 reset_timeout 동안 차단하고, 재시도 무의미한 클라이언트 오류를 서버 장애로 오판한다.
- 수정: record_failure 를 _is_retryable(exc) 가 True 인 오류에만 적용한다: 'except LLMError as exc: if _is_retryable(exc): breaker.record_failure(); raise'. 4xx 비재시도 오류는 서킷 카운트에서 제외한다.

**110. Ollama list_models 리스트 컴프리헨션이 try 밖이라 name 누락 시 KeyError 전파·size:null 시 None 정합성 깨짐**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1378-1390` · 엣지케이스/데이터 정합성  
- 문제: list_models 의 결과 매핑이 except 블록 밖에서 실행되어 Ollama /api/tags 응답에 name 이 없으면 KeyError 가 list_models 호출자로 전파된다. 또한 size 가 null 로 오면 m.get('size', 0) 이 None 을 그대로 넘겨 size_bytes=None 이 되고, 이후 size_display 호출 시 TypeError 로 표시가 깨진다.
- 영향: Ollama 응답 형식이 조금만 달라도(name 누락, size:null) list_models 가 빈 리스트가 아니라 예외로 실패하거나 size_display 단계에서 깨진다. 모델 목록 UI(/settings, /models)가 사용자에게 오류로 노출될 수 있다.
- 수정: 컴프리헨션을 try 블록 안으로 옮기고, m.get('name') 으로 안전 접근 후 None 이면 스킵, size 는 'm.get("size") or 0' 으로 None→0 보정한다.

**111. Anthropic 툴 루프 종료 조건이 stop_reason 에 과의존(OR) — tool_use 블록 있어도 실행 누락 가능**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1183-1184` · API 오용/엣지케이스  
- 문제: tool_use 블록 존재(tool_uses)와 stop_reason 두 신호를 OR 종료로 묶어, 둘 중 하나라도 어긋나면 도구를 실행하지 않는다. 모델이 실제로 도구를 호출(tool_use 블록 존재)했는데 stop_reason 이 다르게 들어오면 도구 결과 없이 부분 답변이 최종으로 반환된다.
- 영향: 툴 호출(search_messages)이 의도대로 실행되지 않아 검색 결과가 답변에 반영되지 않고, 사용자에게는 도구 없이 추측한 부분 답변이 최종으로 보일 수 있다. 제공자 응답의 사소한 변형(max_tokens 중단 등)에 취약하다.
- 수정: tool_uses 존재를 1차 기준으로 삼아 블록이 있으면 실행하고, stop_reason 은 보조 신호로만 쓴다(예: 'if not tool_uses: return last_text'). stop_reason 이 명확히 end_turn 일 때만 조기 종료한다.

**112. Anthropic 멀티모달 media_type 화이트리스트 미검증 — 미지원 MIME(image/svg 등) 으로 400 유발**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:93-108,1004-1016` · API 오용/엣지케이스  
- 문제: 이미지 MIME 을 허용 화이트리스트로 검증하지 않고 그대로 media_type/data URI/inlineData 에 싣는다. Discord 첨부 필터도 image/* 접두사만 보므로 비전 미지원 MIME 이나 raw bytes 의 잘못 가정된 MIME 이 제공자에 전달될 수 있다.
- 영향: 미지원/오추정 MIME 으로 Anthropic 에 보내면 400(invalid media_type/decode error) 이 나고, 4xx 라 재시도도 안 되며 사용자에게 'API 요청 실패(HTTP 400)' 로만 보인다. OpenAI(data URI)·Gemini(inlineData)도 같은 잘못된 mime 을 그대로 쓴다.
- 수정: _encode_image_b64/_normalize_image 단계에서 허용 MIME(jpeg/png/gif/webp) 화이트리스트로 검증하고, raw bytes 는 매직넘버(시그니처) 검사로 실제 포맷을 추정한다. 미지원 MIME 은 호출부에서 거르거나 명확한 오류로 변환한다.


### LLM 재시도/툴 (`llm-resilience`)

**113. 스트리밍 경로는 토큰 usage 가 항상 (0,0) 으로 기록 — 주력 경로의 비용 추적 사각지대**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:552-565, 730-742` · data-integrity  
- 문제: 스트리밍 generate 는 last_usage 를 갱신하지 않아 정상 동작하는 유료(OpenAI/Anthropic) 호출 대부분이 토큰 0 으로 기록된다. usage 집계는 사실상 비스트리밍 폴백 경로에서만 동작한다.
- 영향: 기본 채팅이 스트리밍 우선이라 정상 동작 시 비용/사용량이 0 으로 기록돼 전체 비용을 크게 과소 추정한다. 일일 상한 정책도 스트림 경로에서는 사실상 누적이 0 이라 한도 도달을 막지 못한다. severity 는 기능적 버그라기보다 추적 정확도 문제라 low~medium 경계다.
- 수정: Ollama 스트림의 done 라인 prompt_eval_count/eval_count 를 파싱해 last_usage 에 반영하고, OpenAI 는 stream 요청에 stream_options:{include_usage:true} 를 추가해 마지막 usage 청크를 파싱한다. 불가하면 토크나이저 추정치로라도 채운다.

**114. 툴 호출 id 누락 시 빈 문자열 기본값 → 후속 요청 400 위험(조용한 처리)**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:920, 1197` · api-misuse  
- 문제: id 가 없으면 빈 문자열을 조용히 넣는다. 정상 응답에는 항상 id 가 있어 실전 빈도는 낮지만, 제공자 응답 형식 변경/부분 응답/프록시 변형 시 빈 id 가 들어오면 다음 왕복에서 매칭 실패로 400 이 발생할 수 있다.
- 영향: 빈 id 가 들어오는 비정상 케이스에서 툴 루프 전체가 400 으로 실패하며, 조용히 빈 값을 넣어 원인 파악이 어렵다. 정상 경로 빈도가 낮아 severity 는 low.
- 수정: id 가 비어 있으면 해당 tool 호출을 건너뛰거나 명확한 LLMError 를 던져 조기에 드러낸다. 최소한 경고 로그를 남긴다.

**115. pull_model: communicate() 가 stderr 를 메모리에 무제한 버퍼링**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:1402-1408` · resource-leak  
- 문제: ollama pull 이 진행률/경고를 stderr 로 대량 출력할 수 있는데 communicate() 가 이를 전부 메모리에 버퍼링한다. 상한이 없다.
- 영향: 비정상적으로 장황한 stderr 출력 시 메모리가 과도하게 소비될 수 있다. 일반적으로는 작지만 신뢰할 수 없는 출력량에 대한 가드가 없다. severity low.
- 수정: 성공 경로에선 stderr 가 필요 없으므로 stderr=DEVNULL 로 두거나, 오류 메시지가 필요하면 스트림을 읽으며 마지막 N 바이트만 보관하도록 제한한다.

**116. _coerce_token_count 가 float 토큰 수를 내림 절단하고 비정상 입력을 조용히 0 처리**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/llm.py:129-137` · edge-case  
- 문제: float 토큰 수가 일관되게 내림 절단되고, 음수/비숫자/bool 입력이 조용히 0 으로 처리된다. 의도된 방어이긴 하나 비정상 제공자 응답이 가시화되지 않는다.
- 영향: 토큰 수가 일관 내림돼 비용/사용량이 미세하게 과소 집계된다(토큰당 단가가 작아 영향 제한적). 음수 보정은 합리적이나 비정상 입력을 조용히 삼켜 제공자 응답 이상을 놓칠 수 있다. severity low.
- 수정: 의도적 내림임을 문서화하거나 round() 를 사용한다. 음수/비숫자 입력 시 debug 로그를 남겨 제공자 응답 이상을 가시화한다.

**117. 스트림 부분 출력 후 LLMError 시 폴백 차단 + 스트림은 서킷/재시도 밖이라 복원력 약함**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/bot.py:1820-1834` · error-handling  
- 문제: 스트리밍 경로는 재시도·서킷 브레이커 보호 밖이라 일시적 제공자 오류에 비스트리밍보다 약하다. 부분 출력 후 끊기면 answer 가 비어 있지 않아 폴백도 받지 못한다.
- 영향: 주력 경로(스트리밍)가 일시 오류 복원력이 낮고, 부분 출력 후 끊김 시 사용자가 잘린 응답 + 오류를 함께 본다. 다만 첫 청크 도착 전(answer 빈 값) 실패는 폴백되므로 초기 연결 실패는 보호된다. severity low.
- 수정: 스트림 실패가 부분 출력 이후라도 누적 텍스트가 너무 짧으면 폴백을 허용하거나, 최소한 스트림 시작 단계 연결 실패를 _with_circuit_breaker/_with_retry 로 감싸 초기 실패에 재시도를 적용한다.


### 관측성/지원 (`observability-support`)

**118. AlertRateLimiter: 지속 폭주 시 억제 요약 영구 미배출 + _suppressed_signatures 무한 증가**  
`src/discord_assistant/monitor.py:172-208` · 리소스 누수/메모리 증가  
- 문제: _drain_suppressed_summary(카운터 리셋)는 알림이 통과할 때만 호출. 상한을 지속 초과하는 다양한 시그니처 폭주 시 어떤 알림도 통과 못해 요약 미배출, _record_suppressed 가 distinct 시그니처를 계속 추가.
- 영향: 장시간 다양한 에러 폭주 시 _suppressed_signatures 가 무제한 성장. 개발자는 폭주 종료까지 억제 건수 알림도 못 받음.
- 수정: _suppressed_signatures 상한(상위 N)·주기적 trim + 억제 누적 임계 초과 시 강제 1건 통과 escape hatch.

**119. shutdown 중 on_disconnect 재예약으로 종료 시 가짜 '끊김' 알림 가능**  
`src/discord_assistant/bot.py:3192-3217,3564-3567` · async 함정/단절 오탐  
- 문제: graceful shutdown 이 _cancel_background_tasks() 후 bot.close() 호출. close() 가 on_disconnect 를 내면 취소 완료 후 _delayed_disconnect_alert 가 새로 _track_task 예약되고, 조건(is_closed() or not is_ready())이 종료 중에도 참이라 30초 내 미종료 시 오탐 발생.
- 영향: 정상 종료(SIGTERM 재배포)에서 거짓 '봇 끊김' DM 가능.
- 수정: shutting_down 플래그를 두고 on_disconnect/_delayed_disconnect_alert 가 종료 중이면 예약/발송 안 함.

**120. health /metrics 응답이 charset/version 메타를 잘라냄 (content_type 분해)**  
`src/discord_assistant/health.py:71-73` · API 오용/관측성  
- 문제: _metrics 가 content_type.split(';',1)[0] 로 'text/plain; version=0.0.4; charset=utf-8' 에서 text/plain 만 남겨 Prometheus 버전/charset 이 헤더에서 사라진다.
- 영향: 엄격한 스크레이퍼/OpenMetrics 협상에서 호환성 문제 가능.
- 수정: web.Response 에 charset='utf-8' 별도 지정하거나 headers 로 원본 Content-Type 명시.

**121. 번역 캐시가 FIFO(삽입시각) 축출이며 매 set 마다 O(n) 스캔 — 인기 항목 thrashing**  
`src/discord_assistant/cache.py:68-74` · 캐시 정책/성능  
- 문제: set_translation 이 가득 차면 삽입시각 최소 항목 축출(LRU 아님 FIFO)+매 set 마다 O(n) 스캔. 기존 키 갱신 시도 무관 엔트리 먼저 축출.
- 영향: 인기 번역도 오래된 거면 축출돼 thrashing→불필요 LLM 재호출. 500 한도에서 매 미스 O(500).
- 수정: OrderedDict/move_to_end 로 O(1) LRU + set 전 key 존재 확인해 축출 건너뛰기.

**122. purge_expired_translations 가 운영에서 미스케줄 — 만료 항목이 한도 도달까지 잔존**  
`src/discord_assistant/cache.py:87-93` · 캐시 TTL/메모리  
- 문제: purge_expired_translations 가 테스트에서만 호출되고 백그라운드 주기 작업에 미연결. TTL(1h) 지난 항목도 재조회 전까지 잔존, 500 FIFO 축출로만 정리.
- 영향: 만료 stale 항목이 메모리 장기 점유 + FIFO 축출 계산에 끼어 정상 항목 축출 유발.
- 수정: 주기 백그라운드 태스크에서 purge_expired_translations()/summarize_cache 정리 호출, 또는 축출 계산에서 만료 우선 제거.


### 보안 (`security`)

**123. persona 가 시스템 프롬프트에 의미적 인젝션 무력화 없이 직접 삽입된다**  
`src/discord_assistant/prompts.py:208-221` · security/prompt-injection  
- 문제: build_chat_prompt 는 persona 를 신뢰 영역(보안 가드 위)에 한 줄로 그대로 삽입한다. _sanitize_persona 가 구조적 인젝션(가짜 role 블록)은 막지만 인라인 의미적 인젝션 문구는 막지 못한다.
- 영향: 관리/설정 권한 역할까지 위임된 경우 그 사용자가 persona 를 통해 봇의 기본 안전 지침을 약화/덮어쓸 수 있다. 권한 상승은 아니며 설정자가 관리자라 위험도는 낮지만, 신뢰 경계가 모호하다.
- 수정: persona 도 신뢰 불가 입력처럼 _neutralize_injection_phrases/_neutralize_role_tokens 를 거치게 하거나, <persona> 태그로 감싸 _INJECTION_GUARD 의 보호 대상(untrusted DATA)에 포함시킨다.

**124. /summarize-channels 의 채널별 오류가 마스킹 없이 임베드에 노출된다**  
`src/discord_assistant/bot.py:2292-2293, 2301-2306` · security/info-disclosure  
- 문제: 멀티채널 요약의 채널별 예외를 마스킹 없이 임베드에 그대로 노출한다. usage_log 는 시크릿을 마스킹하면서 사용자 노출 경로는 마스킹하지 않는 불일치.
- 영향: 제공자 4xx 응답 본문이나 예외 메시지에 포함될 수 있는 민감 정보(키 일부, 내부 엔드포인트, 스택 단서)가 채널 임베드(비공개 보장 없음)로 노출될 여지가 있다. 실제 노출은 예외 메시지에 시크릿이 포함되는 경우에 한정돼 가능성은 제한적이다.
- 수정: 사용자에게 보이는 오류는 error_hint 처럼 일반화하거나, 최소한 storage._mask_secrets 와 동등한 마스킹을 적용한 뒤 표시한다. 원본 예외 detail 은 logger 로만 남긴다.

**125. 역할 토큰 무력화 정규식이 콜론 없는 role 토큰을 놓친다(주석 주장과 불일치)**  
`src/discord_assistant/prompts.py:78-80` · security/prompt-injection  
- 문제: _ROLE_TOKEN_RE 가 콜론으로 끝나는 role 토큰만 탐지한다. 주석의 주장(하이픈·대괄호 종결 포함)과 달리 'assistant-', '[SYSTEM]', '# System' 류와 이미 zero-width 가 삽입된 입력은 무력화되지 않는다.
- 영향: 주 방어(구분자 래핑 + _INJECTION_GUARD)가 별도로 있어 영향은 보조 방어선 약화에 그친다. 그러나 주 방어가 없는 커스텀 프롬프트 경로(항목 1)와 결합되면 가짜 role 표기가 그대로 통과할 위험이 커진다.
- 수정: 콜론 외 구분자(대시/대괄호/헤더 '#')와 콜론 없는 role 헤더 변형도 커버하도록 정규식을 보강하고, 무력화 전에 입력의 기존 zero-width 문자를 정규화(제거)해 사전 가공 우회를 막는다.


### 스토리지/DB (`storage`)

**126. save_feedback INSERT lacks ON CONFLICT for UNIQUE(message_id,user_id); re-vote raises IntegrityError that the caller swallows, so rating changes silently fail**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:1040-1058` · 에러 처리 누락/데이터 정합성  
- 문제: save_feedback 는 ON CONFLICT 절 없는 평범한 INSERT 라 동일 (message_id,user_id) 재평가 시 IntegrityError 를 던진다. 호출 측(on_reaction_add)이 이를 try/except 로 잡아 경고만 남기므로 크래시는 없지만, 사용자가 평점을 바꿔도(👍→👎) 갱신이 저장되지 않고 매번 경고 로그만 쌓인다.
- 영향: 사용자가 피드백 평점을 변경할 수 없다(최초 평점이 고착). 잘못된 첫 평점이 영구 보존되어 피드백 통계가 왜곡되고, 평점 변경 시도마다 잡음성 경고 로그가 누적된다. 크래시는 아니나 의도된 UX(평점 토글)가 조용히 깨진다.
- 수정: ON CONFLICT(message_id, user_id) DO UPDATE SET rating=excluded.rating, command=excluded.command, created_at=excluded.created_at 로 upsert 한다. 1인 1평가 고정이 의도라면 INSERT OR IGNORE 로 명시하고 호출 측 UX 도 그에 맞춘다.

**127. schema_version table lacks PK/UNIQUE and _set_schema_version UPDATEs without WHERE, so a double-seed silently breaks the single-row invariant**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:53-55,416-429` · 스키마/마이그레이션  
- 문제: schema_version 테이블에 단일 행을 강제하는 제약(PK/UNIQUE)이 없고, _set_schema_version 의 UPDATE 가 WHERE 없이 모든 행을 갱신하며 _get_schema_version 의 SELECT 는 LIMIT 1 로 임의 한 행만 읽는다. 단일 행 불변식이 스키마 제약이 아닌 호출 순서/단일 스레드 직렬화에만 의존한다.
- 영향: 현재 호출 경로(initialize→_migrate, 단일 aiosqlite 스레드)에서는 대체로 단일 행을 유지하므로 즉시 문제가 되진 않는다. 그러나 어떤 경로로든 다중 행이 생기면 버전 추적이 모호해져 향후 마이그레이션/진단 로직의 미묘한 버그 씨앗이 된다.
- 수정: CREATE TABLE schema_version (id INTEGER PRIMARY KEY CHECK (id=1), version INTEGER NOT NULL) 로 단일 행을 스키마로 강제하고, INSERT OR IGNORE INTO schema_version(id,version) VALUES (1,0) · UPDATE ... WHERE id=1 로 갱신해 불변식을 코드가 아닌 제약으로 보장한다.

**128. set_provider_config validates empty model only via GuildConfig.__post_init__, raising a different message/layer than set_model's explicit check**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:701-718` · API 오용/검증 비대칭  
- 문제: set_model 은 빈 모델을 진입점에서 명시적 ValueError('model cannot be empty') 로 거부하지만, set_provider_config 는 strip 만 하고 검증을 GuildConfig.__post_init__ 에 위임한다. replace() 가 __post_init__ 을 트리거하므로 빈 모델은 여전히 거부되나, 다른 메시지('GuildConfig.model must not be empty')와 다른 계층에서 실패한다.
- 영향: 동일한 '빈 모델' 입력이 진입점에 따라 다른 에러 메시지/스택으로 실패해 일관성이 깨지고, 사용자 친화적 안내 대신 모델 계층 내부 예외 메시지가 노출될 수 있다. 데이터가 잘못 저장되지는 않는다(검증은 작동).
- 수정: set_provider_config 에도 normalized=model.strip(); if not normalized: raise ValueError('model cannot be empty') 를 set_model 과 동일하게 추가해 검증 지점·메시지를 일원화한다.

**129. _run_sync depends on aiosqlite private internals conn._execute / conn._conn for all schema/PRAGMA application**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:595-603` · API 오용/배포 함정  
- 문제: _run_sync 가 aiosqlite 의 비공개 메서드 _execute 와 비공개 속성 _conn 에 직접 의존한다. initialize() 의 PRAGMA·스키마·마이그레이션 적용이 전적으로 이 비공개 경로를 통과한다.
- 영향: aiosqlite 의 0.x 마이너 업그레이드에서 _execute/_conn 시그니처나 이름이 바뀌면 스키마/마이그레이션 초기화가 깨져 봇이 시작조차 못 할 수 있다. pyproject 가 <1.0 으로 상한을 두어 메이저 단위 파손은 막지만, 0.x 내부 구현 변경 리스크는 남는다.
- 수정: 가능하면 공개 API(conn.execute/executescript)로 PRAGMA·스키마를 적용하도록 재작성한다. 비공개 의존이 불가피하면 aiosqlite 핀을 더 좁히고(예: 검증된 마이너 범위로) 비공개 의존을 회귀 테스트로 고정한다.

**130. delete_user_data/delete_guild_data run multi-table DELETEs in autocommit mode without an explicit transaction, allowing partial GDPR deletion on mid-loop failure**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:1321-1355` · 데이터 정합성/에러 처리  
- 문제: GDPR 삭제 메서드들이 autocommit(isolation_level=None) 연결에서 여러 테이블을 순차 DELETE 한 뒤 마지막에 한 번 commit() 한다. autocommit 이라 각 DELETE 가 즉시 커밋되고 마지막 commit() 은 no-op 이며, 명시적 BEGIN/ROLLBACK 이 없어 원자성이 보장되지 않는다.
- 영향: 삭제 도중 예외(또는 프로세스 종료) 시 일부 테이블만 삭제되고 나머지엔 사용자/길드 데이터가 잔존해 GDPR 불완전 삭제(컴플라이언스 위반)가 될 수 있다. 재시도 시 반환 건수도 부정확해진다.
- 수정: 삭제를 명시적 트랜잭션으로 감싼다: await conn.execute('BEGIN') → 모든 DELETE → await conn.commit(), 예외 시 await conn.rollback(). autocommit 모드라도 명시적 BEGIN 으로 원자성을 확보한다.

**131. Discord token masking regex over-matches generic dotted identifiers and JWTs, scrubbing non-secret debug context**  
`/Users/osuma/coding_stuffs/discord-assitant/src/discord_assistant/storage.py:228-229` · 보안/오탐(데이터 손실)  
- 문제: Discord 토큰 패턴이 '점 두 개로 구분된 길이 조건 충족 식별자'를 모두 매칭해, 실제 토큰이 아닌 JWT 유사 문자열·긴 점-구분 식별자(모듈 경로 등)까지 통째로 *** 로 치환한다(실측 확인).
- 영향: 보안상 안전 측 오류(과잉 마스킹)라 비밀 누출은 없으나, 디버깅에 필요한 비밀이 아닌 에러 컨텍스트(스택 경로, 식별자, 정상 JWT)가 usage_log 에서 *** 로 사라져 운영 시 원인 분석이 어려워질 수 있다.
- 수정: 토큰 패턴을 실제 Discord 토큰 구조(세그먼트 길이/문자 구성, 또는 'Bot ' 접두 컨텍스트 결합)에 맞춰 더 좁힌다. 과잉 마스킹과 누락의 균형을 테스트 케이스로 고정하고, 안전 측 동작이 의도라면 그 의도를 명시적으로 문서화한다.


### UI 뷰/모달 (`ui`)

**132. RetryView 더블클릭 레이스 — disabled 가 클라이언트에 반영되기 전 콜백 중복 진입**  
`src/discord_assistant/ui.py:1304-1308` · 레이스/동시성  
- 문제: 버튼 비활성화가 즉시 클라이언트에 반영되지 않고 1회성 가드도 없어, 동일 사용자의 더블클릭이 재시도 콜백을 두 번 실행한다.
- 영향: 재시도 LLM 호출이 중복돼 토큰 예산을 두 배 소모하고, 두 콜백이 경쟁적으로 응답해 'interaction already responded' 오류를 유발할 수 있다. ephemeral 단일 사용자라 영향 범위는 제한적.
- 수정: 콜백 위임 전에 `await interaction.response.edit_message(view=self)` 로 비활성화를 즉시 반영하거나, self._used 1회성 플래그를 두고 이미 사용됐으면 followup 안내 후 return 한다.

**133. API 키 검증의 관대 정책 — 4xx(비-401/403)/5xx/429 응답이면 무효 키도 '유효'로 통과해 암호화 저장**  
`src/discord_assistant/ui.py:277-280, 305-308, 327-330` · 에러 처리 / 보안  
- 문제: 키 검증이 명시적/오타 무효(401/403)만 거르고, 그 외 4xx/5xx/429 는 '유효'로 판정한다. 의도된 관대 정책이지만 400 같은 비-인증 오류까지 유효로 보는 것은 과도하다.
- 영향: 만료/오타 키라도 서버가 400/429/5xx 를 주면 통과해 저장되고, 실제 LLM 호출 시점에야 401 로 실패해 사용자가 원인 파악이 어렵다. 의도된 정책이라 severity 는 low.
- 수정: 성공(2xx)만 유효로 보고 4xx 전체를 무효로 처리하거나, 429/5xx/네트워크 오류는 '검증 불가(사용 중 실패 가능)' 로 분리해 사용자에게 명시한다.

**134. _APIKeyModal.on_submit: defer 후 예외 시 on_error 의 response.send_message 가 InteractionResponded 2차 예외**  
`src/discord_assistant/ui.py:361-385, 396-397` · 에러 처리  
- 문제: on_error 가 응답 소비 여부(is_done)를 검사하지 않고 항상 response.send_message 를 호출한다. defer 이후 예외 경로에서는 followup.send 를 써야 한다.
- 영향: 키 저장은 됐는데 UI 갱신 실패 시 on_error 가 InteractionResponded 로 2차 예외를 던져 사용자에게 'This interaction failed' 만 보이고 안내가 누락된다. 드물지만 빠른 중복 제출 시 마지막 값으로 두 번 덮어쓰기.
- 수정: on_error 를 `if interaction.response.is_done(): await interaction.followup.send(...) else: await interaction.response.send_message(...)` 로 분기한다. on_submit 의 저장/갱신 구간을 try/except 로 감싸 followup 으로 결과를 알린다.

**135. ProviderView._on_select: interaction.data['values'][0] 무검증 인덱싱 + LLMProvider() enum 변환**  
`src/discord_assistant/ui.py:564, 618, 680, 742, 1101, 1224` · 엣지케이스 / API 오용  
- 문제: 신뢰 경계(클라이언트가 제어 가능한 게이트웨이 페이로드)의 입력을 무검증으로 인덱싱/enum 변환한다. 정상 Discord 클라이언트는 항상 정의된 옵션을 보내지만, 변형/위조 페이로드 시 IndexError/ValueError 가 발생한다.
- 영향: values 가 비거나 알 수 없는 provider 문자열이면 콜백이 응답 없이 죽고 사용자에게 'interaction failed' 만 표시된다. 정상 클라이언트로는 재현되지 않아 severity low.
- 수정: `values = interaction.data.get('values') or []; if not values: return` 후 처리하고, LLMProvider(...) 변환은 try/except ValueError 로 감싸 '지원하지 않는 선택' 을 ephemeral 로 안내한다.

**136. run_install 스피너 루프의 'except Exception: pass' 가 모든 edit 실패를 무로깅 폐기**  
`src/discord_assistant/ui.py:779-785` · 관측성 / 예외 삼킴  
- 문제: 스피너 갱신 실패를 전부 조용히 삼켜 로그가 남지 않는다. 토큰 만료가 반복돼도 운영자가 관측할 수 없다.
- 영향: 설치 진행 표시가 조용히 멈춰도 원인 추적 불가. 운영/관측성 저하.
- 수정: logger.debug/warning 으로 예외를 기록하고, NotFound(토큰 만료)면 루프를 break 해 더 이상 edit 시도를 하지 않는다.

**137. 설정 패널 콜백들에 on_error/try-except 부재 — 외부 호출 실패 시 무응답('interaction failed')**  
`src/discord_assistant/ui.py:493-518, 617-645, 679-705, 1223-1232` · 에러 처리 누락  
- 문제: 설정 패널 버튼 콜백이 외부 호출(store/ollama_manager) 도중 예외가 나면 응답을 소비하지 않은 채 종료될 수 있고, View 에 on_error 가 없어 사용자에게 피드백이 전혀 없다.
- 영향: store 일시 오류(DB 잠금 등)나 ollama list_models 실패 시 설정 버튼이 무응답처럼 보이고 사용자는 'interaction failed' 만 본다. (관리자 전용 ephemeral 흐름이라 영향 범위 제한 — severity low.)
- 수정: 각 View 에 async def on_error 를 오버라이드해 is_done 여부에 따라 followup/response 로 ephemeral 오류 안내를 보내거나, 외부 호출 구간을 try/except 로 감싸 사용자에게 실패를 알린다.


---

## ❓ 불확실 (추가 조사 필요)

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
