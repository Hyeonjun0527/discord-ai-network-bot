# 기여 가이드 (Contributing)

이 프로젝트에 기여해주셔서 감사합니다! 아래 절차를 따르면 리뷰와 머지가 빠르게 진행됩니다.

## 개발 환경 설정

```bash
# 1. 저장소 포크 후 클론
git clone https://github.com/<your-username>/discord-assistant.git
cd discord-assistant

# 2. 가상환경 생성 및 활성화
python3 -m venv .venv
source .venv/bin/activate        # macOS / Linux
# .venv\Scripts\activate         # Windows

# 3. 개발 의존성 포함 설치
pip install -e ".[dev]"

# 4. (권장) pre-commit 훅 설치
pip install pre-commit
pre-commit install
```

## pre-commit 훅

`.pre-commit-config.yaml`에 ruff 린트·포맷과 mypy 타입 검사 훅이 정의되어 있습니다.
한 번 `pre-commit install`을 실행하면 커밋할 때마다 자동으로 검사가 돌고, 포맷 문제는 자동 수정됩니다.

전체 파일에 수동으로 실행하려면:

```bash
pre-commit run --all-files
```

## 로컬 검증

PR을 올리기 전에 아래 세 가지를 모두 통과시켜 주세요. CI(`.github/workflows/ci.yml`)에서 동일한 검사를 수행합니다.

```bash
# 린트 (CI에서 강제됨)
python -m ruff check src/

# 포맷 (변경한 파일에 적용해 일관성 유지; pre-commit이 자동 실행)
python -m ruff format src/

# 타입 검사 (CI에서 강제됨)
python -m mypy src/

# 테스트 + 커버리지 게이트 (CI에서 강제됨, fail-under=35)
python -m pytest tests/ -v --cov=discord_assistant --cov-report=term-missing
```

테스트는 외부 서비스(Discord 토큰, Ollama, API 키) 없이 실행됩니다.

## 커밋 메시지 — Conventional Commits

이 저장소는 **[Conventional Commits](https://www.conventionalcommits.org/)** 규칙을 사용하며,
`main`에 배포가 성공하면 커밋 메시지를 분석해 **SemVer 태그/릴리스를 자동 생성**합니다
(`.github/workflows/auto-release.yml`). 따라서 커밋 타입이 버전 증가에 직접 영향을 줍니다.

| 커밋 prefix | 버전 증가 | 예시 |
| --- | --- | --- |
| `feat!:` / 본문에 `BREAKING CHANGE` | **major** | `feat!: drop legacy /config CLI` |
| `feat:` | **minor** | `feat: add /search command` |
| `fix:` / `perf:` / `refactor:` 등 | **patch** | `fix: handle empty transcript` |
| `docs:` / `chore:` / `test:` / `ci:` | patch (릴리스 영향 없음 의도) | `docs: sync command table` |

형식:

```
<type>(<optional scope>): <설명>

<optional body>

<optional footer / BREAKING CHANGE: ...>
```

- 설명은 명령형 현재 시제로, 한국어 또는 영어 모두 가능합니다.
- 하나의 커밋은 하나의 논리적 변경만 담습니다.

## Pull Request 흐름

1. 기능 브랜치를 만듭니다: `git checkout -b feature/<name>` 또는 `fix/<name>`.
2. 변경 사항을 구현하고, 가능하면 테스트를 추가/수정합니다.
3. 위 **로컬 검증**(ruff / mypy / pytest)을 모두 통과시킵니다.
4. Conventional Commits 형식으로 커밋합니다.
5. PR을 엽니다. PR 템플릿(`.github/pull_request_template.md`)을 채워주세요.
   - 무엇을, 왜 바꿨는지 명확히 설명합니다.
   - 관련 이슈가 있으면 `Closes #123` 형태로 연결합니다.
6. PR은 가능한 한 작게 유지합니다 — 하나의 기능/수정 단위로. 큰 변경은 먼저 이슈로 설계를 논의해주세요.

## 코드 스타일

- 린트·포맷: `ruff` (`pyproject.toml`의 `[tool.ruff]` 설정 사용, line-length 100)
- 타입: `mypy` (Python 3.11 기준)
- 주석과 사용자 대상 메시지는 한국어를 유지합니다.

질문이나 제안은 이슈로 자유롭게 남겨주세요. 감사합니다!
