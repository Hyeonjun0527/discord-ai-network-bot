# 기여 가이드 — Provider Pool (central-server + provider-agent)

## 개발 환경
- **central-server**: JDK 21 필요(`JAVA_HOME` 을 21 로). Gradle wrapper 사용.
- **provider-agent**: Python 3.11+, 루트 `.venv`(aiohttp/pytest/ruff/mypy 포함).
- Docker(선택): compose 기동/E2E.

## 자주 쓰는 명령 (`make help`)
```bash
make central-build   # Kotlin 빌드+테스트
make agent-test      # Python 에이전트 테스트
make agent-lint      # ruff+mypy
make contract        # 크로스언어 컨트랙트 테스트(양측)
make e2e             # 로컬 실연동 E2E
make compose-up      # Docker(Postgres+서버)
```

## 커밋 / PR
- Conventional Commits(`feat:`·`fix:`·`docs:`·`test:`·`ci:` …).
- **WS 프로토콜 변경 시 양쪽 동기화 필수**: `central-server/relay/protocol/Frame.kt` 와
  `provider-agent/.../protocol.py` 는 동일 camelCase 와이어를 써야 한다. 변경하면
  `wire-fixtures.json` 도 갱신하고 `make contract` 를 통과시킨다.
- 스키마 변경은 Flyway 마이그레이션(`db/migration/V_n__*.sql`)으로(엔티티 직접 ddl 금지).
- PR 전 검증: 소유 영역의 빌드/테스트/lint 통과 로그를 남긴다.

## 보안
- 토큰·DB 비밀·Discord 토큰은 환경변수. 커밋 금지.
- `central.dev.enabled` 는 운영에서 false. 자세히는 `SECURITY.md`.

## 테스트 격리
- `@SpringBootTest` 는 공유 in-memory H2 를 오염시킬 수 있으니 `@Transactional`(롤백) 을 붙인다.
- 단위 로직은 순수 함수/인터페이스 모킹으로(라우팅·필터·무게 등).
