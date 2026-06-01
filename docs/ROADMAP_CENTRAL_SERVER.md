# central-server (Kotlin/Spring Boot) — 구현 체크리스트

> 스택 결정: [ADR 0004](./adr/0004-kotlin-spring-central-server.md) · 설계: [ADR 0003](./adr/0003-community-provider-pool.md)
> 명세 SSOT: [`specs/product-v2/domains/community-provider-pool`](../specs/product-v2/domains/community-provider-pool)
> 상태: `[ ]` 미착수 · `[x]` 완료. 모듈: `central-server/`. 검증: `./gradlew build`(JDK 21).
> 안전 루프: 구현 → `gradlew build`/test → 커밋 → 다음 차수.

## K-차수 0 — 스캐폴딩 (완료)

- [x] 0-1. Gradle(Kotlin DSL)·Spring Boot 3.4·JDA·Application·application.yml·wrapper
- [x] 0-2. 도메인 enum: ModelBurden·RequestWeight·ProviderState·RequestState·PrivacyMode (specs 매핑)
- [x] 0-3. 도메인 enum 단위 테스트 + `gradlew build` 성공(JDK 21)

## K-차수 1 — WS 프로토콜 (api.md §8)

- [x] 1-1. `relay/protocol/` 패키지
- [x] 1-2. `sealed class Frame` + `type` 디스크리미네이터(Jackson 다형 역직렬화)
- [x] 1-3. `AuthFrame`(token·protocolVersion·agentVersion·platform)
- [x] 1-4. `AuthOkFrame`/`AuthErrFrame`
- [x] 1-5. `InferRequest`(requestId·model·prompt·options)
- [x] 1-6. `InferResult`(requestId·text·usage) + `Usage`
- [x] 1-7. `InferError`(requestId·code·message) + `ErrorCode`
- [x] 1-8. `ChunkFrame`·`PingFrame`·`PongFrame`·`CancelFrame`
- [x] 1-9. `ProviderHelloFrame`(capability·동시한도·일일잔여)·`ProviderStatusFrame`(load·battery·online/busy)
- [x] 1-10. `FrameCodec`(ObjectMapper 래퍼: encode/decode, ensure 한국어 보존)
- [x] 1-11. 옵션 화이트리스트·프롬프트 길이·프레임 크기 상한
- [x] 1-12. 토큰 마스킹(toString)
- [x] 1-13. round-trip 단위 테스트(모든 프레임)·알 수 없는 타입 예외
- [x] 1-14. `gradlew build` 통과 + 커밋

## K-차수 2 — 연결 레지스트리 & Provider Session

- [x] 2-1. `relay/AgentConnection` 인터페이스(send/close/메타)
- [x] 2-2. `ProviderSession`(연결·capability·상태·heartbeat·동시 슬롯)
- [x] 2-3. `ConnectionRegistry`(providerId→session, guildId→sessions[])
- [x] 2-4. 등록/해제/중복 축출(graceful close)
- [x] 2-5. 조회(byProvider/byGuild)·활성 수·스냅샷
- [x] 2-6. 좀비 청소(heartbeat 만료)
- [x] 2-7. request_id↔응답 future(CompletableDeferred/CompletableFuture)
- [x] 2-8. per-session 동시 슬롯·대기 큐 상한(BUSY) — (cap=동시+큐) 하드캡 BUSY; 진정한 순차 대기는 K-차수 11
- [x] 2-9. 요청 타임아웃→cancel
- [x] 2-10. 단위 테스트(등록·라우팅키·축출·타임아웃)
- [x] 2-11. `gradlew build` + 커밋

## K-차수 3 — WebSocket 릴레이 (Spring WebSocket)

- [x] 3-1. `WebSocketConfigurer` + 핸들러 등록(relay path)
- [x] 3-2. `RelayWebSocketHandler`(연결/메시지/종료)
- [x] 3-3. 첫 프레임=auth 강제(타임아웃)·토큰 검증→세션 등록
- [x] 3-4. 수신 디스패치(result/error/chunk/pong)
- [x] 3-5. heartbeat ping 태스크·만료 종료(@Scheduled maintenance)
- [x] 3-6. 잘못된 프레임 방어·프레임 크기 제한
- [x] 3-7. graceful shutdown(afterConnectionClosed 해제)
- [x] 3-8. TLS/wss·origin 정책 문서화(config/핸들러 주석)
- [x] 3-9. 통합 테스트(FakeWebSocketSession 핸들러 end-to-end: 인증→추론 왕복) — 실소켓 테스트는 추후
- [x] 3-10. `gradlew build` + 커밋

## K-차수 4 — Provider 등록/승인 & 토큰

- [x] 4-1. 일회용 토큰 발급(만료·SHA-256 해시 저장·해시 조회로 타이밍 안전)
- [x] 4-2. 토큰 검증자(verifier) → OwnerBinding (TokenService 가 TokenVerifier 구현, 스텁 대체)
- [x] 4-3. 등록 요청(pending)·승인(approved)·거절·제거 라이프사이클
- [x] 4-4. 승인 방식 정책(자동/관리자) — autoApprove 파라미터; 길드 정책 연동은 K-차수 7
- [x] 4-5. audit 로그(AuditLog)
- [x] 4-6. 단위/통합 테스트
- [x] 4-7. `gradlew build` + 커밋

## K-차수 5 — capability·상태머신·heartbeat

- [x] 5-1. provider_hello 수신→capability 저장
- [x] 5-2. provider_status 수신→load/battery/online·busy 반영
- [x] 5-3. 상태 전이 가드(불가 전이 거부)
- [x] 5-4. 일일 잔여/동시 슬롯 카운터
- [x] 5-5. 좀비 세션 청소 스케줄러(handler maintenance + registry.reapStale)
- [x] 5-6. 단위 테스트(상태 전이표)
- [x] 5-7. `gradlew build` + 커밋

## K-차수 6 — 도메인 엔티티 & 영속화 (JPA)

- [x] 6-1. spring-boot-starter-data-jpa + H2(dev/test)/Postgres(prod) + Flyway + kotlin jpa 플러그인
- [x] 6-2. 엔티티: Guild·AllowedChannel·RolePolicy (GuildPolicy 는 guild 컬럼에 통합)
- [x] 6-3. 엔티티: Provider(상태=ApprovalState 통합)·ProviderContributionPolicy (Capability 는 세션 런타임)
- [x] 6-4. 엔티티: AiRequest·UsageLog·ContributionLog·ProviderHealth
- [x] 6-5. Repository(Spring Data)
- [x] 6-6. Flyway 마이그레이션 V1__init.sql(스키마 버전)
- [x] 6-7. billing/price/seller/payout 부재 가드(설계 원칙 — 엔티티/SQL 주석)
- [x] 6-8. Repository 테스트(@DataJpaTest + Flyway H2)
- [x] 6-9. `gradlew build` + 커밋

## K-차수 7 — 서버 정책 (채널/역할/모델 수준)

- [x] 7-1. 허용 채널 추가/제거·판정(미설정 시 제한 없음)
- [x] 7-2. 역할→허용 부담수준·일일 한도 매핑
- [x] 7-3. 멤버 역할→최대 허용 수준 해석(다중 역할 = 최고 등급)
- [x] 7-4. 승인 방식 설정(guild.auto_approve)
- [x] 7-5. 정책 변경 audit
- [x] 7-6. 단위 테스트(@DataJpaTest + PolicyService)
- [x] 7-7. `gradlew build` + 커밋

## K-차수 8 — 요청 무게 판단 & 필요 수준

- [x] 8-1. 요청 메타 추출(RequestMeta: 길이·첨부·명령)
- [x] 8-2. 무게 휴리스틱→RequestWeight
- [x] 8-3. RequestWeight→필요 ModelBurden 매핑
- [x] 8-4. 권한 상한 충돌 시 다운그레이드(1단계)/거절(2단계+)
- [x] 8-5. 순수 함수 단위 테스트
- [x] 8-6. `gradlew build` + 커밋

## K-차수 9 — Provider Pool 필터 파이프라인 (10단계)

- [x] 9-1. 후보 생성(Candidate 추상)
- [x] 9-2. 부담수준 감당·온라인·idle 필터
- [x] 9-3. 요청자/채널 허용 필터
- [x] 9-4. 일일 한도·동시 한도 필터
- [x] 9-5. 과다처리 쿨다운·요청 크기·실패율 필터
- [x] 9-6. RESTRICTED 특수 필터(burden+role 결합)
- [x] 9-7. 후보 0/권한부족 신호(FilterSignal)
- [x] 9-8. 단계별 사유 기록(dropped map)
- [x] 9-9. 단위 테스트(각 단계)
- [x] 9-10. `gradlew build` + 커밋

## K-차수 10 — 공정성 점수 & Router

- [x] 10-1. provider_score(적합도+idle+잔여 − 실패율 − 부하 − heavy낭비 − 최근처리)
- [x] 10-2. light→light·수준 일치 보너스, heavy 낭비 패널티
- [x] 10-3. 최근 과다처리 감점(공정성)
- [x] 10-4. 동점 분산(최근처리량↑ 적은 순)·최종 선택+사유
- [x] 10-5. 순수 함수 단위 테스트
- [x] 10-6. `gradlew build` + 커밋

## K-차수 11 — 요청 상태머신·큐·타임아웃·fallback

- [x] 11-1. RequestState 결과(completed/failed/rejected) — OrchestrationResult
- [x] 11-2. 후보→선택→전송(session.sendInfer)
- [x] 11-3. 타임아웃→실패(세션 orTimeout)+결과 반영
- [x] 11-4. 동일 조건 다른 provider 1회 fallback
- [x] 11-5. fallback 실패 안내·실패 provider 일시 제외(excluded)
- [x] 11-6. 사용량/기여 기록 트리거(UsageRecorder/UsageService)
- [x] 11-7. 단위/통합 테스트(EchoConnection 오케스트레이션)
- [x] 11-8. `gradlew build` + 커밋

## K-차수 12 — 프로바이더 보호 (수동/자동)

- [x] 12-1. pause/resume/leave(ProviderProtectionService); limit 은 contribution policy(K-6)
- [x] 12-2. 배터리→PAUSED·고부하→LIMITED 자동 보호(provider_status)
- [x] 12-3. 동시·시간·길이 제한 강제(세션 세마포어/orTimeout/MAX_PROMPT)
- [x] 12-4. 반복 실패(3연속)→UNHEALTHY 자동 비활성화
- [x] 12-5. 단위 테스트
- [x] 12-6. `gradlew build` + 커밋

## K-차수 13 — Discord (JDA) 슬래시 명령

- [x] 13-1. JDA 부팅(createLight·토큰)·슬래시 명령 등록(enabled 시)
- [x] 13-2. 유저: /ask·/models·/my-usage·/privacy
- [x] 13-3. 관리자: /llm-allow-channel·/llm-deny-channel·/llm-role-policy·/providers·/provider-approve·/provider-remove
- [x] 13-4. 프로바이더: /provider-join·/leave·/pause·/resume·/status (/models·/limit·/scope 는 contribution policy 연동 후속)
- [x] 13-5. 권한 가드(isAdmin)·ephemeral·임베드(텍스트)
- [x] 13-6. /ask→오케스트레이터 라우팅→응답 통합
- [x] 13-7. 통합 테스트(@SpringBootTest CommandService, JDA 비활성)
- [x] 13-8. `gradlew build` + 커밋

## K-차수 14 — 프라이버시 모드 & 사용량/기여 기록

- [x] 14-1. PrivacyMode A/B/C 출력·관리자만 provider 식별
- [x] 14-2. 공유 모드 고지(민감정보 금지, /privacy + ask 안내)
- [x] 14-3. usage/contribution 집계·/my-usage·/providers 연동
- [x] 14-4. 공정성 리포트(/providers provider별 기여량)
- [x] 14-5. 단위 테스트(PrivacyService A/B/C)
- [x] 14-6. `gradlew build` + 커밋

## K-차수 15 — 보안 하드닝

- [x] 15-1. 임의 URL/shell/파일 금지·outbound only(설계 속성, SECURITY 문서)
- [x] 15-2. 일회용 토큰·해시·만료(TokenService)
- [x] 15-3. 프레임 화이트리스트·크기 상한·rate limit(RateLimiter)
- [x] 15-4. provider 간 격리(providerId 키)·권한 상승 방지(정책/가드)
- [x] 15-5. 로그 마스킹(토큰 toString)·프롬프트 미기록
- [x] 15-6. SECURITY.md 작성·보안 테스트(rate limit/토큰/프레임)
- [x] 15-7. `gradlew build` + 커밋

## K-차수 16 — 운영 & 마무리

- [x] 16-1. Actuator 헬스(PoolHealthIndicator — 활성 연결 수)
- [x] 16-2. Dockerfile(JVM 21 멀티스테이지)
- [x] 16-3. CI 잡(.github/workflows/central-server-ci.yml, JDK 21)
- [x] 16-4. README(기존)·DEMO 운영 가이드
- [x] 16-5. 데모 시나리오(다중 provider, docs/DEMO.md)
- [x] 16-6. 전체 통합 점검 + 커밋

## 후속 개선 — 수정형 점진 답변 UX(Discord pseudo-streaming)

> 목표: 긴 답변도 사용자가 기다리는 느낌을 줄이기 위해, Discord의 네이티브 스트리밍이 아니라
> **하나의 답변 메시지를 주기적으로 수정(edit)하는 방식**으로 진행 상황을 보여준다.
> Discord API 한계와 rate limit 때문에 토큰마다 수정하지 않고, 2~3초 단위의 안전한 배치 업데이트를 기본값으로 한다.
>
> 체크리스트 규모: **100개는 과하다.** 구현 안정성·운영 안전성·테스트 범위를 합쳐 **36개 내외**면 충분하다.
> 더 잘게 쪼개야 할 때는 이 섹션을 유지하고 구현 PR에서 하위 태스크로 분리한다.

### 사용자 경험·제품 정책

- [ ] PS-01. 점진 표시 적용 조건 정의: 긴 답변 예상, provider 가 chunk 지원, Discord 채널 응답 가능 상태일 때만 사용
- [ ] PS-02. 사용자에게 처음 보낼 placeholder 문구 정의: “답변을 만들고 있어요…”처럼 불안하지 않은 톤
- [ ] PS-03. 상태 문구 정의: 대기 중, provider 연결 중, 답변 작성 중, 마무리 중, 실패/재시도
- [ ] PS-04. 짧은 답변은 기존처럼 최종 답변 1회만 전송하는 기준 정의
- [ ] PS-05. `/질문`, mention 질문, 모달 질문, 컨텍스트 메뉴 질문에서 같은 UX 원칙 적용
- [ ] PS-06. AI 채널 프로필(webhook) 응답과 일반 봇 reply 응답의 차이를 문서화
- [ ] PS-07. 사용자가 중간 메시지를 보더라도 “미완성 답변”임을 자연스럽게 알 수 있는 꼬리표 정의
- [ ] PS-08. 최종 답변에서는 중간 진행 꼬리표와 불필요한 provider 메타 문구를 제거

### Discord 메시지 수정 전략

- [ ] PS-09. Discord edit 주기 기본값을 2~3초로 제한하고 설정값으로 분리
- [ ] PS-10. 누적 텍스트가 너무 짧을 때는 edit 하지 않는 최소 증가량 기준 정의
- [ ] PS-11. Discord 2,000자 제한 직전에는 다음 메시지로 분리하는 규칙 구현
- [ ] PS-12. Markdown 코드블록이 중간에 깨지지 않도록 임시 닫힘 처리 또는 plain fallback 구현
- [ ] PS-13. 한글/이모지/서로게이트 페어가 잘리지 않도록 UTF-8/Unicode 안전 slice 적용
- [ ] PS-14. rate limit 발생 시 edit 간격을 자동으로 늘리고 최종 답변은 보장
- [ ] PS-15. 메시지 삭제/권한 상실/채널 삭제 시 조용히 중단하고 요청 상태를 실패로 기록
- [ ] PS-16. webhook 메시지 수정이 불가능한 경로가 있으면 bot reply fallback 으로 전환

### Provider Agent ↔ Central Server 프로토콜

- [ ] PS-17. provider-agent Ollama streaming 호출을 운영 경로에 연결하고, 기존 non-streaming 경로는 유지
- [ ] PS-18. 중앙 서버가 `chunk` 프레임을 requestId 별 accumulator 로 모으도록 구현
- [ ] PS-19. `chunk` 순서 보장/중복 방어 기준을 정하고 테스트
- [ ] PS-20. provider 가 chunk 를 보내다가 최종 `result` 를 보내는 정상 종료 계약 확정
- [ ] PS-21. provider 가 chunk 지원을 capability 로 보고하고, 미지원이면 기존 최종 응답 방식으로 fallback
- [ ] PS-22. provider 연결이 중간에 끊기면 마지막 중간 답변을 실패 안내로 수정
- [ ] PS-23. 요청 취소 시 중앙 서버가 provider 에 cancel 을 보내고 Discord 진행 메시지를 취소 상태로 수정
- [ ] PS-24. chunk 프레임 크기·누적 크기 상한을 기존 프레임 제한과 일관되게 적용

### 안정성·관측성·운영

- [ ] PS-25. 요청당 최초 표시 시간(TTFT), 총 응답 시간, chunk 수, edit 수 메트릭 추가
- [ ] PS-26. requestId/traceId 기준으로 chunk 수신·Discord edit·최종 완료 로그를 연결
- [ ] PS-27. provider 별 streaming 실패율을 기록해 반복 실패 provider 를 보호/제외 정책에 반영
- [ ] PS-28. 과도한 edit 로 Discord API 제한에 걸리지 않도록 guild/channel 단위 throttle 적용
- [ ] PS-29. 운영 설정 플래그 추가: streaming enabled, edit interval, max edits, min delta chars
- [ ] PS-30. 장애 시 즉시 기존 “placeholder + 최종 1회 응답” 방식으로 되돌릴 수 있는 kill switch 제공

### 테스트·검증·출시

- [ ] PS-31. accumulator 단위 테스트: chunk 누적, 중복, 순서, 오류, cancel
- [ ] PS-32. Discord 응답 서비스 단위 테스트: edit throttle, 2,000자 분할, markdown 안전 처리
- [ ] PS-33. provider-agent ↔ central-server contract 테스트: chunk/result/error/cancel 호환성
- [ ] PS-34. Cucumber BDD 시나리오 추가: 긴 답변이 중간 업데이트 후 최종 답변으로 마무리된다
- [ ] PS-35. k6 또는 로컬 부하 테스트로 동시 긴 답변에서 edit rate limit 이 안전한지 검증
- [ ] PS-36. 실제 Discord 운영 채널에서 smoke test: slash 질문, mention 질문, provider 끊김, 긴 답변 2,000자 초과

### 완료 기준(Definition of Done)

- [ ] 긴 답변에서 첫 사용자 피드백이 3초 내 표시된다.
- [ ] 최종 답변은 기존 답변 품질·권한·공정성·사용량 기록을 깨뜨리지 않는다.
- [ ] streaming 미지원 agent 와 기존 배포 agent 가 계속 정상 동작한다.
- [ ] Discord rate limit 이 발생해도 사용자는 최종 답변 또는 명확한 실패 안내를 받는다.
- [ ] 운영에서 설정값 하나로 기능을 끄고 기존 응답 방식으로 복귀할 수 있다.

## 후속 개선 — 품질 기반 라우팅과 Provider 보호 정책

> 정책 SSOT: [Provider Pool 민감 정책 — 품질 향상과 Provider PC 보호](./PROVIDER_SAFETY_POLICY.md)
>
> 목표: 사용자 수가 늘어날수록 “더 큰 하나의 AI”를 만드는 것이 아니라,
> 더 적합한 Provider 를 고르고 실패에 강해지는 방식으로 답변 품질을 높인다.
> 단, 어떤 품질 개선도 Provider 의 동의·한도·가용성·보호 정책보다 우선할 수 없다.

### 품질 향상 기획

- [ ] QP-01. Provider capability 에 모델 크기/전문 분야/컨텍스트 길이/한국어 품질 신호를 추가한다.
- [ ] QP-02. 질문 유형 분류를 추가한다: 일반, 코딩, 요약, 긴 글, 한국어, 빠른 답변, 고난도.
- [ ] QP-03. 질문 유형과 Provider capability 를 매칭하는 routing score 를 설계한다.
- [ ] QP-04. Provider 품질 점수를 정의한다: 성공률, 지연, 실패율, 사용자 피드백, 모델 적합도.
- [ ] QP-05. 품질 점수가 높아도 최근 처리량/부하/휴식 시간을 함께 반영해 몰림을 막는다.
- [ ] QP-06. 고난도 질문에서만 선택적 다중 응답/비교 모드를 실험한다.
- [ ] QP-07. 다중 응답 fan-out 은 기본 off, 초기 최대 2명, 정책 허용 채널/역할에서만 사용한다.
- [ ] QP-08. 다중 응답 결과를 최종 답변으로 고르거나 합성하는 기준을 별도 실험으로 분리한다.
- [ ] QP-09. streaming/pseudo-streaming 과 다중 응답을 동시에 켤 때 edit 폭증을 막는 throttle 을 둔다.
- [ ] QP-10. 품질 기능은 기존 non-streaming/single-provider 경로로 즉시 rollback 가능해야 한다.

### Provider 과부하·DDoS화 방지

- [ ] QP-11. 기본 질문은 Provider 1명에게만 보내고, fallback 은 기본 1회로 제한한다.
- [ ] QP-12. 사용자/채널/길드 단위 rate limit 을 품질 라우팅보다 먼저 적용한다.
- [ ] QP-13. Provider 별 동시 요청·일일 요청·heavy 요청 한도를 강제한다.
- [ ] QP-14. 긴 프롬프트·첨부·다중 응답은 더 높은 비용으로 계산한다.
- [ ] QP-15. 반복 실패 요청은 fallback 확산을 막고 즉시 실패 안내로 종료한다.
- [ ] QP-16. 동일 Provider 에게 고난도 요청이 연속 배정되지 않도록 cooldown 을 둔다.
- [ ] QP-17. Provider 의 `/수신정지`, 에이전트 종료, 가용 시간, 배터리/부하 보호를 최우선 조건으로 둔다.
- [ ] QP-18. 악성 반복 질문/mention spam/긴 프롬프트 abuse 를 감지하고 사용자·채널 차단 정책과 연결한다.
- [ ] QP-19. Provider 별 요청 몰림, fallback 급증, fan-out 급증, heavy 요청 급증에 운영 알림을 건다.
- [ ] QP-20. kill switch 로 품질 라우팅·다중 응답·pseudo-streaming 을 각각 독립적으로 끌 수 있게 한다.

### 완료 기준(Definition of Done)

- [ ] 사용자 수가 늘어나도 Provider 1명에게 부하가 몰리지 않는다.
- [ ] 품질 개선 기능을 꺼도 기존 단일 Provider 라우팅은 정상 동작한다.
- [ ] Provider 가 설정한 한도와 일시정지는 어떤 라우팅 점수보다 우선한다.
- [ ] 다중 응답/fan-out 은 명시적으로 허용된 상황에서만 제한적으로 작동한다.
- [ ] 운영자가 Provider 과부하 징후를 지표와 알림으로 확인할 수 있다.

## 후속 개선 — 함께 만드는 AI 네트워크 제품 방향

> 제품 슬로건: **냥시스턴트는 함께 만드는 AI 네트워크입니다.**
>
> 외부 설명: 냥시스턴트는 여러 사용자의 로컬 AI들을 안전하게 연결해,
> 디스코드에서 바로 질문하고 답변받을 수 있게 도와주는 함께 만드는 AI 네트워크다.
>
> 포지셔닝: “즉시 사용할 수 있는 AI”와 “유저들이 함께 구축해나가는 AI”를 동시에 제공한다.
> 내부 아키텍처 용어는 Provider Pool 을 유지하되, 사용자에게는 AI 네트워크로 설명한다.

### 제품 언어·브랜드 정리

- [ ] AN-01. 외부 카피에서 “커뮤니티 로컬 AI Provider Pool”을 “함께 만드는 AI 네트워크”로 전환한다.
- [ ] AN-02. 기술 문서와 코드 도메인에서는 Provider/Provider Pool 용어를 유지한다.
- [ ] AN-03. 사용자-facing 문구에서 “커뮤니티” 남용을 줄이고 “함께 만드는”, “로컬 AI”, “AI 네트워크”를 기본 표현으로 쓴다.
- [ ] AN-04. 봇 소개, 도움말, 메뉴, Provider 참여 안내, 민감정보 안내의 대표 문구를 새 포지셔닝으로 통일한다.
- [ ] AN-05. “여러 PC 를 합쳐 큰 AI 를 만든다”는 오해를 막는 안전 문구를 모든 핵심 안내에 포함한다.

### AI 네트워크다운 핵심 기능

- [ ] AN-06. 네트워크 상태 패널: 온라인 Provider 수, 사용 가능 모델 수준, 평균 응답 시간, 현재 혼잡도를 보여준다.
- [ ] AN-07. AI 라우팅 설명: 이 질문이 왜 이 Provider/모델 수준으로 배정됐는지 사용자 친화적으로 설명한다.
- [ ] AN-08. 모델 지도: 서버 안에서 사용 가능한 로컬 AI 모델과 특화 영역을 목록화한다.
- [ ] AN-09. 질문 유형별 자동 라우팅: 코딩, 요약, 창작, 번역, 긴 답변, 빠른 답변을 구분한다.
- [ ] AN-10. 품질 피드백: 답변별 좋아요/별로예요/신고를 받아 Provider 품질 점수와 라우팅에 반영한다.
- [ ] AN-11. 신뢰 점수: Provider 별 성공률, 응답 속도, 최근 안정성, 과부하 상태를 내부 점수로 관리한다.
- [ ] AN-12. 네트워크 성장 경험: 사용자가 Provider 로 참여하면 네트워크가 어떻게 좋아지는지 시각적으로 보여준다.
- [ ] AN-13. 즉시 사용 모드: Provider 지식 없이도 `/메뉴` → 질문하기로 바로 AI 를 사용할 수 있게 유지한다.
- [ ] AN-14. 함께 구축 모드: 내 컴퓨터의 AI 를 연결해 네트워크 품질과 가용성을 높이는 흐름을 제공한다.
- [ ] AN-15. 안전한 고품질 모드: 다중 응답/비교/합성은 명시적으로 선택한 경우에만 제한적으로 제공한다.

### 깊은 AI 커스터마이징

- [ ] AN-16. 서버 기본 AI 프로필: 말투, 답변 길이, 존댓말/반말, 금지 주제, 안전 문구를 DB 에 저장한다.
- [ ] AN-17. 채널별 AI 프로필: 채널마다 다른 이름, 아이콘, 말투, 용도, 허용 모델 수준을 설정한다.
- [ ] AN-18. 사용자 개인 선호: 답변 길이, 문체, 언어, 코드 설명 수준, 요약 선호를 저장한다.
- [ ] AN-19. 프롬프트 템플릿: 서버/채널/명령별 system prompt 와 response template 을 관리한다.
- [ ] AN-20. AI 역할 프리셋: 도우미, 코딩 튜터, 번역가, 요약가, 회의록 도우미 같은 프리셋을 제공한다.
- [ ] AN-21. 모델 선택 정책: 자동, 빠른 답변 우선, 품질 우선, 가벼운 모델 우선, 특정 모델 고정 옵션을 둔다.
- [ ] AN-22. 답변 포맷 커스터마이징: bullet, 짧게, 자세히, 단계별, 표, 코드블록 우선 같은 출력 형식을 저장한다.
- [ ] AN-23. 안전/민감정보 정책 커스터마이징: 서버별 민감정보 경고 강도와 차단 문구를 설정한다.
- [ ] AN-24. Provider 참여 범위 커스터마이징: Provider 가 허용할 요청 유형, 모델 수준, 시간대, 일일 한도를 세밀하게 정한다.
- [ ] AN-25. 커스터마이징 미리보기: 설정 변경 전에 Discord embed/응답 예시를 미리 보여준다.

### DB 연동·데이터 모델 초안

- [ ] AN-26. `ai_network_profile`: guild 기본 슬로건, 소개 문구, 공개 안전 문구, 기본 프로필 참조를 저장한다.
- [ ] AN-27. `ai_persona`: 서버/채널/사용자 단위 persona, tone, language, answer_style, system_prompt_ref 를 저장한다.
- [ ] AN-28. `ai_prompt_template`: 템플릿 이름, scope, body, version, created_by, enabled 를 저장한다.
- [ ] AN-29. `ai_channel_profile`: channel_id 별 표시 이름, 아이콘, persona, allowed_burden, routing_policy 를 저장한다.
- [ ] AN-30. `ai_user_preference`: user_id 별 답변 길이, 언어, 포맷, 기본 모드를 저장한다.
- [ ] AN-31. `provider_capability_profile`: 모델 목록, 컨텍스트 길이, 특화 태그, 품질 신호, 최근 상태를 저장한다.
- [ ] AN-32. `ai_feedback`: request_id 별 사용자 평가, 신고, 품질 메모를 저장하되 프롬프트 원문 저장은 피한다.
- [ ] AN-33. `routing_policy`: 서버/채널별 자동/품질/속도/안전 우선 정책과 fan-out 허용 여부를 저장한다.
- [ ] AN-34. `customization_audit_log`: AI 설정 변경 이력과 변경자를 남긴다.
- [ ] AN-35. Flyway 마이그레이션과 Repository 테스트로 모든 설정의 기본값/스코프 우선순위를 검증한다.

### 우선순위

- [ ] AN-36. 1차: 외부 문구 통일, 봇 소개/메뉴/도움말 카피 변경.
- [ ] AN-37. 2차: 서버/채널 AI 프로필 DB 저장과 웹훅 프로필 연동.
- [ ] AN-38. 3차: 사용자 선호와 프롬프트 템플릿 관리.
- [ ] AN-39. 4차: 품질 피드백과 모델/Provider capability 기반 라우팅.
- [ ] AN-40. 5차: 안전한 고품질 모드(다중 응답/비교/합성) 실험.

### 완료 기준(Definition of Done)

- [ ] 사용자는 냥시스턴트를 “함께 만드는 AI 네트워크”로 이해한다.
- [ ] 신규 사용자는 설정 없이 즉시 질문할 수 있다.
- [ ] 참여자는 내 로컬 AI 가 네트워크에 어떻게 기여하는지 이해하고 통제할 수 있다.
- [ ] 서버 관리자는 DB 에 저장되는 AI 프로필/말투/정책을 채널별로 커스터마이징할 수 있다.
- [ ] 모든 커스터마이징은 Provider 보호 정책과 민감정보 정책을 우회하지 않는다.
