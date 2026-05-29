# Self-hosted Runner + 자동배포 (Docker Compose, macOS arm64)

yeon과 동일한 모델: **self-hosted runner 가 곧 배포 서버**. SSH 없이 runner 위에서
`docker compose pull && up -d --wait` 로 배포한다. 이미지는 GHCR에 push/pull.

```
main push ──▶ deploy.yml
  ├─ build-and-push (self-hosted, macOS/ARM64): linux/arm64 빌드 → GHCR push (sha-XXXX, latest)
  └─ deploy        (self-hosted, macOS/ARM64): compose pull → up -d --wait → 이미지 검증
                                                   │
                          deploy 성공 ──▶ auto-release.yml: conventional-commit SemVer 태그+릴리스
```

## 구성 요소
| 파일 | 역할 |
|---|---|
| `.github/workflows/deploy.yml` | 빌드+푸시+배포 (CD 코어) |
| `.github/workflows/release.yml` | `v*.*.*` 태그 push 시 GH 릴리스 |
| `.github/workflows/auto-release.yml` | 배포 성공 후 SemVer 자동 태그/릴리스 |
| `.github/workflows/ci.yml` | lint/type/test (PR·push 게이트) |
| `compose.prod.yml` | 운영 compose (GHCR 이미지, 봇 1개) |
| `deploy/.env.prod.example` | 호스트 `.env` 템플릿 (시크릿) |
| `deploy/setup-runner.sh` | macOS runner 등록 스크립트 |

## 1. 사전 준비 (호스트 = 이 Mac)
- **Docker Desktop** 설치 후 실행 중일 것 (`docker compose version` 동작 확인)
- **gh CLI** 인증 (`gh auth status`)

## 2. 시크릿(`ENV_FILE`) 등록
`.env` **전체 내용을 한 개의 Actions 시크릿 `ENV_FILE`** 로 저장한다. 배포 워크플로의
"Render .env from ENV_FILE secret" 단계가 매 배포마다 이 값을 `DEPLOY_DIR/.env`(기본
`$HOME/discord-assistant/.env`, 권한 600)로 렌더링한다. 호스트를 직접 편집할 필요 없음.

```bash
# deploy/.env.prod.example 를 채운 뒤 통째로 ENV_FILE 시크릿에 등록
cp deploy/.env.prod.example /tmp/bot.env
$EDITOR /tmp/bot.env            # DISCORD_BOT_TOKEN(실제), SECRET_KEY 등 입력
gh secret set ENV_FILE --repo Hyeonjun0527/discord-assistant < /tmp/bot.env
rm -f /tmp/bot.env             # 로컬 평문 제거
```
> `SECRET_KEY` 생성: `python -c "import secrets; print(secrets.token_urlsafe(48))"`
> OpenAI/Anthropic 키는 봇의 `/settings` 패널에서 입력(암호화 저장) — env에 둘 필요 없음.
> 워크플로는 `DISCORD_BOT_TOKEN` 이 placeholder(`replace-with...`)면 배포를 거부한다.
> `DEPLOY_DIR` 위치를 바꾸려면 레포 Variable `DEPLOY_DIR` 설정 (Settings → Actions → Variables).
> ⚠️ `SECRET_KEY` 를 바꾸면 기존에 암호화 저장된 API 키를 복호화할 수 없게 되니 한 번 정하면 유지.

## 3. Self-hosted runner 등록
```bash
REPO=Hyeonjun0527/discord-assistant bash deploy/setup-runner.sh
```
이 스크립트는 registration token 발급(gh) → runner 다운로드 → `[self-hosted, macOS, ARM64]`
라벨로 등록 → launchd 서비스로 설치/시작한다. GitHub → **Settings → Actions → Runners**
에서 **Idle(초록)** 상태를 확인한다.

수동 등록을 원하면 GitHub UI의 *New self-hosted runner (macOS)* 안내를 따르되,
라벨에 반드시 `macOS` 와 `ARM64` 를 포함시킨다.

## 4. 첫 배포
```bash
# main 에 push 하면 자동 트리거 (paths 필터: src/scripts/Dockerfile/compose.prod.yml 등)
git push origin main
# 또는 수동:
gh workflow run "Build, Push, and Deploy"
gh run watch
```

## 5. 롤백
GHCR에 커밋별 `sha-XXXX` 태그가 쌓이므로 이전 태그로 되돌릴 수 있다.
```bash
cd "$HOME/discord-assistant"
BOT_IMAGE=ghcr.io/hyeonjun0527/discord-assistant:sha-<이전7자리> \
  docker compose -f compose.prod.yml up -d --wait bot
```

## 운영 노트
- 봇은 HTTP 포트를 열지 않는 워커. 헬스는 컨테이너 내부 `scripts/healthcheck.py`(DB 점검)로
  판정하며, `up -d --wait` 가 healthy 까지 대기한다. 클라우드 API 배포에서는
  `HEALTHCHECK_REQUIRE_OLLAMA=false`(기본) 라 Ollama 점검을 건너뛴다.
- GHCR 인증은 워크플로 자동 `GITHUB_TOKEN`(packages: write). 별도 PAT/SSH 불필요.
- Ollama 백엔드로 바꾸려면: `.env` 에 `OLLAMA_BASE_URL` 지정 + `HEALTHCHECK_REQUIRE_OLLAMA=true`,
  필요 시 `compose.prod.yml` 에 ollama 서비스 추가.
- runner 중지/제거: `cd "$HOME/actions-runner" && ./svc.sh stop && ./svc.sh uninstall`.
