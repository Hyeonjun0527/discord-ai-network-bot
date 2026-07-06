# Testcontainers cleanup policy

`central-server CI`의 `integration` job은 Docker/Testcontainers 기반 BDD와 Flyway 검증을 실행한다. 실패나 취소
중간에 JVM이 종료되면 Ryuk가 정리하기 전에 Postgres/Ryuk container, network, volume이 남을 수 있다.

표준 진단:

```bash
scripts/cleanup-testcontainers.sh
```

표준 정리:

```bash
scripts/cleanup-testcontainers.sh --prune
```

범위:

- `org.testcontainers=true` label이 붙은 Docker container, network, volume만 대상으로 한다.
- 운영 compose(`central-server`, `db`, `searxng`)나 label 없는 Docker 리소스는 건드리지 않는다.
- Docker가 없거나 daemon이 내려가 있으면 skip하고 성공 종료한다. cleanup 자체가 테스트 실패 원인을 가리면 안 된다.

CI 연결:

- `.github/workflows/central-server-ci.yml`의 integration job은 `./gradlew test -PdockerTests` 뒤에
  `if: always()`로 `../scripts/cleanup-testcontainers.sh --prune`을 실행한다.
- 취소 직후 GitHub Actions가 후속 step을 실행할 수 있는 경우에는 이 단계가 Ryuk/container 잔류를 정리한다.
- runner가 강제 종료되어 후속 step 자체가 실행되지 못한 경우에는 다음 진단 때 같은 스크립트를 수동 실행한다.
