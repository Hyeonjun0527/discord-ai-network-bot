# Self-hosted Runner + 자동배포 (Docker Compose, macOS arm64)

yeon과 동일한 모델: **self-hosted runner 가 곧 배포 서버**. SSH 없이 runner 위에서
`docker compose pull && up -d --wait` 로 배포한다. 이미지는 GHCR에 push/pull.

```
main push ──▶ deploy.yml
  ├─ build-and-push  (self-hosted, macOS/ARM64): linux/arm64 빌드 → GHCR push (sha-XXXX, latest)
  ├─ prepare-targets (self-hosted, macOS/ARM64): 배포 타겟 매트릭스 산출(vars.DEPLOY_TARGETS 또는 단일 기본)
  └─ deploy          (matrix: 타겟별 라벨/deploy_dir): compose pull → up -d --wait → 이미지 검증
                                                   │
                          deploy 성공 ──▶ auto-release.yml: conventional-commit SemVer 태그+릴리스
```
> 기본(단일 호스트)은 매트릭스가 타겟 1개(`[self-hosted, macOS, ARM64]`)라 기존과 100% 동일하게 동작.
> 여러 호스트로 확장하려면 아래 [6. 멀티 호스트 배포](#6-멀티-호스트-배포-73) 참고.

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

## 6. 멀티 호스트 배포 (#73)

기본값은 **단일 호스트**(`[self-hosted, macOS, ARM64]` 러너 1개)다. 여러 대의 호스트에
동시에 같은 봇 이미지를 배포하려면 repo Variable **`DEPLOY_TARGETS`** 를 JSON 배열로 등록한다.
배포 워크플로의 `prepare-targets` job 이 이 값을 매트릭스로 펼쳐, 타겟마다 별도의
`deploy` job(자체 러너 라벨 + `deploy_dir`)을 **병렬**로 실행한다.

각 타겟 객체:
| 키 | 의미 |
|---|---|
| `labels` | 그 호스트를 가리키는 러너 라벨 배열(`runs-on` 으로 주입). 각 라벨을 가진 러너가 등록돼 있어야 한다. |
| `deploy_dir` | 그 호스트에서 배포할 디렉터리. 빈 문자열이면 repo Variable `DEPLOY_DIR` → 그것도 없으면 `$HOME/discord-assistant` 로 폴백(기존 동작과 동일). |

```bash
# 예: macOS arm64 호스트 + Linux x64 호스트 두 대로 배포
gh variable set DEPLOY_TARGETS --repo Hyeonjun0527/discord-assistant --body '[
  {"labels":["self-hosted","macOS","ARM64"],"deploy_dir":""},
  {"labels":["self-hosted","Linux","X64"],"deploy_dir":"/srv/discord-assistant"}
]'
```

동작/주의:
- **백워드 호환**: `DEPLOY_TARGETS` 를 설정하지 않으면 단일 타겟(`labels=[self-hosted, macOS, ARM64]`,
  `deploy_dir=""`)으로 폴백 → 기존과 완전히 동일(러너 선택·DEPLOY_DIR 폴백 모두 그대로).
- 호스트마다 라벨이 **유일**하게 매칭되도록 라벨을 지정한다(예: 호스트별 커스텀 라벨 `prod-a`/`prod-b`
  를 추가). 같은 라벨 집합을 여러 러너가 공유하면 어느 러너에 배정될지 보장되지 않는다.
- 두 번째 호스트의 러너 등록은 [3. Self-hosted runner 등록](#3-self-hosted-runner-등록)과 동일하되,
  그 호스트의 OS/아키텍처에 맞는 라벨로 등록한다. Docker Desktop/Engine 이 동작 중이어야 한다.
- `ENV_FILE` 시크릿은 **모든** 타겟에 동일하게 렌더링된다. 호스트별로 다른 `.env` 가 필요하면
  현재 구조로는 지원하지 않으며, 호스트별 시크릿 분리는 추가 작업이 필요하다.
- `fail-fast: false` 라 한 호스트 배포가 실패해도 나머지 호스트 배포는 계속 진행된다.
  실패한 호스트는 자체 롤백/Discord 알림 스텝이 동작한다.

## 운영 노트
- 봇은 HTTP 포트를 열지 않는 워커. 헬스는 컨테이너 내부 `scripts/healthcheck.py`(DB 점검)로
  판정하며, `up -d --wait` 가 healthy 까지 대기한다. 클라우드 API 배포에서는
  `HEALTHCHECK_REQUIRE_OLLAMA=false`(기본) 라 Ollama 점검을 건너뛴다.
- GHCR 인증은 워크플로 자동 `GITHUB_TOKEN`(packages: write). 별도 PAT/SSH 불필요.
### 로컬 Ollama 백엔드 (현재 운영 구성 — 사용자 설정 0, 비용 0)
봇 호스트(이 Mac)에서 **네이티브 Ollama**(Metal GPU 가속)를 돌리고, 컨테이너가 그것을
바라본다. 디스코드 사용자는 아무 설치도 필요 없이 호스트의 로컬 모델을 공유한다.
원격 사용자 PC 에는 아무것도 설치할 수 없다(봇은 사용자 머신에 접근 불가) — 모델 설치
버튼은 *봇 호스트의* Ollama 에 모델을 받는다.

1. **네이티브 Ollama 설치/구동**: `brew install ollama` → `brew services start ollama`
   (launchd 로 부팅 시 자동 구동).
2. **컨테이너에서 닿도록 0.0.0.0 바인딩** — Ollama 는 기본 `127.0.0.1` 이라 컨테이너에서
   안 보인다. launchd plist(`~/Library/LaunchAgents/homebrew.mxcl.ollama.plist`)의
   `EnvironmentVariables` 에 `OLLAMA_HOST=0.0.0.0:11434` 추가 후
   `launchctl unload/load` 로 재적용한다. (PlistBuddy 권장. `brew services restart`
   는 plist 를 재생성하므로 이후 재적용 필요할 수 있음.)
   ⚠️ 0.0.0.0 은 LAN 노출 — 신뢰 못할 네트워크면 방화벽으로 11434 를 제한할 것.
3. **compose 연결**: `compose.prod.yml` 의 bot `environment` 에
   `OLLAMA_BASE_URL: "http://host.docker.internal:11434"` 를 **리터럴**로 둔다.
   (`${..}` 보간은 프로젝트 `.env` 의 `localhost` 에 덮이므로 금지. `environment` 는
   `env_file` 보다 우선이라 리터럴이 확실히 적용된다.) `extra_hosts: host-gateway` 도 둔다.
4. **기본 모델**: `ENV_FILE` 시크릿의 `OLLAMA_MODEL`(예: `llama3.1:8b`). 호스트에 미리
   `ollama pull <model>` 해두면 첫 호출이 빠르다. 길드별로는 `/settings → 모델 관리`
   (큐레이션 목록/커스텀/설치 버튼)로 바꿀 수 있다.
5. `HEALTHCHECK_REQUIRE_OLLAMA` 는 `false` 유지 권장(Ollama 가 잠깐 죽어도 봇 컨테이너가
   재시작 플랩하지 않도록 — 개별 명령은 친절 안내로 폴백).

(클라우드 API 로 가려면 길드별 `/settings` 에서 제공자/키를 등록하면 그게 우선한다.)
- runner 중지/제거: `cd "$HOME/actions-runner" && ./svc.sh stop && ./svc.sh uninstall`.
