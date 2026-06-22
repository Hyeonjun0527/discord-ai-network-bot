# NEXA-P00-T002 빌드·테스트·린트 명령 기준선

- 작업 ID: `NEXA-P00-T002`
- 작성 시각: 2026-06-20 12:14-12:20 KST
- 기준 브랜치: `feat/nexa-p00-t001-baseline`
- 기준 커밋: `a0e2118be225c2e651d29216c69a37d248b80239`
- 선행 작업: `NEXA-P00-T001` = `VERIFIED`
- 목적: 추측 명령이 아니라 현재 저장소에서 실제 실행한 루트·central-server·provider-agent·games 명령의 종료 코드와 사전 조건을 고정한다.

## 1. 로컬 런타임 사전 조건

| 도구 | 현재 로컬 값 | 기준선 영향 |
|---|---|---|
| shell `JAVA_HOME` | `/Users/osuma/.sdkman/candidates/java/current` → Corretto `25.0.3` | 이 값 그대로 `make central-build`를 실행하면 실패한다. central-server는 JDK 21을 명시해야 한다. |
| JDK 21 위치 | `/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home` | `JAVA_HOME=... make central-build`와 direct Gradle build는 통과한다. |
| `python3` | `Python 3.14.5` | 로컬 `.venv`도 Python 3.14.5다. CI는 provider-agent에서 Python 3.12를 사용한다. |
| `.venv/bin/ruff` | `ruff 0.15.15` | provider-agent lint 통과. |
| `.venv/bin/mypy` | `mypy 1.20.2` | provider-agent typecheck 통과. |
| Node | `v22.22.1` | games npm install/test/build/lint에 사용. |
| npm | `10.9.4` | games npm install/test/build/lint에 사용. |

## 2. 루트 Makefile 진입점

실행:

```bash
make help
```

결과: exit code `0`.

출력된 공개 타깃:

```text
help
central-build
central-jar
agent-test
agent-lint
wire-gen
wire-check
contract
packaging-check
sync-desktop
desktop-shapes
desktop-check
ssot-viewer
ssot-viewer-check
compose-up
compose-down
```

관찰:

- `Makefile`에는 `i18n-gen`, `i18n-check`, `e2e`도 있지만 `help`의 grep 패턴이 숫자를 포함한 target을 잡지 못해 출력에서 빠진다.
- 루트 wrapper는 편리하지만 현재 shell `JAVA_HOME`을 상속한다. 따라서 central build는 JDK 21을 명시해야 재현된다.

### 2.1 root wrapper 실패 기준선 — JDK 25 상속

실행:

```bash
make central-build
```

결과: exit code `2`.

핵심 출력:

```text
JAVA_HOME=/Users/osuma/.sdkman/candidates/java/current central-server/gradlew -p central-server build --no-daemon --console=plain

FAILURE: Build failed with an exception.

* What went wrong:
25.0.3

BUILD FAILED in 3s
make: *** [central-build] Error 1
```

판정: 코드 실패가 아니라 로컬 shell의 JDK 25 환경 실패다. `AGENTS.md`의 JDK 21 요구와 일치한다.

### 2.2 root wrapper 성공 기준선 — JDK 21 명시

실행:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home make central-build
make agent-test
make agent-lint
```

결과: exit code `0`.

핵심 출력:

```text
BUILD SUCCESSFUL in 5s
20 actionable tasks: 1 executed, 19 up-to-date

377 passed, 1462 warnings in 3.63s

All checks passed!
Success: no issues found in 35 source files
```

## 3. central-server 직접 명령

실행:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server build --no-daemon --console=plain
```

결과: exit code `0`.

핵심 출력:

```text
> Task :test UP-TO-DATE
> Task :koverVerify
> Task :ktlintKotlinScriptCheck UP-TO-DATE
> Task :ktlintMainSourceSetCheck UP-TO-DATE
> Task :ktlintTestSourceSetCheck UP-TO-DATE
> Task :check
> Task :build

BUILD SUCCESSFUL in 6s
20 actionable tasks: 1 executed, 19 up-to-date
```

사전 조건:

- JDK 21 명시 필요.
- 기본 build는 Docker 의존 Cucumber/Testcontainers를 제외한다. Docker 통합 BDD는 `central-server/gradlew -p central-server test -PdockerTests --no-daemon --console=plain`로 별도 실행한다.

## 4. provider-agent 직접 명령

실행:

```bash
cd provider-agent
../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70
../.venv/bin/ruff check src tests
../.venv/bin/mypy src
```

결과: exit code `0`.

핵심 출력:

```text
377 passed, 1462 warnings in 4.12s
Required test coverage of 70% reached. Total coverage: 73.31%
All checks passed!
Success: no issues found in 35 source files
```

사전 조건과 관찰:

- 로컬 `.venv`는 Python 3.14.5다. CI는 `.github/workflows/provider-agent-ci.yml`에서 Python 3.12를 사용한다.
- 테스트는 통과하지만 Python 3.14 기준 `pytest_asyncio`/event loop deprecation 및 unraisable warning이 많이 출력된다. 현재 기준선에서는 실패로 처리되지 않는다.

## 5. games 명령

`games/README.md` 기준 games는 central-server/provider-agent와 분리된 독립 서브프로젝트다. 루트 CI/빌드와 자동 연결되어 있지 않으므로 각 package를 직접 실행했다.

### 5.1 crazy-nia client/server

사전 조건 설치:

```bash
cd games/crazy-nia
npm ci
npm --prefix server ci
```

결과: exit code `0`.

보안 audit 관찰:

```text
client: 4 vulnerabilities (1 moderate, 1 high, 2 critical)
server: 3 moderate severity vulnerabilities
```

실행:

```bash
cd games/crazy-nia
npm test
npm run build
npm --prefix server test
npm --prefix server run build
```

결과: exit code `0`.

핵심 출력:

```text
client test: 25 pass, 0 fail
client build: ✓ built in 2.73s
server test: 10 pass, 0 fail
server build: tsc -p tsconfig.json completed
```

관찰:

- client build는 `dist/assets/index-*.js`가 500 kB를 넘는 Vite chunk warning을 낸다. 실패는 아니다.
- `npm ci`와 build로 생긴 `games/crazy-nia/node_modules/`, `games/crazy-nia/server/node_modules/`, `dist/`들은 ignored 상태다.

### 5.2 strike-protocol

사전 조건 설치:

```bash
cd games/strike-protocol
npm ci
```

결과: exit code `0`.

보안 audit 관찰:

```text
1 low severity vulnerability
```

실행:

```bash
cd games/strike-protocol
npm test
npm run build
npm run lint
```

결과: 전체 chained command exit code `1` — `npm run lint`에서 실패.

통과한 단계:

```text
npm test: 8 pass, 0 fail
npm run build: client + ssr build completed
```

실패 단계:

```text
npm run lint: exit code 1
```

구조화 재확인:

```bash
cd games/strike-protocol
npx eslint . --format json > /tmp/strike-eslint.json
```

요약:

```json
{
  "rc": 1,
  "errors": 1330,
  "warnings": 6,
  "fixableErrors": 1328,
  "fixableWarnings": 0
}
```

대표 파일:

| 파일 | errors | warnings | 첫 rule |
|---|---:|---:|---|
| `src/components/ui/badge.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/components/ui/button.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/components/ui/form.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/components/ui/navigation-menu.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/components/ui/sidebar.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/components/ui/toggle.tsx` | 0 | 1 | `react-refresh/only-export-components` |
| `src/lib/game/engine.ts` | 1227 | 0 | `prettier/prettier` |
| `src/routes/index.tsx` | 24 | 0 | `prettier/prettier` |
| `src/routes/play.tsx` | 79 | 0 | `prettier/prettier` |

판정: NEXA 작업으로 생긴 신규 실패가 아니라 T002 시점 games 기준선 실패다. build/test는 통과하지만 lint는 Prettier 포맷 오류로 실패한다.

## 6. CI 명령과 로컬 명령 차이

| 영역 | CI 기준 | 로컬 기준선 |
|---|---|---|
| central build | `.github/workflows/central-server-ci.yml`: `./gradlew build --no-daemon --console=plain` in `central-server`, Temurin JDK 21 | JDK 21 명시 시 통과. JDK 25 상속 시 실패. |
| central integration | `./gradlew test -PdockerTests --no-daemon --console=plain` | T002에서는 실행하지 않음. Docker/Testcontainers 전용으로 분리 기록. |
| provider-agent | Python 3.12, `pip install -e ".[dev]"`, ruff, mypy, pytest coverage ≥70 | 로컬 `.venv` Python 3.14.5에서도 통과. |
| docs-links | `python3 scripts/check_links.py`, `python3 scripts/check_packaging.py`, `python3 scripts/gen_i18n.py --check` | T002 최종 검증에서 links는 실행. packaging/i18n은 직접 수정 범위가 아니어서 T002 명령 조사 대상만 기록. |
| games | 현재 루트 CI workflow에 없음 | 직접 npm ci/test/build/lint 실행. strike lint 실패를 기준선으로 기록. |

## 7. 현재 기준선 결론

- central-server: JDK 21 명시 시 build/test/ktlint/kover 통과.
- provider-agent: pytest coverage, ruff, mypy 통과.
- games/crazy-nia: client/server test/build 통과. npm audit 취약점은 존재.
- games/strike-protocol: test/build 통과, lint 실패(`prettier/prettier` 중심 1330 errors + react-refresh warnings 6개).
- root Makefile: `make help`가 일부 숫자 포함 target(`i18n-*`, `e2e`)을 숨긴다. `make central-build`는 shell `JAVA_HOME`을 상속하므로 JDK 21 명시가 필수다.
- `./scripts/nexa-verify.sh`는 아직 없으므로 계획상 검증 래퍼는 T008 전까지 실행 불가다.

## 8. T002 완료 조건 판정

| 조건 | 판정 | 근거 |
|---|---|---|
| 루트 명령 조사 | 충족 | `make help`, `make central-build` 실패/성공 조건, `make agent-*` 실행 결과 기록 |
| central-server 명령 실행 | 충족 | JDK 21 direct Gradle build exit 0 |
| provider-agent 명령 실행 | 충족 | pytest coverage/ruff/mypy exit 0 |
| games 명령 실행 | 충족 | crazy-nia client/server, strike-protocol test/build/lint 실행 결과 기록 |
| 종료 코드 기록 | 충족 | 각 command의 exit code와 핵심 출력 기록 |
| 사전 조건 기록 | 충족 | JDK/Python/Node/npm, npm ci, Docker 통합 테스트 분리 기록 |
| 기존 실패와 신규 실패 구분 | 충족 | JDK 25 root wrapper 실패와 strike lint 실패를 baseline으로 분리 |

## 9. 다음 작업 표시

- 다음 그래프 작업: `NEXA-P00-T003 — Kotlin·Spring·JDA·Python 버전 인벤토리 고정`
- T002는 human gate가 아니며, 산출물과 acceptance가 충족되어 `VERIFIED`로 올린다.
