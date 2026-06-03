# 감사 2026-06-03 — DB 성능·인덱스·클린 아키텍처·정확성

4개 차원 병렬 심층 감사. 근거(file:line) 있는 **실제 결함 ~75건**. "정확히 500개"는 패딩이라
지양하고 진짜 결함만 심각도순으로. ✅ = 이번에 수정(V28), ⬜ = 백로그.

## A. DB 스키마·인덱스 (Entities/Repositories/migration)
누락 인덱스로 파생 쿼리가 풀스캔하던 것들 — **V28__query_indexes_audit.sql 로 수정 ✅**:
- ✅ ai_request `(provider_id,state)`, `(guild_id,id DESC)` — findByProviderIdAndState / count·top20
- ✅ usage_log `(guild,user,created)` + `(guild,created)`, 중복 `idx_usage_guild_user` 제거 — 일일한도 범위쿼리
- ✅ provider `(provider_user_id,guild_id)` — 프로바이더 식별 핵심조회 무인덱스였음
- ✅ ai_feedback `(guild,request_id,user_id)`, multi_response_run `(guild,started_at)`,
  preset_report `(published_preset_id,reporter_user_id,status)`, ai_network_event `(guild,event_type,created)`
- ✅ provider_schedule `(guild_id)`, ai_change_proposal `(guild,status,created)`·`(channel_ai_id)`,
  knowledge_source `(guild_id)`, knowledge_chunk `(guild,space,status)`, embedding_index_job `(guild,queued_at)`
- ✅ 중복(UNIQUE 와 동일 컬럼셋) 비-unique 인덱스 5개 제거(provider_durable_revocation/ai_admin_role/
  channel_ai_profile/channel_ai/ai_network_profile)
- ⬜ 컬럼 길이: constitution VARCHAR(2000)·각종 *_reason VARCHAR(500) 가 사용자/에러 텍스트 truncation 위험 → TEXT 고려
- ⬜ UNIQUE 제약 누락: provider(provider_user_id,guild_id)·retrieval_policy·multi_response_policy 는
  코드가 단일행 가정하나 DB 미보장(NonUniqueResultException 위험). (기존 데이터 중복 가능성 때문에 V28 에선 비-unique 로만)
- ⬜ contribution_log.guild_id 의 `DEFAULT 0` 백필 후 미제거(잠재 무결성 가림)

## B. 쿼리/N+1/성능 (핫패스)
- ⬜ **HIGH** `/ask` 핫패스: 후보 프로바이더마다 `profiles.profile`(DbProviderProfileProvider)·
  `providerSafety.isRoutingProtected` 쿼리 → 시도당 2*P SELECT. 길드 후보 일괄로드/캐시로 접기.
- ⬜ **HIGH** JDA 게이트웨이 스레드가 추론 종료까지 `.get()` 블로킹(DiscordBot/CommandService→RequestOrchestrator)
  → 동시 /ask 에 봇 전체 stall. 전용 executor 로 오프로드.
- ⬜ **HIGH** KnowledgeSearchService: `findByGuildId` 전체 로드 후 메모리 필터/substring 스코어 + scope/policy
  3중 중복쿼리. WHERE 로 내리고 1회 계산 후 전달.
- ⬜ ChannelAiRoutingPolicyService.providerFeedbackSignals N+1(피드백 행마다 findByRequestId) → IN 일괄.
- ⬜ Analytics/PresetRegistry.moderationSummary 가 findAll()·findByProviderIdAndState 무제한 로드 후 메모리 집계 → SQL 집계.
- ⬜ PolicyService/QuotaService 가 /ask 마다 roles.findByGuildId 2~3회 + 무캐시. 길드정책 캐시(짧은 TTL)·1회 로드.
- ⬜ 다수 read 메서드 `@Transactional(readOnly)` 누락.

## C. 클린 아키텍처·도메인
- ⬜ **HIGH** 프로바이더 등록(ProviderRegistrationService) + 블록리스트(BlocklistService)가 **인메모리가
  유일 저장소** → 재시작 시 등록/승인큐/차단 전부 소실. ProviderEntity 테이블은 있는데 **읽기/쓰기 코드 없음(데드 스키마)**. 영속화 필요.
- ⬜ **HIGH** ProviderState.canTransitionTo 가드가 relay 경로에만 적용, 등록 경로(`rec.state = ...`)는 raw 대입 → 불법전이 가능.
- ⬜ **HIGH** 대시보드 컨트롤러가 리포지토리 직접 주입/호출(AiNetworkDashboardController ~11개, DashboardController),
  엔티티를 web 에서 매핑 → ArchUnit 규칙 없음. 규칙 추가(controller↛persistence, entity↛web, service in web) 권장.
- ⬜ 빈약 도메인(모든 엔티티가 var 데이터백·상태가 bare String). God class: CommandService 1617·DiscordBot 1629·
  PresetRegistryService 1612·MultiResponseService 1460·AiNetworkDashboardController 1466.
- ⬜ get-then-put 비원자 갱신(ProviderRegistrationService) → compute/merge. legacy 단일인자 approve/remove 오버로드 모호.
- ⬜ ChannelAi 이중 소스(ChannelAiProfileEntity legacy ↔ ChannelAiEntity).

## D. 정확성·동시성·보안 (central + provider-agent)
- ⬜ **HIGH** singleton 락 레퍼런스카운트 없음 — acquire/release 경로 불일치(run_agent 미해제, 콜백 자동시작).
- ⬜ **HIGH** agent `_cancelled` 셋·TokenService 페어링토큰 스토어·OAuth state 스토어 무한 증가(스윕 없음).
- ⬜ **HIGH** AiNetworkApiSecurityFilter 가 "로그인만 하면(비익명)" admin 통과 — 길드 소유/권한 미검증.
- ⬜ **MED** ConnectionRegistry.register 의 byProviderGuild/byGuild 갱신 비원자(라우팅이 old+new 동시 관측 가능).
- ⬜ **MED** ProviderSession 스트림/이미지 드레인이 commonPool 블로킹. webui `_start_server_thread` 가 site._server
  private 접근 + runner.cleanup 누락 + 예외 시 KeyError. 일일한도 송신실패 시 remainingDaily 미복구.
- ⬜ **MED** provider-agent index/mascot 무인증 페이지가 세션키를 HTML 에 노출(로컬 한정이나 공유 PC 위험).
- ⬜ **LOW** WebContentFetcher DNS rebinding TOCTOU, ollama JSONDecodeError 미캐치, config 0600 전 짧은 윈도우.

## 비결함(검증됨)
DurableTokenService 무상태 HMAC·DB 폐기 / ProviderScheduleService 영속+트랜잭션 / UsageService GROUP BY 집계 /
ConnectionRegistry 키 조회 O(1) / IMAGE_CHUNK_CHARS vs 1MB 프레임 안전 / SSRF UrlSafety·KnowledgeIngestion 파싱 견고.

## 다음 우선순위(권장)
1. (HIGH·안전) ArchUnit 규칙 추가 + 컨트롤러→서비스 이전 / 2. 등록·블록리스트 영속화 /
3. /ask 핫패스 쿼리 일괄화·길드정책 캐시 + JDA 스레드 오프로드 / 4. 무한증가 스토어 스윕·singleton 레퍼런스카운트 / 5. admin 권한 길드검증.
