# 코드 성능·UX·로직 복잡도 개선 후보 1000건

작성일: 2026-06-02

## 목적

현재 저장소의 tracked 파일을 대상으로 성능 개선, UX 개선, 로직 복잡도/리팩토링 후보를 대량 식별한 감사 문서다.
이 문서는 **확정 버그 목록이 아니라 후보 backlog**이며, 각 항목은 구현 전에 영향도·재현성·우선순위를 다시 검증해야 한다.

## 분석 범위와 방법

- 대상 파일: git tracked 텍스트/소스 파일 317개
- 총 라인 수: 54,192라인
- 후보 원천 수: Performance 2,579개, UX 1,998개, Refactor 1,994개
- 최종 선정: Performance 333개, UX 333개, Refactor 334개 = 총 1000개
- 제외: build 산출물, 캐시, 가상환경, node_modules, untracked 로컬 바이너리

## 읽는 법

- `P1`: 다음 리팩토링/UX 개선 라운드에서 먼저 검토
- `P2`: 기능 확장 전 정리 권장
- `P3`: 누적되면 비용이 커지는 개선 후보
- `Evidence`는 정적 신호다. 실제 장애/성능 저하를 단정하지 않는다.

## 요약

- Performance: 333건
- UX: 333건
- Refactor: 334건

### 항목이 많이 나온 파일 Top 20

| Rank | File | Count |
|---:|---|---:|
| 1 | `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt` | 289 |
| 2 | `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt` | 220 |
| 3 | `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt` | 120 |
| 4 | `central-server/src/main/resources/static/dashboard/app.js` | 109 |
| 5 | `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt` | 45 |
| 6 | `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt` | 40 |
| 7 | `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt` | 35 |
| 8 | `docs/BOT_PERMISSIONS.md` | 27 |
| 9 | `docs/UX_ERROR_HANDLING_90.md` | 25 |
| 10 | `central-server/src/main/resources/static/presets/app.js` | 22 |
| 11 | `docs/EDGE_CASE_POLICY.md` | 12 |
| 12 | `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt` | 11 |
| 13 | `central-server/src/main/resources/static/dashboard/index.html` | 10 |
| 14 | `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt` | 7 |
| 15 | `provider-agent/src/provider_agent/agent.py` | 6 |
| 16 | `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandLoc.kt` | 4 |
| 17 | `provider-agent/src/provider_agent/config.py` | 4 |
| 18 | `central-server/src/main/kotlin/com/discordassistant/central/discord/MenuFactory.kt` | 2 |
| 19 | `central-server/src/main/resources/static/install.html` | 2 |
| 20 | `central-server/src/main/resources/static/presets/index.html` | 2 |

## Performance

### IMP-0001 [Performance/P2] 핫패스 비용 계측 필요

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `large-runtime`
- Evidence: 런타임/스크립트 파일 1466라인
- Recommendation: Micrometer/로그 타이머/benchmark로 요청당 DB 호출 수·외부 호출 수·렌더 시간을 측정

### IMP-0002 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:73`
- Signal: `collection-pipeline`
- Evidence: `val publishedPresets = publishedPresets().take(10)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0003 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:94`
- Signal: `collection-pipeline`
- Evidence: `val growthTimeline = growth.timelineCards(guildId).take(5)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0004 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:127`
- Signal: `collection-pipeline`
- Evidence: `dashboard.readiness.areas.map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0005 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:203`
- Signal: `io-bound`
- Evidence: `key = "change_approval_queue",`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0006 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:218`
- Signal: `collection-pipeline`
- Evidence: `"riskCodes=${dashboard.multiResponseOperations.riskCodes.joinToString(",")}",`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0007 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:243`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.status != "ready" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0008 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:244`
- Signal: `collection-pipeline`
- Evidence: `.take(8)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0009 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:245`
- Signal: `collection-pipeline`
- Evidence: `.map { it.nextAction },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0010 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:300`
- Signal: `db-access`
- Evidence: `return channelAis.findByGuildId(guildId).map { channelAi ->`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0011 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:300`
- Signal: `collection-pipeline`
- Evidence: `return channelAis.findByGuildId(guildId).map { channelAi ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0012 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:301`
- Signal: `db-access`
- Evidence: `val behavior = channelAi.activeBehaviorVersionId?.let { behaviorVersions.findByChannelAiIdAndId(channelAi.id, it) }`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0013 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:302`
- Signal: `db-access`
- Evidence: `val route = routingPolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0014 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:303`
- Signal: `db-access`
- Evidence: `val spaces = knowledgeSpaces.findByGuildIdAndChannelId(guildId, channelAi.channelId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0015 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:307`
- Signal: `db-access`
- Evidence: `.findByKnowledgeSpaceId(space.id)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0016 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:313`
- Signal: `db-access`
- Evidence: `.findByKnowledgeSpaceId(space.id)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0017 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:328`
- Signal: `db-access`
- Evidence: `multiResponsePolicies.findByGuildIdAndChannelId(guildId, channelAi.channelId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0018 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:329`
- Signal: `db-access`
- Evidence: `?: multiResponsePolicies.findByGuildIdAndChannelIdIsNull(guildId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0019 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:366`
- Signal: `collection-pipeline`
- Evidence: `val channelsNeedingAttention = channels.filter { it.readinessStatus != "ready" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0020 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:378`
- Signal: `collection-pipeline`
- Evidence: `.sortedWith(`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0021 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:381`
- Signal: `collection-pipeline`
- Evidence: `).take(10)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0022 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:382`
- Signal: `collection-pipeline`
- Evidence: `.map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0023 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:399`
- Signal: `db-access`
- Evidence: `val all = proposals.findByGuildIdOrderByCreatedAtDesc(guildId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0024 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:400`
- Signal: `collection-pipeline`
- Evidence: `val pending = all.filter { it.status == "pending" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0025 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:401`
- Signal: `collection-pipeline`
- Evidence: `val stale = all.filter { it.status == "stale" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0026 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:402`
- Signal: `collection-pipeline`
- Evidence: `val rejected = all.filter { it.status == "rejected" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0027 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:417`
- Signal: `collection-pipeline`
- Evidence: `pendingItems = pending.take(10).map { ChannelAiChangeApprovalItemResponse.from(it) },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0028 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:435`
- Signal: `db-access`
- Evidence: `return providerCapabilities.findByGuildId(guildId).mapIndexed { index, provider ->`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0029 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:460`
- Signal: `db-access`
- Evidence: `.findByGuildId(guildId)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0030 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:461`
- Signal: `collection-pipeline`
- Evidence: `.flatMap { provider ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0031 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:462`
- Signal: `collection-pipeline`
- Evidence: `splitCsv(provider.modelNames).map { modelName ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0032 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:472`
- Signal: `collection-pipeline`
- Evidence: `}.groupBy { it.modelName }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0033 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:473`
- Signal: `collection-pipeline`
- Evidence: `.map { (modelName, providers) ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0034 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:479`
- Signal: `collection-pipeline`
- Evidence: `qualityTiers = providers.map { it.qualityTier }.distinct().sortedByDescending { qualityRank(it) },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0035 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:480`
- Signal: `collection-pipeline`
- Evidence: `maxBurdens = providers.map { it.maxBurden }.distinct().sortedByDescending { burdenRank(it) },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0036 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:481`
- Signal: `collection-pipeline`
- Evidence: `tags = providers.flatMap { it.tags }.distinct().sorted(),`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0037 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:483`
- Signal: `collection-pipeline`
- Evidence: `channels = modelToChannels[modelName].orEmpty().sorted(),`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0038 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:485`
- Signal: `collection-pipeline`
- Evidence: `}.sortedWith(`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0039 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:497`
- Signal: `db-access`
- Evidence: `return knowledgeSpaces.findByGuildId(guildId).map {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0040 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:497`
- Signal: `collection-pipeline`
- Evidence: `return knowledgeSpaces.findByGuildId(guildId).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0041 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:521`
- Signal: `db-access`
- Evidence: `presets.findByGuildId(guildId).map {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0042 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:521`
- Signal: `collection-pipeline`
- Evidence: `presets.findByGuildId(guildId).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0043 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:533`
- Signal: `db-access`
- Evidence: `presetImports.findByTargetGuildId(guildId).map {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0044 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:533`
- Signal: `collection-pipeline`
- Evidence: `presetImports.findByTargetGuildId(guildId).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0045 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:548`
- Signal: `db-access`
- Evidence: `return publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").map {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0046 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:548`
- Signal: `collection-pipeline`
- Evidence: `return publishedPresets.findByStatusOrderByLikeCountDescPublishedAtDesc("published").map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0047 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:765`
- Signal: `collection-pipeline`
- Evidence: `val overallScore = areas.map { it.score }.average().toInt()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0048 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:782`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.status != "ready" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0049 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:783`
- Signal: `collection-pipeline`
- Evidence: `.sortedBy { it.score }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0050 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:784`
- Signal: `collection-pipeline`
- Evidence: `.take(5)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0051 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:785`
- Signal: `collection-pipeline`
- Evidence: `.map { it.nextAction },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0052 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:961`
- Signal: `collection-pipeline`
- Evidence: `val existingActionTypes = map { it.actionType }.toSet()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0053 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:963`
- Signal: `collection-pipeline`
- Evidence: `.filterNot { growthActionCoveredByPrimaryAction(it.key, existingActionTypes) }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0054 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:964`
- Signal: `collection-pipeline`
- Evidence: `.take(3)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0055 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:993`
- Signal: `collection-pipeline`
- Evidence: `}.sortedBy { it.priority }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0056 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1027`
- Signal: `collection-pipeline`
- Evidence: `.map { part ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0057 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1034`
- Signal: `collection-pipeline`
- Evidence: `}.distinct()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0058 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1053`
- Signal: `db-access`
- Evidence: `routingPolicies.findByGuildId(guildId).forEach { policy ->`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0059 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1056`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.isNotBlank() }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0060 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1057`
- Signal: `collection-pipeline`
- Evidence: `.distinct()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0061 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1084`
- Signal: `collection-pipeline`
- Evidence: `.map { it.trim() }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0062 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1085`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.isNotBlank() }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0063 [Performance/P2] 핫패스 비용 계측 필요

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:1`
- Signal: `large-runtime`
- Evidence: 런타임/스크립트 파일 491라인
- Recommendation: Micrometer/로그 타이머/benchmark로 요청당 DB 호출 수·외부 호출 수·렌더 시간을 측정

### IMP-0064 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:88`
- Signal: `db-access`
- Evidence: `selectedCandidateIds = request.selectedCandidateIds,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0065 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:113`
- Signal: `db-access`
- Evidence: `"selectedCandidateId" to adoption.run.selectedCandidateId,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0066 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:129`
- Signal: `db-access`
- Evidence: `"selectedCandidateId" to completion.run.selectedCandidateId,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0067 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:153`
- Signal: `polling`
- Evidence: `"editIntervalMs" to plan.editIntervalMs,`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0068 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:163`
- Signal: `collection-pipeline`
- Evidence: `service.listRecent(guildId).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0069 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:219`
- Signal: `db-access`
- Evidence: `"selectedCandidateIds" to it.selectedCandidateIds,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0070 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:330`
- Signal: `collection-pipeline`
- Evidence: `): String = providerUserId?.let { "Provider ${kotlin.math.abs(it.hashCode()).toString(36).take(6)}" } ?: "Provider ${index + 1}"`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0071 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:362`
- Signal: `db-access`
- Evidence: `val selectedCandidateIds: List<Long> = emptyList(),`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0072 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:477`
- Signal: `collection-pipeline`
- Evidence: `.take(6)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0073 [Performance/P2] 핫패스 비용 계측 필요

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:1`
- Signal: `large-runtime`
- Evidence: 런타임/스크립트 파일 386라인
- Recommendation: Micrometer/로그 타이머/benchmark로 요청당 DB 호출 수·외부 호출 수·렌더 시간을 측정

### IMP-0074 [Performance/P2] 핫패스 비용 계측 필요

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `large-runtime`
- Evidence: 런타임/스크립트 파일 1575라인
- Recommendation: Micrometer/로그 타이머/benchmark로 요청당 DB 호출 수·외부 호출 수·렌더 시간을 측정

### IMP-0075 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:41`
- Signal: `polling`
- Evidence: `val editIntervalMs: Long,`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0076 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:76`
- Signal: `polling`
- Evidence: `private val schedule: com.discordassistant.central.provider.ProviderScheduleService,`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0077 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:176`
- Signal: `db-access`
- Evidence: `if (modelChoice.selectedModel == null && modelChoice.requiresAvailableModel) {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0078 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:182`
- Signal: `db-access`
- Evidence: `val selectedModel =`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0079 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:183`
- Signal: `db-access`
- Evidence: `modelChoice.selectedModel`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0080 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:200`
- Signal: `db-access`
- Evidence: `preferredModel = selectedModel,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0081 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:208`
- Signal: `db-access`
- Evidence: `modelName = selectedModel,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0082 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:238`
- Signal: `collection-pipeline`
- Evidence: `val rawSnapshots = plan?.snapshots?.map { it.content }.orEmpty()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0083 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:245`
- Signal: `polling`
- Evidence: `?.let { ReplyPseudoStream(plan!!.editIntervalMs.toLong(), it.dropLast(1) + finalContent, plan.warning) }`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0084 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:343`
- Signal: `db-access`
- Evidence: `val selected = modelChoice.selectedModel ?: "자동 선택"`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0085 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:344`
- Signal: `db-access`
- Evidence: `return "$this\n\n↪️ 모델 대체: ${modelChoice.explanation} `사용 모델: $selected`"`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0086 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:430`
- Signal: `collection-pipeline`
- Evidence: `.flatMap { it.capability.models }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0087 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:431`
- Signal: `collection-pipeline`
- Evidence: `.distinct()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0088 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:432`
- Signal: `collection-pipeline`
- Evidence: `.sorted()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0089 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:433`
- Signal: `collection-pipeline`
- Evidence: `.take(25) // Discord 자동완성 최대 25개`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0090 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:441`
- Signal: `collection-pipeline`
- Evidence: `"수준: ${ModelBurden.entries.filter { it != ModelBurden.RESTRICTED }.joinToString(" < ")}",`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0091 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:450`
- Signal: `collection-pipeline`
- Evidence: `.flatMap { s -> s.capability.models.map { it to s.providerId } }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0092 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:451`
- Signal: `collection-pipeline`
- Evidence: `.groupBy({ it.first }, { it.second })`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0093 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:453`
- Signal: `collection-pipeline`
- Evidence: `val lines = byModel.entries.sortedBy { it.key }.joinToString("\n") { "· `${it.key}` — ${it.value.distinct().size}명" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0094 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:460`
- Signal: `collection-pipeline`
- Evidence: `val lines = ranked.mapIndexed { i, (pid, c) -> "${i + 1}. <@$pid> — ${c}건" }.joinToString("\n")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0095 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:473`
- Signal: `collection-pipeline`
- Evidence: `val models = pool.flatMap { it.capability.models }.distinct().size`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0096 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:488`
- Signal: `collection-pipeline`
- Evidence: `val counts = pool.map { it.providerId to usage.providerContributionCount(it.providerId) }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0097 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:491`
- Signal: `collection-pipeline`
- Evidence: `counts.sortedByDescending { it.second }.joinToString("\n") { (pid, c) ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0098 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:581`
- Signal: `collection-pipeline`
- Evidence: `sources.take(12).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0099 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:584`
- Signal: `collection-pipeline`
- Evidence: `val sourceLines = sourceRows.joinToString("\n").ifBlank { "• 아직 지식 소스가 없습니다." }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0100 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:595`
- Signal: `collection-pipeline`
- Evidence: `readiness.spaces.take(12).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0101 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:601`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0102 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:607`
- Signal: `collection-pipeline`
- Evidence: `.take(4)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0103 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:608`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0104 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:715`
- Signal: `collection-pipeline`
- Evidence: `result.results.take(10).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0105 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:716`
- Signal: `collection-pipeline`
- Evidence: `val ref = it.sourceUri?.let { uri -> " · ${uri.take(80)}" } ?: ""`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0106 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:717`
- Signal: `collection-pipeline`
- Evidence: `val preview = it.contentPreview?.let { text -> " · ${text.take(100)}" } ?: ""`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0107 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:722`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0108 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:753`
- Signal: `collection-pipeline`
- Evidence: `plan.indexableSources.take(8).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0109 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:756`
- Signal: `collection-pipeline`
- Evidence: `val indexable = indexableRows.joinToString("\n").ifBlank { "• 색인할 소스 없음" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0110 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:758`
- Signal: `collection-pipeline`
- Evidence: `plan.blockedSources.take(8).map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0111 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:761`
- Signal: `collection-pipeline`
- Evidence: `val blocked = blockedRows.joinToString("\n").ifBlank { "• 차단/검토 소스 없음" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0112 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:771`
- Signal: `collection-pipeline`
- Evidence: `val commandRows = ops.commands.take(5).map { "• `$it`" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0113 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:772`
- Signal: `collection-pipeline`
- Evidence: `val commands = commandRows.joinToString("\n").ifBlank { "• 실행할 색인 명령 없음" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0114 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:773`
- Signal: `collection-pipeline`
- Evidence: `val nextRows = ops.nextActions.take(5).map { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0115 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:774`
- Signal: `collection-pipeline`
- Evidence: `val next = nextRows.joinToString("\n").ifBlank { "• 추가 조치 없음" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0116 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:797`
- Signal: `collection-pipeline`
- Evidence: `jobs.map {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0117 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:799`
- Signal: `io-bound`
- Evidence: `"${it.queuedAt}${it.failureReason?.let { reason -> " · $reason" } ?: ""}"`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0118 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:801`
- Signal: `collection-pipeline`
- Evidence: `val lines = rows.joinToString("\n").ifBlank { "• 최근 RAG 색인 작업이 없습니다." }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0119 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:921`
- Signal: `collection-pipeline`
- Evidence: `?.joinToString("\n") { "• `${it.severity}` ${it.message}" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0120 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:985`
- Signal: `collection-pipeline`
- Evidence: `).joinToString(" · ").ifBlank { "인기순" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0121 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:987`
- Signal: `collection-pipeline`
- Evidence: `presets.take(10).map { preset ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0122 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:994`
- Signal: `collection-pipeline`
- Evidence: `val lines = presetLines.joinToString("\n").ifBlank { "• 아직 공개된 프리셋이 없습니다." }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0123 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1006`
- Signal: `io-bound`
- Evidence: `val queue =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0124 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1007`
- Signal: `io-bound`
- Evidence: `summary.queue`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0125 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1008`
- Signal: `collection-pipeline`
- Evidence: `.take(10)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0126 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1009`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { item ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0127 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1012`
- Signal: `collection-pipeline`
- Evidence: `.joinToString(",") { "${it.key}:${it.value}" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0128 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1015`
- Signal: `collection-pipeline`
- Evidence: `"좋아요 ${item.likeCount} · risk `${item.riskCodes.joinToString(",").ifBlank { "none" }}` · " +`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0129 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1021`
- Signal: `collection-pipeline`
- Evidence: `.take(5)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0130 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1022`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0131 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1028`
- Signal: `io-bound`
- Evidence: `"__우선 검토 대상__\n$queue\n\n" +`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0132 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1048`
- Signal: `collection-pipeline`
- Evidence: `val riskText = summary.riskCodes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0133 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1051`
- Signal: `collection-pipeline`
- Evidence: `.take(4)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0134 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1052`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0135 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1056`
- Signal: `collection-pipeline`
- Evidence: `.take(3)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0136 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1057`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { load ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0137 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1058`
- Signal: `collection-pipeline`
- Evidence: `"• Provider ${kotlin.math.abs(load.providerUserId.hashCode()).toString(36).take(6)} — " +`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0138 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1176`
- Signal: `collection-pipeline`
- Evidence: `map.models.take(8).map { model ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0139 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1177`
- Signal: `collection-pipeline`
- Evidence: `val topTags = model.tags.take(3)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0140 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1178`
- Signal: `collection-pipeline`
- Evidence: `val tags = if (topTags.isEmpty()) "태그 없음" else topTags.joinToString(", ")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0141 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1179`
- Signal: `collection-pipeline`
- Evidence: `val tiers = model.qualityTiers.joinToString(",")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0142 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1182`
- Signal: `collection-pipeline`
- Evidence: `val models = modelLines.joinToString("\n").ifBlank { "• 아직 보고된 모델이 없습니다." }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0143 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1184`
- Signal: `collection-pipeline`
- Evidence: `map.channels.take(8).map { channel ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0144 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1188`
- Signal: `collection-pipeline`
- Evidence: `val channels = channelLines.joinToString("\n").ifBlank { "• 아직 채널 AI가 없습니다." }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0145 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1189`
- Signal: `collection-pipeline`
- Evidence: `val next = map.nextActions.take(5).joinToString("\n") { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0146 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1190`
- Signal: `collection-pipeline`
- Evidence: `val topCapabilityTags = map.capabilityTags.take(8)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0147 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1191`
- Signal: `collection-pipeline`
- Evidence: `val tags = if (topCapabilityTags.isEmpty()) "아직 태그 없음" else topCapabilityTags.joinToString(", ")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0148 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1212`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.status != "ready" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0149 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1213`
- Signal: `collection-pipeline`
- Evidence: `.ifEmpty { checklist.items.take(5) }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0150 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1214`
- Signal: `collection-pipeline`
- Evidence: `.take(8)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0151 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1215`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { item ->`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0152 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1226`
- Signal: `collection-pipeline`
- Evidence: `.take(5)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0153 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1227`
- Signal: `collection-pipeline`
- Evidence: `.joinToString("\n") { "• $it" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0154 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1337`
- Signal: `io-bound`
- Evidence: `val queued = s.queueDepth().let { if (it > 0) " · 대기 $it" else "" }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0155 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1338`
- Signal: `io-bound`
- Evidence: `val base = "상태: ${s.state} · 처리중 ${s.activeRequests}$queued · 일일잔여 ${s.remainingDailyRequests} · 실패 ${s.failures}"`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0156 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1348`
- Signal: `collection-pipeline`
- Evidence: `return Reply("✅ 제공 모델 설정: ${models.joinToString(", ")}")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0157 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1372`
- Signal: `polling`
- Evidence: `fun providerSchedule(`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0158 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1378`
- Signal: `polling`
- Evidence: `schedule.setSchedule(ctx.userId, ctx.guildId, fromHour, toHour)`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0159 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1455`
- Signal: `collection-pipeline`
- Evidence: `val channelText = if (channels.isEmpty()) "모든 채널" else channels.joinToString(" ") { "<#$it>" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0160 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1570`
- Signal: `collection-pipeline`
- Evidence: `pool.joinToString("\n") {`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0161 [Performance/P2] 핫패스 비용 계측 필요

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `large-runtime`
- Evidence: 런타임/스크립트 파일 1599라인
- Recommendation: Micrometer/로그 타이머/benchmark로 요청당 DB 호출 수·외부 호출 수·렌더 시간을 측정

### IMP-0162 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:22`
- Signal: `db-access`
- Evidence: `import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0163 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:23`
- Signal: `db-access`
- Evidence: `import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0164 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:124`
- Signal: `io-bound`
- Evidence: `instance.awaitReady()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0165 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:125`
- Signal: `io-bound`
- Evidence: `instance.getGuildById(guildId)?.updateCommands()?.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0166 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:157`
- Signal: `io-bound`
- Evidence: `private fun registerCommands(action: net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction) {`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0167 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:219`
- Signal: `polling`
- Evidence: `.slash("provider-schedule", "가용 시간대를 설정합니다(UTC 시, 시간 밖 자동정지)")`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0168 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:272`
- Signal: `db-access`
- Evidence: `// 인터랙티브(차수 13): 설정 패널(버튼/Select #147/180), 모달 입력(#189)`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0169 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:372`
- Signal: `collection-pipeline`
- Evidence: `.addOption(OptionType.BOOLEAN, "distinct-models", "서로 다른 모델 우선", false)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0170 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:397`
- Signal: `collection-pipeline`
- Evidence: `.filterIsInstance<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData>()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0171 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:399`
- Signal: `io-bound`
- Evidence: `action.addCommands(cmds).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0172 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:417`
- Signal: `io-bound`
- Evidence: `event.reply("이 명령은 서버에서만 사용할 수 있어요.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0173 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:425`
- Signal: `io-bound`
- Evidence: `.replyEmbeds(EmbedFactory.mainMenuEmbed(ctx.isAdmin))`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0174 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:428`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0175 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:434`
- Signal: `io-bound`
- Evidence: `.reply(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0176 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:441`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0177 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:446`
- Signal: `io-bound`
- Evidence: `event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0178 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:451`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 관리자만 사용할 수 있습니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0179 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:454`
- Signal: `io-bound`
- Evidence: `.replyEmbeds(settingsEmbed(ctx))`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0180 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:457`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0181 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:463`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0182 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:466`
- Signal: `io-bound`
- Evidence: `.reply(channelProfilePanelText(ctx))`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0183 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:469`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0184 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:484`
- Signal: `io-bound`
- Evidence: `event.replyModal(modal).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0185 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:488`
- Signal: `io-bound`
- Evidence: `// 모든 명령을 defer 로 먼저 ack(3초 제한 회피) 후 결과 편집. 공유/원격 서버의 지연에도 안전.`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0186 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:489`
- Signal: `io-bound`
- Evidence: `// 공개 명령만 비-ephemeral, 나머지는 ephemeral. defer 시점에 결정.`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0187 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:492`
- Signal: `io-bound`
- Evidence: `event.deferReply(if (useWebhookProfile) true else !isPublic).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0188 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:494`
- Signal: `io-bound`
- Evidence: `val reply = dispatch(event, ctx)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0189 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:496`
- Signal: `io-bound`
- Evidence: `completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0190 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:498`
- Signal: `io-bound`
- Evidence: `editOriginalWithPseudoStream(event.hook, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0191 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:502`
- Signal: `io-bound`
- Evidence: `event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0192 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:508`
- Signal: `polling`
- Evidence: `private const val DEFAULT_PSEUDO_STREAM_INTERVAL_MS = 1200L`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0193 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:568`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.startsWith(typed, ignoreCase = true) }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0194 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:569`
- Signal: `collection-pipeline`
- Evidence: `.take(25)`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0195 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:570`
- Signal: `collection-pipeline`
- Evidence: `.map { Command.Choice(it, it) }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0196 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:571`
- Signal: `io-bound`
- Evidence: `event.replyChoices(choices).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0197 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:584`
- Signal: `io-bound`
- Evidence: `event.replyModal(askModal()).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0198 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:589`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0199 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:592`
- Signal: `io-bound`
- Evidence: `.replyEmbeds(settingsEmbed(ctx))`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0200 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:595`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0201 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:600`
- Signal: `io-bound`
- Evidence: `event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0202 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:605`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0203 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:607`
- Signal: `io-bound`
- Evidence: `event.replyModal(channelProfileModal(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0204 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:613`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0205 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:615`
- Signal: `io-bound`
- Evidence: `event.replyModal(channelProfileAvatarModal(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0206 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:620`
- Signal: `io-bound`
- Evidence: `val reply = commands.setChannelAiProfile(ctx, null, null, reset = true)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0207 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:621`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0208 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:625`
- Signal: `io-bound`
- Evidence: `val reply = commands.setChannelAiProfile(ctx, null, null, reset = false, rollback = true)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0209 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:626`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0210 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:632`
- Signal: `io-bound`
- Evidence: `.reply(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0211 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:639`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0212 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:646`
- Signal: `io-bound`
- Evidence: `event.reply(commands.providerInstallGuide(ctx, os).content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0213 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:649`
- Signal: `io-bound`
- Evidence: `val reply =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0214 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:666`
- Signal: `io-bound`
- Evidence: `event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0215 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:668`
- Signal: `io-bound`
- Evidence: `event.replyModal(channelBulkModal(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0216 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:680`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0217 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:684`
- Signal: `db-access`
- Evidence: `override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0218 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:688`
- Signal: `io-bound`
- Evidence: `val reply =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0219 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:698`
- Signal: `db-access`
- Evidence: `MenuFactory.AUTO_APPROVE_SELECT -> {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0220 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:704`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0221 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:708`
- Signal: `db-access`
- Evidence: `override fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0222 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:712`
- Signal: `collection-pipeline`
- Evidence: `val channelIds = event.values.map { it.idLong }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0223 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:723`
- Signal: `io-bound`
- Evidence: `.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0224 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:787`
- Signal: `collection-pipeline`
- Evidence: `).joinToString("\n")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0225 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:795`
- Signal: `collection-pipeline`
- Evidence: `val distinct = channelIds.distinct()`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0226 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:796`
- Signal: `collection-pipeline`
- Evidence: `val visible = distinct.take(12).joinToString(" ") { "<#$it>" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0227 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:797`
- Signal: `collection-pipeline`
- Evidence: `val suffix = if (distinct.size > 12) " 외 ${distinct.size - 12}개" else ""`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0228 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:798`
- Signal: `collection-pipeline`
- Evidence: `return "${distinct.size}개 채널: $visible$suffix"`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0229 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:810`
- Signal: `collection-pipeline`
- Evidence: `return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0230 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:822`
- Signal: `io-bound`
- Evidence: `event.reply("아직 저장할 변경사항이 없습니다. 언어/모델/채널/자동 승인을 먼저 선택해주세요.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0231 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:825`
- Signal: `io-bound`
- Evidence: `val reply =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0232 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:836`
- Signal: `io-bound`
- Evidence: `.queue({`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0233 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:838`
- Signal: `io-bound`
- Evidence: `.sendMessage(reply.content)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0234 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:840`
- Signal: `io-bound`
- Evidence: `.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0235 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:848`
- Signal: `io-bound`
- Evidence: `event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0236 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:852`
- Signal: `db-access`
- Evidence: `event: StringSelectInteractionEvent,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0237 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:855`
- Signal: `io-bound`
- Evidence: `event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0238 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:859`
- Signal: `db-access`
- Evidence: `event: EntitySelectInteractionEvent,`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0239 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:862`
- Signal: `io-bound`
- Evidence: `event.editMessageEmbeds(settingsEmbed(ctx)).setComponents(settingsRows(ctx)).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0240 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:869`
- Signal: `db-access`
- Evidence: `MenuFactory.languageSelect(current = pendingSettings[settingsKey(ctx)]?.language ?: commands.guildLanguage(ctx)),`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0241 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:872`
- Signal: `db-access`
- Evidence: `MenuFactory.modelSelect(`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0242 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:877`
- Signal: `db-access`
- Evidence: `ActionRow.of(MenuFactory.channelSelect(effectiveAllowedChannelIds(ctx))),`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0243 [Performance/P2] DB 접근 비용/인덱스 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:879`
- Signal: `db-access`
- Evidence: `MenuFactory.autoApproveSelect(`
- Recommendation: 쿼리 실행 계획, 복합 인덱스, projection read model, batch 조회 가능성을 확인

### IMP-0244 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:979`
- Signal: `collection-pipeline`
- Evidence: `val currentText = if (current.isEmpty()) "" else current.joinToString(" ") { "<#$it>" }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0245 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:997`
- Signal: `io-bound`
- Evidence: `val reply =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0246 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1008`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0247 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1015`
- Signal: `io-bound`
- Evidence: `.replyEmbeds(settingsEmbed(ctx))`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0248 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1018`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0249 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1024`
- Signal: `io-bound`
- Evidence: `event.reply("먼저 `프로필 편집`에서 이름을 저장한 뒤 아이콘을 설정하세요.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0250 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1027`
- Signal: `io-bound`
- Evidence: `val reply =`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0251 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1038`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0252 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1044`
- Signal: `io-bound`
- Evidence: `event.deferReply(useWebhookProfile).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0253 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1045`
- Signal: `io-bound`
- Evidence: `val reply = commands.ask(ctx, prompt)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0254 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1047`
- Signal: `io-bound`
- Evidence: `completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0255 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1049`
- Signal: `io-bound`
- Evidence: `editOriginalWithPseudoStream(event.hook, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0256 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1059`
- Signal: `io-bound`
- Evidence: `event.deferReply(useWebhookProfile).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0257 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1060`
- Signal: `io-bound`
- Evidence: `val reply = commands.ask(ctx, event.target.contentRaw)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0258 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1062`
- Signal: `io-bound`
- Evidence: `completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0259 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1064`
- Signal: `io-bound`
- Evidence: `editOriginalWithPseudoStream(event.hook, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0260 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1079`
- Signal: `io-bound`
- Evidence: `.reply("질문 내용을 같이 적어주세요. 예: `@냥시스턴트 오늘 회의 요약해줘`")`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0261 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1081`
- Signal: `io-bound`
- Evidence: `.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0262 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1094`
- Signal: `io-bound`
- Evidence: `event.channel.sendTyping().queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0263 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1096`
- Signal: `io-bound`
- Evidence: `val reply = commands.ask(ctx, prompt)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0264 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1097`
- Signal: `io-bound`
- Evidence: `if (useWebhookProfile && sendAnswerWebhook(event.channel, ctx, reply)) {`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0265 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1100`
- Signal: `io-bound`
- Evidence: `.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0266 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1102`
- Signal: `io-bound`
- Evidence: `replyToMessageWithPseudoStream(event.message, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0267 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1112`
- Signal: `io-bound`
- Evidence: `.reply("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.")`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0268 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1114`
- Signal: `io-bound`
- Evidence: `.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0269 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1131`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0270 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1133`
- Signal: `io-bound`
- Evidence: `if (sendAnswerWebhook(channelUnion, ctx, reply)) {`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0271 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1137`
- Signal: `io-bound`
- Evidence: `reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0272 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1141`
- Signal: `io-bound`
- Evidence: `if (sendBotChannelAnswer(channelUnion, reply)) {`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0273 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1146`
- Signal: `io-bound`
- Evidence: `).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0274 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1149`
- Signal: `io-bound`
- Evidence: `editOriginalWithPseudoStream(hook, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0275 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1154`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0276 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1156`
- Signal: `io-bound`
- Evidence: `if (reply.ephemeral) return false`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0277 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1159`
- Signal: `io-bound`
- Evidence: `val snapshots = reply.publicPseudoStreamSnapshots()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0278 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1160`
- Signal: `io-bound`
- Evidence: `val action = channelUnion.asTextChannel().sendMessage(snapshots?.first() ?: reply.content)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0279 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1161`
- Signal: `io-bound`
- Evidence: `feedbackRows(reply).takeIf { it.isNotEmpty() && snapshots == null }?.let { action.setComponents(it) }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0280 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1163`
- Signal: `io-bound`
- Evidence: `if (snapshots != null) scheduleMessageEdits(sent, reply, snapshots, 1)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0281 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1163`
- Signal: `polling`
- Evidence: `if (snapshots != null) scheduleMessageEdits(sent, reply, snapshots, 1)`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0282 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1172`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0283 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1174`
- Signal: `io-bound`
- Evidence: `val snapshots = reply.publicPseudoStreamSnapshots()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0284 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1176`
- Signal: `io-bound`
- Evidence: `editOriginalWithFeedback(hook, reply.content, reply)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0285 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1179`
- Signal: `io-bound`
- Evidence: `hook.editOriginal(snapshots.first()).queue(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0286 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1180`
- Signal: `io-bound`
- Evidence: `{ scheduleOriginalEdits(hook, reply, snapshots, 1) },`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0287 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1180`
- Signal: `polling`
- Evidence: `{ scheduleOriginalEdits(hook, reply, snapshots, 1) },`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0288 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1183`
- Signal: `io-bound`
- Evidence: `hook.editOriginal(reply.content).queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0289 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1188`
- Signal: `polling`
- Evidence: `private fun scheduleOriginalEdits(`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0290 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1190`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0291 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1197`
- Signal: `io-bound`
- Evidence: `feedbackRows(reply).takeIf { it.isNotEmpty() }?.let { action.setComponents(it) }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0292 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1199`
- Signal: `io-bound`
- Evidence: `action.queueAfter(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0293 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1200`
- Signal: `io-bound`
- Evidence: `reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0294 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1200`
- Signal: `polling`
- Evidence: `reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0295 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1202`
- Signal: `io-bound`
- Evidence: `{ scheduleOriginalEdits(hook, reply, snapshots, index + 1) },`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0296 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1202`
- Signal: `polling`
- Evidence: `{ scheduleOriginalEdits(hook, reply, snapshots, index + 1) },`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0297 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1205`
- Signal: `io-bound`
- Evidence: `hook.editOriginal(reply.content).queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0298 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1210`
- Signal: `io-bound`
- Evidence: `private fun replyToMessageWithPseudoStream(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0299 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1212`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0300 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1214`
- Signal: `io-bound`
- Evidence: `val snapshots = reply.publicPseudoStreamSnapshots()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0301 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1216`
- Signal: `io-bound`
- Evidence: `val action = source.reply(reply.content).mentionRepliedUser(false)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0302 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1217`
- Signal: `io-bound`
- Evidence: `feedbackRows(reply).takeIf { it.isNotEmpty() }?.let { action.setComponents(it) }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0303 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1218`
- Signal: `io-bound`
- Evidence: `action.queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0304 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1223`
- Signal: `io-bound`
- Evidence: `.reply(snapshots.first())`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0305 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1226`
- Signal: `io-bound`
- Evidence: `.queue(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0306 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1227`
- Signal: `io-bound`
- Evidence: `{ sent -> scheduleMessageEdits(sent, reply, snapshots, 1) },`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0307 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1227`
- Signal: `polling`
- Evidence: `{ sent -> scheduleMessageEdits(sent, reply, snapshots, 1) },`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0308 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1230`
- Signal: `io-bound`
- Evidence: `source.reply(reply.content).mentionRepliedUser(false).queue({}, {})`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0309 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1235`
- Signal: `polling`
- Evidence: `private fun scheduleMessageEdits(`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0310 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1237`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0311 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1244`
- Signal: `io-bound`
- Evidence: `feedbackRows(reply).takeIf { it.isNotEmpty() }?.let { action.setComponents(it) }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0312 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1246`
- Signal: `io-bound`
- Evidence: `action.queueAfter(`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0313 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1247`
- Signal: `io-bound`
- Evidence: `reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0314 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1247`
- Signal: `polling`
- Evidence: `reply.pseudoStream?.editIntervalMs ?: DEFAULT_PSEUDO_STREAM_INTERVAL_MS,`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0315 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1249`
- Signal: `io-bound`
- Evidence: `{ scheduleMessageEdits(message, reply, snapshots, index + 1) },`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0316 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1249`
- Signal: `polling`
- Evidence: `{ scheduleMessageEdits(message, reply, snapshots, index + 1) },`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

### IMP-0317 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1257`
- Signal: `collection-pipeline`
- Evidence: `?.filter { it.isNotBlank() }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0318 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1267`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0319 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1269`
- Signal: `io-bound`
- Evidence: `if (reply.ephemeral) return false`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0320 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1280`
- Signal: `io-bound`
- Evidence: `val action = webhook.sendMessage(reply.content).setUsername(profile.displayName)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0321 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1294`
- Signal: `io-bound`
- Evidence: `reply: Reply,`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0322 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1297`
- Signal: `io-bound`
- Evidence: `feedbackRows(reply).takeIf { it.isNotEmpty() }?.let { action.setComponents(it) }`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0323 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1298`
- Signal: `io-bound`
- Evidence: `action.queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0324 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1301`
- Signal: `io-bound`
- Evidence: `private fun feedbackRows(reply: Reply): List<ActionRow> {`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0325 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1302`
- Signal: `io-bound`
- Evidence: `val requestId = reply.feedback?.requestId?.takeIf { it.isNotBlank() } ?: return emptyList()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0326 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1332`
- Signal: `io-bound`
- Evidence: `event.reply("피드백 버튼 정보를 읽지 못했어요. 다시 질문한 뒤 답변 아래 버튼을 눌러주세요.").setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0327 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1335`
- Signal: `io-bound`
- Evidence: `val reply = commands.submitAskFeedback(ctx, requestId, action.rating, action.feedbackType)`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0328 [Performance/P2] I/O 대기와 재시도 정책 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1336`
- Signal: `io-bound`
- Evidence: `event.reply(reply.content).setEphemeral(true).queue()`
- Recommendation: timeout, retry budget, backoff, circuit breaker, 사용자 응답 defer/partial update 전략을 확인

### IMP-0329 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1374`
- Signal: `collection-pipeline`
- Evidence: `roleIds = member?.roles?.map { it.idLong }?.toSet() ?: emptySet(),`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0330 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1496`
- Signal: `collection-pipeline`
- Evidence: `requireDistinctModels = event.getOption("distinct-models")?.asBoolean ?: false,`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0331 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1520`
- Signal: `collection-pipeline`
- Evidence: `.map { it.trim() }`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0332 [Performance/P2] 컬렉션 파이프라인 비용 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1521`
- Signal: `collection-pipeline`
- Evidence: `.filter { it.isNotEmpty() },`
- Recommendation: 대상 크기 상한, lazy sequence, DB-side aggregation, precomputed projection 적용 여부를 확인

### IMP-0333 [Performance/P2] 폴링 주기/부하 제한 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1537`
- Signal: `polling`
- Evidence: `"provider-schedule" ->`
- Recommendation: 사용자 수 증가 시 polling fan-out 비용을 계산하고 SSE/WebSocket/cache 전환 기준을 정의

## UX

### IMP-0334 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:184`
- Signal: `ux-copy`
- Evidence: `nextAction = "후보 수/깊은 답변/다중응답을 낮추고 과부하 Provider를 쉬게 하세요.",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0335 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:212`
- Signal: `ux-copy`
- Evidence: `title = "다중응답 안전 게이트",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0336 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:220`
- Signal: `ux-label-consistency`
- Evidence: `nextAction = dashboard.multiResponseOperations.nextActions.firstOrNull() ?: "다중응답 운영 상태를 점검하세요.",`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0337 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:423`
- Signal: `ux-error-empty-state`
- Evidence: `if (isEmpty()) add("검토 대기 중인 AI 설정 변경은 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0338 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:603`
- Signal: `ux-copy`
- Evidence: `overload.highRiskCount > 0 -> "과부하 Provider를 보호하고 후보 수/응답 모드를 낮추세요."`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0339 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:726`
- Signal: `ux-error-empty-state`
- Evidence: `nextAction = changeApproval.nextActions.firstOrNull() ?: "검토 대기 중인 AI 설정 변경은 없습니다.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0340 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:746`
- Signal: `ux-copy`
- Evidence: `"과부하 알림을 먼저 해소한 뒤 깊은 답변/다중 응답을 켜세요."`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0341 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:846`
- Signal: `ux-error-empty-state`
- Evidence: `description = "온라인 Provider가 없어 질문을 처리할 로컬 AI가 없습니다. /프로바이더참여 안내로 첫 PC를 연결하세요.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0342 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:860`
- Signal: `ux-error-empty-state`
- Evidence: `description = "채널별 이름·역할·말투가 아직 없습니다. 설정 패널에서 이 채널 AI 프로필을 만들면 네트워크 정체성이 생깁니다.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0343 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:940`
- Signal: `ux-error-empty-state`
- Evidence: `description = "아직 품질 피드백이 없습니다. 따봉/신고/사유를 모으면 모델 선택과 채널 AI 개선 근거가 생깁니다.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0344 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:954`
- Signal: `ux-copy`
- Evidence: `description = "과부하 Provider가 있어 깊은 답변·다중 응답보다 보호 정책이 우선됩니다. 수신정지/절약 모드/후보 수 제한을 확인하세요.",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0345 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:986`
- Signal: `ux-copy`
- Evidence: `description = "Provider·채널 AI·지식·피드백 기반이 갖춰졌습니다. 이제 프리셋 공유나 다중 응답 실험을 단계적으로 켜도 됩니다.",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0346 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1031`
- Signal: `ux-label-consistency`
- Evidence: `"model_policy" -> "응답 속도/품질 모드와 선호 모델 정책을 설정하세요."`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0347 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandLoc.kt:83`
- Signal: `ux-copy`
- Evidence: `"다중응답상태",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0348 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandLoc.kt:90`
- Signal: `ux-label-consistency`
- Evidence: `"다중응답설정",`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0349 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandLoc.kt:97`
- Signal: `ux-copy`
- Evidence: `"다중응답실험",`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0350 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandLoc.kt:102`
- Signal: `ux-error-empty-state`
- Evidence: `"bot-permissions" to L("봇권한", null, "Check bot permissions and mention setup (admin)", "Права бота (админ)"),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0351 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:129`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.reject(it.message ?: "AI 설정 변경 권한이 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0352 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:447`
- Signal: `ux-error-empty-state`
- Evidence: `if (pool.isEmpty()) return Reply("현재 풀에 온라인 프로바이더가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0353 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:459`
- Signal: `ux-error-empty-state`
- Evidence: `if (ranked.isEmpty()) return Reply("아직 누적 기여가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0354 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:463`
- Signal: `ux-error-empty-state`
- Evidence: `"_한 번이라도 기여한 사람은 오프라인이어도 계속 기록됩니다. 기여는 비금전 인정입니다. 고마워요!_",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0355 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:479`
- Signal: `ux-error-empty-state`
- Evidence: `"_개별 식별정보 없이 집계됩니다._",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0356 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:487`
- Signal: `ux-error-empty-state`
- Evidence: `if (pool.isEmpty()) return Reply("연결된 프로바이더가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0357 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:493`
- Signal: `ux-error-empty-state`
- Evidence: `"· <@$pid>: ${c}건 ($pct%) · 실패 ${usage.providerFailures(pid)}"`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0358 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:509`
- Signal: `ux-error-empty-state`
- Evidence: `"**냥시스턴트 봇 권한 점검**\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0359 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:512`
- Signal: `ux-error-empty-state`
- Evidence: `"채널 AI 이름/아이콘으로 답변하려면 서버 초대 권한에 **웹후크 관리(Manage Webhooks)** 가 필요합니다.\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0360 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:514`
- Signal: `ux-error-empty-state`
- Evidence: `"슬래시 명령어 사용 권한을 권장합니다.\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0361 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:563`
- Signal: `ux-copy`
- Evidence: `sb.append("· `/ai-multi-response-status` `/ai-multi-response-set` `/ai-multi-response-dry-run` — 다중응답 정책·상태·안전 드라이런\n")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0362 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:564`
- Signal: `ux-copy`
- Evidence: `sb.append("· `/ai-network-check` — Provider·채널AI·RAG·프리셋·다중응답 운영 체크리스트\n")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0363 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:584`
- Signal: `ux-error-empty-state`
- Evidence: `val sourceLines = sourceRows.joinToString("\n").ifBlank { "• 아직 지식 소스가 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0364 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:603`
- Signal: `ux-error-empty-state`
- Evidence: `"• 아직 지식공간이 없습니다. `/ai-knowledge-add title:<제목> url:<https://...>` 로 현재 채널 지식공간을 만들 수 있어요."`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0365 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:609`
- Signal: `ux-error-empty-state`
- Evidence: `.ifBlank { "• 추가 조치 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0366 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:694`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.warn("지식 소스 추가에 실패했어요. ${it.message ?: "입력값을 확인해 주세요."}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0367 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:726`
- Signal: `ux-error-empty-state`
- Evidence: `"rag_scope_required" -> "• 검색 범위가 없습니다. 현재 채널 또는 space-id를 지정해 주세요."`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0368 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:727`
- Signal: `ux-error-empty-state`
- Evidence: `"no_knowledge_space" -> "• 이 채널에 지식공간이 없습니다. 먼저 `/ai-knowledge-add` 로 지식을 추가하세요."`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0369 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:729`
- Signal: `ux-error-empty-state`
- Evidence: `else -> "• 결과 없음"`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0370 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:739`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.warn("지식 검색에 실패했어요. ${it.message ?: "검색어/space-id를 확인해 주세요."}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0371 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:756`
- Signal: `ux-error-empty-state`
- Evidence: `val indexable = indexableRows.joinToString("\n").ifBlank { "• 색인할 소스 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0372 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:761`
- Signal: `ux-error-empty-state`
- Evidence: `val blocked = blockedRows.joinToString("\n").ifBlank { "• 차단/검토 소스 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0373 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:772`
- Signal: `ux-error-empty-state`
- Evidence: `val commands = commandRows.joinToString("\n").ifBlank { "• 실행할 색인 명령 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0374 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:774`
- Signal: `ux-error-empty-state`
- Evidence: `val next = nextRows.joinToString("\n").ifBlank { "• 추가 조치 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0375 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:801`
- Signal: `ux-error-empty-state`
- Evidence: `val lines = rows.joinToString("\n").ifBlank { "• 최근 RAG 색인 작업이 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0376 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:806`
- Signal: `ux-error-empty-state`
- Evidence: `"완료/실패 처리는 `/ai-knowledge-job-complete job-id:<id> status:<completed|failed|cancelled>` 로 기록하세요.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0377 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:924`
- Signal: `ux-error-empty-state`
- Evidence: `"프리셋을 바로 가져오지 못했어요. ${error.message ?: "원인을 확인해 주세요."}\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0378 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:937`
- Signal: `ux-error-empty-state`
- Evidence: `.getOrElse { return Replies.warn("프리셋 좋아요에 실패했어요. ${it.message ?: "프리셋 ID를 확인해 주세요."}") }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0379 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:948`
- Signal: `ux-error-empty-state`
- Evidence: `.getOrElse { return Replies.warn("프리셋 신고에 실패했어요. ${it.message ?: "프리셋 ID와 신고 사유를 확인해 주세요."}") }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0380 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:969`
- Signal: `ux-error-empty-state`
- Evidence: `.getOrElse { return Replies.warn("프리셋 신고 검수 처리에 실패했어요. ${it.message ?: "report-id/decision을 확인해 주세요."}") }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0381 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:994`
- Signal: `ux-error-empty-state`
- Evidence: `val lines = presetLines.joinToString("\n").ifBlank { "• 아직 공개된 프리셋이 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0382 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1018`
- Signal: `ux-error-empty-state`
- Evidence: `}.ifBlank { "• 검토할 프리셋 신고가 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0383 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1023`
- Signal: `ux-error-empty-state`
- Evidence: `.ifBlank { "• 지금은 추가 조치가 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0384 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1038`
- Signal: `ux-copy`
- Evidence: `"이제 이 채널에서 질문하면 가져온 프리셋의 역할·말투·응답 정책이 적용됩니다."`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0385 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1053`
- Signal: `ux-error-empty-state`
- Evidence: `.ifBlank { "• 지금은 추가 조치가 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0386 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1060`
- Signal: `ux-error-empty-state`
- Evidence: `}.ifBlank { "• 최근 fan-out 부하 기록 없음" }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0387 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1063`
- Signal: `ux-copy`
- Evidence: `"🧪 **다중응답 운영 상태** <#$targetChannelId>\n\n" +`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0388 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1070`
- Signal: `ux-error-empty-state`
- Evidence: `"Provider 없음 ${summary.noProviderRunCount}\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0389 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1076`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.warn("다중응답 상태를 불러오지 못했어요. ${error.message ?: "설정/기능 플래그를 확인해 주세요."}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0390 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1119`
- Signal: `ux-copy`
- Evidence: `"다중응답 정책을 저장했습니다. <#$targetChannelId>\n" +`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0391 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1124`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.warn("다중응답 정책을 저장하지 못했어요. ${error.message ?: "입력값을 확인해 주세요."}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0392 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1158`
- Signal: `ux-copy`
- Evidence: `"🧪 **다중응답 드라이런** <#$targetChannelId>\n" +`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0393 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1165`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.warn("다중응답 드라이런을 만들지 못했어요. ${error.message ?: "정책/Provider 상태를 확인해 주세요."}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0394 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1178`
- Signal: `ux-error-empty-state`
- Evidence: `val tags = if (topTags.isEmpty()) "태그 없음" else topTags.joinToString(", ")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0395 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1182`
- Signal: `ux-error-empty-state`
- Evidence: `val models = modelLines.joinToString("\n").ifBlank { "• 아직 보고된 모델이 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0396 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1188`
- Signal: `ux-error-empty-state`
- Evidence: `val channels = channelLines.joinToString("\n").ifBlank { "• 아직 채널 AI가 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0397 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1191`
- Signal: `ux-error-empty-state`
- Evidence: `val tags = if (topCapabilityTags.isEmpty()) "아직 태그 없음" else topCapabilityTags.joinToString(", ")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0398 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1228`
- Signal: `ux-error-empty-state`
- Evidence: `.ifBlank { "• 지금 막을 항목은 없습니다." }`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0399 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1252`
- Signal: `ux-copy`
- Evidence: `return Reply("✅ 이 채널의 AI 응답 프로필을 기본 봇 표시로 되돌렸습니다.")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0400 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1257`
- Signal: `ux-label-consistency`
- Evidence: `?: return Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다.")`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0401 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1264`
- Signal: `ux-label-consistency`
- Evidence: `Reply("현재 이 채널의 AI 응답 프로필은 설정되지 않았습니다. `name` 옵션으로 설정하세요.")`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0402 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1293`
- Signal: `ux-error-empty-state`
- Evidence: `"이후 `/ask` 답변은 이 채널에서 그 이름으로 보입니다. 봇에 `웹후크 관리` 권한이 필요해요.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0403 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1327`
- Signal: `ux-error-empty-state`
- Evidence: `if (protection.pause(ctx.userId, ctx.guildId)) Reply("⏸️ 일시정지했습니다.") else Reply("연결된 에이전트가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0404 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1330`
- Signal: `ux-error-empty-state`
- Evidence: `if (protection.resume(ctx.userId, ctx.guildId)) Reply("▶️ 재개했습니다.") else Reply("연결된 에이전트가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0405 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1333`
- Signal: `ux-error-empty-state`
- Evidence: `if (protection.leave(ctx.userId, ctx.guildId)) Reply("👋 풀에서 나갔습니다.") else Reply("연결된 에이전트가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0406 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1336`
- Signal: `ux-error-empty-state`
- Evidence: `val s = registry.byProvider(ctx.guildId, ctx.userId) ?: return Reply("연결 상태: 오프라인")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0407 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1338`
- Signal: `ux-error-empty-state`
- Evidence: `val base = "상태: ${s.state} · 처리중 ${s.activeRequests}$queued · 일일잔여 ${s.remainingDailyRequests} · 실패 ${s.failures}"`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0408 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1413`
- Signal: `ux-error-empty-state`
- Evidence: `Replies.ok("프로바이더 **자동 승인** — 이제 `/provider-join` 한 사람은 관리자 승인 없이 바로 참여합니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0409 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1529`
- Signal: `ux-error-empty-state`
- Evidence: `?: return Reply("승인할 대기 중 프로바이더가 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0410 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1543`
- Signal: `ux-error-empty-state`
- Evidence: `Reply("해당 프로바이더를 찾을 수 없습니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0411 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:146`
- Signal: `ux-error-empty-state`
- Evidence: `log.error("Message Content Intent 없는 안전 재기동 실패: {}", e.message, e)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0412 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:147`
- Signal: `ux-error-empty-state`
- Evidence: `gatewayStatus.markShutdown(4014, "Message Content Intent 없는 안전 재기동 실패: ${e.message}")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0413 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:170`
- Signal: `ux-copy`
- Evidence: `.OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0414 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:306`
- Signal: `ux-error-empty-state`
- Evidence: `.slash("ai-knowledge-job-complete", "RAG 색인 작업 완료/실패 상태를 기록합니다(관리자)")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0415 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:310`
- Signal: `ux-error-empty-state`
- Evidence: `.OptionData(OptionType.STRING, "status", "completed/failed/cancelled", false)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0416 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:312`
- Signal: `ux-error-empty-state`
- Evidence: `.addChoice("실패", "failed")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0417 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:314`
- Signal: `ux-error-empty-state`
- Evidence: `).addOption(OptionType.STRING, "reason", "실패/취소 사유", false)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0418 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:359`
- Signal: `ux-copy`
- Evidence: `.slash("ai-multi-response-status", "다중응답 정책/부하/위험 상태를 봅니다(관리자)")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0419 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:363`
- Signal: `ux-copy`
- Evidence: `.slash("ai-multi-response-set", "현재 또는 선택 채널의 다중응답 정책을 저장합니다(관리자)")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0420 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:377`
- Signal: `ux-copy`
- Evidence: `.slash("ai-multi-response-dry-run", "다중응답 fan-out 후보와 RAG 컨텍스트를 안전하게 드라이런합니다(관리자)")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0421 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:381`
- Signal: `ux-copy`
- Evidence: `.OptionData(OptionType.STRING, "mode", "응답 속도/품질 모드", false)`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0422 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:388`
- Signal: `ux-error-empty-state`
- Evidence: `Commands.slash("bot-permissions", "봇 권한과 @멘션 호출 설정을 점검합니다(관리자)").setDefaultPermissions(adminPerm),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0423 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:501`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("명령 처리 실패: {} — {}", event.name, e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0424 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:544`
- Signal: `ux-error-empty-state`
- Evidence: `"DISALLOWED_INTENTS: Message Content Intent 권한/설정 불일치 가능"`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0425 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:678`
- Signal: `ux-error-empty-state`
- Evidence: `else -> Reply("알 수 없는 동작입니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0426 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:702`
- Signal: `ux-error-empty-state`
- Evidence: `else -> Reply("알 수 없는 선택입니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0427 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:719`
- Signal: `ux-error-empty-state`
- Evidence: `val channel = event.guild.systemChannel ?: return // 시스템 채널 없으면 스킵`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0428 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:822`
- Signal: `ux-error-empty-state`
- Evidence: `event.reply("아직 저장할 변경사항이 없습니다. 언어/모델/채널/자동 승인을 먼저 선택해주세요.").setEphemeral(true).queue()`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0429 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:891`
- Signal: `ux-error-empty-state`
- Evidence: `"아직 이 채널 전용 AI 프로필이 없습니다."`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0430 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:900`
- Signal: `ux-error-empty-state`
- Evidence: `"아래 버튼으로 설정하세요. 긴 명령어 옵션을 직접 외울 필요가 없습니다."`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0431 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1106`
- Signal: `ux-error-empty-state`
- Evidence: `"멘션 질문 처리 실패(channel={}, user={}): {}",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0432 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1144`
- Signal: `ux-error-empty-state`
- Evidence: `"⚠️ 채널 AI 이름/아이콘으로 보내려면 봇에 `웹후크 관리` 권한이 필요해요. " +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0433 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1166`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("일반 봇 메시지 폴백 전송 실패: {}", e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0434 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1182`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("의사 스트리밍 초기 응답 편집 실패: {}", e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0435 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1204`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("의사 스트리밍 응답 편집 실패(index={}): {}", index, e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0436 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1229`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("멘션 의사 스트리밍 초기 답변 실패: {}", e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0437 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1250`
- Signal: `ux-error-empty-state`
- Evidence: `{ e -> log.warn("의사 스트리밍 메시지 수정 실패(index={}): {}", index, e.message) },`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0438 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1287`
- Signal: `ux-error-empty-state`
- Evidence: `log.warn("채널 AI 프로필 웹훅 전송 실패(channel={}): {}", ctx.channelId, e.message)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0439 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1596`
- Signal: `ux-error-empty-state`
- Evidence: `else -> Reply("알 수 없는 명령입니다.")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0440 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/EmbedFactory.kt:34`
- Signal: `ux-error-empty-state`
- Evidence: `.addField("실패", failures.toString(), true)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0441 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/MenuFactory.kt:70`
- Signal: `ux-copy`
- Evidence: `sb.append("• **언어**: 봇 응답 언어(한/영)\n")`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0442 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/MenuFactory.kt:72`
- Signal: `ux-error-empty-state`
- Evidence: `sb.append("• **기본 모델**: 아직 연결된 프로바이더가 없어 *자동 선택*만 있어요. 프로바이더가 PC를 연결하면 그 PC의 모델들이 여기 채워집니다.\n")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0443 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/Messages.kt:17`
- Signal: `ux-error-empty-state`
- Evidence: `Key.NO_PROVIDER to "현재 풀에 온라인 프로바이더가 없습니다.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0444 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/ProviderOnboarding.kt:42`
- Signal: `ux-error-empty-state`
- Evidence: `"🪟 **Windows** — 먼저 PowerShell(관리자)을 여세요: `Win + X` → 터미널(관리자) / 또는 시작 메뉴에서 PowerShell 검색 → 우클릭 → 관리자 권한 실행.\n" +`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0445 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:97`
- Signal: `ux-error-empty-state`
- Evidence: `badge.textContent = "연결 실패";`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0446 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:145`
- Signal: `ux-error-empty-state`
- Evidence: `$("wizardPreview").textContent = `마법사 옵션 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0447 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:192`
- Signal: `ux-error-empty-state`
- Evidence: `$("wizardPreview").textContent = `미리보기 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0448 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:210`
- Signal: `ux-error-empty-state`
- Evidence: `$("wizardPreview").textContent = `저장 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0449 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:236`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("routingModelCandidates", catalog.modelSummaries?.slice(0, 12), "사용 가능한 모델 후보가 없습니다", (m) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0450 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:237`
- Signal: `ux-error-empty-state`
- Evidence: ``<li><strong>${esc(m.modelName)}${m.recommended ? " · 추천" : ""}${m.preferred ? " · 선호" : ""}</strong><span>${esc(m.available ? "사용 가능" : "불가`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0451 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:243`
- Signal: `ux-error-empty-state`
- Evidence: `["요청 모델", choice.requestedModel || "(직접 선택 없음)"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0452 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:244`
- Signal: `ux-error-empty-state`
- Evidence: `["선호 모델", choice.preferredModel || "(정책 선호 없음)"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0453 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:245`
- Signal: `ux-error-empty-state`
- Evidence: `["선택 모델", choice.selectedModel || "(선택 실패)"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0454 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:246`
- Signal: `ux-copy`
- Evidence: `["응답 모드", choice.responseMode || "balanced"],`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0455 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:247`
- Signal: `ux-error-empty-state`
- Evidence: `["fallback", choice.fallbackReason || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0456 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:249`
- Signal: `ux-error-empty-state`
- Evidence: `["유저 안내", choice.userMessage || "추가 안내 없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0457 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:252`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("routingModelChoice", rows, "선택 결과 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0458 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:274`
- Signal: `ux-error-empty-state`
- Evidence: `$("routingResult").textContent = `현재 정책 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0459 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:290`
- Signal: `ux-error-empty-state`
- Evidence: `$("routingResult").textContent = `라우팅 정책 저장 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0460 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:306`
- Signal: `ux-error-empty-state`
- Evidence: ``사용 가능: ${(catalog.availableModels || []).join(", ") || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0461 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:307`
- Signal: `ux-error-empty-state`
- Evidence: ``허용됐지만 현재 불가: ${(catalog.unavailableAllowedModels || []).join(", ") || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0462 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:310`
- Signal: `ux-error-empty-state`
- Evidence: `$("routingResult").textContent = `모델 후보 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0463 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:328`
- Signal: `ux-error-empty-state`
- Evidence: ``선택 모델: ${choice.selectedModel || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0464 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:329`
- Signal: `ux-error-empty-state`
- Evidence: ``fallback: ${choice.fallbackReason || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0465 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:334`
- Signal: `ux-error-empty-state`
- Evidence: `$("routingResult").textContent = `모델 선택 확인 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0466 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:361`
- Signal: `ux-error-empty-state`
- Evidence: `], "RAG 상태 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0467 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:370`
- Signal: `ux-error-empty-state`
- Evidence: `) : ["- 아직 지식공간이 없습니다."]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0468 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:373`
- Signal: `ux-error-empty-state`
- Evidence: `...(actions.length ? actions.map((a) => `- ${a}`) : ["- 없음"]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0469 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:384`
- Signal: `ux-error-empty-state`
- Evidence: `["최근 작업", latest ? `#${latest.id} · ${latest.status} · chunks ${latest.chunkCount}` : "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0470 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:385`
- Signal: `ux-error-empty-state`
- Evidence: `["실행 명령", commands[0] || ops.nextActions?.[0] || "색인할 작업 없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0471 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:386`
- Signal: `ux-error-empty-state`
- Evidence: `], "색인 작업 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0472 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:407`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `RAG 상태 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0473 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:423`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `지식공간 생성 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0474 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:443`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `지식 소스 추가 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0475 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:460`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `색인 작업 큐잉 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0476 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:479`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `색인 작업 상태 기록 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0477 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:521`
- Signal: `ux-error-empty-state`
- Evidence: `: ["검색 결과가 없습니다. 지식공간/색인/질문 키워드를 확인하세요."]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0478 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:524`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `RAG 검색 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0479 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:548`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `골든 케이스 JSON 파싱 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0480 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:569`
- Signal: `ux-error-empty-state`
- Evidence: `: ["평가 결과가 없습니다."]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0481 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:572`
- Signal: `ux-error-empty-state`
- Evidence: `$("knowledgeResult").textContent = `RAG 평가 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0482 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:598`
- Signal: `ux-error-empty-state`
- Evidence: `items.push(["최근 사유", reasons || "아직 없음"]);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0483 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:599`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("qualitySummary", items, "품질 요약 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0484 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:605`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("qualityReviewQueue", review.queue?.slice(0, 12), "검토할 신고 없음", (item) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0485 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:606`
- Signal: `ux-error-empty-state`
- Evidence: ``<li><strong>#${esc(item.id)} · ${esc(item.feedbackType)} · rating ${esc(item.rating ?? "-")}</strong><span>channel ${esc(item.channelId)} ·`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0486 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:611`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("qualityModelSignals", models?.slice(0, 12), "모델 품질 신호 없음", (model) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0487 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:640`
- Signal: `ux-error-empty-state`
- Evidence: `$("qualityResult").textContent = `품질 현황 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0488 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:658`
- Signal: `ux-error-empty-state`
- Evidence: `$("qualityResult").textContent = `피드백 저장 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0489 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:679`
- Signal: `ux-error-empty-state`
- Evidence: `$("qualityResult").textContent = `신고 검토 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0490 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:687`
- Signal: `ux-label-consistency`
- Evidence: `["다중응답 안전", dashboard.fanoutSafe ? "가능" : "제한 필요"],`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0491 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:689`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("safetyOverloadSummary", summary, "과부하 요약 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0492 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:692`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("safetyOverloadAlerts", dashboard.alerts?.slice(0, 12), "활성 과부하 알림 없음", (alert) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0493 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:700`
- Signal: `ux-error-empty-state`
- Evidence: `["비활성 기능", (plan.disabledFeatures || []).join(", ") || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0494 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:701`
- Signal: `ux-error-empty-state`
- Evidence: `["이유", (plan.reasons || []).join(" / ") || "위험 없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0495 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:703`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("safetyExecutionPlan", planItems, "실행 계획 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0496 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:725`
- Signal: `ux-error-empty-state`
- Evidence: `$("safetyResult").textContent = `과부하 현황 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0497 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:745`
- Signal: `ux-error-empty-state`
- Evidence: `$("safetyResult").textContent = `Provider 보호 상태 저장 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0498 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:791`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("localPresetList", local?.presets?.slice(0, 8), "서버 프리셋 없음", (p) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0499 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:794`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("publishedPresetList", published?.presets?.slice(0, 8), "게시 프리셋 없음", (p) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0500 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:795`
- Signal: `ux-error-empty-state`
- Evidence: ``<li><strong>${esc(p.id)} · ${esc(p.title)}</strong><span>좋아요 ${esc(p.likeCount)} · 가져오기 ${esc(p.importCount)} · 신고 ${esc(p.reportCount)} · `
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0501 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:818`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `프리셋 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0502 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:834`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `프리셋 생성 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0503 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:855`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `프리셋 수정 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0504 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:871`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `게시 프리셋 수정 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0505 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:890`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `게시 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0506 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:905`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `삭제 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0507 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:921`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `게시 프리셋 숨김 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0508 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:937`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `게시 프리셋 비공개 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0509 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:953`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `게시 프리셋 재공개 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0510 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:968`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `따봉 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0511 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:990`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `신고 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0512 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1000`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("presetModerationList", summary?.queue?.slice(0, 12), "검토할 신고 없음", (item) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0513 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1017`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `신고 큐 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0514 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1037`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetManageResult").textContent = `신고 처리 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0515 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1078`
- Signal: `ux-copy`
- Evidence: `["다중응답", featureState(features.multiResponse)],`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0516 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1079`
- Signal: `ux-copy`
- Evidence: `["다중응답 대시보드", featureState(features.multiResponseDashboard)],`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0517 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1084`
- Signal: `ux-error-empty-state`
- Evidence: `], "기능 플래그 데이터 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0518 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1097`
- Signal: `ux-error-empty-state`
- Evidence: `["최근 보호 사유", (summary.recentProviderProtectionReasons || []).join(" / ") || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0519 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1099`
- Signal: `ux-error-empty-state`
- Evidence: `["위험 코드", (summary.riskCodes || []).join(", ") || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0520 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1100`
- Signal: `ux-error-empty-state`
- Evidence: `], "다중응답 운영 데이터 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0521 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1107`
- Signal: `ux-error-empty-state`
- Evidence: `["사유", (recommendation.reasons || []).join(", ") || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0522 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1108`
- Signal: `ux-error-empty-state`
- Evidence: `["Provider", (recommendation.providers || []).map((p) => `${p.providerLabel}·${p.modelName || "-"}`).join(" / ") || "없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0523 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1109`
- Signal: `ux-error-empty-state`
- Evidence: `], "추천 fanout 미리보기 없음", ([label, value]) => `<li><strong>${esc(label)}</strong><span>${esc(value)}</span></li>`);`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0524 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1110`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("multiProviderLoad", summary.providerLoads?.slice(0, 8), "Provider 부하 데이터 없음", (p) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0525 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1113`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("multiRecentRuns", runs?.slice(0, 8), "최근 다중응답 실행 없음", (run) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0526 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1142`
- Signal: `ux-copy`
- Evidence: ``다중응답 상태: ${summary.status || "unknown"}`,`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0527 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1151`
- Signal: `ux-error-empty-state`
- Evidence: `...((summary.nextActions || []).length ? summary.nextActions.map((a) => `- ${a}`) : ["- 없음"]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0528 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1154`
- Signal: `ux-error-empty-state`
- Evidence: `$("multiResult").textContent = `다중응답 운영 상태 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0529 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1171`
- Signal: `ux-error-empty-state`
- Evidence: `$("multiResult").textContent = `다중응답 정책 저장 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0530 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1194`
- Signal: `ux-error-empty-state`
- Evidence: `$("multiResult").textContent = `수정 스냅샷 계산 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0531 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1206`
- Signal: `ux-error-empty-state`
- Evidence: `["다음 행동", metadata?.degradedReason || "필요 없음"],`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0532 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1208`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("dashboardFreshness", rows, "상태 신뢰도 정보 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0533 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1235`
- Signal: `ux-error-empty-state`
- Evidence: `$("dashboardProjectionResult").textContent = `projection 재생성 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0534 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1250`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("networkActions", data.nextActions?.slice(0, 5), "권장 액션 없음", (a) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0535 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1253`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("channelAiCards", data.channels?.slice(0, 6), "채널 AI 없음", (c) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0536 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1256`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("modelMap", data.modelMap?.slice(0, 6), "사용 가능한 모델 없음", (m) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0537 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1259`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("growthTimeline", data.growthTimeline?.slice(0, 5), "최근 성장 이벤트 없음", (e) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0538 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1262`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("presetCatalog", data.publishedPresets?.slice(0, 5), "게시된 프리셋 없음", (p) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0539 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1265`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("changeApproval", data.changeApproval?.pendingItems?.slice(0, 5), data.changeApproval?.nextActions?.[0] || "승인 대기 없음", (p) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0540 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1266`
- Signal: `ux-error-empty-state`
- Evidence: ``<li><strong>#${esc(p.channelId)} 변경 대기</strong><span>${esc(p.reason || "사유 없음")} · 제안 ${esc(p.proposedBehaviorId || "-")}</span></li>`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0541 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1274`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("qualityReview", qualityItems, "품질 데이터 없음", ([label, value]) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0542 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1289`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("launchChecklist", headline, "체크리스트 없음", (item) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0543 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1304`
- Signal: `ux-error-empty-state`
- Evidence: `renderList("launchChecklist", [{ title: "체크리스트 로딩 실패", status: "error", evidence: [e.message] }], "체크리스트 없음", (item) =>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0544 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1314`
- Signal: `ux-error-empty-state`
- Evidence: `? ["- 충돌 없음: 바로 가져올 수 있습니다."]`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0545 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1318`
- Signal: `ux-error-empty-state`
- Evidence: `preview.willOverwriteChannelAi ? "- 기존 채널 AI 설정을 덮어쓸 수 있음" : "- 기존 채널 AI 덮어쓰기 없음",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0546 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1327`
- Signal: `ux-error-empty-state`
- Evidence: ``태그: ${(preview.tags || []).join(", ") || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0547 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1328`
- Signal: `ux-error-empty-state`
- Evidence: ``질문 예시: ${(preview.exampleQuestions || []).join(" / ") || "없음"}`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0548 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1336`
- Signal: `ux-error-empty-state`
- Evidence: `"문제가 없으면 [미리보기한 프리셋 가져오기]를 누르세요.",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0549 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1366`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetImportResult").textContent = `프리셋 미리보기 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0550 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1389`
- Signal: `ux-error-empty-state`
- Evidence: `$("presetImportResult").textContent = `프리셋 가져오기 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0551 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1424`
- Signal: `ux-error-empty-state`
- Evidence: `).join("") || `<tr><td colspan="5">연결된 프로바이더 없음</td></tr>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0552 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1431`
- Signal: `ux-error-empty-state`
- Evidence: `).join("") || `<tr><td colspan="5">요청 없음</td></tr>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0553 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/app.js:1447`
- Signal: `ux-error-empty-state`
- Evidence: `$("writeResult").textContent = res.ok ? `✅ 적용됨 (${path})` : `⛔ 실패 ${res.status}(인증 필요?)`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0554 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:103`
- Signal: `ux-error-empty-state`
- Evidence: `<table id="providers"><thead><tr><th>provider</th><th>상태</th><th>처리중</th><th>대기</th><th>실패</th><th>모델수</th></tr></thead><tbody></tbody></tab`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0555 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:151`
- Signal: `ux-label-consistency`
- Evidence: `<label>응답 모드`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0556 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:216`
- Signal: `ux-error-empty-state`
- Evidence: `<option value="failed">실패</option>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0557 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:225`
- Signal: `ux-error-empty-state`
- Evidence: `<input id="knowledgeJobReason" type="text" placeholder="실패/취소 사유 또는 완료 메모" />`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0558 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:229`
- Signal: `ux-error-empty-state`
- Evidence: `<input id="knowledgeSearchQuery" type="search" placeholder="예: 배포 실패 시 롤백 방법" />`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0559 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:325`
- Signal: `ux-copy`
- Evidence: `<p class="muted">고품질·다중응답보다 참여 PC 보호가 우선입니다. 위험 Provider는 고급 요청에서 제외하고, 회복되면 normal로 되돌립니다.</p>`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0560 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:335`
- Signal: `ux-label-consistency`
- Evidence: `<label>응답 모드`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0561 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:346`
- Signal: `ux-error-empty-state`
- Evidence: `<input id="safetyReason" type="text" placeholder="예: CPU 과열, 실패율 급증, Provider 요청" />`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0562 [UX/P1] 패널/버튼 라벨 일관성 점검

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:379`
- Signal: `ux-label-consistency`
- Evidence: `<label>응답 모드<input id="presetResponseMode" type="text" placeholder="fast / balanced / deep" /></label>`
- Recommendation: 주요 행동 1개는 primary, 보조 행동은 secondary로 고정하고 동일 용어를 문서/Discord/웹에 재사용

### IMP-0563 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/dashboard/index.html:473`
- Signal: `ux-copy`
- Evidence: `<button id="multiSavePolicy">다중응답 정책 저장</button>`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0564 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/install.html:241`
- Signal: `ux-error-empty-state`
- Evidence: `<div class="open-terminal"><b>PowerShell 여는 법</b><br />단축키: <code>Win + X</code> → 터미널(관리자) 또는 PowerShell(관리자)<br />GUI: 시작 버튼 → PowerShell `
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0565 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/install.html:252`
- Signal: `ux-error-empty-state`
- Evidence: `<div class="note"><span class="ic">!</span><p><b>PowerShell을 관리자 권한</b>으로 실행한 뒤 붙여넣으세요. SmartScreen 경고가 뜨면 <b>추가 정보 → 실행</b>을 누르면 됩니다.</p></`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0566 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:112`
- Signal: `ux-error-empty-state`
- Evidence: `$("catalog").innerHTML = `<article class="card"><h3>아직 공개된 프리셋이 없습니다.</h3><p>대시보드에서 서버 프리셋을 게시하면 여기에 나타납니다.</p></article>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0567 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:125`
- Signal: `ux-error-empty-state`
- Evidence: `<p>${esc(preset.description || "설명 없음")}</p>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0568 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:150`
- Signal: `ux-error-empty-state`
- Evidence: `$("status").textContent = `프리셋 로딩 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0569 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:157`
- Signal: `ux-error-empty-state`
- Evidence: `$("recommendations").innerHTML = `<span class="badge">추천할 공개 프리셋이 아직 없습니다.</span>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0570 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:179`
- Signal: `ux-error-empty-state`
- Evidence: `$("facets").innerHTML = buttons.length ? buttons.join("") : `<span class="badge">아직 집계된 카테고리/태그가 없습니다.</span>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0571 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:200`
- Signal: `ux-error-empty-state`
- Evidence: `$("webReadinessMessage").textContent = `프리셋 웹 기능 상태 확인 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0572 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:214`
- Signal: `ux-error-empty-state`
- Evidence: `$("recommendations").innerHTML = `<span class="badge">추천 로딩 실패: ${esc(e.message)}</span>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0573 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:215`
- Signal: `ux-error-empty-state`
- Evidence: `$("facets").innerHTML = `<span class="badge">탐색 필터 로딩 실패</span>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0574 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:222`
- Signal: `ux-error-empty-state`
- Evidence: `$("importHistory").innerHTML = "<li>아직 가져오기 기록이 없습니다.</li>";`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0575 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:263`
- Signal: `ux-error-empty-state`
- Evidence: `$("importHistory").innerHTML = `<li>가져오기 기록 로딩 실패: ${esc(e.message)}</li>`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0576 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:286`
- Signal: `ux-error-empty-state`
- Evidence: ``프리셋: ${published.title || published.name || "이름 없음"} (#${selectedPresetId})`,`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0577 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:293`
- Signal: `ux-copy`
- Evidence: `optionalLine("응답 모드", published.responseMode || behavior.responseMode),`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0578 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:309`
- Signal: `ux-error-empty-state`
- Evidence: `preview.willOverwriteChannelAi ? "- 기존 채널 AI 설정을 덮어쓸 수 있음" : "- 기존 채널 AI 덮어쓰기 없음",`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0579 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:313`
- Signal: `ux-error-empty-state`
- Evidence: `...(conflicts.length ? conflicts.map((c) => `- [${c.severity}] ${c.code}: ${c.message}`) : ["- 충돌 없음"]),`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0580 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:338`
- Signal: `ux-error-empty-state`
- Evidence: `if (!Number.isFinite(selectedPresetId)) throw new Error("프리셋 ID를 확인할 수 없습니다.");`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0581 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:361`
- Signal: `ux-error-empty-state`
- Evidence: `$("preview").textContent = `미리보기 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0582 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:381`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `자동 복사 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0583 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:419`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `신고 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0584 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:437`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `따봉 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0585 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:455`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `추천 취소 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0586 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:476`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `자동 복사 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0587 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/app.js:502`
- Signal: `ux-error-empty-state`
- Evidence: `$("result").textContent = `가져오기 실패: ${e.message}`;`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0588 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/index.html:68`
- Signal: `ux-error-empty-state`
- Evidence: `<input id="adminToken" type="password" autocomplete="off" placeholder="가져오기 권한 토큰" />`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0589 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Dashboard/API
- File: `central-server/src/main/resources/static/presets/index.html:89`
- Signal: `ux-error-empty-state`
- Evidence: `<p class="section-copy">신고가 없는 공개 프리셋 중에서 좋아요·가져오기·안전 신호를 함께 본 추천입니다.</p>`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0590 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:1`
- Signal: `ux-error-empty-state`
- Evidence: `# 냥시스턴트 Discord 봇 권한 명세`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0591 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:3`
- Signal: `ux-error-empty-state`
- Evidence: `이 문서는 냥시스턴트를 Discord 서버에 초대하거나 운영할 때 필요한 **서버 권한**, **OAuth2 scope**, **Privileged Gateway Intent** 를 명확히 정의한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0592 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:7`
- Signal: `ux-error-empty-state`
- Evidence: `- `관리자(Administrator)` 권한은 기본 요구사항이 아니다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0593 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:8`
- Signal: `ux-error-empty-state`
- Evidence: `- `/질문`, `/도움말`, 버튼, 모달 같은 **슬래시/인터랙션 기능은 Message Content Intent 없이도 동작**해야 한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0594 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:10`
- Signal: `ux-error-empty-state`
- Evidence: `- 권한/Intent 누락은 봇 전체 장애처럼 보이면 안 된다. 가능한 한 관리자에게 무엇을 켜야 하는지 알려줘야 한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0595 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:23`
- Signal: `ux-error-empty-state`
- Evidence: `- `identify`, `guilds`, `email` 등 사용자 OAuth 로그인 scope 는 봇 초대에는 필요 없다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0596 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:25`
- Signal: `ux-error-empty-state`
- Evidence: `## 2. 서버 권한 체크리스트`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0597 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:27`
- Signal: `ux-error-empty-state`
- Evidence: `### 2.1 최소 권한`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0598 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:29`
- Signal: `ux-error-empty-state`
- Evidence: `슬래시 명령/버튼/모달을 기본 봇 이름으로만 쓰는 최소 권한이다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0599 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:31`
- Signal: `ux-error-empty-state`
- Evidence: `| Discord 권한 | 필수 여부 | 필요한 기능 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0600 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:37`
- Signal: `ux-error-empty-state`
- Evidence: `| 슬래시 명령어 사용 / Use Application Commands | 권장 | 서버/채널 권한 정책에서 앱 명령 사용을 막지 않기 위해 허용한다. |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0601 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:51`
- Signal: `ux-error-empty-state`
- Evidence: `### 2.2 권장 권한`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0602 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:53`
- Signal: `ux-error-empty-state`
- Evidence: `냥시스턴트의 현재 제품 UX를 제대로 쓰기 위한 권장 권한이다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0603 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:55`
- Signal: `ux-error-empty-state`
- Evidence: `| Discord 권한 | 필수 여부 | 필요한 기능 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0604 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:64`
- Signal: `ux-error-empty-state`
- Evidence: `| 웹후크 관리 / Manage Webhooks | 강력 권장 | 채널별 AI 이름/프로필 아이콘으로 답변을 보내는 Channel AI Webhook 표시. 없으면 일반 봇 응답으로 폴백해야 한다. |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0605 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:79`
- Signal: `ux-error-empty-state`
- Evidence: `### 2.3 채널 AI 프로필 표시 권한`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0606 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:81`
- Signal: `ux-error-empty-state`
- Evidence: `채널별 AI가 `코드냥`, `번역냥`처럼 **그 채널만의 이름/아이콘으로 답변**하려면 다음 권한이 필요하다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0607 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:87`
- Signal: `ux-error-empty-state`
- Evidence: `없을 때 기대 UX:`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0608 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:90`
- Signal: `ux-error-empty-state`
- Evidence: `2. Webhook 전송에 실패하면 일반 봇 메시지로 폴백한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0609 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:91`
- Signal: `ux-error-empty-state`
- Evidence: `3. 관리자에게 “채널 AI 이름/아이콘 표시에는 웹후크 관리 권한이 필요합니다”를 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0610 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:106`
- Signal: `ux-error-empty-state`
- Evidence: `- `/질문`, `/도움말`, `/메뉴`, 버튼, 모달, 컨텍스트 메뉴는 Message Content Intent 없이도 설계상 동작 가능해야 한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0611 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:117`
- Signal: `ux-error-empty-state`
- Evidence: `3. Bot Permissions 에서 권장 권한 선택:`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0612 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:144`
- Signal: `ux-error-empty-state`
- Evidence: `권한/Intent 문제는 사용자가 봤을 때 “봇이 멍청하게 죽었다”가 아니라 “무엇을 켜야 하는지 알려준다”가 되어야 한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0613 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:149`
- Signal: `ux-error-empty-state`
- Evidence: `| Manage Webhooks 없음 | `채널 AI 이름/아이콘 표시에는 웹후크 관리 권한이 필요합니다. 답변은 기본 봇 이름으로 보냅니다.` |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0614 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:150`
- Signal: `ux-error-empty-state`
- Evidence: `| Send Messages 없음 | 가능한 경우 ephemeral interaction 으로 `이 채널에 메시지 보내기 권한이 없습니다.` |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0615 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:151`
- Signal: `ux-error-empty-state`
- Evidence: `| Embed Links 없음 | plain text 도움말로 폴백. |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0616 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/BOT_PERMISSIONS.md:152`
- Signal: `ux-error-empty-state`
- Evidence: `| 채널 보기 없음 | 해당 채널 기능을 비활성/경고로 처리. |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0617 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:18`
- Signal: `ux-error-empty-state`
- Evidence: `| `REQ-PPOOL-003` | 한 번이라도 기여한 프로바이더는 오프라인이어도 기여순위에 영구 표시된다. | `central-server/src/test/resources/features/provider_pool_lifecycle.feature` `
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0618 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:36`
- Signal: `ux-error-empty-state`
- Evidence: `| 6 | 질문 처리 중 봇이 삭제되면? | 진행 중 요청은 실패/취소 처리하고 프로바이더 세션을 닫는다. 사용자에게 후속 응답을 보장하지 않는다. | 구현됨 | `ConnectionRegistryTest` |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0619 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:37`
- Signal: `ux-error-empty-state`
- Evidence: `| 7 | 답변 전송 중 Discord 권한이 사라지면? | 프로바이더 처리는 완료하되 Discord 전송 실패는 로그/관측성으로 남기고 가능하면 기본 응답으로 폴백한다. | 부분 구현 | 어댑터 테스트 보강 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0620 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:38`
- Signal: `ux-error-empty-state`
- Evidence: `| 8 | 봇이 없는 서버인데 에이전트가 계속 켜져 있으면? | 길드 제거 이벤트 또는 정합성 검사로 세션을 닫는다. | 구현됨 | `GuildRemovalCleanupServiceTest` + `ProviderPoolReconciliationServ`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0621 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:40`
- Signal: `ux-error-empty-state`
- Evidence: `| 10 | 서버 소유자가 바뀌면? | 설정은 유지하고, 이후 명령 권한은 Discord 의 현재 관리자 권한 기준으로 판정한다. | 구현됨 | `CommandServiceTest` |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0622 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:41`
- Signal: `ux-error-empty-state`
- Evidence: `| 11 | 봇의 관리자 권한 일부가 줄어들면? | 풀 자체는 유지하되 실패한 기능은 명확히 안내한다. 임시 권한 문제로 등록을 삭제하지 않는다. | 정책 확정 | Discord 어댑터 테스트 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0623 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:42`
- Signal: `ux-error-empty-state`
- Evidence: `| 12 | 채널 웹훅 권한이 없는데 채널 프로필을 설정하면? | 답변은 일반 봇 응답으로 폴백하고, 관리자에게 웹훅 권한 필요를 안내한다. | 부분 구현 | 웹훅 폴백 테스트 보강 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0624 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:48`
- Signal: `ux-error-empty-state`
- Evidence: `| 18 | DB 에는 승인 상태인데 에이전트가 꺼져 있으면? | `/providers` 는 승인됨/오프라인으로 보여준다. 라우팅에는 온라인 세션만 사용한다. | 구현됨 | CommandService 테스트 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0625 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:54`
- Signal: `ux-error-empty-state`
- Evidence: `| 24 | 봇이 없는 서버 ID 로 토큰이 만들어지면? | 운영에서는 봇 제거 정리로 미사용 토큰을 폐기한다. 서버에 묶이지 않은 토큰은 WS 인증 단계에서 거부한다. dev 엔드포인트는 운영에서 비활성이다. | 부분 구현 | `RelayWebSoc`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0626 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:56`
- Signal: `ux-error-empty-state`
- Evidence: `| 26 | 배포/마이그레이션 중 에이전트 연결이 끊기면? | 에이전트는 지수 백오프로 재연결한다. 진행 중 요청은 실패할 수 있다. | 구현됨(에이전트) | E2E/운영 확인 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0627 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:58`
- Signal: `ux-error-empty-state`
- Evidence: `| 28 | 일시 권한 장애와 진짜 봇 삭제를 어떻게 구분하나? | 권한 장애는 등록 삭제가 아니라 경고/폴백이다. 봇 제거 이벤트 또는 멤버십 부재만 삭제 트리거다. | 정책 확정 | 어댑터 테스트 |`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0628 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/EDGE_CASE_POLICY.md:66`
- Signal: `ux-error-empty-state`
- Evidence: `3. **P1 운영 품질**: 웹훅 권한 폴백, Discord API 장애/권한 장애 안내, 오래된 DB 복원 런북.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0629 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/FAQ.md:11`
- Signal: `ux-error-empty-state`
- Evidence: `그 LLM 들을 공정하게 나눠 쓰는 구조입니다. **금전 거래가 아니며**(판매·결제 없음), 기여·동의·`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0630 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/FAQ.md:52`
- Signal: `ux-error-empty-state`
- Evidence: `**Q. "프로바이더가 없습니다"가 떠요.** — 풀에 온라인 프로바이더가 없을 때입니다. 누군가`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0631 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:5`
- Signal: `ux-error-empty-state`
- Evidence: `> 예상치 못한 Discord 권한/Intent/네트워크/Provider 상태 에러가 났을 때 사용자가 이해할 수 있는 안내가 부족하다. 현재 체감 처리율은 약 20%이며, 목표는 운영 전 90% 이상이다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0632 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:17`
- Signal: `ux-error-empty-state`
- Evidence: `- 사용자는 `애플리케이션이 응답하지 않았어요`만 보고 끝나면 안 된다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0633 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:19`
- Signal: `ux-error-empty-state`
- Evidence: `- 권한 부족은 “실패”가 아니라 “설정 필요”로 설명한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0634 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:20`
- Signal: `ux-error-empty-state`
- Evidence: `- Provider 부재/오프라인은 사용자 잘못이 아니므로 불안하지 않게 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0635 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:24`
- Signal: `ux-error-empty-state`
- Evidence: `## 2. P0 — 바로 잡아야 하는 UX 실패`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0636 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:30`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-005. `/메뉴`, `/질문`, `/도움말`, `/내상태`가 gateway 연결 실패 상태에서 어떤 사용자 경험을 보이는지 재현 테스트한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0637 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:35`
- Signal: `ux-error-empty-state`
- Evidence: `- [x] UX-010. `Manage Webhooks` 권한이 없으면 채널 AI webhook 실패를 일반 봇 응답으로 폴백하고 관리자 안내를 남긴다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0638 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:37`
- Signal: `ux-error-empty-state`
- Evidence: `## 3. P1 — 권한/설정 문제 안내`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0639 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:39`
- Signal: `ux-error-empty-state`
- Evidence: `- [x] UX-011. 봇 초대 권한 점검 명령(`/bot-permissions`) 또는 설정 패널 섹션을 만든다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0640 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:40`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-012. View Channel 없음/채널 접근 불가를 감지하고 관리자에게 알려준다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0641 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:41`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-013. Send Messages 없음 시 가능한 interaction 응답으로 권한 문제를 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0642 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:42`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-014. Embed Links 없음 시 plain text 도움말로 폴백한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0643 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:43`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-015. Read Message History 없음 시 컨텍스트/멘션 관련 제한을 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0644 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:44`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-016. Add Reactions 없음 시 성공 리액션 없이 메시지 답변으로 폴백한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0645 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:45`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-017. Use Application Commands 제한 시 서버 관리자에게 앱 명령 권한 설정을 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0646 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:46`
- Signal: `ux-error-empty-state`
- Evidence: `- [x] UX-018. 권한 문제 메시지에는 “어디서 켜는지”와 “왜 필요한지”를 같이 쓴다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0647 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:47`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-019. 권한 안내 문구는 `docs/BOT_PERMISSIONS.md`와 같은 용어를 사용한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0648 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:48`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-020. 관리자 전용 설정 문제는 일반 유저에게 내부 권한 세부값을 과하게 노출하지 않는다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0649 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:56`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-025. 사용자 입력/권한/정책 오류는 Provider fallback 하지 않고 즉시 안내한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0650 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:67`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-033. 최근 interaction 실패 원인을 metric 으로 집계한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0651 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:68`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-034. 권한 부족 실패와 Provider 실패를 다른 metric 으로 분리한다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0652 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:70`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-036. smoke test 실패 시 배포 성공으로 간주하지 않는 기준을 만든다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0653 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:72`
- Signal: `ux-error-empty-state`
- Evidence: `- [ ] UX-038. webhook 생성/전송 실패율을 metric 으로 남긴다.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0654 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:90`
- Signal: `ux-copy`
- Evidence: `- [ ] Manage Webhooks OFF 상태에서도 질문 결과가 사라지지 않고 일반 봇 응답으로 폴백한다.`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0655 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Docs/Planning
- File: `docs/UX_ERROR_HANDLING_90.md:94`
- Signal: `ux-error-empty-state`
- Evidence: `- 봇 권한 명세(BOT_PERMISSIONS.md)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0656 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/__init__.py:14`
- Signal: `ux-copy`
- Evidence: `__all__ = ["AGENT_VERSION", "FrameType", "ErrorCode"]`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0657 [UX/P1] 사용자 다음 행동 안내 보강

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:71`
- Signal: `ux-copy`
- Evidence: `await self._safe_send(conn, InferError(req.request_id, code=ErrorCode.BUSY, message="일일 한도 초과"))`
- Recommendation: 문구에 현재 상태, 원인, 사용자가 지금 누를 버튼/명령, 관리자에게 요청할 권한을 함께 포함

### IMP-0658 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:106`
- Signal: `ux-error-empty-state`
- Evidence: `logger.debug("응답 송신 실패(연결 끊김?): %s", exc)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0659 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:135`
- Signal: `ux-error-empty-state`
- Evidence: `logger.info("감지된 모델: %s", self._models or "(없음)")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0660 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:137`
- Signal: `ux-error-empty-state`
- Evidence: `logger.warning("Ollama 모델 목록 실패(%s) — 빈 목록으로 진행", exc)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0661 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:190`
- Signal: `ux-error-empty-state`
- Evidence: `logger.error("❌ Ollama 연결 실패: %s", cfg.ollama_url)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0662 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/agent.py:200`
- Signal: `ux-error-empty-state`
- Evidence: `logger.error("⚠️ 추론 테스트 실패: %s", exc)`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0663 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/config.py:37`
- Signal: `ux-error-empty-state`
- Evidence: `f"daily_limit={self.daily_limit}, token={'***' if self.token else '(없음)'})"`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0664 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/config.py:59`
- Signal: `ux-error-empty-state`
- Evidence: `p.add_argument("--self-test", action="store_true", help="연결 없이 Ollama 자가 점검 후 종료")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0665 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/config.py:68`
- Signal: `ux-error-empty-state`
- Evidence: `"""CLI/env/저장파일 로부터 (config, verbose) 를 만든다. 토큰이 없으면 SystemExit.`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

### IMP-0666 [UX/P1] 실패/빈상태 문구를 행동 유도형으로 개선

- Area: Provider Relay
- File: `provider-agent/src/provider_agent/config.py:80`
- Signal: `ux-error-empty-state`
- Evidence: `build_parser().error("토큰이 필요합니다: --token 또는 AGENT_TOKEN 환경변수")`
- Recommendation: 단순 실패 문구 대신 재시도, Provider 참여, 권한 확인, 상태 확인 버튼 중 하나를 제시

## Refactor

### IMP-0667 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(1466라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0668 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0669 [Refactor/P1] 의존성 폭 축소

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `many-imports`
- Evidence: import 32개로 의존 방향이 넓음
- Recommendation: 생성자 주입 서비스 묶음을 use-case facade로 줄이고, DTO 의존을 boundary로 이동

### IMP-0670 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `test-surface`
- Evidence: 1466라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0671 [Refactor/P1] 파일 책임 경계 재검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1`
- Signal: `large-file`
- Evidence: 1466라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0672 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:39`
- Signal: `many-classes`
- Evidence: 타입 22개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0673 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:50`
- Signal: `duplicate-literal`
- Evidence: `knowledge` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0674 [Refactor/P1] `dashboard` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:59`
- Signal: `function-boundary`
- Evidence: 함수 선언이 59라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0675 [Refactor/P1] 함수 수 기반 책임 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:59`
- Signal: `many-functions`
- Evidence: 함수 30개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0676 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:61`
- Signal: `duplicate-literal`
- Evidence: `public` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0677 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:62`
- Signal: `duplicate-literal`
- Evidence: `balanced` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0678 [Refactor/P1] `launchChecklist` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:120`
- Signal: `function-boundary`
- Evidence: 함수 선언이 120라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0679 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:134`
- Signal: `duplicate-literal`
- Evidence: `blocked` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0680 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:157`
- Signal: `duplicate-literal`
- Evidence: `warning` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0681 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:230`
- Signal: `duplicate-literal`
- Evidence: `ready` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0682 [Refactor/P1] `overview` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:250`
- Signal: `function-boundary`
- Evidence: 함수 선언이 250라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0683 [Refactor/P1] `readiness` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:278`
- Signal: `function-boundary`
- Evidence: 함수 선언이 278라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0684 [Refactor/P1] `channels` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:296`
- Signal: `function-boundary`
- Evidence: 함수 선언이 296라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0685 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:323`
- Signal: `duplicate-literal`
- Evidence: `needs_review` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0686 [Refactor/P1] `channelsSummary` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:357`
- Signal: `function-boundary`
- Evidence: 함수 선언이 357라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0687 [Refactor/P1] `changeApproval` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:395`
- Signal: `function-boundary`
- Evidence: 함수 선언이 395라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0688 [Refactor/P1] `providers` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:429`
- Signal: `function-boundary`
- Evidence: 함수 선언이 429라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0689 [Refactor/P1] `modelMap` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:454`
- Signal: `function-boundary`
- Evidence: 함수 선언이 454라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0690 [Refactor/P1] `knowledgeSpaces` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:493`
- Signal: `function-boundary`
- Evidence: 함수 선언이 493라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0691 [Refactor/P1] `guildPresets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:514`
- Signal: `function-boundary`
- Evidence: 함수 선언이 514라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0692 [Refactor/P1] `publishedPresets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:546`
- Signal: `function-boundary`
- Evidence: 함수 선언이 546라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0693 [Refactor/P1] `readiness` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:564`
- Signal: `function-boundary`
- Evidence: 함수 선언이 564라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0694 [Refactor/P1] `readinessArea` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:789`
- Signal: `function-boundary`
- Evidence: 함수 선언이 789라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0695 [Refactor/P1] `checklistItem` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:806`
- Signal: `function-boundary`
- Evidence: 함수 선언이 806라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0696 [Refactor/P1] `nextActions` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:828`
- Signal: `function-boundary`
- Evidence: 함수 선언이 828라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0697 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:843`
- Signal: `duplicate-literal`
- Evidence: `critical` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0698 [Refactor/P1] `growthActionCoveredByPrimaryAction` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:995`
- Signal: `function-boundary`
- Evidence: 함수 선언이 995라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0699 [Refactor/P1] `ChannelAiCardResponse` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1009`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1009라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0700 [Refactor/P1] `readinessRank` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1038`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1038라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0701 [Refactor/P1] `modelChannelUsage` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1051`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1051라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0702 [Refactor/P1] `qualityRank` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1063`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1063라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0703 [Refactor/P1] `burdenRank` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1071`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1071라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0704 [Refactor/P1] `splitCsv` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1080`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1080라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0705 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1152`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1152라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0706 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1193`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1193라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0707 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1212`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1212라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0708 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1297`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1297라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0709 [Refactor/P1] `state` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1419`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1419라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0710 [Refactor/P1] `risk` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1428`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1428라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0711 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/AiNetworkDashboardController.kt:1438`
- Signal: `function-boundary`
- Evidence: 함수 선언이 1438라인에 있으며 파일 전체 1466라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0712 [Refactor/P1] `overview` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:30`
- Signal: `function-boundary`
- Evidence: 함수 선언이 30라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0713 [Refactor/P1] `providerHistory` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:46`
- Signal: `function-boundary`
- Evidence: 함수 선언이 46라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0714 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:48`
- Signal: `duplicate-literal`
- Evidence: `public` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0715 [Refactor/P1] `usageTrend` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:62`
- Signal: `function-boundary`
- Evidence: 함수 선언이 62라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0716 [Refactor/P1] `requests` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:72`
- Signal: `function-boundary`
- Evidence: 함수 선언이 72라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0717 [Refactor/P1] `providerHistoryLabel` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:94`
- Signal: `function-boundary`
- Evidence: 함수 선언이 94라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0718 [Refactor/P1] `providerLabel` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/DashboardController.kt:99`
- Signal: `function-boundary`
- Evidence: 함수 선언이 99라인에 있으며 파일 전체 109라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0719 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:1`
- Signal: `test-surface`
- Evidence: 491라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0720 [Refactor/P1] 파일 책임 경계 재검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:1`
- Signal: `large-file`
- Evidence: 491라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0721 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:14`
- Signal: `many-classes`
- Evidence: 타입 11개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0722 [Refactor/P1] `savePolicy` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:18`
- Signal: `function-boundary`
- Evidence: 함수 선언이 18라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0723 [Refactor/P1] 함수 수 기반 책임 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:18`
- Signal: `many-functions`
- Evidence: 함수 18개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0724 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:25`
- Signal: `duplicate-literal`
- Evidence: `channelId` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0725 [Refactor/P1] `startRun` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:45`
- Signal: `function-boundary`
- Evidence: 함수 선언이 45라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0726 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:49`
- Signal: `duplicate-literal`
- Evidence: `requestId` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0727 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:53`
- Signal: `duplicate-literal`
- Evidence: `status` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0728 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:54`
- Signal: `duplicate-literal`
- Evidence: `candidateCount` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0729 [Refactor/P1] `recordCandidate` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:61`
- Signal: `function-boundary`
- Evidence: 함수 선언이 61라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0730 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:70`
- Signal: `duplicate-literal`
- Evidence: `answerRef` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0731 [Refactor/P1] `synthesize` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:80`
- Signal: `function-boundary`
- Evidence: 함수 선언이 80라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0732 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:90`
- Signal: `duplicate-literal`
- Evidence: `qualitySummary` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0733 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:91`
- Signal: `duplicate-literal`
- Evidence: `safetySummary` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0734 [Refactor/P1] `adoptCandidate` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:104`
- Signal: `function-boundary`
- Evidence: 함수 선언이 104라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0735 [Refactor/P1] `completeBest` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:121`
- Signal: `function-boundary`
- Evidence: 함수 선언이 121라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0736 [Refactor/P1] `fail` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:137`
- Signal: `function-boundary`
- Evidence: 함수 선언이 137라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0737 [Refactor/P1] `pseudoStreamPlan` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:146`
- Signal: `function-boundary`
- Evidence: 함수 선언이 146라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0738 [Refactor/P1] `recentRuns` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:160`
- Signal: `function-boundary`
- Evidence: 함수 선언이 160라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0739 [Refactor/P1] `runDetail` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:176`
- Signal: `function-boundary`
- Evidence: 함수 선언이 176라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0740 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:178`
- Signal: `duplicate-literal`
- Evidence: `public` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0741 [Refactor/P1] `providerLoad` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:230`
- Signal: `function-boundary`
- Evidence: 함수 선언이 230라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0742 [Refactor/P1] `decisionSummary` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:239`
- Signal: `function-boundary`
- Evidence: 함수 선언이 239라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0743 [Refactor/P1] `stats` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:265`
- Signal: `function-boundary`
- Evidence: 함수 선언이 265라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0744 [Refactor/P1] `fanoutRecommendation` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:280`
- Signal: `function-boundary`
- Evidence: 함수 선언이 280라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0745 [Refactor/P1] `operationsSummary` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:314`
- Signal: `function-boundary`
- Evidence: 함수 선언이 314라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0746 [Refactor/P1] `providerLabel` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:327`
- Signal: `function-boundary`
- Evidence: 함수 선언이 327라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0747 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:413`
- Signal: `function-boundary`
- Evidence: 함수 선언이 413라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0748 [Refactor/P1] `from` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/MultiResponseController.kt:461`
- Signal: `function-boundary`
- Evidence: 함수 선언이 461라인에 있으며 파일 전체 491라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0749 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:1`
- Signal: `test-surface`
- Evidence: 386라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0750 [Refactor/P1] 파일 책임 경계 재검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:1`
- Signal: `large-file`
- Evidence: 386라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0751 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:17`
- Signal: `duplicate-literal`
- Evidence: `preset` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0752 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:18`
- Signal: `many-classes`
- Evidence: 타입 11개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0753 [Refactor/P1] `listGuildPresets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:22`
- Signal: `function-boundary`
- Evidence: 함수 선언이 22라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0754 [Refactor/P1] 함수 수 기반 책임 분리

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:22`
- Signal: `many-functions`
- Evidence: 함수 27개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0755 [Refactor/P1] `presetDetail` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:27`
- Signal: `function-boundary`
- Evidence: 함수 선언이 27라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0756 [Refactor/P1] `publishedPresets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:32`
- Signal: `function-boundary`
- Evidence: 함수 선언이 32라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0757 [Refactor/P1] `recommendedPresets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:47`
- Signal: `function-boundary`
- Evidence: 함수 선언이 47라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0758 [Refactor/P1] `catalogFacets` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:58`
- Signal: `function-boundary`
- Evidence: 함수 선언이 58라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0759 [Refactor/P1] `webReadiness` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:61`
- Signal: `function-boundary`
- Evidence: 함수 선언이 61라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0760 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:63`
- Signal: `duplicate-literal`
- Evidence: `status` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0761 [Refactor/P1] `moderationSummary` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:79`
- Signal: `function-boundary`
- Evidence: 함수 선언이 79라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0762 [Refactor/P1] `importHistory` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:82`
- Signal: `function-boundary`
- Evidence: 함수 선언이 82라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0763 [Refactor/P1] `reports` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:93`
- Signal: `function-boundary`
- Evidence: 함수 선언이 93라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0764 [Refactor/P1] `reportsByStatus` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:96`
- Signal: `function-boundary`
- Evidence: 함수 선언이 96라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0765 [Refactor/P1] `publishedPresetDetailBySlug` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:101`
- Signal: `function-boundary`
- Evidence: 함수 선언이 101라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0766 [Refactor/P1] `publishedPresetDetail` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:106`
- Signal: `function-boundary`
- Evidence: 함수 선언이 106라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0767 [Refactor/P1] `create` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:111`
- Signal: `function-boundary`
- Evidence: 함수 선언이 111라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0768 [Refactor/P1] 반복 문자열 상수화

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:125`
- Signal: `duplicate-literal`
- Evidence: `currentRevisionId` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0769 [Refactor/P1] `saveFromChannel` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:129`
- Signal: `function-boundary`
- Evidence: 함수 선언이 129라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0770 [Refactor/P1] `update` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:148`
- Signal: `function-boundary`
- Evidence: 함수 선언이 148라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0771 [Refactor/P1] `publish` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:166`
- Signal: `function-boundary`
- Evidence: 함수 선언이 166라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0772 [Refactor/P1] `updatePublished` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:181`
- Signal: `function-boundary`
- Evidence: 함수 선언이 181라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0773 [Refactor/P1] `importPreview` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:203`
- Signal: `function-boundary`
- Evidence: 함수 선언이 203라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0774 [Refactor/P1] `importPreset` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:217`
- Signal: `function-boundary`
- Evidence: 함수 선언이 217라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0775 [Refactor/P1] `like` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:240`
- Signal: `function-boundary`
- Evidence: 함수 선언이 240라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0776 [Refactor/P1] `unlike` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:249`
- Signal: `function-boundary`
- Evidence: 함수 선언이 249라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0777 [Refactor/P1] `report` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:258`
- Signal: `function-boundary`
- Evidence: 함수 선언이 258라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0778 [Refactor/P1] `reviewReport` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:274`
- Signal: `function-boundary`
- Evidence: 함수 선언이 274라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0779 [Refactor/P1] `delete` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:288`
- Signal: `function-boundary`
- Evidence: 함수 선언이 288라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0780 [Refactor/P1] `deletePublished` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:296`
- Signal: `function-boundary`
- Evidence: 함수 선언이 296라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0781 [Refactor/P1] `unlistPublished` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:304`
- Signal: `function-boundary`
- Evidence: 함수 선언이 304라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0782 [Refactor/P1] `republishPublished` 함수 책임 축소 검토

- Area: Dashboard/API
- File: `central-server/src/main/kotlin/com/discordassistant/central/dashboard/PresetRegistryController.kt:312`
- Signal: `function-boundary`
- Evidence: 함수 선언이 312라인에 있으며 파일 전체 386라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0783 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(1575라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0784 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0785 [Refactor/P1] 의존성 폭 축소

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `many-imports`
- Evidence: import 27개로 의존 방향이 넓음
- Recommendation: 생성자 주입 서비스 묶음을 use-case facade로 줄이고, DTO 의존을 boundary로 이동

### IMP-0786 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `test-surface`
- Evidence: 1575라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0787 [Refactor/P1] 파일 책임 경계 재검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1`
- Signal: `large-file`
- Evidence: 1575라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0788 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:32`
- Signal: `many-classes`
- Evidence: 타입 5개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0789 [Refactor/P1] `lang` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:108`
- Signal: `function-boundary`
- Evidence: 함수 선언이 108라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0790 [Refactor/P1] 함수 수 기반 책임 분리

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:108`
- Signal: `many-functions`
- Evidence: 함수 85개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0791 [Refactor/P1] `adminOnly` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:110`
- Signal: `function-boundary`
- Evidence: 함수 선언이 110라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0792 [Refactor/P1] `channelAiAdminOnly` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:113`
- Signal: `function-boundary`
- Evidence: 함수 선언이 113라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0793 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:118`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0794 [Refactor/P1] `publicWebBaseUrl` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:133`
- Signal: `function-boundary`
- Evidence: 함수 선언이 133라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0795 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:141`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0796 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:143`
- Signal: `duplicate-literal`
- Evidence: `/agent` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0797 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:143`
- Signal: `broad-exception`
- Evidence: `val scheme = uri.scheme ?: return@runCatching normalized.substringBefore("/agent").trimEnd('/')`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0798 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:144`
- Signal: `broad-exception`
- Evidence: `val authority = uri.rawAuthority ?: return@runCatching normalized.substringBefore("/agent").trimEnd('/')`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0799 [Refactor/P1] `presetCatalogUrl` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:151`
- Signal: `function-boundary`
- Evidence: 함수 선언이 151라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0800 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:153`
- Signal: `duplicate-literal`
- Evidence: ` } ?: ` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0801 [Refactor/P1] `ask` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:158`
- Signal: `function-boundary`
- Evidence: 함수 선언이 158라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0802 [Refactor/P1] `completedAskReply` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:220`
- Signal: `function-boundary`
- Evidence: 함수 선언이 220라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0803 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:227`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0804 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:245`
- Signal: `broad-exception`
- Evidence: `?.let { ReplyPseudoStream(plan!!.editIntervalMs.toLong(), it.dropLast(1) + finalContent, plan.warning) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0805 [Refactor/P1] `submitAskFeedback` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:254`
- Signal: `function-boundary`
- Evidence: 함수 선언이 254라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0806 [Refactor/P1] `startRuntimeMultiResponseObservation` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:284`
- Signal: `function-boundary`
- Evidence: 함수 선언이 284라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0807 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:290`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0808 [Refactor/P1] `recordRuntimeMultiResponseResult` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:303`
- Signal: `function-boundary`
- Evidence: 함수 선언이 303라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0809 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:310`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0810 [Refactor/P1] `shouldObserveMultiResponse` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:323`
- Signal: `function-boundary`
- Evidence: 함수 선언이 323라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0811 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:326`
- Signal: `duplicate-literal`
- Evidence: `deep` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0812 [Refactor/P1] `com` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:328`
- Signal: `function-boundary`
- Evidence: 함수 선언이 328라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0813 [Refactor/P1] `elapsedMillis` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:335`
- Signal: `function-boundary`
- Evidence: 함수 선언이 335라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0814 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:340`
- Signal: `function-boundary`
- Evidence: 함수 선언이 340라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0815 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:347`
- Signal: `function-boundary`
- Evidence: 함수 선언이 347라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0816 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:354`
- Signal: `function-boundary`
- Evidence: 함수 선언이 354라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0817 [Refactor/P1] `normalizeAskResponseMode` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:361`
- Signal: `function-boundary`
- Evidence: 함수 선언이 361라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0818 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:367`
- Signal: `duplicate-literal`
- Evidence: `balanced` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0819 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:371`
- Signal: `function-boundary`
- Evidence: 함수 선언이 371라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0820 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:376`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0821 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:400`
- Signal: `function-boundary`
- Evidence: 함수 선언이 400라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0822 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:405`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0823 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:410`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0824 [Refactor/P1] `autocompleteModels` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:427`
- Signal: `function-boundary`
- Evidence: 함수 선언이 427라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0825 [Refactor/P1] `models` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:435`
- Signal: `function-boundary`
- Evidence: 함수 선언이 435라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0826 [Refactor/P1] `catalog` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:445`
- Signal: `function-boundary`
- Evidence: 함수 선언이 445라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0827 [Refactor/P1] `contributions` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:457`
- Signal: `function-boundary`
- Evidence: 함수 선언이 457라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0828 [Refactor/P1] `communityStats` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:469`
- Signal: `function-boundary`
- Evidence: 함수 선언이 469라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0829 [Refactor/P1] `fairness` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:484`
- Signal: `function-boundary`
- Evidence: 함수 선언이 484라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0830 [Refactor/P1] `myUsage` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:498`
- Signal: `function-boundary`
- Evidence: 함수 선언이 498라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0831 [Refactor/P1] `privacy` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:504`
- Signal: `function-boundary`
- Evidence: 함수 선언이 504라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0832 [Refactor/P1] `botPermissions` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:506`
- Signal: `function-boundary`
- Evidence: 함수 선언이 506라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0833 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:520`
- Signal: `function-boundary`
- Evidence: 함수 선언이 520라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0834 [Refactor/P1] `help` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:537`
- Signal: `function-boundary`
- Evidence: 함수 선언이 537라인에 있으며 파일 전체 1575라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0835 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:576`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0836 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:608`
- Signal: `duplicate-literal`
- Evidence: `• $it` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0837 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:637`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0838 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:689`
- Signal: `duplicate-literal`
- Evidence: `}`\n` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0839 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:705`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0840 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:749`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0841 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:794`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0842 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:820`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0843 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:845`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0844 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:864`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0845 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:899`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0846 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:911`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0847 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:936`
- Signal: `broad-exception`
- Evidence: `runCatching { presetRegistry.likePreset(publishedPresetId, ctx.userId) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0848 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:947`
- Signal: `broad-exception`
- Evidence: `runCatching { presetRegistry.reportPreset(publishedPresetId, ctx.userId, reason) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0849 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:957`
- Signal: `broad-exception`
- Evidence: `return runCatching { Reply(formatPresetModeration(presetRegistry.moderationSummary())) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0850 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:968`
- Signal: `broad-exception`
- Evidence: `runCatching { presetRegistry.reviewReport(reportId, decision, reviewerUserId = ctx.userId) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0851 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1046`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0852 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1083`
- Signal: `duplicate-literal`
- Evidence: `single` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0853 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1093`
- Signal: `duplicate-literal`
- Evidence: `compare` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0854 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1099`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0855 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/CommandService.kt:1141`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0856 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(1599라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0857 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0858 [Refactor/P1] 의존성 폭 축소

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `many-imports`
- Evidence: import 43개로 의존 방향이 넓음
- Recommendation: 생성자 주입 서비스 묶음을 use-case facade로 줄이고, DTO 의존을 boundary로 이동

### IMP-0859 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `test-surface`
- Evidence: 1599라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0860 [Refactor/P1] 파일 책임 경계 재검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1`
- Signal: `large-file`
- Evidence: 1599라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0861 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:55`
- Signal: `duplicate-literal`
- Evidence: `models` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0862 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:56`
- Signal: `duplicate-literal`
- Evidence: `catalog` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0863 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:57`
- Signal: `duplicate-literal`
- Evidence: `my-usage` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0864 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:58`
- Signal: `duplicate-literal`
- Evidence: `contributions` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0865 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:59`
- Signal: `duplicate-literal`
- Evidence: `community-stats` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0866 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:60`
- Signal: `duplicate-literal`
- Evidence: `privacy` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0867 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:61`
- Signal: `duplicate-literal`
- Evidence: `help` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0868 [Refactor/P1] 반복 문자열 상수화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:62`
- Signal: `duplicate-literal`
- Evidence: `welcome` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0869 [Refactor/P1] `start` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:97`
- Signal: `function-boundary`
- Evidence: 함수 선언이 97라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0870 [Refactor/P1] 함수 수 기반 책임 분리

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:97`
- Signal: `many-functions`
- Evidence: 함수 54개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0871 [Refactor/P1] `launchJda` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:105`
- Signal: `function-boundary`
- Evidence: 함수 선언이 105라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0872 [Refactor/P1] `handleDisallowedIntents` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:133`
- Signal: `function-boundary`
- Evidence: 함수 선언이 133라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0873 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:143`
- Signal: `broad-exception`
- Evidence: `runCatching { jda?.shutdownNow() }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0874 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:144`
- Signal: `broad-exception`
- Evidence: `runCatching { launchJda(messageContentIntent = false) }`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0875 [Refactor/P1] `stop` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:153`
- Signal: `function-boundary`
- Evidence: 함수 선언이 153라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0876 [Refactor/P1] `registerCommands` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:157`
- Signal: `function-boundary`
- Evidence: 함수 선언이 157라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0877 [Refactor/P1] `onSlashCommandInteraction` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:413`
- Signal: `function-boundary`
- Evidence: 함수 선언이 413라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0878 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:500`
- Signal: `broad-exception`
- Evidence: `} catch (e: Exception) {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0879 [Refactor/P1] `onReady` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:531`
- Signal: `function-boundary`
- Evidence: 함수 선언이 531라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0880 [Refactor/P1] `onShutdown` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:541`
- Signal: `function-boundary`
- Evidence: 함수 선언이 541라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0881 [Refactor/P1] `onCommandAutoCompleteInteraction` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:553`
- Signal: `function-boundary`
- Evidence: 함수 선언이 553라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0882 [Refactor/P1] `onButtonInteraction` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:575`
- Signal: `function-boundary`
- Evidence: 함수 선언이 575라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0883 [Refactor/P1] `onStringSelectInteraction` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:684`
- Signal: `function-boundary`
- Evidence: 함수 선언이 684라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0884 [Refactor/P1] `onEntitySelectInteraction` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:708`
- Signal: `function-boundary`
- Evidence: 함수 선언이 708라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0885 [Refactor/P1] `onGuildJoin` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:718`
- Signal: `function-boundary`
- Evidence: 함수 선언이 718라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0886 [Refactor/P1] `onGuildLeave` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:727`
- Signal: `function-boundary`
- Evidence: 함수 선언이 727라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0887 [Refactor/P1] `onGuildMemberRemove` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:732`
- Signal: `function-boundary`
- Evidence: 함수 선언이 732라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0888 [Refactor/P1] `onChannelDelete` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:737`
- Signal: `function-boundary`
- Evidence: 함수 선언이 737라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0889 [Refactor/P1] `askModal` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:743`
- Signal: `function-boundary`
- Evidence: 함수 선언이 743라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0890 [Refactor/P1] `settingsEmbed` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:755`
- Signal: `function-boundary`
- Evidence: 함수 선언이 755라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0891 [Refactor/P1] `settingsKey` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:775`
- Signal: `function-boundary`
- Evidence: 함수 선언이 775라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0892 [Refactor/P1] `allowedChannelText` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:777`
- Signal: `function-boundary`
- Evidence: 함수 선언이 777라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0893 [Refactor/P1] `currentSettingsSummary` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:779`
- Signal: `function-boundary`
- Evidence: 함수 선언이 779라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0894 [Refactor/P1] `effectiveAllowedChannelIds` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:790`
- Signal: `function-boundary`
- Evidence: 함수 선언이 790라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0895 [Refactor/P1] `formatChannelPolicy` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:793`
- Signal: `function-boundary`
- Evidence: 함수 선언이 793라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0896 [Refactor/P1] `pendingSummary` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:801`
- Signal: `function-boundary`
- Evidence: 함수 선언이 801라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0897 [Refactor/P1] `pendingSettings` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:813`
- Signal: `function-boundary`
- Evidence: 함수 선언이 813라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0898 [Refactor/P1] `savePendingSettings` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:815`
- Signal: `function-boundary`
- Evidence: 함수 선언이 815라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0899 [Refactor/P1] `updateSettingsPanel` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:844`
- Signal: `function-boundary`
- Evidence: 함수 선언이 844라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0900 [Refactor/P1] `updateSettingsPanel` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:851`
- Signal: `function-boundary`
- Evidence: 함수 선언이 851라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0901 [Refactor/P1] `updateSettingsPanel` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:858`
- Signal: `function-boundary`
- Evidence: 함수 선언이 858라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0902 [Refactor/P1] `settingsRows` 함수 책임 축소 검토

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:866`
- Signal: `function-boundary`
- Evidence: 함수 선언이 866라인에 있으며 파일 전체 1599라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0903 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1104`
- Signal: `broad-exception`
- Evidence: `} catch (e: Exception) {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0904 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1158`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0905 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1272`
- Signal: `broad-exception`
- Evidence: `val channel = runCatching { channelUnion.asTextChannel() }.getOrNull() ?: return false`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0906 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1273`
- Signal: `broad-exception`
- Evidence: `return runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0907 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1412`
- Signal: `broad-exception`
- Evidence: `title = event.getOption("title")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0908 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1421`
- Signal: `broad-exception`
- Evidence: `query = event.getOption("query")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0909 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1440`
- Signal: `broad-exception`
- Evidence: `jobId = event.getOption("job-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0910 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1447`
- Signal: `broad-exception`
- Evidence: `spaceId = event.getOption("space-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0911 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1448`
- Signal: `broad-exception`
- Evidence: `sourceId = event.getOption("source-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0912 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1454`
- Signal: `broad-exception`
- Evidence: `spaceId = event.getOption("space-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0913 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1455`
- Signal: `broad-exception`
- Evidence: `sourceId = event.getOption("source-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0914 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1467`
- Signal: `broad-exception`
- Evidence: `publishedPresetId = event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0915 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1470`
- Signal: `broad-exception`
- Evidence: `"ai-preset-like" -> commands.likePreset(ctx, event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0916 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1474`
- Signal: `broad-exception`
- Evidence: `publishedPresetId = event.getOption("published-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0917 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1475`
- Signal: `broad-exception`
- Evidence: `reason = event.getOption("reason")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0918 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1481`
- Signal: `broad-exception`
- Evidence: `reportId = event.getOption("report-id")!!.asString.toLongOrNull() ?: -1L,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0919 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1482`
- Signal: `broad-exception`
- Evidence: `decision = event.getOption("decision")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0920 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1493`
- Signal: `broad-exception`
- Evidence: `mode = event.getOption("mode")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0921 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1494`
- Signal: `broad-exception`
- Evidence: `maxCandidates = event.getOption("candidates")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0922 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1502`
- Signal: `broad-exception`
- Evidence: `prompt = event.getOption("prompt")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0923 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1507`
- Signal: `broad-exception`
- Evidence: `"llm-welcome-set" -> commands.setWelcome(ctx, event.getOption("message")!!.asString)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0924 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1517`
- Signal: `broad-exception`
- Evidence: `.getOption("models")!!`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0925 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1526`
- Signal: `broad-exception`
- Evidence: `event.getOption("model")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0926 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1527`
- Signal: `broad-exception`
- Evidence: `event.getOption("daily")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0927 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1528`
- Signal: `broad-exception`
- Evidence: `event.getOption("concurrency")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0928 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1529`
- Signal: `broad-exception`
- Evidence: `event.getOption("seconds")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0929 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1534`
- Signal: `broad-exception`
- Evidence: `event.getOption("model")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0930 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1535`
- Signal: `broad-exception`
- Evidence: `event.getOption("role")!!.asString,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0931 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1540`
- Signal: `broad-exception`
- Evidence: `event.getOption("from")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0932 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1541`
- Signal: `broad-exception`
- Evidence: `event.getOption("to")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0933 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1549`
- Signal: `broad-exception`
- Evidence: `"llm-allow-channel" -> commands.allowChannel(ctx, event.getOption("channel")!!.asChannel.idLong)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0934 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1550`
- Signal: `broad-exception`
- Evidence: `"llm-deny-channel" -> commands.denyChannel(ctx, event.getOption("channel")!!.asChannel.idLong)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0935 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1554`
- Signal: `broad-exception`
- Evidence: `event.getOption("role")!!.asRole.idLong,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0936 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1555`
- Signal: `broad-exception`
- Evidence: `runCatching {`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0937 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1557`
- Signal: `broad-exception`
- Evidence: `event.getOption("level")!!.asString.uppercase(),`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0938 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1560`
- Signal: `broad-exception`
- Evidence: `event.getOption("limit")!!.asInt,`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0939 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1582`
- Signal: `broad-exception`
- Evidence: `val target = event.getOption("user")!!.asUser`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0940 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1593`
- Signal: `broad-exception`
- Evidence: `"provider-remove" -> commands.removeProvider(ctx, event.getOption("user")!!.asUser.idLong)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0941 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1594`
- Signal: `broad-exception`
- Evidence: `"llm-block" -> commands.blockUser(ctx, event.getOption("user")!!.asUser.idLong)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0942 [Refactor/P1] 예외/nullable 처리 의미 구체화

- Area: Discord UX
- File: `central-server/src/main/kotlin/com/discordassistant/central/discord/DiscordBot.kt:1595`
- Signal: `broad-exception`
- Evidence: `"llm-unblock" -> commands.unblockUser(ctx, event.getOption("user")!!.asUser.idLong)`
- Recommendation: 도메인별 예외 타입·Result sealed class·명시적 null 처리로 실패 원인을 보존

### IMP-0943 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/AiNetworkGrowthService.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(682라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0944 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/AiNetworkGrowthService.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0945 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(984라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0946 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0947 [Refactor/P1] 의존성 폭 축소

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:1`
- Signal: `many-imports`
- Evidence: import 22개로 의존 방향이 넓음
- Recommendation: 생성자 주입 서비스 묶음을 use-case facade로 줄이고, DTO 의존을 boundary로 이동

### IMP-0948 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:1`
- Signal: `test-surface`
- Evidence: 984라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0949 [Refactor/P1] 파일 책임 경계 재검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:1`
- Signal: `large-file`
- Evidence: 984라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0950 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:27`
- Signal: `many-classes`
- Evidence: 타입 14개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0951 [Refactor/P1] `wizardOptions` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:37`
- Signal: `function-boundary`
- Evidence: 함수 선언이 37라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0952 [Refactor/P1] 함수 수 기반 책임 분리

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:37`
- Signal: `many-functions`
- Evidence: 함수 32개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0953 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:43`
- Signal: `duplicate-literal`
- Evidence: `development` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0954 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:49`
- Signal: `duplicate-literal`
- Evidence: `translation` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0955 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:55`
- Signal: `duplicate-literal`
- Evidence: `meeting` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0956 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:61`
- Signal: `duplicate-literal`
- Evidence: `announcement` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0957 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:75`
- Signal: `duplicate-literal`
- Evidence: `친근하게` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0958 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:76`
- Signal: `duplicate-literal`
- Evidence: `전문적으로` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0959 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:77`
- Signal: `duplicate-literal`
- Evidence: `짧고 명확하게` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0960 [Refactor/P1] 반복 문자열 상수화

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:81`
- Signal: `duplicate-literal`
- Evidence: `short` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0961 [Refactor/P1] `draftFromAnswers` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:95`
- Signal: `function-boundary`
- Evidence: 함수 선언이 95라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0962 [Refactor/P1] `createFromWizard` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:117`
- Signal: `function-boundary`
- Evidence: 함수 선언이 117라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0963 [Refactor/P1] `rollbackToVersion` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:195`
- Signal: `function-boundary`
- Evidence: 함수 선언이 195라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0964 [Refactor/P1] `approveProposal` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:275`
- Signal: `function-boundary`
- Evidence: 함수 선언이 275라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0965 [Refactor/P1] `applyRoutingSnapshot` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:331`
- Signal: `function-boundary`
- Evidence: 함수 선언이 331라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0966 [Refactor/P1] `rejectProposal` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:346`
- Signal: `function-boundary`
- Evidence: 함수 선언이 346라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0967 [Refactor/P1] `replaceAiAdminRoles` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:375`
- Signal: `function-boundary`
- Evidence: 함수 선언이 375라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0968 [Refactor/P1] `aiAdminRolePolicy` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:409`
- Signal: `function-boundary`
- Evidence: 함수 선언이 409라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0969 [Refactor/P1] `canManageChannelAi` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:415`
- Signal: `function-boundary`
- Evidence: 함수 선언이 415라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0970 [Refactor/P1] `requireCanManageChannelAi` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:437`
- Signal: `function-boundary`
- Evidence: 함수 선언이 437라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0971 [Refactor/P1] `proposalReviewSummary` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:459`
- Signal: `function-boundary`
- Evidence: 함수 선언이 459라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0972 [Refactor/P1] `pendingProposals` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:509`
- Signal: `function-boundary`
- Evidence: 함수 선언이 509라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0973 [Refactor/P1] `channelHistory` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:514`
- Signal: `function-boundary`
- Evidence: 함수 선언이 514라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0974 [Refactor/P1] `promptPreview` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:526`
- Signal: `function-boundary`
- Evidence: 함수 선언이 526라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0975 [Refactor/P1] `channelOnboarding` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:593`
- Signal: `function-boundary`
- Evidence: 함수 선언이 593라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0976 [Refactor/P1] `AiChangeProposalEntity` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:623`
- Signal: `function-boundary`
- Evidence: 함수 선언이 623라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0977 [Refactor/P1] `String` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:645`
- Signal: `function-boundary`
- Evidence: 함수 선언이 645라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0978 [Refactor/P1] `jobPreset` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:651`
- Signal: `function-boundary`
- Evidence: 함수 선언이 651라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0979 [Refactor/P1] `tonePreset` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:665`
- Signal: `function-boundary`
- Evidence: 함수 선언이 665라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0980 [Refactor/P1] `normalizeAnswerLength` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:673`
- Signal: `function-boundary`
- Evidence: 함수 선언이 673라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0981 [Refactor/P1] `constitutionFor` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:680`
- Signal: `function-boundary`
- Evidence: 함수 선언이 680라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0982 [Refactor/P1] `examplesForPurpose` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:702`
- Signal: `function-boundary`
- Evidence: 함수 선언이 702라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0983 [Refactor/P1] `onboardingMessage` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:718`
- Signal: `function-boundary`
- Evidence: 함수 선언이 718라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0984 [Refactor/P1] `approvalDecision` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:735`
- Signal: `function-boundary`
- Evidence: 함수 선언이 735라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0985 [Refactor/P1] `aiAdminRoleIds` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:761`
- Signal: `function-boundary`
- Evidence: 함수 선언이 761라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0986 [Refactor/P1] `AiBehaviorVersionEntity` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:769`
- Signal: `function-boundary`
- Evidence: 함수 선언이 769라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0987 [Refactor/P1] `sha256` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:783`
- Signal: `function-boundary`
- Evidence: 함수 선언이 783라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0988 [Refactor/P1] `audit` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:789`
- Signal: `function-boundary`
- Evidence: 함수 선언이 789라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0989 [Refactor/P1] `normalize` 함수 책임 축소 검토

- Area: AI Network Domain
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/ChannelAiCustomizationService.kt:812`
- Signal: `function-boundary`
- Evidence: 함수 선언이 812라인에 있으며 파일 전체 984라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0990 [Refactor/P1] 대형 모듈 분할 기준 필요

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:1`
- Signal: `huge-file`
- Evidence: 500라인 초과(808라인)로 코드 탐색·리뷰 비용이 커짐
- Recommendation: 하위 패키지 기준으로 command/route/view/model/policy 모듈 분할 ADR 작성

### IMP-0991 [Refactor/P1] 변경 충돌 가능성 높은 파일 축소

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:1`
- Signal: `merge-hotspot`
- Evidence: 대형 핵심 파일은 여러 기능 PR이 동시에 건드릴 가능성이 큼
- Recommendation: 핵심 변경 축별로 extension service 또는 handler registry 도입

### IMP-0992 [Refactor/P1] 테스트 가능한 단위로 쪼개기

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:1`
- Signal: `test-surface`
- Evidence: 808라인 파일은 작은 회귀도 영향 범위를 파악하기 어려움
- Recommendation: public facade는 유지하고 내부 pure function/mapper/validator 단위로 분할

### IMP-0993 [Refactor/P1] 파일 책임 경계 재검토

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:1`
- Signal: `large-file`
- Evidence: 808라인 파일로 여러 책임이 섞일 가능성이 높음
- Recommendation: 명령 처리·도메인 계산·DTO 변환·렌더링을 별도 서비스/컴포넌트로 분리

### IMP-0994 [Refactor/P1] 동일 파일 다중 타입 분리

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:18`
- Signal: `many-classes`
- Evidence: 타입 10개가 같은 파일에 있음
- Recommendation: public API 타입과 내부 command/result 타입 파일을 분리

### IMP-0995 [Refactor/P1] `createSpace` 함수 책임 축소 검토

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:26`
- Signal: `function-boundary`
- Evidence: 함수 선언이 26라인에 있으며 파일 전체 808라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0996 [Refactor/P1] 함수 수 기반 책임 분리

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:26`
- Signal: `many-functions`
- Evidence: 함수 28개가 한 파일에 집중됨
- Recommendation: 함수 그룹별로 command handler / renderer / repository adapter를 분리

### IMP-0997 [Refactor/P1] `listSources` 함수 책임 축소 검토

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:56`
- Signal: `function-boundary`
- Evidence: 함수 선언이 56라인에 있으며 파일 전체 808라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리

### IMP-0998 [Refactor/P1] 반복 문자열 상수화

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:63`
- Signal: `duplicate-literal`
- Evidence: `knowledge space not found: guild=$guildId space=$spaceId` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-0999 [Refactor/P1] 반복 문자열 상수화

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:66`
- Signal: `duplicate-literal`
- Evidence: `deleted` 문자열이 3회 이상 반복됨
- Recommendation: 상수/enum/message catalog로 추출해 문구·프로토콜 drift를 방지

### IMP-1000 [Refactor/P1] `spaceStatus` 함수 책임 축소 검토

- Area: RAG/Knowledge
- File: `central-server/src/main/kotlin/com/discordassistant/central/network/KnowledgeIngestionService.kt:71`
- Signal: `function-boundary`
- Evidence: 함수 선언이 71라인에 있으며 파일 전체 808라인 컨텍스트에 포함
- Recommendation: 입력 검증, 권한 판단, 상태 변경, 응답 포맷을 별도 private 함수/도메인 서비스로 분리
