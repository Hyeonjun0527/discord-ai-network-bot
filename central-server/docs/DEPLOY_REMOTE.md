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
                              ├─ production Environment Secret → Docker secret file
                              ├─ compose.remote.yml 렌더(평문 시크릿·host .env 없음)
                              └─ compose pull/up + runtime-secret/health/policy 감사
```
- Compose 프로젝트명 `central-server` 로 격리 → 같은 호스트의 다른 서비스와 컨테이너/네트워크 이름 충돌을 피한다.
- 포트 `127.0.0.1:8085`. 운영은 필수 시크릿이 하나라도 비면 배포 전에 실패한다.

## GitHub `production` Environment Secrets
| 시크릿 | 상태 | 설명 |
|---|---|---|
| `CENTRAL_DB_PASSWORD` | 필수 | Postgres 비밀번호 |
| `DISCORD_BOT_TOKEN` | 필수 | 봇 토큰 |
| `CENTRAL_DURABLE_SECRET` | 필수 | durable 토큰 HMAC 키 |
| `NEXA_FIELD_ENC_KEY` | 필수 | NEXA raw context 필드 암호화 키 |
| `ZAI_API_KEY` | 필수 | z.ai 키 |
| `CONNECT_DISCORD_CLIENT_SECRET` | 필수 | Discord OAuth client secret |
| `DISCORD_ENABLED`, `RELAY_PUBLIC_URL` | 필수 | 봇·relay 운영 설정 |
| `CONNECT_DISCORD_CLIENT_ID` | 필수 | Discord OAuth client ID |
| `CENTRAL_OAUTH_ENABLED`, `CENTRAL_DASHBOARD_ADMIN_USER_IDS` | 필수 | 관리자 로그인 설정 |

> SSH 키·GHCR PAT·Cloudflare 토큰 **불필요** — self-hosted 러너가 서버에서 직접 돌며 GHCR 는
> 러너의 `GITHUB_TOKEN` 으로 pull 한다.
> 저장소 단위 `ENV_FILE`이나 운영 호스트 `.env`는 사용하지 않는다. central-server는 Spring configtree,
> Postgres는 `POSTGRES_PASSWORD_FILE`로 `/run/secrets`의 파일만 읽는다.

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
ssh ssh.yeon.world 'cd ~/deploy/central-server && ./ops_runtime_secret_audit.sh'
ssh ssh.yeon.world 'cd ~/deploy/central-server && ./ops_healthcheck.sh'
ssh ssh.yeon.world 'cd ~/deploy/central-server && DISCORD_GUILD_ID=all ./ops_policy_audit.sh'
```
