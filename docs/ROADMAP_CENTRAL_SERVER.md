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

- [ ] 12-1. pause/resume/leave/limit
- [ ] 12-2. 부하(CPU/GPU)/메모리/배터리/절전/네트워크 자동 보호
- [ ] 12-3. 동시·시간·길이 제한 강제
- [ ] 12-4. 반복 실패→unhealthy 자동 비활성화
- [ ] 12-5. 단위 테스트
- [ ] 12-6. `gradlew build` + 커밋

## K-차수 13 — Discord (JDA) 슬래시 명령

- [ ] 13-1. JDA 부팅(토큰·인텐트)·명령 등록
- [ ] 13-2. 유저: /ask·/models·/my-usage·/privacy
- [ ] 13-3. 관리자: /llm-settings·/llm-allow-channel·/llm-deny-channel·/llm-role-policy·/providers·/provider-approve·/provider-remove
- [ ] 13-4. 프로바이더: /provider-join·/leave·/pause·/resume·/status·/models·/limit·/scope
- [ ] 13-5. 권한 가드·ephemeral·임베드
- [ ] 13-6. /ask→라우팅→응답 통합
- [ ] 13-7. 통합 테스트(JDA mock)
- [ ] 13-8. `gradlew build` + 커밋

## K-차수 14 — 프라이버시 모드 & 사용량/기여 기록

- [ ] 14-1. PrivacyMode A/B/C 출력·관리자 식별 로그
- [ ] 14-2. 공유 모드 고지(민감정보 금지)
- [ ] 14-3. usage/contribution 집계·/my-usage·/providers 연동
- [ ] 14-4. 공정성 리포트
- [ ] 14-5. 단위 테스트
- [ ] 14-6. `gradlew build` + 커밋

## K-차수 15 — 보안 하드닝

- [ ] 15-1. 임의 URL/shell/파일 금지·outbound only 보장
- [ ] 15-2. 일회용 토큰·해시·상수시간·만료
- [ ] 15-3. 프레임 화이트리스트·크기 상한·rate limit
- [ ] 15-4. provider 간 격리·권한 상승 방지
- [ ] 15-5. 로그 마스킹(토큰·프롬프트)
- [ ] 15-6. SECURITY 문서 갱신·보안 테스트
- [ ] 15-7. `gradlew build` + 커밋

## K-차수 16 — 운영 & 마무리

- [ ] 16-1. Actuator 헬스(provider/세션)·메트릭
- [ ] 16-2. Dockerfile(JVM 21)·compose 연동
- [ ] 16-3. CI 잡(central-server gradle build/test) 추가
- [ ] 16-4. README·운영 가이드·롤백 갱신
- [ ] 16-5. 데모 시나리오(다중 provider)
- [ ] 16-6. 전체 통합 점검 + 커밋
