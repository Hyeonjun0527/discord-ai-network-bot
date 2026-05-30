# 원격 자동 배포 (CI/CD → linuxssh.dailyting.cloud)

`central-server` 를 원격 우분투 서버에 **자동 배포**한다. 워크플로: `.github/workflows/central-deploy-ssh.yml`.

## 흐름
```
push(central-server/**) ─▶ GitHub Actions(ubuntu-latest)
   ├─ gradlew bootJar
   ├─ docker build → GHCR push (ghcr.io/<owner>/central-server:latest,:sha)
   └─ SSH → linuxssh.dailyting.cloud
        ├─ compose.remote.yml 복사(→ ~/deploy/central-server/compose.yml)
        ├─ .env 렌더(CENTRAL_IMAGE/DB_PASSWORD/DISCORD_*)
        ├─ docker login ghcr + compose pull + up -d
        └─ 헬스 판정(/actuator/health == UP)
```
- 프로젝트명 `central-server` 로 격리 → 같은 호스트의 dailyting 서비스와 충돌 없음.
- 포트는 `127.0.0.1:8080`(로컬/터널 전용). 토큰 없으면 Discord 만 비활성, 서버는 기동.

## GitHub Secrets
| 시크릿 | 상태 | 설명 |
|---|---|---|
| `SSH_HOST` | ✅ 설정됨 | `linuxssh.dailyting.cloud` |
| `SSH_USER` | ✅ 설정됨 | `osuma` |
| `SSH_PRIVATE_KEY` | ✅ 설정됨 | CI 전용 배포키(`~/.ssh/central_ci_deploy`, 공개키는 원격 authorized_keys 등록됨) |
| `CENTRAL_DB_PASSWORD` | ✅ 설정됨 | 원격 실행 중 Postgres 와 일치 |
| `DISCORD_BOT_TOKEN` | ⛔ **사용자 추가 필요** | 봇 토큰(이게 있어야 봇이 라이브) |
| `DISCORD_GUILD_ID` | ⬜ 선택 | 즉시 명령 등록용 서버 ID(없으면 글로벌, ~1h) |
| `GHCR_PAT` | ⛔ **사용자 추가 필요** | 원격이 GHCR 에서 이미지 pull(`read:packages`). 패키지를 public 으로 바꾸면 생략 가능 |

### 사용자가 추가할 시크릿
```bash
# 봇 토큰(Discord 개발자포털 Bot 탭)
gh secret set DISCORD_BOT_TOKEN          # 프롬프트에 붙여넣기(화면 미표시)
# (선택) 즉시 명령 등록용 서버 ID
gh secret set DISCORD_GUILD_ID -b "여기에_서버ID"
# GHCR pull용 PAT(read:packages) — https://github.com/settings/tokens
gh secret set GHCR_PAT                    # 프롬프트에 PAT 붙여넣기
```
> GHCR_PAT 대신 패키지를 public 으로: GitHub → Packages → central-server → Package settings → Change visibility → Public. 그러면 `GHCR_PAT` 불필요.

## 배포 트리거
- `feat/remote-agent-byollm` 또는 `main` 의 `central-server/**` 변경 push → 자동 배포.
- 수동: GitHub Actions → "central-server CI/CD (SSH 원격 배포)" → Run workflow.

## 봇 라이브 전환
1. `DISCORD_BOT_TOKEN`(필수) + `GHCR_PAT`(또는 public) 시크릿 추가.
2. push 하거나 워크플로 수동 실행 → 배포 시 토큰이 주입되어 봇이 Discord 에 연결.
3. Discord 에서 `/menu` 확인.

## 원격 프로바이더 노출(후속)
원격 PC 의 provider-agent 가 WS(`/agent`)로 붙으려면 central 을 공개해야 한다. 이 서버는
Cloudflare Tunnel(cloudflared)로 노출하므로, 터널 ingress 에 호스트네임 → `http://localhost:8080`
(또는 central 컨테이너) 라우팅을 추가한다. 에이전트는 `--relay-url wss://<호스트네임>/agent` 로 접속.

## 운영
```bash
ssh linuxssh.dailyting.cloud 'cd ~/deploy/central-server && docker compose logs -f central-server'
ssh linuxssh.dailyting.cloud 'cd ~/deploy/central-server && docker compose ps'
```
> self-hosted(Mac) 배포(`central-server-deploy.yml`)는 비상 수동 폴백으로 강등됨.
