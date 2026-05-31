# Provider Pool 에지케이스 정책

이 문서는 커뮤니티 로컬 AI Provider Pool 의 특수 상황 처리 기준이다. 30개 전체 에지케이스는 이 문서에 기록하고, 제품 안전성에 직접 연결되는 핵심 계약만 Cucumber 요구사항으로 추적한다.

## 테스트 기록 원칙

- **문서(이 파일)**: 모든 특수 상황의 기대 동작, 현재 상태, 향후 작업을 남긴다.
- **Cucumber**: 사용자가 체감하는 P0/P1 핵심 계약만 올린다. 예: 서버 격리, 봇 제거 정리, 영구 기여 기록, 일회용 토큰.
- **단위/통합 테스트**: 구현 세부 정책을 넓게 커버한다. 모든 에지케이스를 Cucumber 로 만들지는 않는다.
- **운영 문서/FAQ**: 사용자가 실제로 물어볼 문장으로 요약한다.

## 현재 Cucumber 로 추적하는 핵심 계약

| 요구사항 | 정책 | Feature |
| --- | --- | --- |
| `REQ-PPOOL-001` | 다른 서버의 온라인 프로바이더는 이 서버 질문을 처리하지 않는다. | `central-server/src/test/resources/features/provider_pool_lifecycle.feature` |
| `REQ-PPOOL-002` | 봇이 서버에서 제거되면 해당 서버의 프로바이더 세션·등록·미사용 토큰을 정리한다. | `central-server/src/test/resources/features/provider_pool_lifecycle.feature` |
| `REQ-PPOOL-003` | 한 번이라도 기여한 프로바이더는 오프라인이어도 기여순위에 영구 표시된다. | `central-server/src/test/resources/features/provider_pool_lifecycle.feature` |
| `REQ-PPOOL-004` | 프로바이더 토큰은 서버에 묶인 일회용 토큰이다. | `central-server/src/test/resources/features/provider_pool_lifecycle.feature` |

## 30개 에지케이스 처리 기준

상태 표기:

- **구현됨**: 현재 코드와 테스트로 보호된다.
- **부분 구현**: 기본 동작은 있으나 보강이 필요하다.
- **정책 확정**: 기대 동작은 정했지만 구현/검증은 남아 있다.

| # | 질문/상황 | 처리 정책 | 현재 상태 | 권장 검증 |
| ---: | --- | --- | --- | --- |
| 1 | 프로바이더가 연결된 서버에서 봇을 삭제하면? | 해당 길드의 활성 세션을 닫고, 등록·미사용 토큰·서버 정책을 정리한다. 기여 로그는 남긴다. | 구현됨 | Cucumber + `GuildRemovalCleanupServiceTest` |
| 2 | 봇을 다시 초대하면 예전 프로바이더가 자동 복구되나? | 자동 복구하지 않는다. 새 서버 상태로 보고 다시 `/provider-join` 한다. | 정책 확정 | 단위/운영 테스트 |
| 3 | 봇 삭제 후 기여순위 기록은 사라지나? | 사라지지 않는다. 기여는 영구 감사/인정 기록이다. | 구현됨 | Cucumber + `CommandServiceTest` |
| 4 | 서버 A 토큰으로 서버 B 에 연결하면? | 토큰은 발급 길드에 묶인다. 인증 성공 시에도 해당 길드 세션으로만 등록된다. | 구현됨 | Cucumber + `TokenServiceTest` |
| 5 | 토큰을 받았지만 쓰기 전에 봇이 서버에서 삭제되면? | 해당 길드 미사용 토큰을 폐기한다. | 구현됨 | Cucumber + `ProviderRegistrationServiceTest` |
| 6 | 질문 처리 중 봇이 삭제되면? | 진행 중 요청은 실패/취소 처리하고 프로바이더 세션을 닫는다. 사용자에게 후속 응답을 보장하지 않는다. | 구현됨 | `ConnectionRegistryTest` |
| 7 | 답변 전송 중 Discord 권한이 사라지면? | 프로바이더 처리는 완료하되 Discord 전송 실패는 로그/관측성으로 남기고 가능하면 기본 응답으로 폴백한다. | 부분 구현 | 어댑터 테스트 보강 |
| 8 | 봇이 없는 서버인데 에이전트가 계속 켜져 있으면? | 길드 제거 이벤트 또는 정합성 검사로 세션을 닫는다. | 구현됨 | `GuildRemovalCleanupServiceTest` + `ProviderPoolReconciliationServiceTest` |
| 9 | 관리자 승인 대기 중 봇이 삭제되면? | 승인 대기 등록을 삭제하고 토큰을 발급하지 않는다. | 구현됨 | `ProviderRegistrationServiceTest` |
| 10 | 서버 소유자가 바뀌면? | 설정은 유지하고, 이후 명령 권한은 Discord 의 현재 관리자 권한 기준으로 판정한다. | 구현됨 | `CommandServiceTest` |
| 11 | 봇의 관리자 권한 일부가 줄어들면? | 풀 자체는 유지하되 실패한 기능은 명확히 안내한다. 임시 권한 문제로 등록을 삭제하지 않는다. | 정책 확정 | Discord 어댑터 테스트 |
| 12 | 채널 웹훅 권한이 없는데 채널 프로필을 설정하면? | 답변은 일반 봇 응답으로 폴백하고, 관리자에게 웹훅 권한 필요를 안내한다. | 부분 구현 | 웹훅 폴백 테스트 보강 |
| 13 | 채널을 삭제하면 채널별 AI 프로필/허용 설정은? | 삭제 채널 설정은 정리 대상이다. JDA 채널 삭제 이벤트/정합성 서비스가 허용 채널과 AI 프로필을 함께 청소한다. | 구현됨 | `ProviderPoolReconciliationServiceTest` |
| 14 | 채널 프로필 이름을 자주 바꾸면 웹훅이 계속 늘어나나? | 하나의 채널 웹훅을 재사용하고 설정 값만 갱신한다. | 부분 구현 | 웹훅 재사용 검증 |
| 15 | 부적절한 프로필명/아이콘을 올리면? | 관리자만 설정 가능하고 reset 으로 즉시 기본값 복구가 가능하다. 파일 업로드는 Discord 이미지 attachment 만 허용한다. | 구현됨 | `CommandServiceTest` |
| 16 | 영어/한국어 슬래시 명령이 둘 다 보이면? | 길드 스코프 명령을 정리하고 글로벌 명령 로컬라이징만 유지한다. | 구현됨 | 명령 등록 테스트/운영 확인 |
| 17 | 봇 삭제 후 에이전트가 재연결하면? | 길드 등록/미사용 토큰을 제거하고, 서버에 묶이지 않은 레거시 토큰은 인증 거부한다. | 구현됨 | `RelayWebSocketHandlerTest` + `GuildRemovalCleanupServiceTest` |
| 18 | DB 에는 승인 상태인데 에이전트가 꺼져 있으면? | `/providers` 는 승인됨/오프라인으로 보여준다. 라우팅에는 온라인 세션만 사용한다. | 구현됨 | CommandService 테스트 |
| 19 | 프로바이더 유저가 Discord 서버를 나가면? | 해당 길드의 프로바이더 등록과 세션을 정리한다. 기여 로그는 유지한다. | 구현됨 | `ProviderPoolReconciliationServiceTest` |
| 20 | 한 유저가 여러 서버에 프로바이더로 등록하면? | 등록·토큰·세션·보호 명령은 `(guildId, providerId)` 단위로 분리한다. provider 모델 정책은 전역 provider 설정으로 유지한다. | 구현됨 | `ProviderRegistrationTest` + `ConnectionRegistryTest` |
| 21 | 같은 유저가 서버 A/B 에 동시에 에이전트를 켜면? | 서버별 토큰/세션을 분리하며, 같은 provider 라도 다른 길드 세션은 서로 교체하지 않는다. | 구현됨 | `ConnectionRegistryTest` |
| 22 | 서버 A 프로바이더가 서버 B 질문을 처리할 수 있나? | 절대 불가. 라우팅 후보는 질문 길드의 세션만이다. | 구현됨 | Cucumber |
| 23 | DM 에서 질문하면 어느 풀을 쓰나? | DM 전용 `DM_SCOPE` 와 길드 스코프를 섞지 않는다. | 구현됨 | `DmScopeRoutingTest` |
| 24 | 봇이 없는 서버 ID 로 토큰이 만들어지면? | 운영에서는 봇 제거 정리로 미사용 토큰을 폐기한다. 서버에 묶이지 않은 토큰은 WS 인증 단계에서 거부한다. dev 엔드포인트는 운영에서 비활성이다. | 부분 구현 | `RelayWebSocketHandlerTest` + 운영 가드 테스트 |
| 25 | DB 백업 복원으로 오래된 등록이 살아나면? | 정합성 서비스가 실제 known guild 목록과 후보 guild 목록을 비교해 누락 길드를 정리할 수 있다. | 부분 구현 | `ProviderPoolReconciliationServiceTest` + 복구 런북 필요 |
| 26 | 배포/마이그레이션 중 에이전트 연결이 끊기면? | 에이전트는 지수 백오프로 재연결한다. 진행 중 요청은 실패할 수 있다. | 구현됨(에이전트) | E2E/운영 확인 |
| 27 | Discord API 장애로 봇 삭제 이벤트가 늦게 오면? | 이벤트 기반 정리에 더해 정합성 서비스로 누락 길드를 청소할 수 있게 한다. | 부분 구현 | 스케줄러 연결 작업 필요 |
| 28 | 일시 권한 장애와 진짜 봇 삭제를 어떻게 구분하나? | 권한 장애는 등록 삭제가 아니라 경고/폴백이다. 봇 제거 이벤트 또는 멤버십 부재만 삭제 트리거다. | 정책 확정 | 어댑터 테스트 |
| 29 | 삭제된 Discord 계정의 기여 기록은? | 기록은 유지하고 `<@id>`/ID 기반으로 표시한다. 멤버 이탈 정리는 등록만 제거하고 contribution_log 는 삭제하지 않는다. | 구현됨 | `ProviderPoolReconciliationServiceTest` + `CommandServiceTest` |
| 30 | 토큰/세션을 악용해 여러 세션을 열면? | 동일 provider/guild 의 활성 세션은 하나로 제한하고 오래된 세션을 내보낸다. 다른 guild 세션은 독립 유지한다. | 구현됨 | `ConnectionRegistryTest` |

## 구현 우선순위

1. **이미 보호 중인 P0**: 서버 격리, 봇 제거 정리, 영구 기여 기록, 일회용 길드 토큰.
2. **다음 P0 후보**: 정합성 서비스를 주기 스케줄러/JDA 채널 삭제 이벤트에 직접 연결, provider 모델 정책의 guild 스코프화 여부 결정.
3. **P1 운영 품질**: 웹훅 권한 폴백, Discord API 장애/권한 장애 안내, 오래된 DB 복원 런북.
