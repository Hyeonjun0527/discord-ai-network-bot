# Policy/State Refactor Backlog — 2026-06-27

목표: 운영누락, 허술한 정책, 상태머신 결함을 코드 근거로 추적하고 순차 리팩토링한다.

상태:
- `DONE`: 코드/테스트 반영 완료.
- `TODO`: 현 코드/문서에서 결함 가능성이 확인되어 구현 필요.
- `VERIFY`: 구현은 있어 보이나 현 검증 범위가 약해 보강 필요.

## Action Runtime 상태머신

1. `DONE` JPA scheduled-action store가 `ActionStatus` 전이 그래프를 우회해 문자열 상태를 직접 대입하던 경로 제거.
   - 근거: `JpaScheduledActionStore.complete/fail/reschedule/cancel`이 domain 메서드 없이 status를 덮어썼다.
   - 조치: `ScheduledSocialAction` 도메인 전이를 통과하도록 변경, 불법 전이 테스트 추가.
2. `DONE` in-memory scheduler fake가 실제 store보다 느슨하게 상태를 바꾸던 테스트 위장 제거.
   - 근거: `ActionSchedulerTestSupport`가 `copy(status=...)`로 불법 전이를 허용했다.
   - 조치: fake도 domain 메서드/전이 가드를 사용.
3. `DONE` due 재평가 통과 후 TYPING 상태가 영속되지 않던 경계 보강.
   - 근거: `DueActionPoller`는 반환 객체만 `passReevaluation()`하고 store 상태를 갱신하지 않았다.
   - 조치: `ActionSchedulerPort.markTyping` 추가 및 poller 연결.
4. `DONE` partial burst 취소 전에 PARTIALLY_SENT 상태가 영속되지 않던 경계 보강.
   - 근거: `ActionExecutionService`가 부분 전송 뒤 곧바로 cancel했다.
   - 조치: `markPartiallySent` 추가 후 cancel.
5. `DONE` execution service가 REEVALUATING/SCHEDULED action을 직접 실행할 수 있던 경로 차단.
   - 근거: chaos test가 claim 결과를 바로 execute에 넘겼다.
   - 조치: `ActionExecutionService`/`ReactionExecutionService`가 TYPING 입력만 허용.
6. `DONE` action scheduler transition API가 missing identity를 조용히 무시하는 정책 정리.
   - 근거: port 메서드들이 nullable/no-op 스타일로 구현되어 운영 누락이 숨을 수 있다.
   - 조치: transition API는 missing identity를 `NoSuchElementException`으로 fail-fast 처리하고, terminal cancel만
     idempotent `false`로 분리.
7. `DONE` lease ownership 검증 누락.
   - 근거: `ClaimedAction`에 lease 만료만 있고 complete/cancel/fail 시 owner 검증이 없다.
   - 조치: `lease_owner`가 다른 worker인 in-flight action은 markTyping/partial/reschedule/complete/cancel/fail에서
     Fail Fast. recovery가 만료 lease를 회수해 owner를 비운 뒤에만 다른 worker가 정리 가능.
   - 검증: cross-worker `JpaScheduledActionStoreTest` 추가.
8. `DONE` `reclaimExpiredLeases`가 상태별 회수 범위를 DB 쿼리에서 제한하지 않는다.
   - 근거: lease만 있으면 terminal도 반환될 수 있다.
   - 조치: JPA 쿼리와 in-memory fake 모두 `REEVALUATING`/`TYPING`/`PARTIALLY_SENT` in-flight 상태만 회수.
9. `DONE` PARTIALLY_SENT 복구를 COMPLETED로 종결하는 정책의 audit phase 보강.
   - 근거: recovery가 scheduler.complete만 호출하고 recovery audit은 별도 없음.
   - 조치: `RECOVERED_NO_RESEND` audit phase와 `partial_recovery_no_resend` reason을 append-only로 기록.
10. `DONE` shadow suppressed action의 terminal 정책 명확화.
    - 근거: execution service는 SUPPRESSED_SHADOW audit 후 scheduler terminal 상태를 남기지 않는다.
    - 조치: scheduled action이 shadow guard에서 suppress되면 audit 후 `CANCELLED`로 종결. SPEAK/REACT 테스트 추가.

## 자동 채널·LLM allow-list 정책

11. `DONE` 기존 setup 카테고리만 있고 ai채팅/ai그림/니아수다가 빠진 부분 setup 복구.
    - 근거: 기존 코드는 member만 생성하고 chat/image 누락은 복구하지 않았다.
    - 조치: `ensureTextChannel`로 세 텍스트 채널 모두 복구.
12. `DONE` auto-created channel allow-list 등록이 non-empty allow-list에서만 동작하는 정책.
    - 근거: 빈 목록은 전체 허용이라 등록하면 오히려 범위를 좁힌다.
    - 조치: `NiaChannelSetupHandlerTest`에서 non-empty 등록과 empty 미등록을 양쪽 고정.
13. `DONE` 자동 생성 후 profile/auto-respond/allow-list/participation enable 중간 실패 rollback 정책.
    - 근거: 현재 try/catch는 사용자 안내만 하고 부분 생성 상태가 남을 수 있다.
    - 조치: Discord 채널 삭제 rollback 대신 `create_channels`/`chat_runtime_policy`/`llm_allow_list`/
      `participation_live` 단계별 audit를 남기고 실패 단계에서 중단. 재실행 시 기존 setup 복구 경로가 누락 정책을 보정.
14. `DONE` guide pin 실패와 채널 생성 성공 사이 운영 가시성 부족.
    - 근거: pin 실패는 warn 로그뿐이고 관리자에게 복구 안내가 없다.
    - 조치: guide pin 성공/실패를 `nia_setup_pin_succeeded`/`nia_setup_pin_failed` audit로 기록. pin 실패는 채널 생성
      성공을 깨지 않고 runtime policy 적용을 계속한다.
15. `DONE` 기존 자동 채널 재실행 시 chat profile/auto-respond 복구 여부.
    - 근거: alreadySetUp 분기는 allow-list/participation만 복구하고 profile/auto-respond 재보장은 하지 않는다.
    - 조치: 신규 생성과 기존 setup 재실행 모두 `ensureChatRuntimePolicy`를 통해 ai채팅 profile/autoRespond를 재보장.
16. `DONE` channel delete 이벤트 후 allow-list/participation/channelai 정리 일관성.
    - 근거: cleanup 서비스들이 분산되어 있다.
    - 조치: `ProviderPoolReconciliationService.cleanupChannel`에서 routing allow-list, channelAI/autoRespond 캐시,
      participation override/kill-switch를 한 경로로 정리. JPA store/service/reconciliation 테스트 보강.
17. `DONE` 기존 ai채팅을 수동 rename한 경우 복구 정책.
    - 근거: 이름 기반 탐색만 한다.
    - 조치: 신규/복구 채널에 `nia-managed-channel:<role>` topic marker를 붙이고, marker가 없는 과거 ai채팅은
      channelAI 프로필(`displayName=니아`)로 탐색해 runtime policy를 재보장.
18. `DONE` i18n 채널명 변경 후 기존 채널 탐색 정책.
    - 근거: 언어별 이름만 현재 language로 찾는다.
    - 조치: 기능 카테고리와 ai채팅/ai그림/니아수다 채널 탐색이 ko/en/ja 지원 locale 이름 전체를 후보로 사용.
      언어 전환 후 재실행해도 중복 생성하지 않고 기존 채널을 복구.
19. `DONE` 자동 채널 권한 overwrites 정책 미정.
    - 근거: 생성 시 기본 권한 상속만 사용한다.
    - 조치: 자동 텍스트 채널 생성/복구 시 공개 역할에는 읽기·쓰기·히스토리·리액션·첨부·명령 사용 권한을,
      봇 멤버에는 그 권한과 채널/웹훅/메시지 관리 권한을 allow overwrite 로 보정. 실패는 `channel_permissions`
      audit 단계 실패로 드러난다.
20. `DONE` ai그림 채널의 실제 LLM/image 라우팅 권한 구분.
    - 근거: LLM allow-list에는 등록하지만 image policy와의 관계가 모호하다.
    - 조치: 자동 setup은 ai채팅만 text LLM allow-list 에 등록하고, ai그림은 `/imagine` image capability,
      니아수다는 participation capability 로 분리. `/imagine` 이 text LLM allow-list 제한과 독립적으로 이미지
      provider capability 로 동작하는 테스트 추가.

## Participation/발화 정책

21. `DONE` global LIVE와 channel override/excluded 우선순위.
    - 근거: `NexaParticipationFlagService`가 override와 excluded를 같이 다룬다.
    - 조치: application 서비스 truth table 테스트로 `excluded > channel override > guild/global lane` 우선순위 고정.
22. `DONE` 채널별 사람 니아 켜기/끄기와 자동 생성 member channel enable의 audit 통일.
    - 조치: `NexaParticipationFlagService`가 enable/disable 공통 audit 이벤트
      `nexa_participation_channel_enabled`/`nexa_participation_channel_disabled`를 actor/source/mode/detail 형식으로 기록.
      자동 setup은 `nia_setup`, 관리자 토글은 `guild_admin_toggle` source 로 같은 형식을 사용.
23. `DONE` participation gate가 auto-respond 채널을 assistant channel로 오인하지 않는 불변식 테스트.
    - 조치: `NexaParticipationEmitBridgeTest`에서 policy request config를 캡처해 participation 경로가
      `channelMode=participation`, `autoRespondEnabled=false`, `speechAllowed=true`로만 정책 엔진에 내려가는 것을 고정.
      channelAI 자동응답 상태는 participation assistant-channel 판정에 섞지 않는다.
24. `DONE` clear_speak/clear_silent rule 우선순위가 hard_policy와 충돌하지 않는지 decision table화.
    - 조치: `CoreInterventionRules.attentionHardPolicy`로 verdict→attention hard_policy 사상을 단일화하고,
      `CoreInterventionRulesTest`에 WAIT/SILENT/SPEAK/CANDIDATE decision table을 추가. 브리지는 이 도메인 사상을 사용.
25. `DONE` pingpong 즉답과 rate-limit 충돌 시 우선순위 명시.
    - 조치: `NexaParticipationEmitBridgeTest`에 pingpong wake 통과 후에도 emit 직전 rate-limit 이 최종 hard cap 으로
      우선해 `RateLimited`와 GLM 호출 0회(초과분)를 보장하는 테스트 추가.
26. `DONE` typing debounce/min_gap 상수가 운영 설정으로 노출될지 고정값일지 정책화.
    - 조치: `AttentionGateConstants`를 Spring 환경 설정이 아닌 고정 core port 상수로 명시. 서버별 임의 튜닝으로
      LIVE/SHADOW 판정과 audit 재현성이 갈라지지 않도록 값 변경은 코드 리뷰, `ATTENTION_VERSION`, 불변식 테스트
      갱신을 동반하게 했다. `ChannelAttentionGateTest`가 idle/min_gap/typing/pingpong/gap_window 값과 상대 관계를 고정.
27. `DONE` 사적 핑퐁 판단에서 thread/forum 채널 context boundary 테스트.
    - 조치: `ParticipationSignalDeriver`의 히스토리 ring buffer 키를 채널 단일 키에서 `channelId + contextBoundaryId`
      로 확장. 같은 부모 채널이라도 thread/forum topic boundary 가 다르면 `firstMessageText`·prior speaker·니아 호명
      신호가 섞이지 않는다. 기존 호출 호환성을 유지하고, DiscordBot 호출부는 현재 JDA channel id를 boundary 로 명시.
28. `DONE` reply target이 bot/self/other user일 때 발화 정책 table 보강.
    - 조치: reply target 을 `replyToNia`/`replyToSelf`/`replyToOtherUser`로 분해. 니아 reply 는 즉시 SPEAK,
      자기 reply 는 이어말 보충으로 WAIT, 다른 사람/봇 reply 는 타인 대화로 SILENT 처리한다. DiscordBot
      `referencedMessage.author`에서 세 신호를 도출하고, Core/Bridge 테스트로 decision table을 고정.
29. `DONE` user opt-out/consent revocation이 pending action과 participation live gate 양쪽에 반영되는지 검증.
    - 조치: `ActionTarget.subjectPseudonym` + V71 nullable column을 추가해 새 participation 예약이 동의 주체를
      보존한다. `PendingActionPurgePort.findPendingIn` 이 guild/channel/user 범위를 모두 적용하므로 user opt-out
      철회가 같은 채널의 해당 사용자 pending action/content 만 즉시 취소한다.
    - 조치: `NexaParticipationEmitBridge`가 flag ON 뒤 core rules/policy/emit 진입 전 `ConsentPolicyPort`를 live
      조회한다. DENIED/OBSERVE_ONLY 또는 조회 실패는 `ConsentBlocked`로 fail-closed 되어 core SPEAK 즉결도 예약으로
      넘어가지 않는다. emit seam 의 2차 동의 재확인은 그대로 유지한다.
30. `DONE` shadow/canary/live 전환 중 예약된 action의 처리 정책.
    - 조치: `ActionExecutionModePort`를 전송 경계에 추가해 예약/due 시점 모드가 아니라 실행 직전 현재 모드가
      최종 권한이 되게 했다. 호출자가 stale `LIVE`를 넘겨도 현재 모드가 `OFF`/`SHADOW_PREDICT`면
      `ActionExecutionService`/`ReactionExecutionService`가 executor 를 0회 호출하고 `SUPPRESSED_SHADOW`로 취소한다.
    - 조치: `ParticipationActionExecutionModeAdapter`가 현재 guild lane + channel override + excluded channel 을 합성해
      actionruntime 에 제공한다. 숫자가 아닌 channel target 은 운영 Discord 채널로 검증할 수 없으므로 fail-closed
      `OFF`로 처리한다.

## Routing/Guild Policy

31. `DONE` `PolicyService` 캐시 무효화가 쓰기 메서드마다 흩어진 구조 정리.
    - 조치: 모든 정책 쓰기가 `writePolicy(guildId) { ... }` 단일 경계를 통과하게 정리했다. 캐시 무효화는
      `finally`에서 수행되어 저장/감사 중 예외가 나도 다음 읽기가 오래된 TTL 캐시를 신뢰하지 않는다.
    - 검증: `PolicyServiceCacheTest`/`PolicyServiceTest`가 채널·역할·길드 설정 쓰기 직후 TTL 전 즉시 반영을 확인한다.
32. `DONE` `allowedChannelIds` 빈 목록 의미(전체 허용)를 타입으로 표현.
    - 조치: `AllowedChannelPolicy`를 도입해 빈 저장 목록을 `isAllChannelsAllowed=true`로 명시했다.
      `PolicyService.isChannelAllowed`는 더 이상 빈 `List`를 직접 해석하지 않고 타입의 `allows`를 사용한다.
    - 호환: 기존 `allowedChannelIds`/관리 UI 계약은 `toLegacyAllowedIds()`로 유지하되, 새 테스트가 전체 허용과
      명시 allow-list 의 차이를 고정한다.
33. `DONE` role dailyLimit의 0 무제한 정책을 API 응답/문구와 일치시키는 테스트.
    - 조치: `DailyLimitPolicy` 타입을 도입해 저장값 `0`의 의미를 "0회"가 아니라 `무제한`으로 고정했다.
      음수 입력은 저장 전 `0`으로 정규화해 API/Discord 문구가 같은 정책을 보게 했다.
    - 조치: `/관리 role-policy` 응답, `/내사용량` 응답, 대시보드 role-policy API가 모두 `무제한` 문구 또는
      `dailyLimitUnlimited`/`dailyLimitLabel`을 노출한다. 숫자 `0`만 보고 호출자가 의미를 재해석하지 않게 했다.
    - 검증: `ktlintCheck`와 `PolicyServiceTest`/`DashboardWriteControllerTest`/`CommandServiceCoverageTest` focused
      테스트가 통과했다.
34. `DONE` allow-list 변경과 autoRespond cache invalidation의 cross-policy audit.
    - 조치: `AutoRespondChannelPolicyView` 좁은 포트를 추가해 allow-list 쓰기 직후 현재 자동응답 채널이
      명시 allow-list 에서 차단되는지 감사한다. 전체 허용(빈 목록) 상태는 conflict 로 보지 않는다.
    - 조치: autoRespond 캐시 무효화 책임은 `setAutoRespond`/채널 삭제/길드 삭제 경계에 유지했다. allow-list 변경은
      자동응답 캐시 값을 바꾸는 이벤트가 아니므로, 캐시를 흔들지 않고 "autoRespond=true 이지만 routing=false" 위험 상태를
      `llm_allow_list_auto_respond_conflict` 감사 로그로 남긴다.
    - 검증: `ktlintCheck`와 `PolicyServiceCacheTest`/`AutoRespondChannelRegistryTest` focused 테스트가 통과했다.
35. `DONE` request rejection reason i18n/locale 독립 테스트.
    - 조치: `RequestRejectionCode`를 `OrchestrationResult`에 추가해 사용자 표시 문구(`failReason`)와 독립적인
      거절 사유 계약을 만들었다. 기존 문구는 유지하되 테스트/후속 UI/API는 코드로 검증할 수 있다.
    - 조치: blocklist, quota, channel allow-list, burden 권한 부족, provider policy denied 경로가 각각 안정 코드로
      반환되도록 `RequestOrchestrator`의 REJECTED 생성부를 정리했다.
    - 검증: `ktlintCheck`와 `RequestOrchestratorTest` focused 테스트가 통과했다.
36. `DONE` `maxAllowedBurden`에서 RESTRICTED 역할과 다른 역할이 섞일 때 정책 명확화.
    - 조치: `RESTRICTED`는 role-policy 선택지에서 제외된 시스템/프로바이더 특수 burden 이므로 일반 역할 권한 상승에
      쓰지 않는다고 KDoc에 명시했다. DB에 남아 있어도 normal max burden 산정에서는 무시한다.
    - 검증: `PolicyServiceTest`가 `RESTRICTED + STANDARD → STANDARD`, `RESTRICTED only → LIGHT`를 고정한다.
      `ktlintCheck`와 `PolicyServiceTest`/`RequestOrchestratorTest` focused 테스트가 통과했다.
37. `DONE` guild default model/env fallback 문서와 코드 기본값 일치 검증.
    - 조치: `/질문`·ai채팅 무료 클라우드 폴백은 `central.cloud.free-model`/`ZAI_FREE_MODEL`가 SSOT이며
      빈 값은 `glm-4.5-air`로 해석된다고 코드(`AskCommandHandler.resolveFreeCloudModel`)와 baseline/ai-context에
      명시했다. guild default model은 채널 routing preferred model 입력일 뿐, 무료 클라우드 env fallback과 혼동하지 않는다.
    - 검증: JSON SSOT 유효성 검사, `make ssot-viewer-check`, `glm-5.1 무료 폴백` 잔여 검색, `ktlintCheck`와
      `CommandServiceCoverageTest`/`CommandServiceTest`/`ChannelAiRoutingPolicyServiceTest` focused 테스트가 통과했다.
38. `DONE` `/질문` free model과 speech model default drift 검증.
    - 조치: speech 모델 설정 KDoc을 실제 계약으로 정리했다. `/질문` 무료 클라우드 기본값과 speech 기본값은 둘 다
      빠른 상호작용용 `glm-4.5-air`이며, 운영 변경은 각각 `ZAI_FREE_MODEL`, `NEXA_SPEECH_MODEL` env 로 한다.
    - 조치: `SpeechModelConfig.DEFAULT_SPEECH_MODEL`을 추가하고 `/질문`의
      `AskCommandHandler.DEFAULT_FREE_CLOUD_MODEL`과 일치해야 한다는 테스트를 추가했다.
    - 검증: `central.cloud.* 기본값을 따른다` 같은 모호한 표현 잔여 검색, `ktlintCheck`와
      `RoutingCloudSpeechGenerationAdapterTest`/`CommandServiceCoverageTest` focused 테스트가 통과했다.
39. `DONE` channel policy 변경 직후 running request 처리 정책.
    - 조치: `RequestOrchestrator`의 admission 단계를 `admitAtRequestStart`로 분리하고, blocklist·quota·channel
      allow-list·role burden cap은 요청 시작 시점 스냅샷이라고 명시했다. provider/cloud 실행 중 정책이 바뀌어도
      이미 admission 을 통과한 요청은 기존 requestId 로 종결하고, 새 정책은 다음 요청부터 적용한다.
    - 검증: 실행 중 `channelAllowed=true → false`로 바뀌어도 첫 요청은 `COMPLETED`, 다음 요청은
      `CHANNEL_NOT_ALLOWED`로 `REJECTED` 되는 회귀 테스트를 추가했다. `ktlintCheck`와 `RequestOrchestratorTest`
      focused 테스트가 통과했다.
40. `DONE` provider contribution policy와 guild channel allow-list 충돌 시 우선순위 명시.
    - 조치: guild channel allow-list는 전역 request admission gate이고, provider contribution policy는 모델·burden·
      capability 한도만 소유한다고 `DbProviderProfileProvider`와 baseline에 명시했다. V41 이후 DB contribution policy에는
      channel/role scope 컬럼이 없으므로 DB 어댑터는 `ProviderProfile.allowedChannelIds`/`allowedRoleIds`를 채우지 않는다.
    - 검증: contribution policy + capability profile을 가진 provider가 channel/role scope를 생성하지 않는 JPA 테스트를
      추가했다. `ktlintCheck`와 `DbProviderProfileProviderTest`/`RequestOrchestratorTest`/`RoutingTest` focused 테스트가
      통과했다.

## 운영/CI/배포

41. `DONE` self-hosted runner cancelled-job 잔류 감지/복구 runbook을 표준 스크립트로 자동화.
   - 근거: cancelled job 이후 GitHub runner busy 상태와 원격 `Runner.Worker` 잔류 여부를 수동으로 대조해야 했다.
   - 조치: `scripts/diagnose-central-ops.sh` 가 GitHub runner 상태, central listener/worker 수, 최근 session conflict 를 판정하고,
     `CENTRAL_OPS_REPAIR_RUNNER=true` 명시 시 runner systemd 서비스만 재시작한다. SSH/runner/app health 분리 문서 갱신.
42. `DONE` central deploy 후 image SHA public/actuator info 노출 또는 SSH 검증 script 표준화.
    - 조치: `scripts/diagnose-central-ops.sh`가 SSH, runner, compose `CENTRAL_IMAGE`, local/public health를 표준 확인.
43. `DONE` ops policy audit를 deploy 후 health gate에 hard-fail로 둘 범위 정리.
    - 조치: `.github/workflows/central-deploy.yml`의 health 성공 직후와 `scripts/diagnose-central-ops.sh`에
      read-only 운영 DB policy audit를 추가했다. hard-fail 범위는 `missing_auto_respond_allow_list`,
      `broken_auto_respond_behavior`, `stale_routing_policy_channel_ai`.
    - 운영 보정: audit 적용 직후 `broken_auto_respond_behavior=3`을 발견했고, 삭제 없이 기본 behavior version을
      추가해 `active_behavior_version_id`만 연결했다(`UPDATE 3`). 재검증 결과 세 항목 모두 0.
44. `DONE` Cloudflare SSH 장애와 runner busy stuck의 분리 진단 스크립트화.
    - 조치: 진단 스크립트와 `DEPLOY_REMOTE.md` 판정 기준으로 SSH 실패/runner session conflict/배포 SHA/app health를 분리.
45. `DONE` deploy workflow가 다른 CI job을 먼저 잡아 deploy가 대기하는 구조 완화.
    - 조치: `.github/workflows/central-deploy.yml`의 `build` job을 `ubuntu-latest` + QEMU/buildx arm64 이미지 빌드로
      이동하고, 원격 compose/health/policy audit가 필요한 `deploy` job만 `[self-hosted, yeon-arm]`에 남겼다.
      서버 runner 점유 시간이 deploy 단계로 줄어 다른 self-hosted CI가 build 시간을 가로막는 구조를 완화했다.
46. `DONE` GitHub API polling rate-limit 준수 helper/runbook 보강.
    - 조치: `scripts/gh-run-watch-safe.sh`를 추가해 기본 480초 간격/최대 30회/단일 run 조회로 제한하고,
      480초 미만은 `GH_RUN_WATCH_ALLOW_FAST=true` 없이는 exit 64로 거부한다.
      `docs/nexa/operations/github-actions-polling.md`에 GitHub API 대신 SSH/health 진단을 우선하는 runbook을 추가했다.
47. `DONE` generated RAG index 변경과 코드 PR 변경 분리 정책.
    - 조치: `scripts/check-rag-generated-boundary.sh`를 추가하고 `./scripts/nexa-verify.sh docs`에 연결했다.
      `rag/bm25/corpus.jsonl`·`rag/meta.db`가 일반 코드/config 변경과 섞이면 실패한다.
      `docs/nexa/operations/rag-generated-boundary.md`에 AI RAG rebuild workflow 전용 generated lane을 명시했다.
48. `DONE` i18n check가 테스트 locale 의존 문자열을 잡는 규칙 보강.
    - 조치: `scripts/check-locale-dependent-tests.py`를 추가하고 `make i18n-check`에 연결했다. provider-agent Python
      테스트의 JSON 응답 텍스트 필드(`error`/`message`/`reason`/`detail`/`failReason`)가 한국어 단일 substring에
      고정되면 실패한다.
    - 범위: 실제 CI 회귀였던 webui/provider-agent locale 의존 assertion만 좁게 잡는다. i18n 전용 테스트, 도메인 객체
      `err.message`, static JSON fixture는 제외해 과검출을 피한다. 한국어+영어 fallback을 같은 assertion에서 검증하면 허용한다.
    - 테스트 정리: 기존 provider-agent 테스트 3곳을 문구 substring 대신 403/status, response shape, delegate passthrough
      검증으로 바꿨다.
    - 검증: 검출기 자체 통과, py_compile 통과, negative/positive fixture 검증, `make i18n-check`, focused provider-agent
      테스트 통과.
49. `DONE` Docker/Testcontainers job 취소 시 ryuk/container cleanup 확인.
    - 조치: `scripts/cleanup-testcontainers.sh`를 추가했다. 기본은 audit, `--prune`은 `org.testcontainers=true`
      label이 붙은 container/network/volume만 제거한다. Docker 미설치·daemon down은 cleanup 실패가 원인을 가리지 않도록
      skip 성공으로 처리한다.
    - 조치: `.github/workflows/central-server-ci.yml` integration job의 `./gradlew test -PdockerTests` 뒤에
      `if: always()` cleanup 단계를 추가했다. push/PR path filter에도 스크립트 경로를 넣어 cleanup 정책 변경이 CI를 탄다.
    - 문서: `docs/nexa/operations/testcontainers-cleanup.md`에 수동 진단/정리 명령과 범위를 명시하고,
      `docs/nexa/baseline/ci-matrix.md`에 self-hosted runner 및 Testcontainers cleanup surface를 반영했다.
    - 검증: `bash -n`, YAML parse, `actionlint`, `check_links`, `git diff --check` 통과. 로컬 Docker에서 label이 붙은
      잔류 Testcontainers container 7개를 실제 발견했고 `--prune` 후 remaining containers `none`을 확인했다.
50. `DONE` 운영 DB read-only audit와 migration/repair script의 권한 경계 명시.
    - 조치: `.github/workflows/central-deploy.yml` post-health policy audit와 `scripts/diagnose-central-ops.sh`
      운영 DB audit 모두 `PGOPTIONS='-c default_transaction_read_only=on'` + `psql -v ON_ERROR_STOP=1`로 고정했다.
      audit SQL에 write가 섞이면 DB write 전에 실패한다.
    - 문서: `docs/nexa/operations/ops-db-audit-boundary.md`를 추가해 read-only audit lane과 repair/migration lane을
      분리했다. `CENTRAL_OPS_REPAIR_RUNNER=true`는 runner systemd 재시작만 하며 운영 DB write repair 옵션이 아니라고
      명시했다.
    - 문서: `central-server/docs/DEPLOY_REMOTE.md`에 policy audit 실패는 배포/진단 실패로만 보고, data repair/migration은
      별도 승인된 repair lane에서 처리한다고 연결했다.
    - 검증: 원격 psql 세션에서 `default_transaction_read_only=on` 확인, `scripts/diagnose-central-ops.sh` audit
      세 항목 0 확인, YAML parse, `actionlint`, `check_links`, `git diff --check` 통과.
