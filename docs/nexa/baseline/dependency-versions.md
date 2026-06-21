# NEXA-P00-T003 런타임·플러그인·의존성 버전 인벤토리

- 작업 ID: `NEXA-P00-T003`
- 작성 시각: 2026-06-20 KST
- 기준 브랜치: `feat/nexa-p00-t001-baseline`
- 기준 커밋: `a0e2118be225c2e651d29216c69a37d248b80239`
- 선행 작업: `NEXA-P00-T002` = `VERIFIED`
- 목적: Kotlin/Spring/JDA/Python 핵심 런타임과 플러그인 버전을 실제 파일·명령 출력 기준으로 고정하고, CI와 로컬의 버전 차이를 명시한다.

## 1. 증거로 사용한 파일·명령

파일:

```text
central-server/build.gradle.kts
central-server/settings.gradle.kts
central-server/gradle/wrapper/gradle-wrapper.properties
provider-agent/pyproject.toml
provider-agent/src/provider_agent/constants.py
.github/workflows/central-server-ci.yml
.github/workflows/central-server-image.yml
.github/workflows/central-deploy.yml
.github/workflows/provider-agent-ci.yml
.github/workflows/agent-build.yml
```

명령:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server --version --no-daemon

JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server dependencyInsight \
  --dependency net.dv8tion:JDA --configuration runtimeClasspath --no-daemon --console=plain

JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home \
  central-server/gradlew -p central-server dependencyInsight \
  --dependency org.springframework.boot:spring-boot-starter-web \
  --configuration runtimeClasspath --no-daemon --console=plain

python3 --version
.venv/bin/python --version
.venv/bin/python -m pip show nexa-agent discord-ai-provider-agent discord-ai-network-bot aiohttp certifi sentry-sdk pytest pytest-asyncio pytest-cov ruff mypy setuptools wheel
```

## 2. central-server JVM/Gradle/Kotlin/Spring 기준

| 항목 | 선언/해결 버전 | 근거 | CI와 로컬 일치 여부 |
|---|---:|---|---|
| Gradle wrapper | `8.12` | `central-server/gradle/wrapper/gradle-wrapper.properties`의 `gradle-8.12-bin.zip`; `gradlew --version`도 `Gradle 8.12` | 일치. CI와 로컬 모두 wrapper를 사용한다. |
| Gradle wrapper 내장 Kotlin | `2.0.21` | `gradlew --version` 출력 | Gradle 내부 런타임 값이다. 프로젝트 Kotlin plugin과 다르지만 정상이다. |
| 프로젝트 Kotlin JVM plugin | `2.1.0` | `kotlin("jvm") version "2.1.0"` | CI/로컬 동일. |
| Kotlin Spring plugin | `2.1.0` | `kotlin("plugin.spring") version "2.1.0"` | CI/로컬 동일. |
| Kotlin JPA plugin | `2.1.0` | `kotlin("plugin.jpa") version "2.1.0"` | CI/로컬 동일. |
| Kotlin JVM target | `JVM_21` | `compilerOptions.jvmTarget = JvmTarget.JVM_21` | JDK 21 toolchain 요구와 일치. |
| Java toolchain | `21` | `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }` | CI는 Temurin 21, 로컬은 Corretto 21 명시 시 일치. shell 기본은 JDK 25라 불일치. |
| Spring Boot Gradle plugin | `3.4.1` | `id("org.springframework.boot") version "3.4.1"` | CI/로컬 동일. |
| Spring dependency-management plugin | `1.1.7` | `id("io.spring.dependency-management") version "1.1.7"` | CI/로컬 동일. |
| ktlint Gradle plugin | `12.1.1` | `id("org.jlleitschuh.gradle.ktlint") version "12.1.1"` | CI/로컬 동일. |
| ktlint CLI/tool version | `1.4.1` | `ktlint { version.set("1.4.1") }` | CI/로컬 동일. |
| Kover | `0.9.1` | `id("org.jetbrains.kotlinx.kover") version "0.9.1"` | CI/로컬 동일. |
| Foojay toolchain resolver | `0.9.0` | `central-server/settings.gradle.kts` | CI/로컬 동일. |

### 2.1 central-server 핵심 런타임 의존성

| 항목 | 버전/관리 방식 | 근거 |
|---|---|---|
| Spring Web starter | resolved `3.4.1` | `dependencyInsight`: `org.springframework.boot:spring-boot-starter-web:3.4.1` |
| Spring WebSocket starter | resolved `3.4.1` | `dependencyInsight`: `spring-boot-starter-websocket:3.4.1` |
| Spring Security/OAuth2/Actuator/Validation/Data JPA/Data Redis | Spring Boot BOM 관리(`3.4.1` 계열) | `build.gradle.kts` starter 선언 + Boot plugin/dependency management |
| JDA | `5.2.1` | `implementation("net.dv8tion:JDA:5.2.1")`; `dependencyInsight`도 `5.2.1` |
| springdoc OpenAPI starter | `2.7.0` | `implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")` |
| Sentry Spring Boot starter | `7.14.0` | `implementation("io.sentry:sentry-spring-boot-starter-jakarta:7.14.0")` |
| Cucumber BOM | `7.20.1` | `testImplementation(platform("io.cucumber:cucumber-bom:7.20.1"))` |
| ArchUnit | `1.3.0` | `testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")` |
| Testcontainers | Spring Boot dependency management | `testImplementation("org.testcontainers:junit-jupiter")`, `postgresql` without explicit version |
| PostgreSQL/H2/Flyway | Spring Boot dependency management + explicit Flyway modules | `runtimeOnly(...)`, `implementation("org.flywaydb:flyway-core")` |

### 2.2 central-server CI/로컬 차이

| 환경 | 실제 값 | 판정 |
|---|---|---|
| CI build/integration | `.github/workflows/central-server-ci.yml`: `actions/setup-java@v4`, `distribution: temurin`, `java-version: "21"` | 프로젝트 요구와 일치. |
| CI image/deploy | `central-server-image.yml`, `central-deploy.yml`: Temurin JDK 21로 `bootJar`/`clean bootJar` | 프로젝트 요구와 일치. |
| 로컬 shell 기본 | `JAVA_HOME=/Users/osuma/.sdkman/candidates/java/current`, Java `25.0.3` | 불일치. `make central-build` 실패 기준선과 연결된다. |
| 로컬 JDK 21 명시 | `/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home`, Java `21.0.11` | 일치. direct Gradle build와 `make central-build`가 통과한다. |

## 3. provider-agent Python 기준

| 항목 | 선언/실제 값 | 근거 | CI와 로컬 일치 여부 |
|---|---|---|---|
| 패키지명 | `nexa-agent` | `provider-agent/pyproject.toml` | CI는 현재 pyproject 기준 설치. 로컬 editable metadata는 stale. |
| pyproject version | `0.59.1` | `provider-agent/pyproject.toml` | 코드 상수와 일치. |
| runtime AGENT_VERSION | `0.59.1` | `provider-agent/src/provider_agent/constants.py` | pyproject와 일치. |
| requires-python | `>=3.11` | `pyproject.toml` | CI Python 3.12와 로컬 Python 3.14 모두 범위 안. 단 AGENTS 정책은 3.12를 요구한다. |
| ruff target-version | `py311` | `[tool.ruff] target-version = "py311"` | 로컬/CI 모두 같은 설정 사용. |
| mypy python_version | `3.11` | `[tool.mypy] python_version = "3.11"` | 로컬/CI 모두 같은 설정 사용. |
| pytest asyncio mode | `auto` | `[tool.pytest.ini_options] asyncio_mode = "auto"` | 로컬/CI 동일. |
| CI Python | `3.12` | `provider-agent-ci.yml`, `agent-build.yml`의 `python-version: "3.12"` | AGENTS 정책과 일치. |
| 로컬 `python3` | `3.14.5` | `python3 --version` | CI와 불일치. |
| 로컬 `.venv/bin/python` | `3.14.5` | `.venv/bin/python --version` | CI와 불일치. |

### 3.1 provider-agent 선언 의존성

| 그룹 | 선언 |
|---|---|
| build-system | `setuptools>=69`, `wheel` |
| runtime | `aiohttp>=3.9,<4.0`, `certifi>=2024.8.30`, `sentry-sdk>=2.45,<3.0` |
| dev | `pytest>=8,<9`, `pytest-asyncio>=0.23,<1.0`, `pytest-cov>=5,<7`, `ruff>=0.4`, `mypy>=1.10` |
| monitor optional | `psutil>=5.9,<7.0` |
| tray optional | `pystray>=0.19`, `Pillow>=10` |
| gui optional | `pywebview>=5` |

### 3.2 로컬 `.venv`에서 확인한 주요 설치 버전

| package | local installed | pyproject 선언 대비 |
|---|---:|---|
| `aiohttp` | `3.13.5` | `>=3.9,<4.0` 범위 안 |
| `certifi` | `2026.5.20` | `>=2024.8.30` 범위 안 |
| `sentry-sdk` | not installed | pyproject runtime 선언과 불일치. `bugsink.py`가 optional import fallback을 갖고 있어 현재 테스트는 통과한다. |
| `pytest` | `8.4.2` | `>=8,<9` 범위 안 |
| `pytest-asyncio` | `0.26.0` | `>=0.23,<1.0` 범위 안 |
| `pytest-cov` | `5.0.0` | `>=5,<7` 범위 안 |
| `ruff` | `0.15.15` | `>=0.4` 범위 안 |
| `mypy` | `1.20.2` | `>=1.10` 범위 안 |
| `setuptools` | `82.0.1` | `>=69` 범위 안 |
| `wheel` | not installed | build-system 선언과 현재 local venv가 불일치. CI install 시 별도 환경에서 해결될 수 있다. |

### 3.3 로컬 editable metadata 드리프트

현재 `.venv`/source egg-info에는 예전 패키지명이 남아 있다.

```text
nexa-agent: 0.47.0, Location: provider-agent/src, Requires: aiohttp, certifi
discord-ai-provider-agent: 0.1.0, Location: provider-agent/src, Requires: aiohttp
discord-ai-network-bot: 0.9.0, Editable project location: provider-agent, Requires: aiohttp, certifi
```

판정:

- 현재 소스의 진짜 SSOT는 `pyproject.toml` + `provider_agent/constants.py`의 `0.59.1`이다.
- 로컬 `.venv`는 오래된 editable install/egg-info 흔적을 포함한다.
- T003에서는 수정하지 않고 baseline drift로 기록한다. 다음에 provider-agent 패키징/릴리스 작업을 할 때는 깨끗한 venv에서 `pip install -e "provider-agent[dev]"`를 재확인해야 한다.

## 4. CI와 로컬 버전 일치/차이 요약

| 영역 | CI | 로컬 현재 | 결과 |
|---|---|---|---|
| central Java | Temurin 21 | shell 기본 Corretto 25.0.3 | 불일치. JDK 21 명시 필요. |
| central Java 명시 실행 | Temurin 21 | Corretto 21.0.11 | major version 일치. vendor만 다름. |
| Gradle | wrapper 8.12 | wrapper 8.12 | 일치. |
| Kotlin plugin | 2.1.0 | 2.1.0 | 일치. |
| Spring Boot | 3.4.1 | 3.4.1 | 일치. |
| JDA | 5.2.1 | 5.2.1 | 일치. |
| provider Python | 3.12 | 3.14.5 | 불일치. 로컬 테스트는 통과하지만 CI와 완전 동일하지 않다. |
| provider Python declared range | `>=3.11` | 3.14.5 satisfies | 범위상 가능하지만 AGENTS 정책은 3.12. |
| provider package version | pyproject `0.59.1` | installed metadata has stale `0.47.0`/old names | 불일치. source constant는 `0.59.1`. |
| provider `sentry-sdk` | CI fresh install should install `>=2.45,<3.0` | not installed | 불일치. 현재 코드가 optional import fallback으로 견딘다. |

## 5. T003 완료 조건 판정

| 조건 | 판정 | 근거 |
|---|---|---|
| Kotlin 버전 기록 | 충족 | 프로젝트 Kotlin plugin `2.1.0`, Gradle runtime Kotlin `2.0.21` 구분 기록 |
| Spring 버전 기록 | 충족 | Spring Boot plugin/starter resolved `3.4.1` 기록 |
| JDA 버전 기록 | 충족 | 선언/`dependencyInsight` 모두 `5.2.1` 기록 |
| Python 요구 버전 기록 | 충족 | pyproject `>=3.11`, CI `3.12`, local `3.14.5`, AGENTS 정책 `3.12` 차이 기록 |
| 핵심 플러그인 기록 | 충족 | Gradle wrapper, dependency-management, ktlint, Kover, Foojay 기록 |
| CI와 로컬 차이 명시 | 충족 | Java 25 vs 21, Python 3.14 vs 3.12, editable metadata drift, missing sentry-sdk/wheel 기록 |


## 6. 검증 결과

계획상 T003 검증 명령은 `./scripts/nexa-verify.sh docs`, `central`, `agent`이지만, 이 래퍼는 아직 T008 산출물이므로 현재 실행할 수 없다. 실제 실행 결과는 다음과 같다.

```text
$ ./scripts/nexa-verify.sh docs
zsh:1: no such file or directory: ./scripts/nexa-verify.sh
docs_rc=127

$ ./scripts/nexa-verify.sh central
zsh:1: no such file or directory: ./scripts/nexa-verify.sh
central_rc=127

$ ./scripts/nexa-verify.sh agent
zsh:1: no such file or directory: ./scripts/nexa-verify.sh
agent_rc=127
```

대체 검증으로 현재 존재하는 실제 명령을 실행했다.

```text
$ python3 docs/nexa/validate_nexa_500_task_graph.py docs/nexa/nexa_500_task_graph.yaml
VALID: 500 tasks, 20 programs, DAG acyclic

$ python3 scripts/check_links.py
검사한 문서: 118개
✅ 깨진 상대 링크 없음

$ git diff --check
<no output, exit 0>

$ JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home central-server/gradlew -p central-server build --no-daemon --console=plain
BUILD SUCCESSFUL in 5s
20 actionable tasks: 1 executed, 19 up-to-date

$ cd provider-agent && ../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70 && ../.venv/bin/ruff check src tests && ../.venv/bin/mypy src
377 passed, 1462 warnings in 4.02s
Required test coverage of 70% reached. Total coverage: 73.31%
All checks passed!
Success: no issues found in 35 source files
```

## 7. 다음 작업 표시

- 다음 그래프 작업: `NEXA-P00-T004 — 루트 AGENTS.md 최소 헌법 작성`
- T003는 human gate가 아니며, 산출물과 acceptance가 충족되어 `VERIFIED`로 올린다.
