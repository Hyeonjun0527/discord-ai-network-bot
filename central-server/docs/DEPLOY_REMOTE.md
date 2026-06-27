# 원격 자동 배포 (CI/CD → ssh.yeon.world)

`central-server` 를 원격 우분투 서버에 **자동 배포**한다. 워크플로: `.github/workflows/central-deploy.yml`.

> `ssh.yeon.world` 는 Cloudflare Tunnel 뒤 운영 호스트다.
> 그래서 원격 서버에 **self-hosted 러너(`yeon-arm`)** 를 설치하고, 배포 잡을 거기서 직접 실행한다.

## 흐름
```
push(central-server/**) ─▶ build (self-hosted: yeon-arm)
                              ├─ gradlew bootJar
                              └─ docker build → GHCR push (:latest, :sha)
                           ─▶ deploy (self-hosted: yeon-arm, 원격 서버에서 실행)
                              ├─ docker login ghcr (GITHUB_TOKEN)
                              ├─ compose.remote.yml → ~/deploy/central-server/compose.yml
                              ├─ .env 렌더(CENTRAL_IMAGE/DB_PASSWORD/DISCORD_*)
                              └─ docker compose pull + up -d + 헬스(/actuator/health==UP)
```
- Compose 프로젝트명 `central-server` 로 격리 → 같은 호스트의 다른 서비스와 컨테이너/네트워크 이름 충돌을 피한다.
- 포트 `127.0.0.1:8085`. 토큰 없으면 Discord 만 비활성, 서버는 기동.

## GitHub Secrets
| 시크릿 | 상태 | 설명 |
|---|---|---|
| `CENTRAL_DB_PASSWORD` | ✅ 설정됨 | 원격 실행 중 Postgres 와 일치 |
| `DISCORD_BOT_TOKEN` | ⛔ **사용자 추가 필요** | 봇 토큰(이게 있어야 봇이 라이브) |
| `DISCORD_GUILD_ID` | ⬜ 선택 | 즉시 명령 등록용 서버 ID(없으면 글로벌 ~1h) |

> SSH 키·GHCR PAT·Cloudflare 토큰 **불필요** — self-hosted 러너가 서버에서 직접 돌며 GHCR 는
> 러너의 `GITHUB_TOKEN` 으로 pull 한다.

### 봇 라이브 전환
```bash
gh secret set DISCORD_BOT_TOKEN              # 프롬프트에 봇 토큰(화면 미표시)
gh secret set DISCORD_GUILD_ID -b "서버ID"   # (선택) 즉시 명령
```
→ 다음 push 또는 워크플로 수동 실행 시 봇이 Discord 에 연결되고 `/menu` 가 뜬다.

## 배포 트리거
- `main` 의 `central-server/**` push → 자동 빌드+배포.
- 수동: Actions → "central-server CI/CD (원격 배포)" → Run workflow.

## self-hosted 러너 (원격, 설치 완료)
- 이름 `yeon-arm`, 라벨 `self-hosted,yeon-arm`.
- 위치: 원격 self-hosted runner 서비스.
```bash
ssh ssh.yeon.world 'sudo systemctl status actions.runner.*yeon-arm*'
```

## 원격 프로바이더 노출(후속)
원격 PC 의 provider-agent 가 WS(`/agent`)로 붙으려면 central 을 공개해야 한다. 이 서버는
Cloudflare Tunnel 로 노출하므로, 터널 ingress 에 호스트네임 → `http://localhost:8085` 라우팅을
추가하고, 에이전트는 `--relay-url wss://<호스트네임>/agent` 로 접속한다.
현재 정식 호스트는 `discord-ai.yeon.world` (`discordai.yeon.world`는 별칭/리다이렉트만 허용 가능).

## 운영
```bash
ssh ssh.yeon.world 'cd ~/deploy/central-server && docker compose logs -f central-server'
ssh ssh.yeon.world 'cd ~/deploy/central-server && docker compose ps'
ssh ssh.yeon.world 'cd ~/deploy/central-server && ./ops_healthcheck.sh'
ssh ssh.yeon.world 'cd ~/deploy/central-server && DISCORD_GUILD_ID=all ./ops_policy_audit.sh'
```
