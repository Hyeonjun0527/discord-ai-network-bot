# 테스트 가이드 (차수 17)

## 실행
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew check   # 테스트 + 커버리지 게이트
```
- `check` 는 `test` → `jacocoTestReport` → `jacocoTestCoverageVerification` 을 포함한다.
- 커버리지 게이트(#254): INSTRUCTION **≥ 60%**(실측 ~71%). 부트스트랩/설정 클래스는 집계 제외.
- provider-agent: `pytest --cov=provider_agent --cov-fail-under=70`(실측 ~80%).

## 테스트 데이터 정리/격리 표준 (#260)
공유 in-memory H2 오염을 막기 위한 규칙:

1. **DB 를 건드리는 통합 테스트(`@SpringBootTest`/`@DataJpaTest`)는 반드시 격리**한다.
   - `@SpringBootTest` 통합 테스트에는 **`@Transactional`** 을 붙여 각 테스트 후 롤백시킨다
     (예: `CommandServiceTest`, `DashboardControllerTest`).
   - `@DataJpaTest` 는 기본적으로 트랜잭션 롤백된다(예: `PolicyServiceTest`).
2. **유닛 테스트는 DB 를 쓰지 않는다.** 순수 로직은 페이크/인메모리 객체로 검증한다
   (예: `MetricsApiControllerTest`, `PoolAlertMonitorTest`, `GuildIsolationTest`, `DiscordUxTest`).
3. **고정 id 충돌 회피**: 테스트마다 가능한 한 **서로 다른 guildId** 를 사용한다
   (예: 100/300/700/701) — 롤백이 보장돼도 가독성과 안전을 위해.

## 회귀 스위트 태깅 (#262)
- 빠른 유닛 테스트와 컨텍스트가 필요한 통합 테스트가 섞여 있다. 통합 테스트는
  `@SpringBootTest`/`@DataJpaTest` 어노테이션으로 식별된다.
- 향후 `@Tag("integration")` 를 도입해 `./gradlew test -PexcludeTags=integration` 로 빠른 루프를
  분리할 수 있다(현재는 전체 실행이 수 초라 미도입).

## 플래키 점검 (#263)
- 비결정성 원인(시간/랜덤/동시성)을 배제: 알림/격리/페이지네이션 테스트는 결정적 입력만 사용.
- `aiosqlite` 테스트는 store 를 반드시 닫아 인터프리터 종료 hang 을 방지(에이전트 측 규약).
- 와이어 컨트랙트는 공유 픽스처로 양측 고정 → 드리프트성 플래키 차단.
