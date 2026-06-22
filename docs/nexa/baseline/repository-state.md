# NEXA-P00-T001 저장소 상태 기준선

- 작업 ID: `NEXA-P00-T001`
- 작성 시각: 2026-06-20 12:12:46 KST (+0900)
- 기준 커밋: `a0e2118be225c2e651d29216c69a37d248b80239`
- 기준 short commit: `a0e2118b`
- 기준 worktree: `/Users/osuma/coding_stuffs/discord-assitant`
- 상태: `VERIFIED` — 2026-06-20 12:14:41 KST에 사용자가 “이어 진행”을 지시해 REVIEW 산출물을 다음 작업의 선행조건으로 인정했다.

## 1. 재현 명령

다른 개발자가 같은 커밋에서 아래 명령을 실행해 이 기준선을 재확인할 수 있다.

```bash
pwd
git rev-parse HEAD
git rev-parse --short HEAD
git branch --show-current
git status --short
git submodule status --recursive
git branch -vv --no-color | sed -n '1,20p'
git ls-files | wc -l
python3 docs/nexa/validate_nexa_500_task_graph.py docs/nexa/nexa_500_task_graph.yaml
```

큰 추적 파일은 다음 명령으로 재계산한다.

```bash
python3 - <<'PY'
from pathlib import Path
import subprocess
files = subprocess.check_output(['git', 'ls-files'], text=True).splitlines()
rows = []
for f in files:
    p = Path(f)
    if p.is_file():
        rows.append((p.stat().st_size, f))
for size, f in sorted(rows, reverse=True)[:20]:
    print(f'{size}\t{f}')
PY
```

무시된 로컬 생성물·캐시는 다음 명령으로 확인한다. 비밀 값은 출력하지 않고 파일명만 본다.

```bash
git status --ignored --short | grep -E '(^!!|generated|webui_assets|build|node_modules|\.gradle|coverage|dist)' | sed -n '1,120p'
```

## 2. 브랜치와 커밋

| 항목 | 값 |
|---|---|
| T001 시작 전 checkout | `main` |
| T001 작업 브랜치 | `feat/nexa-p00-t001-baseline` |
| HEAD | `a0e2118be225c2e651d29216c69a37d248b80239` |
| origin/main | `a0e2118be225c2e651d29216c69a37d248b80239` |
| 브랜치 생성 이유 | 루트 `AGENTS.md`가 `main` 직접 작업을 금지하므로, 같은 HEAD에서 별도 브랜치를 만들었다. |

확인 출력:

```text
branch=feat/nexa-p00-t001-baseline
head=a0e2118be225c2e651d29216c69a37d248b80239
short_head=a0e2118b
```

브랜치 관련 관찰:

```text
* feat/nexa-p00-t001-baseline a0e2118b Merge remote-tracking branch 'origin/main'
  feat/remote-agent-byollm    e555474e [origin/main: behind 11] feat(image): 이미지 안전심사·번역을 central GLM 으로 이전(ADR 0006 단계2)
  main                        a0e2118b [origin/main] Merge remote-tracking branch 'origin/main'
```

`feat/remote-agent-byollm`는 로컬 기준 `main`보다 뒤처져 있어 이번 기준선 작업에는 사용하지 않았다.

## 3. 작업 트리와 서브모듈

T001 문서 작성 전 기준 상태:

```text
$ git status --short
<no output>

$ git submodule status --recursive
<no output>
```

결론:

- 추적 파일 기준 작업 트리는 깨끗했다.
- 등록된 Git submodule은 없다.
- 이 문서와 `docs/nexa/nexa_500_task_graph.yaml`의 상태 변경은 T001 수행으로 생기는 변경이다.

## 4. 실제 루트 구조

추적 파일 수:

```text
1147
```

주요 루트 디렉터리:

```text
admin-console/
ai-context/
assets/
central-server/
data/              # ignored local runtime data 포함
deploy/
docker/
docs/
games/
i18n/
logs/
packaging/
personal_space/    # ignored local/private workspace
protocol/
prototypes/
provider-agent/
rag/
scripts/
site/
specs/
src/               # ignored legacy/local artifact로 보임
tests/             # ignored legacy/local artifact로 보임
```

주요 루트 파일:

```text
AGENTS.md
CLAUDE.md
LICENSE
Makefile
README.md
SECURITY.md
docker-compose.qdrant.yml
.gitignore
```

주의: `.env`, `.env.backup-*`, IDE 설정, venv, 캐시, 개인 작업 폴더가 로컬에 있지만 Git에서는 ignored 상태다. 값은 확인·복사하지 않았다.

## 5. 핵심 진입점과 현재 문서 SSOT

실제 확인한 기준 파일:

| 영역 | 파일/명령 | 확인 내용 |
|---|---|---|
| 저장소 규칙 | `AGENTS.md` | central-server, provider-agent, desktop prototype, wire/i18n/packaging 계약과 검증 규칙을 소유한다. |
| 프로젝트 소개 | `README.md` | Provider Pool 구조, `central-server/`, `provider-agent/`, `site/`, `admin-console/`, `specs/product-v2/`, `docs/`를 설명한다. |
| 루트 빌드 진입점 | `Makefile` | `central-build`, `agent-test`, `agent-lint`, `contract`, `desktop-check`, `ssot-viewer-check`, `i18n-check`, `packaging-check`, `e2e` 등이 있다. |
| central-server | `central-server/build.gradle.kts` | Kotlin 2.1.0, Spring Boot 3.4.1, JDK 21 toolchain, ktlint, Kover, ArchUnit, Cucumber/Testcontainers 설정이 있다. |
| provider-agent | `provider-agent/pyproject.toml` | 패키지명 `nexa-agent`, 버전 `0.59.1`, `requires-python >=3.11`, ruff/mypy/pytest 설정이 있다. |
| AI context | `ai-context/index.json` | 에이전트용 SSOT는 `ai-context/*.json` 6개이며, 사람용 HTML은 생성물이다. |
| NEXA 계획 | `docs/nexa/nexa_500_task_graph.yaml` | 500개 작업, 20개 프로그램, DAG 검증 통과. |

현재 CI workflow 파일:

```text
.github/workflows/agent-autorelease.yml
.github/workflows/agent-build.yml
.github/workflows/ai-rag-rebuild.yml
.github/workflows/central-deploy.yml
.github/workflows/central-release.yml
.github/workflows/central-server-ci.yml
.github/workflows/central-server-deploy.yml
.github/workflows/central-server-image.yml
.github/workflows/ghcr-cleanup.yml
.github/workflows/provider-agent-ci.yml
```

현재 `ai-context/` 파일:

```text
ai-context/contracts.json
ai-context/domain.json
ai-context/index.json
ai-context/navigation.json
ai-context/policies.json
ai-context/product.json
```

## 6. 대용량 추적 파일 상위 20개

| bytes | path |
|---:|---|
| 4620288 | `rag/meta.db` |
| 1776297 | `rag/bm25/corpus.jsonl` |
| 1552600 | `games/strike-protocol/public/models/character-enemy.glb` |
| 1545557 | `central-server/docs/dashboard-redesign/08-presets.png` |
| 1491506 | `central-server/docs/dashboard-redesign/09-advanced-response.png` |
| 1421492 | `central-server/docs/dashboard-redesign/04-model-policy.png` |
| 1415344 | `central-server/docs/dashboard-redesign/07-providers.png` |
| 1401712 | `games/strike-protocol/public/models/character-hazmat.glb` |
| 1382100 | `assets/brand/nia-mascot.png` |
| 1359769 | `central-server/docs/dashboard-redesign/03-channel-ai.png` |
| 1345894 | `central-server/docs/dashboard-redesign/02-guild-detail.png` |
| 1333700 | `games/strike-protocol/public/models/character-soldier.glb` |
| 1320608 | `provider-agent/packaging/icons/app.icns` |
| 1306893 | `central-server/docs/dashboard-redesign/05-knowledge-rag.png` |
| 1300947 | `central-server/docs/dashboard-redesign/01-overview.png` |
| 1289034 | `central-server/docs/dashboard-redesign/10-policy-settings.png` |
| 1271263 | `central-server/docs/dashboard-redesign/06-quality-reports.png` |
| 1083943 | `site/public/img/nexa-logo.png` |
| 1083943 | `prototypes/desktop/img/nexa-logo.png` |
| 1083943 | `central-server/src/main/resources/static/img/nexa-logo.png` |

## 7. ignored 로컬 생성물·캐시 요약

확인된 ignored 항목의 범주:

- 비밀/환경 파일명: `.env`, `.env.backup-20260531233322` — 값은 확인하지 않음.
- Python/테스트 캐시: `.coverage`, `.mypy_cache/`, `.pytest_cache/`, `.ruff_cache/`, `__pycache__/`.
- 로컬 런타임/개인 영역: `.omc/`, `.omx/`, `.codegraph/`, `data/`, `personal_space/`, `logs/`.
- Node/프론트 산출물: `admin-console/dist/`, `admin-console/node_modules/`, `prototypes/desktop/node_modules/`, `site/dist/`, `site/node_modules/`.
- Kotlin/Gradle 산출물: `central-server/.gradle/`, `central-server/.kotlin/`, `central-server/build/`.
- 데스크톱 생성물: `provider-agent/src/provider_agent/webui_assets/`.
- 로컬 legacy처럼 보이는 ignored 루트: `src/`, `tests/`.

이 항목들은 T001 기준선의 “추적 작업 트리 깨끗함”과 별개인 로컬 ignored 상태다.

## 8. 사용자 제공 인벤토리와 다른 점 / 수정 제안

1. 루트 `AGENTS.md`는 “현재 작업 브랜치는 `feat/remote-agent-byollm`”라고 쓰지만, 실제 시작 checkout은 `main`이었다. 그 브랜치는 로컬에서 `origin/main` 대비 11커밋 뒤처져 있어 이번 T001에서는 새 브랜치 `feat/nexa-p00-t001-baseline`을 사용했다.
2. NEXA task graph의 T001 검증 명령은 `./scripts/nexa-verify.sh docs`이지만, 현재 `scripts/nexa-verify.sh`는 존재하지 않는다. 이는 T008 산출물로 계획되어 있어 초기 작업들의 검증 명령과 실제 순서가 어긋난다.
3. `provider-agent/pyproject.toml`의 `requires-python`과 도구 설정은 Python 3.11 기준(`>=3.11`, `py311`)이고, 루트 `AGENTS.md`는 Python 3.12를 요구한다. P00-T003에서 CI/로컬 실제 버전 차이를 따로 고정해야 한다.
4. NEXA validator는 현재 `docs/nexa/validate_nexa_500_task_graph.py`에 있고, T010 권장 경로는 `scripts/validate-nexa-task-graph.py`다. T010에서 스크립트 위치·이름을 정식화해야 한다.
5. README의 `specs/product-v2/` 경로는 실제 존재한다. 다만 `ai-context/`가 현재 운용 SSOT이고, `docs/ssot-viewer/...html`은 생성물이라는 점은 `AGENTS.md`와 `ai-context/index.json` 기준으로 함께 봐야 한다.

## 9. 검증 결과

실행한 명령과 결과:

```text
$ python3 docs/nexa/validate_nexa_500_task_graph.py docs/nexa/nexa_500_task_graph.yaml
VALID: 500 tasks, 20 programs, DAG acyclic
```

계획상 T001 검증 명령은 아직 실행 불가능하다.

```text
$ ./scripts/nexa-verify.sh docs
zsh:1: no such file or directory: ./scripts/nexa-verify.sh
exit code: 127
```

따라서 T001 산출물 자체는 작성했지만, 계획에 적힌 통합 검증 래퍼는 T008 전까지 사용할 수 없다. 2026-06-20 12:14:41 KST의 사용자 재개 지시를 선행 작업 확인으로 받아 `VERIFIED`로 전환한다. 단, `scripts/nexa-verify.sh` 부재는 T008에서 해소해야 할 계획상 불일치로 남긴다.

## 10. T001 완료 조건 판정

| 조건 | 현재 판정 | 근거 |
|---|---|---|
| 현재 커밋 기록 | 충족 | HEAD full SHA와 short SHA 기록 |
| 현재 브랜치 기록 | 충족 | 시작 checkout과 작업 브랜치 모두 기록 |
| 미추적 파일 기록 | 충족 | T001 전 `git status --short` 무출력 기록 |
| 서브모듈 기록 | 충족 | `git submodule status --recursive` 무출력 기록 |
| 대용량 파일 기록 | 충족 | 추적 파일 상위 20개 bytes/path 기록 |
| 같은 커밋 재현 가능성 | 대부분 충족 | 재현 명령과 핵심 출력 기록 |
| 계획상 검증 명령 통과 | 예외 인정 | `scripts/nexa-verify.sh`가 아직 없음(T008 범위). 대체 검증으로 task graph DAG와 링크 검사를 통과했고, 사용자 재개 지시로 REVIEW 산출물을 승인받았다. |

## 11. 다음 작업 표시만 함

- 다음 그래프 작업: `NEXA-P00-T002 — 루트 빌드 진입점과 실제 명령 조사`
- 2026-06-20 12:14:41 KST 사용자 재개 지시 이후 T001을 `VERIFIED`로 전환했으므로 다음 작업의 선행조건은 충족된 것으로 본다.
