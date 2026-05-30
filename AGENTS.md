# AGENTS.md — 프로젝트 공용 규칙 (SSOT)

이 파일은 **discord-assistant 저장소의 단일 진실 원천(SSOT)** 이다. 빌드/검증/커밋/브랜치/
배포 규약을 여기서 정의한다. `CLAUDE.md` 는 이 파일을 가리키는 짧은 포인터일 뿐이며,
규칙 본문을 복제하지 않는다. 규칙 변경은 **반드시 이 파일에서만** 한다(드리프트 방지).

전역(사용자 단위) 공용 규칙은 `~/.codex/AGENTS.md` 가 SSOT다. 저장소 규칙과 전역 규칙이
충돌하면 안전한 쪽(더 보수적인 쪽)을 따른다.

## 프로젝트 개요

로컬 LLM(Ollama) 및 OpenAI/Anthropic 으로 디스코드 채널 대화를 요약하고 맥락 기반 Q&A 를
제공하는 봇이다. 구조:

- `src/discord_assistant/` — 봇 패키지(슬래시 명령, LLM 어댑터, 저장소, 설정 등)
- `dashboard/backend/` — FastAPI 백엔드, `dashboard/frontend/` — 프론트엔드
- `scripts/` — 헬스체크/E2E 등 운영 스크립트
- `docs/` — 아키텍처/ADR/품질 문서, `specs/product-v2/` — Provider Pool 명세
- `tests/` — 단위 테스트
- **`central-server/`** — 커뮤니티 Provider Pool 중앙 서버(Kotlin/Spring Boot, ADR 0004)
- **`provider-agent/`** — 유저 PC용 Provider Agent(Python, 경량 aiohttp)

### central-server (Kotlin) 빌드/검증/배포
JDK 21 필요(Gradle 8.12 wrapper 는 JDK 26 미지원 — `JAVA_HOME` 을 21 로).
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server build      # 컴파일+테스트
central-server/gradlew -p central-server bootJar     # app.jar
cd central-server && docker compose up -d --build    # Postgres+서버(8080)
```
- 스키마는 Flyway(`db/migration`) 가 소유(ddl-auto=none). WS 프로토콜은 `provider-agent` 와
  **camelCase 와이어로 동일 계약**(api.md §8) — 한쪽 변경 시 양쪽 동기화.
- CI: `central-server-ci`(build/test) · `central-server-image`(GHCR) · `central-server-deploy`(self-hosted).

### provider-agent (Python) 빌드/검증
```bash
cd provider-agent && PYTHONPATH=src ../.venv/bin/python -m pytest tests/ -q
../.venv/bin/ruff check src tests && ../.venv/bin/mypy src/provider_agent
```

### 로컬 E2E (실연동 검증)
```bash
.venv/bin/python scripts/e2e_local.py   # mock Ollama + bootRun + agent → /dev/ask 실왕복
```

## 빌드 / 설치

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"          # 봇 패키지 + 개발 도구(ruff/mypy/pytest)
pip install -r dashboard/backend/requirements.txt   # 대시보드 백엔드 작업 시
```

- Python: **3.11** (CI 기준, `pyproject.toml` `requires-python = ">=3.11"`).
- 실행: `discord-assistant` 또는 `python -m discord_assistant`.

## 검증 (커밋 전 / 머지 전 필수)

소유 영역에 맞춰 아래를 실제로 실행하고 통과를 확인한다. 가상환경 파이썬을 사용한다.

```bash
.venv/bin/python -m ruff check src/        # 또는 변경한 영역(dashboard/backend 등)
.venv/bin/python -m mypy src/
.venv/bin/python -m pytest tests/ --no-cov -q   # 전체 권장
```

CI(`.github/workflows/ci.yml`)는 동일하게 ruff → mypy → pytest 를 돌리며, 커버리지
하한선은 **35%**(`--cov-fail-under=35`, `pyproject` 와 일치)이다. 보강되면 함께 올린다.
대시보드 백엔드/프론트엔드는 별도 잡에서 ruff/pytest, lint/tsc/build 로 검증된다.

### SSOT 검증 가드 (자동)

문서·설정이 코드와 어긋나면 빌드에서 실패한다. 명령을 추가하거나 환경 변수를 바꾸면
아래 가드를 함께 통과시켜야 한다.

- **env SSOT** (`.github/workflows/ssot-check.yml`, #97):
  `.env.example` 키 ↔ `src/discord_assistant` 가 읽는 env 키를 대조.
  - `.env.example` 에 죽은(코드 미사용) 키가 있으면 실패.
  - 코드가 읽는 필수 키가 `.env.example` 에도, 워크플로의 `OPTIONAL_KEYS` 허용 목록에도
    없으면 실패. 새 선택 키를 추가하면 `OPTIONAL_KEYS` 도 갱신할 것.
- **docs drift** (`.github/workflows/docs-drift.yml`, #100):
  `README.md`/`README_EN.md` 의 명령 표 ↔ `bot.py` 의 `@bot.tree.command(name=...)` /
  `@config_group.command(name=...)` 집합을 대조. 봇 명령이 문서에 없으면 실패.

## 커밋 / 브랜치 / PR

- **브랜치**: 기본/배포 브랜치는 `main`. 직접 `main` 에 작업하지 말고 항상 브랜치를 판다.
- **커밋 메시지**: Conventional Commits 사용(`feat:`, `fix:`, `perf:`, `refactor:`, `docs:`,
  `chore:` 등). 자동 릴리스가 이 접두사로 SemVer 를 올린다.
  - `feat:` / `feat!:` / `BREAKING CHANGE` → minor / major
  - `fix:` / `perf:` / `refactor:` 등 → patch (기본 patch)
- **PR**: 가능하면 CI 가 초록인 상태로 올린다. 변경 영역에 맞는 검증 로그를 남긴다.

## 배포 / 릴리스

- `main` 에 머지되면 `Build, Push, and Deploy`(`deploy.yml`)가 이미지 빌드·푸시·배포를 수행.
- 성공한 `main` 배포 후 `auto-release.yml` 이 커밋 기준으로 SemVer 태그(`vX.Y.Z`)와 GitHub
  Release 를 생성. 수동 릴리스는 `release.yml`(태그 푸시 또는 `workflow_dispatch`).
- 오래된 GHCR 이미지는 `ghcr-cleanup.yml` 이 정리.
- 롤백 절차는 `docs/ROLLBACK.md` 참고.

## 비밀 / 보안

- 토큰·API 키·`SECRET_KEY` 는 절대 커밋하지 않는다. `.env.example` 만 갱신하고 `.env` 는 로컬.
- 운영(`ENVIRONMENT`/`APP_ENV` 가 production)에서는 기본 `SECRET_KEY` 사용이 거부된다.

## 환경 변수 문서화 규칙

- 봇이 사용하는 **필수** 환경 변수는 `.env.example` 와 `README.md` 환경변수 표에 둘 다 반영.
- 코드 기본값이 있는 **선택** 키는 `.env.example` 생략 가능하나, env SSOT 가드의
  `OPTIONAL_KEYS` 에 등록해 검증을 정직하게 유지한다.
