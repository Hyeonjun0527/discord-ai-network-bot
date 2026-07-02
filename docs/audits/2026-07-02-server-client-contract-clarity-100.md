# 서버-클라이언트 계약 명확성 100개 취약점 감사

작성일: 2026-07-02

목표: 서버가 클라이언트에게 실패/상태/다음 행동을 오해 없이 전달하도록, 암묵적 응답·raw body·수동 JSON·
200+`ok:false` 혼재·클라이언트측 에러 은닉을 제거한다.

현재 스캔 근거:

- `central-server/src/main/kotlin/**/adapter/inbound/web/**`: 51개 파일 중 35개에서 계약 위험 패턴 247건.
- 주요 패턴: `Map<String, Any?>` 응답, `mapOf` 응답 조립, `ResponseStatusException`, 경계의
  `IllegalArgumentException`/`IllegalStateException`, 문자열/수동 JSON 응답.
- 클라이언트 패턴: `admin-console/src/api.ts`의 raw body 에러 문자열화, `loadDashboard`의 핵심 인증 실패 은닉.
- Provider Agent 패턴: `provider-agent/src/provider_agent/webui.py`의 `200 + {"ok": false, "error": "..."}`
  응답 혼재.

상태 표기:

- `FIXED-2026-07-02-A`: 이번 배치에서 보완.
- `FIXED-2026-07-02-B`: 두 번째 배치에서 보완.
- `FIXED-2026-07-02-C`: 세 번째 배치에서 보완.
- `FIXED-2026-07-02-D`: 네 번째 배치에서 보완.
- `FIXED-2026-07-02-E`: 다섯 번째 배치에서 보완.
- `FIXED-2026-07-02-F`: 여섯 번째 배치에서 보완.
- `FIXED-2026-07-02-G`: 일곱 번째 배치에서 보완.
- `FIXED-2026-07-02-H`: 여덟 번째 배치에서 보완.
- `OPEN`: 아직 보완 필요. 다음 배치에서 하나씩 닫는다.

## 체크리스트

1. [x] `AiNetworkApiSecurityFilter`가 403을 수동 문자열 JSON으로 작성해 `ApiErrorResponse`와 드리프트될 수 있음. 보완: `ObjectMapper` + `ApiErrorResponse` 직렬화. 상태: `FIXED-2026-07-02-A`.
2. [x] dashboard admin 403 코드가 `dashboard_admin_required` 소문자라 서버 에러 코드 규칙과 분기 기준이 불명확함. 보완: `DASHBOARD_ADMIN_REQUIRED`. 상태: `FIXED-2026-07-02-A`.
3. [x] dashboard admin 403에 `failedCondition`이 없어 무엇이 실패했는지 클라이언트가 추론해야 함. 보완: `dashboard_admin_authenticated`. 상태: `FIXED-2026-07-02-A`.
4. [x] dashboard admin 403에 `blockedAction`이 없어 막힌 행동이 불명확함. 보완: `AI_NETWORK_ADMIN_ACCESS`. 상태: `FIXED-2026-07-02-A`.
5. [x] dashboard admin 403에 `actionGuide`가 없어 사용자가 다음 행동을 알 수 없음. 보완: OAuth 또는 admin token 안내. 상태: `FIXED-2026-07-02-A`.
6. [x] dashboard admin 403 테스트가 문자열 포함만 확인해 envelope 드리프트를 못 잡음. 보완: `jsonPath`로 `success/status/requestId/error.*` 검증. 상태: `FIXED-2026-07-02-A`.
7. [x] admin-console이 서버 에러를 `status path: raw body`로 문자열화해 `error.code`를 잃음. 보완: `ApiRequestError.code`. 상태: `FIXED-2026-07-02-A`.
8. [x] admin-console이 서버 `requestId`를 에러 객체와 Bugsink 컨텍스트에 보존하지 않음. 보완: `serverRequestId`. 상태: `FIXED-2026-07-02-A`.
9. [x] admin-console이 `actionGuide`를 사용자 에러 메시지에 반영하지 않음. 보완: `ApiRequestError` 메시지 suffix. 상태: `FIXED-2026-07-02-A`.
10. [x] admin-console이 `/api/dashboard/guilds` 403을 빈 서버 목록으로 숨김. 보완: 핵심 서버 목록 요청은 실패를 전파. 상태: `FIXED-2026-07-02-A`.
11. [x] admin-console이 서버 에러 JSON 필드 타입을 검증하지 않고 그대로 신뢰할 수 있음. 보완: `optionalString`/`optionalNumber`/`isRecord` sanitizing. 상태: `FIXED-2026-07-02-A`.
12. [x] Bugsink API 컨텍스트가 서버 에러 코드를 태그로 받지 못함. 보완: `errorCode` 태그. 상태: `FIXED-2026-07-02-A`.
13. [x] Bugsink API 컨텍스트가 클라이언트 생성 requestId와 서버 requestId를 구분하지 못함. 보완: `serverRequestId`. 상태: `FIXED-2026-07-02-A`.
14. [x] `GlobalExceptionHandler`가 예상 못 한 `Exception`을 Spring 기본 HTML/기본 JSON으로 내보낼 수 있음. 보완: 내부 원인 비노출 `INTERNAL_SERVER_ERROR` envelope와 Sentry capture. 상태: `FIXED-2026-07-02-B`.
15. [x] `GlobalExceptionHandler`가 `IllegalStateException`을 모두 미처리해 경계 상태 오류가 500 기본 응답으로 보일 수 있음. 보완: `INVALID_SERVER_STATE` envelope 명시 handler. 상태: `FIXED-2026-07-02-B`.
16. [x] `ResponseStatusException` 변환이 `reason`만 message로 쓰고 `actionGuide`를 제공하지 않음. 보완: 401/403/503 등 공통 guide 매핑. 상태: `FIXED-2026-07-02-B`.
17. [x] `HttpMessageNotReadableException` 응답에 `failedCondition`이 없어 body parse 실패임을 코드 외에는 알기 어려움. 보완: `failedCondition=request_body_json_parseable`. 상태: `FIXED-2026-07-02-B`.
18. [x] `ApiErrorResponse`의 `code` 문자열 enum/레지스트리가 없어 서버/클라이언트 분기 코드가 drift될 수 있음. 보완: `ApiErrorCodes` registry + [docs/contracts/rest-error-envelope.md](../contracts/rest-error-envelope.md). 상태: `FIXED-2026-07-02-C`.
19. [x] `ApiErrorResponse.details`가 자유 `Map`이라 필드별 스키마가 없음. 보완: `ApiErrorDetailsSchemas` + details schema 문서. 상태: `FIXED-2026-07-02-C`.
20. [x] 필터 단계 에러와 MVC advice 에러가 같은 serializer 설정을 쓰는지 회귀 테스트가 부족함. 보완: `ApiErrorEnvelopeContractTest`에서 filter/advice shared JSON envelope 검증. 상태: `FIXED-2026-07-02-C`.
21. [x] `admin-console`이 optional panel 실패를 여전히 `null`/`[]`로 숨김. 보완: panel별 `partialErrors`를 상태와 UI에 포함. 상태: `FIXED-2026-07-02-D`.
22. [x] `admin-console`의 `buildApiContext.method`가 항상 `GET`이라 향후 쓰기 API에서 오해 가능. 보완: request method를 옵션화. 상태: `FIXED-2026-07-02-D`.
23. [x] `admin-console`의 base URL이 빈 문자열이면 fetch URL이 path만 되어 Bugsink `serverBaseUrl`과 실제 URL이 어긋날 수 있음. 보완: URL builder를 단일화. 상태: `FIXED-2026-07-02-D`.
24. [x] `admin-console` 성공 응답 JSON parse 실패가 구조화되지 않음. 보완: `INVALID_RESPONSE_JSON` client-side error. 상태: `FIXED-2026-07-02-D`.
25. [x] `admin-console`에는 API parser 단위 테스트가 없음. 보완: TypeScript emit + Node 내장 test runner fixture 검증. 상태: `FIXED-2026-07-02-D`.
26. [x] `PresetRegistryController`에 `Map<String, Any?>` 응답 27건. 보완: endpoint별 response DTO. 상태: `FIXED-2026-07-02-E`.
27. [x] `PresetRegistryController`에 `mapOf` 응답 조립 13건. 보완: DTO factory로 필드명 고정. 상태: `FIXED-2026-07-02-E`.
28. [x] `PresetRegistryResponses.kt`에 `Map<String, Any?>` 변환 11건. 보완: nested DTO로 contract 고정. 상태: `FIXED-2026-07-02-E`.
29. [x] `PresetRegistryResponses.kt`에 `mapOf` 변환 11건. 보완: `toMap()` 제거. 상태: `FIXED-2026-07-02-E`.
30. [x] `MultiResponseController`에 `Map<String, Any?>` 응답 14건. 보완: multi-response DTO. 상태: `FIXED-2026-07-02-F`.
31. [x] `MultiResponseResponses.kt`에 `Map<String, Any?>` 응답 14건. 보완: typed response tree. 상태: `FIXED-2026-07-02-F`.
32. [x] `MultiResponseResponses.kt`에 `mapOf` 응답 조립 14건. 보완: DTO serializer. 상태: `FIXED-2026-07-02-F`.
33. [x] `KnowledgeResponses.kt`에 `Map<String, Any?>` 응답 6건. 보완: typed knowledge response DTO. 상태: `FIXED-2026-07-02-G`.
34. [x] `KnowledgeResponses.kt`에 `mapOf` 조립 6건. 보완: map factory 제거. 상태: `FIXED-2026-07-02-G`.
35. [x] `ChannelAiCustomizationResponses.kt`에 `Map<String, Any?>` 응답 6건. 보완: typed channel AI DTO. 상태: `FIXED-2026-07-02-H`.
36. [x] `ChannelAiCustomizationResponses.kt`에 `mapOf` 조립 6건. 보완: nested DTO. 상태: `FIXED-2026-07-02-H`.
37. [x] `ChannelAiCustomizationController`에 `Map<String, Any?>` 응답 7건. 보완: DTO 반환형. 상태: `FIXED-2026-07-02-H`.
38. [ ] `NexaSettingsController`에 `Map<String, Any?>`/`mapOf` 응답 6건. 보완: settings DTO.
39. [ ] `DashboardWriteController`에 쓰기 성공을 `Map`으로 반환. 보완: `DashboardWriteResult` DTO.
40. [ ] `ConversationProjectionOpsController`가 `mapOf`와 `IllegalArgumentException`을 섞음. 보완: request validation DTO + domain exception.
41. [ ] `KnowledgeIngestionController`가 `IllegalStateException`을 경계 밖으로 흘릴 수 있음. 보완: `ConflictException`/`PreconditionFailedException`.
42. [ ] `LicenseController`가 `ResponseStatusException` 4건을 직접 던짐. 보완: license domain exception.
43. [ ] `PaddleWebhookController`가 `ResponseStatusException` 2건과 map 응답을 섞음. 보완: webhook response DTO + domain exception.
44. [ ] `ProviderAdminController`가 `ResponseEntity.body(mapOf(...))` 2건으로 error envelope를 우회. 보완: `ApiErrorResponse` 또는 domain exception.
45. [ ] `MeController`가 `Map<String, Any?>`로 auth 상태를 반환. 보완: `MeResponse` DTO.
46. [ ] `MetricsApiController`가 map/string body를 혼합. 보완: metric DTO와 actuator/public split.
47. [ ] `AiNetworkDashboardController`에 map 응답 1건. 보완: dashboard DTO.
48. [ ] `AiNetworkGrowthController`에 map 응답 3건. 보완: growth DTO.
49. [ ] `DashboardController`에 map 응답 3건. 보완: dashboard list/channel DTO.
50. [ ] `ChannelAiRoutingPolicyController`에 map 응답 4건. 보완: routing policy DTO.
51. [ ] `AiQualityFeedbackController`에 map 응답 2건. 보완: feedback DTO.
52. [ ] `ProviderSafetyController`에 map/string 응답 혼합. 보완: safety DTO.
53. [ ] `AiNetworkGrowthResponses.kt`에 map/string response helper 혼합. 보완: typed history DTO.
54. [ ] `DashboardResponses.kt`에 map/string helper 혼합. 보완: typed dashboard DTO.
55. [ ] `ChannelAiRoutingPolicyResponses.kt`에 `Map` 5건. 보완: nested DTO.
56. [ ] `AiQualityFeedbackResponses.kt`에 map 응답 4건. 보완: feedback DTO.
57. [ ] `AiNetworkDashboardResponses.kt`에 map 응답 1건. 보완: dashboard DTO.
58. [ ] `ConnectPageRenderer`가 HTML 문자열 6건을 직접 조립. 보완: HTML 렌더 경계와 API 경계 분리.
59. [ ] `ProviderConnectController`가 HTML/string 응답과 JSON map 응답을 한 컨트롤러에 혼합. 보완: page/API controller split.
60. [ ] `InstallPageController`가 `IllegalArgumentException`과 string response를 섞음. 보완: install page validation response.
61. [ ] `ChannelAiCustomizationService`의 `IllegalArgumentException("channel ai not found")`가 400으로 변환되어 404 의미를 잃음. 보완: `NotFoundException`.
62. [ ] `ChannelAiCustomizationService`의 `behavior version not found`가 400으로 변환됨. 보완: `NotFoundException`.
63. [ ] `ChannelAiCustomizationService`의 proposal not found가 400으로 변환됨. 보완: `NotFoundException`.
64. [ ] `ChannelAiCustomizationService`의 `proposal has no channel ai`가 400으로 변환됨. 보완: `InvalidStateTransitionException`.
65. [ ] `ChannelAiCustomizationService`의 payload changed `IllegalStateException`이 기본 500 가능. 보완: `ConflictException`.
66. [ ] `PresetRegistryService`의 preset not found 예외들이 400으로 변환됨. 보완: `NotFoundException`.
67. [ ] `PresetRegistryService`의 published preset not found 예외들이 400으로 변환됨. 보완: `NotFoundException`.
68. [ ] `PresetRegistryService`의 revision not found 예외들이 400으로 변환됨. 보완: `NotFoundException`.
69. [ ] `PresetCatalogQueryService`의 catalog not found 계열이 400으로 변환됨. 보완: `NotFoundException`.
70. [ ] `KnowledgeIndexingService`의 sensitive document cannot be indexed가 `INVALID_REQUEST`만 됨. 보완: code `SENSITIVE_DOCUMENT_NOT_INDEXABLE`.
71. [ ] `KnowledgeIndexingService`의 index job not found가 400으로 변환됨. 보완: `NotFoundException`.
72. [ ] `KnowledgeIndexingService`의 source not found가 400으로 변환됨. 보완: `NotFoundException`.
73. [ ] `KnowledgeIndexingPlanner`의 space not found가 400으로 변환됨. 보완: `NotFoundException`.
74. [ ] `AiNetworkFeatureGate`의 feature-disabled `IllegalStateException`이 기본 500 가능. 보완: `PreconditionFailedException`/503 policy.
75. [ ] `DashboardActor.required`의 missing actor `IllegalStateException`이 기본 500 가능. 보완: 403/500 경계 구분 테스트.
76. [ ] Provider Agent `_bad_server()`가 잘못된 guild id를 HTTP 200으로 반환. 보완: compatibility layer + structured 400 envelope.
77. [ ] Provider Agent `_bad_server()`가 code 없이 한국어 `error` 문자열만 반환. 보완: `{ok:false, code, message}`.
78. [ ] Provider Agent admin manage/action/set policy가 agent stopped를 200+`ok:false`로 반환. 보완: typed error with status/code.
79. [ ] Provider Agent admin prompt set endpoints가 동일 error 문자열을 반복. 보완: shared error helper.
80. [ ] Provider Agent channel toggle invalid channel이 code 없이 문자열만 반환. 보완: `INVALID_CHANNEL_ID`.
81. [ ] Provider Agent safety review missing decision이 code 없이 문자열만 반환. 보완: `SAFETY_REVIEW_DECISION_REQUIRED`.
82. [ ] Provider Agent OAuth callback state mismatch가 plain text 403. 보완: HTML/API 경계별 structured response.
83. [ ] Provider Agent OAuth pending/error callback이 plain HTML body에 의존. 보완: page render DTO.
84. [ ] Provider Agent model URL validation이 200+`ok:false`. 보완: 400 + code.
85. [ ] Provider Agent ComfyUI not installed/running states가 문자열만 반환. 보완: `COMFY_NOT_INSTALLED`/`COMFY_NOT_RUNNING`.
86. [ ] Provider Agent update/check failures가 `error` nullable field로만 표현됨. 보완: status enum + code.
87. [ ] Provider Agent billing/entitlement failures가 `str(exc)`를 노출. 보완: exception translation.
88. [ ] Provider Agent filesystem export failures가 `str(exc)`를 노출. 보완: safe message + internal log.
89. [ ] Provider Agent webui JSON success shapes가 endpoint마다 `ok`, `status`, `error` 필드를 다르게 씀. 보완: local API envelope.
90. [ ] Desktop prototype adapter가 non-OK response body contract를 검사하지 않음. 보완: shared `http()` error parser.
91. [ ] OpenAPI/contract test가 `ApiErrorResponse` error-code coverage를 강제하지 않음. 보완: error code golden test.
92. [ ] generated `protocol/wire-contract.json`이 REST admin-console error contract를 포함하지 않음. 보완: REST error contract section.
93. [ ] `ai-context/contracts.json`에 admin error envelope와 actionGuide semantics가 없음. 보완: contract SSOT 추가.
94. [ ] `make contract`가 controller map-return regressions를 막지 않음. 보완: static contract lint.
95. [ ] `make ssot-viewer-check`가 API error shape drift를 감지하지 않음. 보완: generated viewer includes error envelope.
96. [ ] central-server CI가 admin-console build를 REST contract 변경과 함께 강제하지 않음. 보완: path-aware build gate.
97. [ ] admin-console에는 API parser fixture가 없어 서버 envelope 변경이 UI에 깨져도 감지 안 됨. 보완: parser test harness.
98. [ ] provider-agent tests가 200+`ok:false` compatibility만 고정하고 HTTP status 개선 경로를 안내하지 않음. 보완: dual-contract migration tests.
99. [ ] 문서에 “어떤 오류는 `actionGuide` 필수” 같은 정책이 없음. 보완: error-code authoring rules.
100. [ ] 신규 컨트롤러 작성 시 DTO/envelope 규칙을 체크하는 리뷰 체크리스트가 없음. 보완: AGENTS 또는 docs contract checklist에 추가.
