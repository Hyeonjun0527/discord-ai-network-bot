# [폐기됨/DEPRECATED] 리버스 터널 에이전트 & 커뮤니티 Provider Pool (Python 계획)

> # 🛑 이 로드맵은 폐기되었습니다 (ADR 0004, 2026-05-30)
> 이 674항목 계획은 **중앙 서버를 Python 으로 만드는 원안**이었으나, 중앙 서버를
> **Kotlin + Spring Boot 로 전환**([ADR 0004](./adr/0004-kotlin-spring-central-server.md))하면서
> **폐기**되었다. 아래 `[x]` 150개(Python 차수 1~5)는 **전환 때 코드가 제거된 PoC 기록**이며
> 실 산출물이 아니다. 실제 구현은 **[`ROADMAP_CENTRAL_SERVER.md`](./ROADMAP_CENTRAL_SERVER.md)
> (Kotlin, 131/131 완료)** 를 보라. 설계(ADR 0002/0003)·명세(`specs/`)·WS 프로토콜 계약은 유효하다.
>
> ## 674 ↔ Kotlin 131 커버리지 매핑
> Kotlin 131 은 이 674의 **중앙 서버 부분**을 재구현한 것이다. 각 Kotlin 차수 ↔ 본 674 차수:
>
> | Kotlin K-차수 | 본 674 차수(항목) |
> |---|---|
> | K1 WS 프로토콜 | 차수2(26~55) + Phase B 차수17(373~394) |
> | K2 레지스트리/세션 | 차수3(56~85) + Phase B 차수17 |
> | K3 WS 릴레이 | 차수4(86~120) |
> | K4 등록/토큰 | 차수6(151~185 일부) + Phase B 차수16(353~372) |
> | K5 capability/상태 | Phase B 차수17(373~394) |
> | K6 JPA 영속화 | 차수6(151~185) + Phase B 차수14(317~340) |
> | K7 서버 정책 | Phase B 차수18(395~414) |
> | K8 요청 무게 | Phase B 차수20(437~450) |
> | K9 필터 파이프라인 | Phase B 차수21(451~472) |
> | K10 공정성 라우터 | Phase B 차수22(473~490) |
> | K11 오케스트레이터 | Phase B 차수23(491~514) |
> | K12 프로바이더 보호 | Phase B 차수24(515~538) |
> | K13 Discord 명령 | 차수7(186~215) + Phase B 차수25~27(539~586) |
> | K14 프라이버시/사용량 | Phase B 차수28~29(587~614) |
> | K15 보안 | Phase B 차수30(615~632) |
> | K16 운영 | 차수12(296~300) + Phase B 차수32(657~674) |
>
> → **결론: 674 ⊇ 131.** 131의 모든 개념은 674 안에 들어 있다(674가 더 큰 상위집합).
> 단, K6/K13/K16 일부는 Kotlin 스택 고유(JPA/JDA/Gradle/Docker/Actuator)라 674 항목과
> 1:1 줄 대응이 아니라 *범주 대응*이다.
>
> ## 674에서 131이 **커버하지 못한** 부분 (진짜 잔여)
> - **차수 9 유저 Python 에이전트(241~265)** — 미구현. 이게 없으면 end-to-end 미동작. **최우선 잔여.**
> - 차수 5 RemoteAgentClient(121~150)·차수 8 봇 통합(216~240) — Python 봇 전제라 Kotlin 전환으로 **N/A**(서버=봇).
> - 차수 16/20/23 스트리밍·멀티모달·툴콜 등 일부 고급 기능, Phase B 차수31 일부 테스트, 기존 Python 봇 이관.
>
> 근거: [ADR 0002](./adr/0002-remote-agent-byollm.md)·[ADR 0003](./adr/0003-community-provider-pool.md).
> 아래 본문은 **역사적 기록**으로 보존한다.
>
> ## 두 단계 구조
>
> - **Phase A — 단일 원격 에이전트 (차수 1~12, 항목 1~300)**
>   유저/방장 PC의 로컬 LLM(Ollama)을 중앙 봇 하나로 디스코드에서 사용.
>   라우팅 모드: 개인 모드(`user_id`) + 서버 공유 모드(`guild_id`, 단일 호스트).
> - **Phase B — 커뮤니티 Provider Pool (차수 13~, 항목 301~)**
>   한 서버에 **여러 프로바이더**가 각자 감당 가능한 로컬 LLM 자원을 등록하고,
>   중앙 봇이 권한·요청 무게·모델 부담 수준·기여 한도·공정성을 기준으로 요청을 분배하는
>   커뮤니티형 협동 시스템. Phase A 의 단일 공유 호스트를 **다중 프로바이더 풀**로 일반화한다.
>   비-목표: 판매/구매/가격표/수수료/정산. 중심 개념: 기여(contribution)·동의(consent)·
>   수용량(capacity)·가용성(availability)·공정성(fairness).

## 차수 1 — 설계 확정 & 스캐폴딩 (1~25)

- [x] 1. `feat/remote-agent-byollm` 브랜치 생성
- [x] 2. ADR 0002 에 개인/공유 두 모드 반영(완료분 확인)
- [x] 3. 300 단계 로드맵 파일 작성(이 문서)
- [x] 4. `src/discord_assistant/remote/` 패키지 디렉토리 생성
- [x] 5. `remote/__init__.py` 작성(공개 심볼 export)
- [x] 6. `models.py` `LLMProvider` 에 `REMOTE_AGENT = "remote_agent"` 추가
- [x] 7. `RoutingMode` enum 추가(`PERSONAL`, `SHARED`) — models.py
- [x] 8. settings 에 `relay_enabled: bool` 추가
- [x] 9. settings 에 `relay_host: str`(기본 0.0.0.0) 추가
- [x] 10. settings 에 `relay_port: int`(기본 8765) 추가
- [x] 11. settings 에 `relay_path: str`(기본 `/agent`) 추가
- [x] 12. settings 에 `relay_request_timeout_seconds: float` 추가
- [x] 13. settings 에 `relay_max_concurrency_per_host: int`(기본 1) 추가
- [x] 14. settings 에 `relay_heartbeat_seconds: float` 추가
- [x] 15. settings 에 `agent_token_ttl_seconds: int`(페어링 토큰 수명) 추가
- [x] 16. `from_env` 에 위 relay/agent 환경변수 파싱 추가
- [x] 17. relay/agent 환경변수 검증(범위/형식) 로직 추가
- [x] 18. `.env.example` 에 새 선택 키 추가
- [x] 19. env SSOT 워크플로 `OPTIONAL_KEYS` 에 새 키 등록
- [x] 20. `pyproject.toml` 에 `agent` extra(websockets/aiohttp client) 정의
- [x] 21. `pyproject.toml` 에 `discord-assistant-agent` 콘솔 스크립트 등록
- [x] 22. remote 모듈용 로거 네이밍 컨벤션 결정/적용
- [x] 23. 공통 상수 모듈(`remote/constants.py`) — 프레임 타입 문자열 등
- [x] 24. 타입 힌트/`mypy` 통과를 위한 `py.typed` 영향 확인
- [x] 25. 차수 1 산출물 ruff/mypy 통과 확인

## 차수 2 — 프로토콜 정의 (26~55)

- [x] 26. `remote/protocol.py` 파일 생성
- [x] 27. 프레임 타입 enum/상수 정의(`auth`,`infer`,`result`,`error`,`ping`,`pong`,`chunk`,`cancel`)
- [x] 28. `AuthFrame` dataclass(토큰, 에이전트 버전, 플랫폼)
- [x] 29. `AuthOkFrame` / `AuthErrFrame` dataclass
- [x] 30. `InferRequest` dataclass(request_id, model, prompt, options)
- [x] 31. `InferResult` dataclass(request_id, text, usage)
- [x] 32. `InferError` dataclass(request_id, code, message)
- [x] 33. `ChunkFrame` dataclass(스트리밍용 부분 텍스트)
- [x] 34. `PingFrame`/`PongFrame` dataclass
- [x] 35. `CancelFrame` dataclass(request_id)
- [x] 36. 프레임 → dict 직렬화 함수
- [x] 37. dict → 프레임 역직렬화(dispatch by type)
- [x] 38. JSON encode/decode 래퍼(`dumps_frame`/`loads_frame`)
- [x] 39. 알 수 없는 타입에 대한 `ProtocolError` 예외 정의
- [x] 40. 필수 필드 누락 검증
- [x] 41. request_id 생성 헬퍼(에이전트/봇 양쪽 일관)
- [x] 42. 프로토콜 버전 상수 + 핸드셰이크 버전 협상 필드
- [x] 43. 최대 프레임 크기 상한 상수
- [x] 44. 프롬프트 길이 상한 검증
- [x] 45. options 화이트리스트(temperature/num_predict 등)
- [x] 46. 직렬화 round-trip 불변식 명시(docstring)
- [x] 47. 토큰을 로그에 남기지 않도록 `__repr__` 마스킹
- [x] 48. `InferResult.usage` 스키마(prompt/completion tokens) 정의
- [x] 49. 에러 코드 enum(`OFFLINE`,`TIMEOUT`,`OLLAMA_ERROR`,`AUTH_FAILED`,`BUSY`)
- [x] 50. 프레임 dataclass 들 `slots=True`/frozen 적용 검토
- [x] 51. mypy strict 호환 타입 정리
- [x] 52. 프로토콜 상수의 단일 출처화(constants 재사용)
- [x] 53. 직렬화 함수의 비ASCII(한국어) 안전성 확인(`ensure_ascii=False`)
- [x] 54. 프로토콜 모듈 ruff/mypy 통과
- [x] 55. 차수 2 산출물 자체 점검

## 차수 3 — 연결 레지스트리 & 라우팅 (56~85)

- [x] 56. `remote/registry.py` 생성
- [x] 57. `AgentConnection` 추상(보내기/받기/닫기 인터페이스)
- [x] 58. 연결 식별자 타입(owner key) 정의
- [x] 59. `ConnectionRegistry` 클래스 골격
- [x] 60. user_id → connection 매핑 저장소
- [x] 61. guild_id → host connection 매핑 저장소
- [x] 62. 등록(register) 메서드(중복 연결 교체 정책)
- [x] 63. 해제(unregister) 메서드
- [x] 64. 연결 조회(get_for_user / get_for_guild)
- [x] 65. 라우팅 결정 함수(mode + user_id + guild_id → connection)
- [x] 66. 개인 모드 라우팅 경로 구현
- [x] 67. 공유 모드 라우팅 경로 구현
- [x] 68. 연결 없음 → `AgentOfflineError` 반환
- [x] 69. 동일 owner 재연결 시 이전 연결 정리(graceful close)
- [x] 70. 레지스트리 thread/async 안전성(락 또는 단일 루프 가정 명시)
- [x] 71. 활성 연결 수 카운터(메트릭용)
- [x] 72. 연결 메타데이터(연결 시각, 에이전트 버전, 마지막 ping)
- [x] 73. 좀비 연결 청소(heartbeat 만료 → 제거)
- [x] 74. guild 호스트 owner_user_id 보관(누가 호스트인지)
- [x] 75. `iter_connections()` 진단용 이터레이터
- [x] 76. 레지스트리 스냅샷(상태 표시용 dict)
- [x] 77. 라우팅 정책의 단위 테스트 대상 함수 순수화
- [x] 78. owner key 충돌 방지(user vs guild 네임스페이스 분리)
- [x] 79. 연결 종료 콜백 훅
- [x] 80. 등록/해제 로깅(토큰 미노출)
- [x] 81. per-host 동시성 슬롯 보관 위치 결정(registry vs relay)
- [x] 82. 라우팅 결과에 owner 정보 포함(고지/로그용)
- [x] 83. 빈 레지스트리 기본 동작 정의
- [x] 84. registry 모듈 ruff/mypy 통과
- [x] 85. 차수 3 자체 점검

## 차수 4 — WS 릴레이 서버 (86~120)

- [x] 86. `remote/relay.py` 생성(aiohttp web)
- [x] 87. aiohttp 미설치 가드(health.py 패턴 재사용)
- [x] 88. `RelayServer` 클래스 골격(start/stop)
- [x] 89. WebSocket 라우트 핸들러 등록(`relay_path`)
- [x] 90. 업그레이드/핸드셰이크 처리
- [x] 91. 연결 직후 첫 프레임=auth 강제(타임아웃)
- [x] 92. auth 토큰 검증 → owner 결정
- [x] 93. 인증 성공 시 레지스트리 등록 + `AuthOkFrame` 송신
- [x] 94. 인증 실패 시 `AuthErrFrame` 후 종료
- [x] 95. `RelayConnection`(AgentConnection 구현, ws 래핑)
- [x] 96. 수신 루프(프레임 파싱 → dispatch)
- [x] 97. `result`/`error` 프레임 → 대기 future resolve
- [x] 98. `chunk` 프레임 → 스트림 큐 push
- [x] 99. request_id ↔ future 레지스트리(per-connection)
- [x] 100. 송신 메서드(`send_infer`) + 동시성 슬롯 획득
- [x] 101. 요청 타임아웃 → future 취소 + `cancel` 프레임 송신
- [x] 102. heartbeat 송신 태스크(주기 ping)
- [x] 103. pong 수신 시 last_seen 갱신
- [x] 104. heartbeat 만료 → 연결 종료
- [x] 105. 연결 종료 시 레지스트리 해제 + 대기 future 실패 처리
- [x] 106. 동시 처리 제한(세마포어, per-host)
- [x] 107. 초과 요청 큐잉 + `BUSY`/대기 처리
- [x] 108. 큐 길이 상한 + 초과 시 거절
- [x] 109. 최대 프레임 크기 적용(수신)
- [x] 110. 잘못된 프레임 수신 시 방어(연결 유지/종료 정책)
- [x] 111. graceful shutdown(모든 연결 close)
- [x] 112. 봇 부팅 시 RelayServer 기동 통합(`relay_enabled`) — `maybe_start_relay` 게이트 구현, bot.main() 호출은 차수 8(항목 222)에서 배선
- [x] 113. 봇 종료 시 RelayServer 정리 — `RelayServer.stop()` 구현, bot 종료 훅 배선은 차수 8
- [x] 114. relay 로깅(연결/해제/오류, 토큰 미노출)
- [x] 115. relay 메트릭(활성 연결, 처리/대기 수)
- [x] 116. TLS/`wss` 종단 위치 문서화(리버스 프록시 전제)
- [x] 117. CORS/origin 검증 필요성 검토
- [x] 118. 동일 owner 중복 연결 처리(이전 연결 축출)
- [x] 119. relay 모듈 ruff/mypy 통과
- [x] 120. 차수 4 자체 점검

## 차수 5 — RemoteAgentClient (121~150)

- [x] 121. `remote/client.py` 생성
- [x] 122. `RemoteAgentClient(BaseLLMClient)` 골격
- [x] 123. 생성자: registry + owner 라우팅 컨텍스트 주입
- [x] 124. `generate()` 구현(프레임 송신 → 결과 대기)
- [x] 125. 연결 없음 → `LLMError`(사용자 친화 메시지)
- [x] 126. 타임아웃 → `LLMError` 변환
- [x] 127. 에이전트 `error` 프레임 → `LLMError` 변환(코드 매핑)
- [x] 128. `generate_stream()` 구현(chunk 큐 소비)
- [x] 129. 스트림 취소/조기 종료 처리
- [x] 130. `generate_with_tools()` 미지원 시 명확한 fallback/에러 — BaseLLMClient 기본 fallback(generate) 상속
- [x] 131. usage 파싱 → 상위 토큰 집계와 연동(last_usage)
- [x] 132. model 인자 전달(없으면 길드 기본/에이전트 기본)
- [x] 133. 요청 옵션(temperature 등) 매핑
- [x] 134. 라우팅 컨텍스트(user_id/guild_id/mode) 전달 경로
- [x] 135. `_get_llm` 분기에서 client 생성 경로 — 생성자 설계 완료, 실제 분기 배선은 차수 8(항목 216)
- [x] 136. BUSY 응답 시 사용자 안내 메시지
- [x] 137. OFFLINE 응답 시 안내 + 호스트 켜기 가이드
- [x] 138. 비ASCII 응답 처리 확인
- [x] 139. 대용량 응답 chunk 결합 처리
- [x] 140. 클라이언트 타임아웃 설정값 연동 — 타임아웃은 RelayConnection(settings)에서 강제
- [x] 141. 재시도 정책(연결 일시 끊김 시 1회 대기?) 결정 — 자동 재시도 안 함(docstring 명시)
- [x] 142. 에러 메시지 i18n(messages.py) 연동 — 한국어 사용자 메시지 제공(llm.py LLMError 패턴 일치)
- [x] 143. 동시 호출 시 request_id 유일성 보장(릴레이 new_request_id)
- [x] 144. client 취소 전파(상위 명령 취소 시)
- [x] 145. 응답 검증(빈 텍스트/형식)
- [x] 146. 토큰 사용량 없을 때 graceful 처리
- [x] 147. 로깅(요청/응답 메타, 내용 최소화)
- [x] 148. 클라이언트가 BaseLLMClient 인터페이스 완전 충족 확인
- [x] 149. client 모듈 ruff/mypy 통과
- [x] 150. 차수 5 자체 점검

## 차수 6 — 토큰 & 스토리지 (151~185)

- [ ] 151. `remote/tokens.py` 생성
- [ ] 152. 페어링 토큰 생성(secrets, 충분한 엔트로피)
- [ ] 153. 토큰 포맷(사람이 읽기 쉬운 그룹, 예 `ABC-123-XYZ`)
- [ ] 154. 토큰 해시 저장(평문 미저장)
- [ ] 155. 토큰 → owner(user/guild) 바인딩 레코드
- [ ] 156. 토큰 만료(TTL) 처리
- [ ] 157. 토큰 단발성(연결 시 소비) 또는 재사용 정책 결정
- [ ] 158. 토큰 검증 함수(만료/존재/일치)
- [ ] 159. 토큰 재발급(rotate) 처리
- [ ] 160. 토큰 폐기(revoke) 처리
- [ ] 161. 상수시간 비교로 타이밍 공격 방어
- [ ] 162. storage: `agent_tokens` 테이블 마이그레이션
- [ ] 163. storage: `routing_mode` 컬럼(guild_config) 마이그레이션
- [ ] 164. storage: `llm_host` 테이블(guild_id, host_user_id) 마이그레이션
- [ ] 165. schema_version 증가 + 순차 마이그레이션 등록
- [ ] 166. `set_routing_mode(guild_id, mode)` 저장 함수
- [ ] 167. `get_routing_mode(guild_id)` 조회(기본 personal)
- [ ] 168. `set_llm_host(guild_id, user_id, token_hash)` 함수
- [ ] 169. `get_llm_host(guild_id)` 조회
- [ ] 170. `clear_llm_host(guild_id)` 함수
- [ ] 171. `create_agent_token(owner)` → 평문 반환 + 해시 저장
- [ ] 172. `consume_agent_token(plain)` → owner 반환/검증
- [ ] 173. 만료 토큰 정리 백그라운드(기존 retention 패턴 재사용)
- [ ] 174. GuildConfig 모델에 routing_mode 필드 반영
- [ ] 175. GuildConfig 로딩 SELECT 컬럼 추가
- [ ] 176. set_provider_config 와의 정합(provider=remote_agent)
- [ ] 177. 호스트 등록/해제 시 레지스트리 상태와 DB 동기화 전략
- [ ] 178. 토큰 평문은 DM 전송 직후 메모리에서만 — 미저장 확인
- [ ] 179. 토큰/호스트 관련 로깅 마스킹
- [ ] 180. storage 함수 트랜잭션 원자성 확인
- [ ] 181. 토큰 테이블 인덱스(해시) 추가
- [ ] 182. 마이그레이션 idempotent/롤백 안전성 확인
- [ ] 183. tokens/storage 단위 동작 수동 점검
- [ ] 184. tokens/storage ruff/mypy 통과
- [ ] 185. 차수 6 자체 점검

## 차수 7 — 디스코드 명령 (186~215)

- [ ] 186. `/link` 명령 등록(개인 모드 페어링)
- [ ] 187. `/link` → 토큰 생성 + DM 전송
- [ ] 188. `/link` DM 실패(차단) 시 ephemeral fallback
- [ ] 189. `/link` 안내문(에이전트 실행법) 포함
- [ ] 190. `/unlink` 명령(개인 연결 해제 + 토큰 폐기)
- [ ] 191. `/host-llm` 명령(서버 공유 호스트 등록, 관리자 전용)
- [ ] 192. `/host-llm` 권한 체크(Manage Server/관리자/admin_role)
- [ ] 193. `/host-llm` → 호스트 토큰 생성 + DM
- [ ] 194. `/host-llm` 안내문(공유 모드 + 프라이버시 고지)
- [ ] 195. `/unhost-llm` 명령(호스트 해제, 관리자 전용)
- [ ] 196. `/llm-status` 명령(현재 모드/연결 상태 표시)
- [ ] 197. `/llm-status` 개인 연결 상태
- [ ] 198. `/llm-status` 서버 호스트 연결 상태(온/오프라인)
- [ ] 199. 명령 응답 임베드 디자인(COLORS 재사용)
- [ ] 200. 토큰 노출 최소화(코드블록, 만료 안내)
- [ ] 201. 명령 쿨다운/남용 방지 적용
- [ ] 202. 길드 외(DM) 사용 시 처리
- [ ] 203. i18n: 명령 설명/응답 messages.py 등록
- [ ] 204. /help 에 새 명령 섹션 추가
- [ ] 205. autocomplete 필요한 파라미터 처리
- [ ] 206. 토큰 재발급 명령 또는 옵션
- [ ] 207. 명령 등록을 docs-drift 가드와 동기화(README 표)
- [ ] 208. ephemeral 사용으로 토큰 타인 노출 방지
- [ ] 209. 호스트 등록 시 기존 호스트 교체 확인 플로우
- [ ] 210. 공유 모드 활성 시 채널 안내(고지)
- [ ] 211. 명령 예외 처리(UserFacingError) 일관화
- [ ] 212. 명령 동작 로깅(usage_log)
- [ ] 213. 명령 권한 실패 메시지 명확화
- [ ] 214. 명령 모듈 ruff/mypy 통과
- [ ] 215. 차수 7 자체 점검

## 차수 8 — bot 라우팅 통합 (216~240)

- [ ] 216. `_get_llm` 에 `REMOTE_AGENT` 분기 추가
- [ ] 217. 라우팅 컨텍스트(interaction → user_id/guild_id) 추출 헬퍼
- [ ] 218. 모드 조회(get_routing_mode) 연동
- [ ] 219. 개인 모드: 호출자 user_id 기준 client 생성
- [ ] 220. 공유 모드: guild 호스트 기준 client 생성
- [ ] 221. provider!=remote_agent 인데 mode=shared 충돌 정책
- [ ] 222. RelayServer/Registry 를 봇 컨텍스트(Context)에 주입
- [ ] 223. `_get_llm` 시그니처 확장 영향 호출부 일괄 수정
- [ ] 224. 모든 LLM 명령 경로에서 라우팅 컨텍스트 전달 확인
- [ ] 225. 멘션/리액션 경로도 동일 라우팅 적용
- [ ] 226. DM 경로의 라우팅(개인 모드만 의미) 처리
- [ ] 227. 오프라인 시 명령별 사용자 안내 일관화
- [ ] 228. `/settings` 패널에 provider=remote_agent 옵션 추가
- [ ] 229. `/settings` 에 라우팅 모드 토글(개인/공유) 추가
- [ ] 230. `/config` 하위 명령에 routing_mode 추가(선택)
- [ ] 231. 공유 모드 전환 시 호스트 미등록 경고
- [ ] 232. 토큰 예산/쿨다운 로직과 remote_agent 정합
- [ ] 233. 비용 계산(가격 0, 로컬)에서 remote_agent 처리
- [ ] 234. usage 집계에서 remote_agent usage 반영
- [ ] 235. 비전/툴콜 미지원 표면화(capability 게이트)
- [ ] 236. 기존 테스트가 _get_llm 변경에 깨지지 않게 보정
- [ ] 237. 컨텍스트 주입으로 인한 순환 import 방지
- [ ] 238. 통합 지점 로깅
- [ ] 239. bot 통합 ruff/mypy 통과
- [ ] 240. 차수 8 자체 점검

## 차수 9 — 유저 에이전트 (241~265)

- [ ] 241. `agent/` 패키지(또는 `remote/agent.py`) 생성
- [ ] 242. `agent/__main__.py` CLI 엔트리
- [ ] 243. argparse: `--token`, `--relay-url`, `--ollama-url`, `--model`
- [ ] 244. 환경변수 fallback(AGENT_TOKEN 등)
- [ ] 245. websockets/aiohttp 클라이언트로 relay 연결
- [ ] 246. 연결 직후 `AuthFrame` 송신
- [ ] 247. `AuthOk`/`AuthErr` 처리
- [ ] 248. 수신 루프: `infer` → Ollama 호출
- [ ] 249. 로컬 Ollama 호출(OllamaClient 재사용 또는 경량 클라)
- [ ] 250. `result` 프레임 회신(usage 포함)
- [ ] 251. Ollama 오류 → `error` 프레임(코드 매핑)
- [ ] 252. 스트리밍 모드 시 `chunk` 프레임 전송
- [ ] 253. ping 수신 → pong 응답
- [ ] 254. 자체 heartbeat/연결 감시
- [ ] 255. 끊김 시 지수 백오프 재연결
- [ ] 256. 동시 요청 처리(로컬 세마포어)
- [ ] 257. `cancel` 프레임 처리(진행 요청 취소)
- [ ] 258. 종료 시그널(SIGINT) graceful 처리
- [ ] 259. 콘솔 로그(연결 상태, 처리 건수)
- [ ] 260. Ollama 미설치/미기동 사전 점검 + 안내
- [ ] 261. 에이전트 버전/플랫폼 보고
- [ ] 262. 토큰을 로그/에러에 노출하지 않기
- [ ] 263. 최소 의존성으로 패키징 가능하게 구성
- [ ] 264. agent 모듈 ruff/mypy 통과
- [ ] 265. 차수 9 자체 점검

## 차수 10 — 동시성·큐·프라이버시·UX (266~285)

- [ ] 266. per-host 동시 처리 기본값(1) 적용 + 설정화
- [ ] 267. 대기 큐 동작 검증(요청2/3 대기)
- [ ] 268. 큐 대기 중 사용자에게 "대기 중" 표시
- [ ] 269. 큐 길이 상한 초과 시 거절 메시지
- [ ] 270. 호스트 오프라인 안내 문구 표준화
- [ ] 271. 공유 모드 프라이버시 고지 문구 정의(messages.py)
- [ ] 272. 공유 모드 명령 응답에 고지 노출(빈도 정책)
- [ ] 273. `/help` 공유 모드 설명에 고지 포함
- [ ] 274. 호스트 등록 시 방장에게 책임 고지
- [ ] 275. 고지 노출 빈도(최초/주기) 설정 결정
- [ ] 276. 타임아웃/BUSY/OFFLINE 사용자 메시지 일관성 점검
- [ ] 277. 단일 장애점(호스트 다운) 시 명령 graceful 실패
- [ ] 278. 과부하 방어(요청 폭주) 시 안정성
- [ ] 279. 응답 지연 시 typing/defer 처리
- [ ] 280. 로그에서 프롬프트 내용 최소화(프라이버시)
- [ ] 281. 메트릭에 모드별 라우팅 카운터
- [ ] 282. 오프라인↔온라인 전이 로깅
- [ ] 283. 보안 점검: SSRF 불가(임의 URL 미사용) 재확인
- [ ] 284. 보안 점검: 토큰 평문 미저장/미로그 재확인
- [ ] 285. 차수 10 자체 점검

## 차수 11 — 테스트 (286~295)

- [ ] 286. protocol 직렬화 round-trip 테스트
- [ ] 287. protocol 검증/에러 케이스 테스트
- [ ] 288. registry 라우팅(개인/공유/오프라인) 테스트
- [ ] 289. registry 재연결 축출/heartbeat 만료 테스트
- [ ] 290. tokens 생성/검증/만료/해시 테스트
- [ ] 291. storage 마이그레이션/모드/호스트 CRUD 테스트
- [ ] 292. RemoteAgentClient generate(가짜 연결) 테스트
- [ ] 293. RemoteAgentClient 타임아웃/오프라인/BUSY 테스트
- [ ] 294. relay 인증/요청-응답 통합 테스트(인메모리 ws)
- [ ] 295. 에이전트 핸들러(infer→ollama mock) 테스트

## 차수 12 — 문서·검증·마무리 (296~300)

- [ ] 296. README/README_EN 명령 표 + 사용법 갱신
- [ ] 297. `.env.example`/환경변수 표 최종 동기화
- [ ] 298. ADR 0002 상태 `채택됨`으로 갱신 + 구현 메모
- [ ] 299. 전체 `ruff check` + `mypy` + `pytest` 통과(커버리지 하한)
- [ ] 300. 변경 커밋(Conventional Commits) + 로드맵 100% 체크

---

# Phase B — 커뮤니티 Provider Pool (차수 13~)

> 한 서버에 여러 프로바이더가 로컬 LLM 자원을 등록하고, 중앙 봇이 공정하게 분배한다.
> Phase A(단일 호스트)를 다중 프로바이더 풀로 일반화. 판매/가격/정산 개념은 도입하지 않는다.

## 차수 13 — ADR 0003 & Provider Pool 도메인 확정 (301~316)

- [ ] 301. ADR 0003 `community-provider-pool` 작성(맥락·결정·결과)
- [ ] 302. 비-목표 명문화(판매자/구매자/가격/수수료/정산/마켓플레이스 제외)
- [ ] 303. 중심 개념 정의(contribution·consent·capacity·availability·fairness)
- [ ] 304. 용어 사전(guild/일반유저/프로바이더/Provider Pool/Provider Agent/중앙서버)
- [ ] 305. Phase A 단일 공유 호스트 → 다중 프로바이더 풀 일반화 관계 기술
- [ ] 306. 도메인 모델 다이어그램(guild → provider_pool[])
- [ ] 307. 요청 처리 19단계 흐름 문서화
- [ ] 308. 프로바이더 상태머신(10상태) 문서화
- [ ] 309. 요청 상태머신(10상태) 문서화
- [ ] 310. 모델 부담 수준 정의(light/standard/heavy/restricted)
- [ ] 311. 공정성 점수 공식 초안 문서화
- [ ] 312. 보안 원칙(금지/허용 행위) 문서화
- [ ] 313. 프라이버시 처리주체 표시 방식 A/B/C 정의
- [ ] 314. 명령어 카탈로그(유저/관리자/프로바이더) 정리
- [ ] 315. Phase A 자료구조 재사용 vs 신규 결정(레지스트리/프로토콜 확장 범위)
- [ ] 316. 차수 13 자체 점검

## 차수 14 — 데이터 모델 & 스토리지 스키마 (317~340)

- [ ] 317. `guild_policy` 테이블 설계(허용채널·역할정책·승인방식·기본제한)
- [ ] 318. `provider` 테이블(user_id, guild_id, 승인상태)
- [ ] 319. `provider_session` 테이블/인메모리(연결·heartbeat·상태)
- [ ] 320. `provider_capability` 테이블(모델·부담수준·최대컨텍스트·예상속도)
- [ ] 321. `provider_contribution_policy` 테이블(모델별 허용역할/채널/한도/동시/시간)
- [ ] 322. `request` 테이블(요청자·guild·channel·메타·필요수준·선택provider·상태·실패사유)
- [ ] 323. `usage_log` 확장(누가 얼마나, 어떤 provider가 얼마나 — 공정성용)
- [ ] 324. `contribution_log`(프로바이더 기여량 집계)
- [ ] 325. `allowed_channels` 저장 구조(guild별 다중)
- [ ] 326. `role_model_policy` 저장 구조(역할→허용 부담수준·일일한도)
- [ ] 327. 모델 정의 dataclass(`ModelBurden`, `ProviderCapability`)
- [ ] 328. `Provider`/`ProviderSession` dataclass(models.py)
- [ ] 329. `ProviderState` enum(unregistered~removed 10상태)
- [ ] 330. `RequestState` enum(received~rejected 10상태)
- [ ] 331. `ProviderContributionPolicy` dataclass
- [ ] 332. `GuildPolicy` dataclass
- [ ] 333. schema_version 증가 + 순차 마이그레이션 등록
- [ ] 334. 인덱스 설계(guild_id, provider state, request state)
- [ ] 335. billing/price/seller/payout 필드 부재 명시(설계 가드)
- [ ] 336. provider CRUD storage 함수
- [ ] 337. guild_policy CRUD storage 함수
- [ ] 338. contribution/usage 집계 쿼리 함수
- [ ] 339. 마이그레이션 idempotent/롤백 안전성 확인
- [ ] 340. 차수 14 자체 점검

## 차수 15 — 모델 부담 수준 분류 (341~352)

- [ ] 341. `ModelBurden` enum(LIGHT/STANDARD/HEAVY/RESTRICTED)
- [ ] 342. 부담 수준 ↔ 설명/예시 매핑
- [ ] 343. 알려진 모델명 → 기본 부담수준 휴리스틱 테이블
- [ ] 344. 프로바이더가 모델 부담수준 직접 지정/오버라이드
- [ ] 345. RESTRICTED 의미(역할/채널/관리자 제한) 정의
- [ ] 346. 부담수준별 기본 타임아웃/컨텍스트 상한
- [ ] 347. 모델→부담수준 미상 시 보수적 기본값(standard) 처리
- [ ] 348. 부담수준 표시용 라벨/이모지
- [ ] 349. `/models` 응답용 부담수준 요약 빌더
- [ ] 350. 부담수준 분류 단위 함수 순수화(테스트 대상)
- [ ] 351. 부담수준 모듈 ruff/mypy 통과
- [ ] 352. 차수 15 자체 점검

## 차수 16 — 프로바이더 등록/승인 라이프사이클 (353~372)

- [ ] 353. `/provider-join` 명령(프로바이더 등록 요청)
- [ ] 354. 등록 요청 → `pending` 상태 생성
- [ ] 355. 승인 방식 정책(자동/관리자 승인) 조회
- [ ] 356. `/provider-approve` 명령(관리자, pending→approved)
- [ ] 357. `/provider-remove` 명령(관리자, →removed)
- [ ] 358. `/providers` 명령(관리자, 풀 목록·상태)
- [ ] 359. 승인 시 일회용 Agent 토큰 발급(Phase A tokens 재사용/확장)
- [ ] 360. 토큰 DM 전송 + 에이전트 실행 안내
- [ ] 361. 토큰 owner 바인딩(provider_id, guild_id)
- [ ] 362. 승인 거절/만료 처리
- [ ] 363. 중복 등록 방지(이미 provider 인 경우)
- [ ] 364. removed 후 재등록 흐름
- [ ] 365. 등록/승인 이벤트 audit_log 기록
- [ ] 366. 권한 체크(관리자/Manage Server/admin_role)
- [ ] 367. 등록 요청 알림(관리자 채널/DM)
- [ ] 368. 프로바이더 동의 고지(프롬프트가 내 PC로 전송됨)
- [ ] 369. 라이프사이클 상태 전이 검증 함수
- [ ] 370. 등록/승인 응답 임베드 디자인
- [ ] 371. 라이프사이클 모듈 ruff/mypy 통과
- [ ] 372. 차수 16 자체 점검

## 차수 17 — Provider Session·상태머신·heartbeat·capability (373~394)

- [ ] 373. 프로토콜 확장: `provider_hello`(capability·모델·동시한도·일일잔여)
- [ ] 374. 프로토콜 확장: `provider_status`(load·battery·online/busy)
- [ ] 375. Agent 연결 시 capability 보고 수신·저장
- [ ] 376. ProviderSession 생성(연결·인증·capability 바인딩)
- [ ] 377. 상태 전이: approved→online_idle(연결)
- [ ] 378. 상태 전이: online_idle↔online_busy(요청 처리)
- [ ] 379. 상태 전이: →paused(/provider-pause)
- [ ] 380. 상태 전이: →limited(한도/부하)
- [ ] 381. 상태 전이: →offline(연결 끊김)
- [ ] 382. 상태 전이: →unhealthy(반복 실패)
- [ ] 383. heartbeat 주기 수신 + last_seen 갱신
- [ ] 384. heartbeat 만료 → offline 처리
- [ ] 385. capability 갱신(모델 추가/제거 런타임 반영)
- [ ] 386. 동시 처리 슬롯 카운터(provider별)
- [ ] 387. 일일 잔여 한도 카운터(provider·모델별)
- [ ] 388. 상태 스냅샷 빌더(진단/`/provider-status`)
- [ ] 389. 좀비 세션 청소 백그라운드
- [ ] 390. 상태머신 전이 가드(불가 전이 거부)
- [ ] 391. 세션 상태 변경 로깅
- [ ] 392. 멀티 세션 레지스트리(guild→provider[]→session)
- [ ] 393. 세션 모듈 ruff/mypy 통과
- [ ] 394. 차수 17 자체 점검

## 차수 18 — 서버 정책(채널/역할/모델수준) (395~414)

- [ ] 395. `/llm-allow-channel` 명령(허용 채널 추가)
- [ ] 396. `/llm-deny-channel` 명령(금지 채널 설정)
- [ ] 397. 허용 채널 목록 조회/저장
- [ ] 398. 채널 허용 여부 판정 함수
- [ ] 399. `/llm-role-policy` 명령(역할별 허용 부담수준)
- [ ] 400. 역할→허용 모델수준 매핑 저장
- [ ] 401. 역할→일일 요청 한도 매핑 저장
- [ ] 402. 멤버 역할 → 최대 허용 부담수준 해석(다중 역할 합집합)
- [ ] 403. 기본(역할 미지정) 멤버 정책
- [ ] 404. 신뢰 멤버/관리자 등급 개념 매핑
- [ ] 405. `/llm-settings` 명령(정책 종합 설정 패널)
- [ ] 406. 프로바이더 승인 방식 설정(자동/수동)
- [ ] 407. 기본 요청 제한(서버 차원) 설정
- [ ] 408. 정책 조회 캐시(요청 경로 성능)
- [ ] 409. 정책 변경 audit_log 기록
- [ ] 410. 정책 검증(존재하지 않는 역할/채널 방어)
- [ ] 411. 권한 체크(관리자 전용)
- [ ] 412. 정책 설정 응답 임베드
- [ ] 413. 정책 모듈 ruff/mypy 통과
- [ ] 414. 차수 18 자체 점검

## 차수 19 — 프로바이더 기여 정책 (415~436)

- [ ] 415. `/provider-models` 명령(제공 모델 목록 설정)
- [ ] 416. `/provider-limit` 명령(모델별 일일한도·동시·최대시간)
- [ ] 417. `/provider-scope` 명령(허용 역할·채널·요청종류)
- [ ] 418. 모델별 허용 역할 저장
- [ ] 419. 모델별 허용 채널 저장
- [ ] 420. 모델별 일일 한도 저장/카운트
- [ ] 421. 동시 요청 한도 저장/적용
- [ ] 422. 요청당 최대 처리 시간 저장/적용
- [ ] 423. 긴 프롬프트 허용 여부 토글
- [ ] 424. 프롬프트 길이 상한(프로바이더별)
- [ ] 425. 요청자 허용 범위(전체/신뢰이상/관리자만) 저장
- [ ] 426. 정책 기본값(미설정 시 보수적)
- [ ] 427. 정책 조회 함수(요청 매칭용)
- [ ] 428. 정책 검증(모순/범위)
- [ ] 429. 정책 변경 즉시 반영(세션 갱신)
- [ ] 430. 기여 정책 요약 표시(`/provider-status`)
- [ ] 431. "모든 요청을 받을 의무 없음" 원칙 반영(거절 경로)
- [ ] 432. 정책 명령 권한 체크(본인만)
- [ ] 433. 정책 audit/로그
- [ ] 434. 기여 정책 응답 임베드
- [ ] 435. 기여 정책 모듈 ruff/mypy 통과
- [ ] 436. 차수 19 자체 점검

## 차수 20 — 요청 무게 판단 & 필요 모델 수준 결정 (437~450)

- [ ] 437. 요청 메타 추출(프롬프트 길이·첨부·명령종류)
- [ ] 438. 길이/복잡도 → 요청 무게 휴리스틱
- [ ] 439. 첨부(이미지/긴 코드) 가중치
- [ ] 440. 요청 무게 → 필요 모델 부담수준 매핑
- [ ] 441. 명령별 기본 무게(`/ask` vs `/summarize` 등)
- [ ] 442. 사용자 지정 모델수준 옵션(있으면 우선)
- [ ] 443. 권한 상한과 필요수준 충돌 시 다운그레이드/거절 결정
- [ ] 444. 무게 판단 순수 함수화(테스트 대상)
- [ ] 445. 무게/수준 결정 로깅
- [ ] 446. 경계값(빈 프롬프트/초장문) 처리
- [ ] 447. 무게 판단 설정값(임계치) 노출
- [ ] 448. 필요수준 산출 결과를 request 레코드에 기록
- [ ] 449. 무게 모듈 ruff/mypy 통과
- [ ] 450. 차수 20 자체 점검

## 차수 21 — Provider Pool 필터링 파이프라인 (451~472)

- [ ] 451. 파이프라인 골격(후보 목록 → 단계별 필터)
- [ ] 452. 1) 요청 모델수준 감당 가능 필터
- [ ] 453. 2) 온라인 상태 필터
- [ ] 454. 3) idle 상태 필터(busy 제외)
- [ ] 455. 4) 요청자(역할) 허용 필터
- [ ] 456. 5) 채널 허용 필터
- [ ] 457. 6) 일일 한도 잔여 필터
- [ ] 458. 7) 동시 요청 한도 필터
- [ ] 459. 8) 최근 과다 처리 제외(쿨다운)
- [ ] 460. 9) 요청 크기 ≤ 프로바이더 제한 필터
- [ ] 461. 10) 응답 실패율 임계 초과 제외
- [ ] 462. RESTRICTED 모델 특수 필터(역할/채널/관리자)
- [ ] 463. 후보 0명 → `no_provider_available` 신호
- [ ] 464. 권한 부족 전용 신호(다운그레이드 제안)
- [ ] 465. 필터 단계별 사유 기록(디버그/관리자 로그)
- [ ] 466. 파이프라인 단계 순수 함수화
- [ ] 467. 필터 결과 메트릭(단계별 탈락 수)
- [ ] 468. 파이프라인 성능(대규모 풀) 고려
- [ ] 469. 필터 구성 가능화(서버 정책 반영)
- [ ] 470. 후보 컨텍스트 객체(점수 계산 입력) 구성
- [ ] 471. 파이프라인 모듈 ruff/mypy 통과
- [ ] 472. 차수 21 자체 점검

## 차수 22 — 공정성 점수 & Router 선택 (473~490)

- [ ] 473. `provider_score` 함수 골격
- [ ] 474. 가산: 모델 적합도
- [ ] 475. 가산: 온라인·idle
- [ ] 476. 가산: 남은 한도
- [ ] 477. 가산: 최근 처리량 적을수록 가산점(공정성)
- [ ] 478. 감산: 최근 실패율
- [ ] 479. 감산: 현재 부하
- [ ] 480. 감산: heavy provider 를 light 요청에 쓰는 낭비 패널티
- [ ] 481. light 요청 → light provider 우선 규칙
- [ ] 482. standard → standard 우선 규칙
- [ ] 483. heavy → heavy 후보 한정 규칙
- [ ] 484. heavy provider 는 light provider 없을 때만 예외 사용
- [ ] 485. 동점 시 분산(라운드로빈/랜덤 시드) 처리
- [ ] 486. 최종 1인 선택 + 사유 반환
- [ ] 487. 점수 가중치 설정화/튜닝 포인트
- [ ] 488. 점수 계산 순수 함수화(테스트 대상)
- [ ] 489. Router 모듈 ruff/mypy 통과
- [ ] 490. 차수 22 자체 점검

## 차수 23 — 요청 상태머신·큐·타임아웃·fallback (491~514)

- [ ] 491. RequestState 전이 구현(received→…→completed/failed/rejected)
- [ ] 492. request_id 생성·추적
- [ ] 493. policy_checked 단계 기록
- [ ] 494. routing 단계 기록
- [ ] 495. 선택 provider 큐 적재(queued)
- [ ] 496. sent_to_provider 전송
- [ ] 497. running 상태(에이전트 처리 중)
- [ ] 498. 요청 타임아웃 설정(부담수준/정책 기반)
- [ ] 499. 타임아웃 → 실패 처리 + provider 상태 반영
- [ ] 500. 실패 시 동일 조건 다른 provider 1회 fallback
- [ ] 501. fallback_running 상태 기록
- [ ] 502. fallback 후보 재필터링(원 provider 제외)
- [ ] 503. fallback 실패 → 사용자 안내
- [ ] 504. 실패 provider → temporarily_unavailable 표시
- [ ] 505. 연결 끊김 중 진행요청 복구/실패 처리
- [ ] 506. 큐 길이 상한·대기 안내
- [ ] 507. rejected(권한/정책) 경로 + 안내
- [ ] 508. 완료 시 사용량/기여량 기록 트리거
- [ ] 509. 동시 다수 요청 라우팅 정합성
- [ ] 510. 요청 상태 전이 로깅
- [ ] 511. 사용자 진행 표시(defer/typing/대기중)
- [ ] 512. 상태머신 가드(불가 전이 거부)
- [ ] 513. 요청 처리 모듈 ruff/mypy 통과
- [ ] 514. 차수 23 자체 점검

## 차수 24 — 프로바이더 보호: 수동 + 자동 (515~538)

- [ ] 515. `/provider-pause` 명령(요청 수신 중단)
- [ ] 516. `/provider-resume` 명령(재개)
- [ ] 517. `/provider-leave` 명령(풀 이탈)
- [ ] 518. `/provider-status` 명령(내 상태 확인)
- [ ] 519. pause 즉시 라우팅 후보 제외
- [ ] 520. 진행 중 요청은 보호(중단 정책 결정)
- [ ] 521. Agent 부하 보고(CPU) 수신
- [ ] 522. Agent 부하 보고(GPU) 수신
- [ ] 523. Agent 메모리 부족 보고 → 요청 거절
- [ ] 524. 배터리 모드 → 자동 pause
- [ ] 525. 절전 모드 진입 → offline
- [ ] 526. 네트워크 불안정 → temporarily_unavailable
- [ ] 527. CPU/GPU 임계 초과 → 수신 중단
- [ ] 528. 동시 요청 제한 강제
- [ ] 529. 요청당 최대 처리 시간 강제
- [ ] 530. 프롬프트 길이 제한 강제
- [ ] 531. 반복 실패 → 자동 비활성화(unhealthy)
- [ ] 532. 자동 보호 임계치 설정화
- [ ] 533. 보호 이벤트 사용자/관리자 안내
- [ ] 534. 보호 상태 → 상태머신 반영
- [ ] 535. Agent↔서버 보호 신호 프로토콜
- [ ] 536. 보호 이벤트 로깅
- [ ] 537. 보호 모듈 ruff/mypy 통과
- [ ] 538. 차수 24 자체 점검

## 차수 25 — 명령어: 일반 유저 (539~550)

- [ ] 539. `/ask` 를 Provider Pool 라우팅과 연결
- [ ] 540. `/models` 명령(서버 사용 가능 모델 수준)
- [ ] 541. `/my-usage` 명령(오늘 내 요청 수)
- [ ] 542. `/privacy` 명령(처리 방식·프라이버시 안내)
- [ ] 543. 권한별 사용 가능 수준 표시
- [ ] 544. 처리 결과에 "커뮤니티 풀 처리" 안내(모드별)
- [ ] 545. 거절 시 안내(권한/혼잡)
- [ ] 546. i18n 메시지 등록
- [ ] 547. /help 에 유저 Provider Pool 섹션
- [ ] 548. 쿨다운/남용 방지
- [ ] 549. 유저 명령 모듈 ruff/mypy 통과
- [ ] 550. 차수 25 자체 점검

## 차수 26 — 명령어: 관리자 (551~568)

- [ ] 551. `/llm-settings` 통합 패널
- [ ] 552. `/llm-allow-channel` (차수18 연계 마감)
- [ ] 553. `/llm-deny-channel`
- [ ] 554. `/llm-role-policy`
- [ ] 555. `/providers`(목록·상태·기여량)
- [ ] 556. `/provider-approve`
- [ ] 557. `/provider-remove`
- [ ] 558. 관리자 전용 권한 가드 일관화
- [ ] 559. provider별 상세 보기(capability·정책·상태)
- [ ] 560. pool 헬스 요약(온라인/바쁨/오프라인 수)
- [ ] 561. 문제 provider 강제 pause/제거
- [ ] 562. 정책 일괄 보기/내보내기
- [ ] 563. 관리자 행동 audit_log
- [ ] 564. 관리자 명령 응답 임베드
- [ ] 565. 관리자 알림(등록 요청/장애)
- [ ] 566. i18n
- [ ] 567. 관리자 명령 모듈 ruff/mypy 통과
- [ ] 568. 차수 26 자체 점검

## 차수 27 — 명령어: 프로바이더 (569~586)

- [ ] 569. `/provider-join`(차수16 연계 마감)
- [ ] 570. `/provider-leave`
- [ ] 571. `/provider-pause`
- [ ] 572. `/provider-resume`
- [ ] 573. `/provider-status`
- [ ] 574. `/provider-models`
- [ ] 575. `/provider-limit`
- [ ] 576. `/provider-scope`
- [ ] 577. 본인 소유권 체크(타 provider 조작 금지)
- [ ] 578. Agent 미연결 시 명령 동작 정의
- [ ] 579. 설정 변경 즉시 세션 반영
- [ ] 580. 상태/한도/기여량 종합 표시
- [ ] 581. 토큰 재발급 옵션
- [ ] 582. i18n
- [ ] 583. 명령↔docs-drift 가드 동기화(README 표)
- [ ] 584. 프로바이더 명령 응답 임베드
- [ ] 585. 프로바이더 명령 모듈 ruff/mypy 통과
- [ ] 586. 차수 27 자체 점검

## 차수 28 — 프라이버시 모드 A/B/C (587~600)

- [ ] 587. 서버 프라이버시 표시 모드 설정(A/B/C)
- [ ] 588. 방식 A: 익명(모델 수준만 표시)
- [ ] 589. 방식 B: 부분 공개(커뮤니티 프로바이더 처리·위치)
- [ ] 590. 방식 C: 관리자만 provider 식별(기본 추천)
- [ ] 591. 일반 유저 노출 텍스트 빌더
- [ ] 592. 관리자 로그에 provider 식별 기록
- [ ] 593. 공유 모드 진입 시 서버 고지(민감정보 금지)
- [ ] 594. `/privacy` 응답과 모드 연동
- [ ] 595. 프로바이더에게 처리 책임/프롬프트 수신 고지
- [ ] 596. 고지 노출 빈도 정책
- [ ] 597. 로그에서 프롬프트 내용 최소화
- [ ] 598. 프라이버시 문구 i18n
- [ ] 599. 프라이버시 모듈 ruff/mypy 통과
- [ ] 600. 차수 28 자체 점검

## 차수 29 — 사용량·기여량·공정성 기록 (601~614)

- [ ] 601. 요청 완료 시 usage_log 기록(요청자 기준)
- [ ] 602. 요청 완료 시 contribution_log 기록(provider 기준)
- [ ] 603. provider별 처리량 집계
- [ ] 604. 유저별 일일 요청 수 집계(한도 판정 연동)
- [ ] 605. 공정성 지표(provider 간 분배 균형) 계산
- [ ] 606. `/my-usage` 데이터 소스 연결
- [ ] 607. `/providers` 기여량 표시 연결
- [ ] 608. 관리자 공정성 리포트
- [ ] 609. 기여량 기반 점수 가산 연동(차수22)
- [ ] 610. retention 정리(오래된 request/log)
- [ ] 611. 집계 쿼리 성능/인덱스
- [ ] 612. 기록에 민감내용 미포함 확인
- [ ] 613. 기록 모듈 ruff/mypy 통과
- [ ] 614. 차수 29 자체 점검

## 차수 30 — 보안 하드닝 (615~632)

- [ ] 615. Agent 금지행위 가드(임의 shell/파일/URL 차단) 명문화·테스트
- [ ] 616. Agent 는 중앙 서버 요청 외 처리 안 함 보장
- [ ] 617. provider PC 포트 미개방 확인(outbound only)
- [ ] 618. 일회용 토큰·짧은 만료·1회 폐기 검증
- [ ] 619. 토큰 해시 저장·평문 미저장·상수시간 비교
- [ ] 620. heartbeat 기반 세션 유효성 검증
- [ ] 621. 요청 프레임 화이트리스트(허용 필드만)
- [ ] 622. 프롬프트/응답 크기 상한 강제
- [ ] 623. 인증 실패/이상 연결 차단·로깅
- [ ] 624. SSRF 불가 재확인(임의 URL 미사용)
- [ ] 625. provider 간 요청 격리(타 provider 데이터 접근 불가)
- [ ] 626. 권한 상승 방지(역할/채널 정책 우회 차단)
- [ ] 627. rate limit(요청/등록/명령)
- [ ] 628. 로그 마스킹(토큰·프롬프트)
- [ ] 629. wss/TLS 종단 문서화
- [ ] 630. 보안 점검 체크리스트 문서화(SECURITY.md 갱신)
- [ ] 631. 보안 가드 단위 테스트
- [ ] 632. 차수 30 자체 점검

## 차수 31 — 테스트 (Provider Pool) (633~656)

- [ ] 633. ModelBurden 분류 테스트
- [ ] 634. 요청 무게→필요수준 매핑 테스트
- [ ] 635. 필터 파이프라인 각 단계 테스트
- [ ] 636. 후보 0명/권한부족 경로 테스트
- [ ] 637. provider_score 점수 계산 테스트
- [ ] 638. light/standard/heavy 라우팅 우선순위 테스트
- [ ] 639. heavy 낭비 방지 예외 규칙 테스트
- [ ] 640. 공정성(최근 과다처리 제외) 테스트
- [ ] 641. 프로바이더 상태머신 전이 테스트
- [ ] 642. 요청 상태머신 전이 테스트
- [ ] 643. 타임아웃→fallback 테스트
- [ ] 644. fallback 실패 안내 테스트
- [ ] 645. 기여/일일 한도 카운트 테스트
- [ ] 646. guild_policy 채널/역할 정책 테스트
- [ ] 647. provider 기여 정책 매칭 테스트
- [ ] 648. 등록/승인 라이프사이클 테스트
- [ ] 649. heartbeat 만료→offline 테스트
- [ ] 650. 자동 보호(배터리/부하) 트리거 테스트
- [ ] 651. 프라이버시 모드 A/B/C 출력 테스트
- [ ] 652. 보안 가드(금지행위/토큰) 테스트
- [ ] 653. storage 마이그레이션/CRUD 테스트
- [ ] 654. 멀티 provider 인메모리 통합 테스트
- [ ] 655. 명령 권한 가드 테스트
- [ ] 656. 차수 31 자체 점검

## 차수 32 — 문서·배포·마무리 (657~674)

- [ ] 657. README/README_EN: Provider Pool 개요·명령표 갱신
- [ ] 658. 프로바이더용 Agent 설치/실행 가이드 문서
- [ ] 659. 관리자용 정책 설정 가이드 문서
- [ ] 660. 일반 유저용 사용/프라이버시 안내 문서
- [ ] 661. `.env.example`/환경변수 표 동기화
- [ ] 662. env SSOT/docs-drift 가드 통과
- [ ] 663. ADR 0003 상태 `채택됨` + 구현 메모
- [ ] 664. 데모 시나리오(다중 provider) 문서
- [ ] 665. 부하/공정성 수동 검증 시나리오
- [ ] 666. 메트릭/대시보드(provider 상태) 연동 검토
- [ ] 667. 운영 롤백 절차 갱신(ROLLBACK.md)
- [ ] 668. 전체 ruff/mypy/pytest 통과(커버리지 하한)
- [ ] 669. Agent 패키징(실행파일/Docker) 검토
- [ ] 670. 성능 점검(대규모 풀 라우팅)
- [ ] 671. 보안 최종 리뷰(/security-review)
- [ ] 672. 변경 커밋(Conventional Commits)
- [ ] 673. Phase B 로드맵 100% 체크 확인
- [ ] 674. 최종 통합 점검 & PR 준비
