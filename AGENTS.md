# AGENTS.md — discord-assistant 저장소 운영 헌법

이 파일은 이 저장소의 최상위 에이전트 규칙이다. 세부 설계·히스토리는 각 SSOT 문서로 넘기고,
여기에는 **모노레포 구조, 공통 금지사항, 검증 명령, SSOT 규칙, 인간 승인 게이트**만 둔다.
`CLAUDE.md`는 이 파일을 가리키는 포인터여야 하며, 규칙 본문을 복제하지 않는다.

전역 규칙은 `~/.codex/AGENTS.md`가 SSOT다. 전역 규칙과 저장소 규칙이 충돌하면 더 보수적이고
안전한 쪽을 따른다.

## 1. 제품과 구조

제품: 커뮤니티 멤버가 자기 PC의 로컬 LLM(Ollama)을 Provider Pool에 기여하고, Discord에서
`/ask`로 공정하게 나눠 쓰는 시스템이다. 판매보다 **기여·동의·가용성·공정성**이 핵심이다.
레거시 단일 Python 봇은 2026-05-30 제거되었다([docs/BOT_MIGRATION.md](docs/BOT_MIGRATION.md)).

| 경로 | 책임 | 세부 문서 |
|---|---|---|
| [central-server/](central-server/) | Kotlin/Spring Boot 중앙 서버 + Discord 봇(JDA), 라우팅·정책·관측성·대시보드 | [central-server/README.md](central-server/README.md) |
| [provider-agent/](provider-agent/) | Python/aiohttp Provider Agent, 로컬 Ollama 연결, 데스크톱 webui 백엔드 | [provider-agent/README.md](provider-agent/README.md) |
| [prototypes/desktop/](prototypes/desktop/) | 데스크톱 앱 UI/UX 단일 SSOT | `make sync-desktop`, `make desktop-check` |
| [admin-console/](admin-console/) | 관리자 콘솔 React/Vite SPA | package scripts |
| [site/](site/) | 공개 사이트 Astro static | package scripts |
| [games/](games/) | Discord Activity 게임 서브프로젝트 | [games/README.md](games/README.md) |
| [ai-context/](ai-context/) | 에이전트용 제품·도메인·정책·계약 JSON SSOT | [ai-context/index.json](ai-context/index.json) |
| [docs/](docs/) | ADR·운영·로드맵·감사·NEXA 계획 | [README.md](README.md) |

NEXA 500단계 작업은 [docs/nexa/nexa_500_task_graph.yaml](docs/nexa/nexa_500_task_graph.yaml)을
따른다. 기준선은 [docs/nexa/baseline/repository-state.md](docs/nexa/baseline/repository-state.md),
[docs/nexa/baseline/build-commands.md](docs/nexa/baseline/build-commands.md),
[docs/nexa/baseline/dependency-versions.md](docs/nexa/baseline/dependency-versions.md)에 기록한다.

## 2. 공통 작업 원칙

- 추측하지 말고 현재 worktree·명령 출력·테스트·로그를 먼저 확인한다.
- 직접 `main`에서 작업하지 않는다. 항상 작업 브랜치를 만들고, `git status --short`로 소유 변경만 확인한다.
- 사용자의 작업물·ignored 로컬 파일·비밀 파일을 삭제/reset/정리하지 않는다.
- 토큰·API 키·원문 사용자 데이터를 문서나 로그에 복사하지 않는다.
- 생성물 SSOT를 우회 편집하지 않는다. 생성물은 해당 generator/check 명령으로 갱신한다.
- 테스트 삭제, assertion 약화, 실패 숨기기, “통과한 척”을 금지한다.
- 코드 변경 시 KISS/YAGNI를 우선하고, 불필요한 추상화·AI slop·의미 없는 주석을 만들지 않는다.

## 3. SSOT 규칙

| 영역 | SSOT | 규칙/검증 |
|---|---|---|
| 제품·도메인·정책·계약 | [ai-context/index.json](ai-context/index.json) + 5개 JSON | 사람용 HTML은 생성물. `make ssot-viewer-check` |
| wire protocol | [protocol/wire-contract.json](protocol/wire-contract.json) | 직접 생성본 편집 금지. `make wire-gen`, `make contract` |
| 데스크톱 UI | [prototypes/desktop/](prototypes/desktop/) | `webui_assets` 직접 편집 금지. `make sync-desktop`, `make desktop-check` |
| i18n 문구 | [i18n/messages.json](i18n/messages.json) | ko/en/ja 필수. `make i18n-gen`, `make i18n-check` |
| 패키지/릴리스 자산명 | [packaging/assets.json](packaging/assets.json) | `make packaging-check` |
| DB 스키마 | `central-server/src/main/resources/db/migration/` | Flyway가 소유. 임의 ddl-auto 금지 |
| NEXA task graph | [docs/nexa/nexa_500_task_graph.yaml](docs/nexa/nexa_500_task_graph.yaml) | `python3 docs/nexa/validate_nexa_500_task_graph.py docs/nexa/nexa_500_task_graph.yaml` |

## 4. 검증 명령

소유 영역에 맞춰 실제로 실행하고 결과를 보고한다. 현재 기준선과 로컬/CI 차이는
[docs/nexa/baseline/build-commands.md](docs/nexa/baseline/build-commands.md)와
[docs/nexa/baseline/dependency-versions.md](docs/nexa/baseline/dependency-versions.md)를 먼저 확인한다.

```bash
# central-server: JDK 21 필수
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server build --no-daemon --console=plain
central-server/gradlew -p central-server test -PdockerTests --no-daemon --console=plain  # Docker 필요 BDD/Testcontainers

# provider-agent: CI 기준 Python 3.12, 로컬 venv는 기준선 문서 확인
cd provider-agent
../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70
../.venv/bin/ruff check src tests
../.venv/bin/mypy src

# 계약/생성물/문서
make contract
make desktop-check
make ssot-viewer-check
make i18n-check
make packaging-check
python3 scripts/check_links.py
```

변경 범위별 최소 게이트:

- `central-server/**` 변경 → central build 통과. Docker 의존 흐름이면 `-PdockerTests`도 실행.
- `provider-agent/**` 변경 → pytest coverage, ruff, mypy 통과.
- `prototypes/desktop/**` 또는 webui 계약 변경 → `cd prototypes/desktop && npx playwright test`,
  `make desktop-check`, 필요 시 `make sync-desktop` 후 실제 webui 로드 확인.
- wire/i18n/packaging SSOT 변경 → 해당 `*-gen` 후 `*-check` 또는 `make contract` 통과.
- 문서만 변경 → `python3 scripts/check_links.py`와 관련 generator/check 통과.

## 5. 인간 승인 게이트

다음은 자동으로 넘기지 말고 사용자 확인을 받는다.

- 프로덕션/배포 브랜치(`main`) 머지·직접 push·릴리스 태그·운영 배포.
- Discord `LIVE` 발화, 실제 운영 토큰 사용, `CENTRAL_DEV_ENABLED=false` 운영 환경 변경.
- DB 마이그레이션 삭제·수정, 사용자 데이터 삭제, 비밀/인증서/서명 키 변경.
- Provider Agent 설치/업데이트/서명/패키지 자산명 변경.
- NEXA task graph의 `human_gate: true` 작업과 각 프로그램의 T025 게이트.

## 6. 배포·릴리스·운영

- central-server 배포/릴리스는 [central-server/docs/OPERATIONS.md](central-server/docs/OPERATIONS.md),
  [central-server/docs/RUNBOOK.md](central-server/docs/RUNBOOK.md),
  [central-server/docs/GO_LIVE.md](central-server/docs/GO_LIVE.md)를 따른다.
- `central-server/**` push는 GHCR 이미지·self-hosted deploy 체인에 영향을 줄 수 있으므로 PR/CI 상태를 확인한다.
- 정식 central 릴리스는 `central-v*` 태그와 [central-server/CHANGELOG.md](central-server/CHANGELOG.md)를 사용한다.
- Provider Agent 릴리스·서명·패키지 매니저는 [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md),
  [docs/PACKAGE_MANAGERS.md](docs/PACKAGE_MANAGERS.md), [packaging/assets.json](packaging/assets.json)을 따른다.

## 7. 보안

- `.env*`, Discord 토큰, provider token, API key, 인증서, 사용자 원문 로그를 커밋하지 않는다.
- 운영에서 `CENTRAL_DEV_ENABLED`는 반드시 `false`다.
- 에이전트 토큰/설정 파일은 0600 권한을 유지한다.
- `/dev/*`와 테스트 편의 기능은 운영에 노출하지 않는다.
