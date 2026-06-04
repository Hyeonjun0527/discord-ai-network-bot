# Deployment Wizard — discord-assistant 운영 종합 가이드

central-server(서버·봇) 배포, 어드민 대시보드, OAuth, 에이전트(데스크톱 앱) 릴리스, `ENV_FILE`
키 레퍼런스를 한곳에 정리한다. 인프라/러너 세부는 [`DEPLOY_REMOTE.md`](./DEPLOY_REMOTE.md) 참조.

공개 주소: **`https://discord-ai.yeon.world`** (Cloudflare Tunnel → 원격 우분투 `localhost:8085`).

---

## 0. 시스템 한눈에

- **central-server** (Kotlin/Spring Boot + JDA) — Provider Pool 중앙 서버 + 디스코드 봇. GHCR 이미지로 원격 배포.
- **provider-agent** ("냥시스턴트", Python) — 유저 PC의 로컬 Ollama를 풀에 연결하는 데스크톱 앱(GUI `.app`/`.exe` + CLI). GitHub Release로 배포, 인앱 자동 업데이트.

---

## A. central-server 배포 (CI/CD)

- 워크플로: **`central-deploy.yml`** = "central-server CI/CD (원격 배포)".
  - 트리거: `main`(또는 `feat/remote-agent-byollm`)의 `central-server/**` push, 또는 **수동**(`workflow_dispatch`).
  - build(ubuntu): `gradlew bootJar` → docker build → **GHCR push**(`:latest`, `:sha`).
  - deploy(self-hosted `dailyting-remote`, 원격 서버): `deploy/compose.remote.yml`로 GHCR 이미지 **pull + up**, 헬스 `:8085/actuator/health == UP`.
- 참고: `central-server-deploy.yml`은 **deprecated**(소스 빌드, `docker-compose.yml`). 실제 운영은 위 `central-deploy.yml`.

### ENV_FILE (GitHub repo secret) = 컨테이너 `.env` 전체
배포가 `secrets.ENV_FILE`을 그대로 `.env`로 렌더한다. **시크릿을 통째로 덮어써도 안전** — 단, 아래 구분을 지킬 것.

**🔧 배포가 자동 주입 (ENV_FILE에 넣지 말 것 — 넣어도 `awk`로 떼어내고 덮어씀):**
`CENTRAL_IMAGE`(이미지:sha) · `CENTRAL_DURABLE_SECRET`(러너에 `.durable-secret`로 1회 생성·유지) · `SERVER_PORT` · `APP_PORT` · SearXNG `settings.yml`.
> 근거: `central-deploy.yml`의 `awk '!/^(CENTRAL_IMAGE|SERVER_PORT|APP_PORT|CENTRAL_DURABLE_SECRET)=/'` + 이후 자동 append.

**👤 사용자가 ENV_FILE에 넣는 키:**

| 키 | 필수 | 설명 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | 봇 라이브에 필수 | 디스코드 봇 토큰 |
| `DISCORD_ENABLED` | | `true`면 봇 연결 |
| `DISCORD_GUILD_ID` | 선택 | 즉시 슬래시명령 등록용 서버 ID(없으면 글로벌 ~1h) |
| `CENTRAL_DB_PASSWORD` | 필수 | Postgres 비밀번호(컨테이너와 일치) |
| `RELAY_PUBLIC_URL` | | `wss://discord-ai.yeon.world/agent` |
| `CONNECT_DISCORD_CLIENT_ID` / `CONNECT_DISCORD_CLIENT_SECRET` | OAuth용 | **디스코드 OAuth 앱**(에이전트 '디스코드 로그인 추가' + 대시보드 어드민 로그인이 **공용**으로 사용) |
| `CONNECT_PUBLIC_BASE_URL` | | `https://discord-ai.yeon.world` (compose 기본값 있음) |
| `CENTRAL_OAUTH_ENABLED` | 대시보드 어드민 로그인용 | **`true`면** 대시보드가 디스코드 로그인 게이트로 전환(기본 false) |
| `CENTRAL_DASHBOARD_ADMIN_USER_IDS` | 어드민 지정 | 콤마구분 **디스코드 userId** 허용목록 |
| `CENTRAL_DASHBOARD_ADMIN_TOKEN` | (A안 대안) | `X-Dashboard-Admin-Token` 헤더 토큰 |
| `CENTRAL_SEARCH_*` | 선택 | 웹검색(SearXNG) 설정 |

> `CENTRAL_OAUTH_ENABLED`/`CENTRAL_DASHBOARD_ADMIN_USER_IDS`/`CENTRAL_DASHBOARD_ADMIN_TOKEN`은
> `compose.remote.yml`이 패스스루한다.

### 재배포 절차
1. **Settings → Secrets and variables → Actions → `ENV_FILE`** 시크릿을 수정(완전한 `.env` 통째 붙여넣기).
2. **Actions → "central-server CI/CD (원격 배포)" → Run workflow** (또는 `central-server/**` push).

---

## B. 어드민 대시보드 접속

- URL: **`https://discord-ai.yeon.world/admin/dashboard/`** (정적 SPA). 대시보드는 **서버(길드)별** — `/api/dashboard/{guildId}/...`.
- 인증 방식 2가지:
  - **B안 — Discord OAuth (권장)**: `CENTRAL_OAUTH_ENABLED=true` + `CENTRAL_DASHBOARD_ADMIN_USER_IDS=<userId>`. 디스코드 OAuth 앱 redirect에 **`https://discord-ai.yeon.world/login/oauth2/code/discord`** 추가 등록.
    - **OAuth 켜지면 `/admin/dashboard/`는 인증 필요** → 미로그인 접속 시 **디스코드 로그인으로 자동 리디렉트**(SecurityConfig가 `/admin/dashboard/**`를 authenticated로 두고, 단일 OAuth 클라이언트라 `/oauth2/authorization/discord`로 직행). 로그인 후 허용목록 계정만 어드민.
    - 페이지 헤더에 로그인 상태/**로그아웃** 버튼(`/api/me`로 상태 조회, `/logout` POST). 수동 시작 URL: `https://discord-ai.yeon.world/oauth2/authorization/discord`.
    - 코드: `SecurityConfig`가 `central.oauth.enabled=true`일 때만 Discord OAuth2 `ClientRegistration`을 **코드로** 생성(빈 client-id로 application.yml에 두면 부팅이 깨짐). `CONNECT_DISCORD_*` 앱 재사용.
  - **A안 — 관리자 토큰**: `CENTRAL_DASHBOARD_ADMIN_TOKEN=<비밀>` → 페이지 상단 "관리자 접근"에 입력(이후 `X-Dashboard-Admin-Token` 자동 첨부). OAuth OFF 환경에서 페이지는 공개(읽기), 어드민 작업만 토큰 보호.
    - 정책 쓰기(`/api/dashboard/{id}/welcome`·`auto-approve`·`role-policy`)도 **OAuth 없이 토큰만으로 동작**한다(`DashboardWriteController`는 항상 등록, 인증은 `AiNetworkApiSecurityFilter`가 토큰/OAuth 허용목록으로 강제 — 둘 다 없으면 403). 로컬(`localhost:8080`)에서도 `CENTRAL_DASHBOARD_ADMIN_TOKEN`만 주면 전 기능 테스트 가능.
- **디스코드 userId 얻기**: 디스코드 설정 → 고급 → **개발자 모드 ON** → 내 프로필 우클릭 → **"사용자 ID 복사"**(18~19자리 숫자).
- 로컬: `http://localhost:8080/admin/dashboard/`.

> ⚠️ B안을 켜면 대시보드 **페이지·데이터 API가 로그인 필요**가 된다(허용목록 계정만 어드민, 공개 익명 뷰 없음).

---

## C. 에이전트 "디스코드 로그인 추가" (provider-connect OAuth)

데스크톱 앱이 디스코드 로그인으로 **여러 서버**의 프로바이더 토큰을 받는 흐름.
- 서버: `CONNECT_DISCORD_CLIENT_ID/SECRET` 설정 시 `/provider/connect` 활성(미설정이면 503 안내).
- 디스코드 앱 redirect에 **`https://discord-ai.yeon.world/provider/connect/callback`** 등록.
- 에이전트는 **서버 `/provider/connect/status`를 폴링**해 버튼을 켠다(에이전트 env 불필요 — `AGENT_CONNECT_ENABLED`는 강제 오버라이드용).
- 콜백은 성공 시 `token` + `guild`(id) + `guildName`을 돌려줘 '내 서버 목록'에 이름 표시.
- 확인: `curl -A x https://discord-ai.yeon.world/provider/connect/status` → `{"enabled":true}`.

---

## D. 에이전트(데스크톱 앱) 릴리스

- `agent-autorelease`: `main`의 `provider-agent/src/**`·`pyproject.toml` 변경 → **SemVer 자동 bump**(`feat:`→minor, `fix:`→patch, BREAKING→major) → `agent-v<버전>` 태그 → `agent-build` dispatch.
- `agent-build`: **GitHub 호스티드** win/mac/ubuntu(공개 레포라 무료). 산출물:
  - CLI: `discord-ai-network-bot-{linux,macos,windows.exe}`
  - GUI(네이티브 창): mac **`nyassistant-macos.zip`**(`.app`), win **`nyassistant-windows.exe`**
- **자산명은 반드시 ASCII** (GitHub Release가 한글 파일명을 스트립 → 인앱 업데이터가 못 찾음). 빌드 산출물(`냥시스턴트.app`/`.exe`)·앱 표시이름은 한글 유지.
- CI는 **`pip install .[gui]`**(pywebview) 해야 네이티브 창(없으면 브라우저 폴백).
- 인앱 업데이트: 릴리스 `latest` 리다이렉트로 버전 비교 → 다운로드(SHA256 검증)·교체·재실행. GUI 워처(기본 2h) + 헤드리스 서비스 워처(`--service`로 재실행).

---

## E. 라이브 점검 (반드시 `User-Agent` 붙일 것 — WAF가 기본 UA 403)

```bash
curl -A x https://discord-ai.yeon.world/provider/connect/status      # {"enabled":true}
curl -A x -G --data-urlencode "cb=http://127.0.0.1:1/connect/callback" --data-urlencode "state=x" \
  -D - -o /dev/null https://discord-ai.yeon.world/provider/connect   # 302 → discord.com(OAuth 켜짐)
curl -A x -o /dev/null -w '%{http_code}\n' https://discord-ai.yeon.world/dashboard/
```

---

## 함정 (반복 주의)

- **WAF/CDN이 기본 `Python-urllib` UA를 403으로 막는다** → 서버 API를 코드/curl로 칠 때 **User-Agent 필수**. (인앱 connect-status probe 실패의 원인이었음)
- **릴리스 자산명 ASCII 필수** (GitHub 한글 스트립).
- **싱글톤 락 포트 48569** — 앱은 머신당 1인스턴스. 로컬에서 앱이 떠 있으면 그 락 때문에 provider-agent 테스트(에이전트 start)가 실패한다. 테스트 전 `pkill -f 냥시스턴트` + 48569 점유 PID kill.
- **워크트리 공유 위험**: 동시 실행되는 다른 에이전트가 공유 워킹트리에서 `git checkout`으로 브랜치를 전환하면 **미커밋 변경이 유실**된다. 작업은 `origin/main` 기반 클린 브랜치로 분리하고, 오염 시 내 파일만 추출해 다시 커밋.
- **JDK 21 필요**: `export JAVA_HOME=.../amazon-corretto-21.jdk/Contents/Home`; `central-server/gradlew -p central-server build`. ktlint(`ktlintMainSourceSetCheck`)·Kover·ArchUnit 게이트, integration은 `-PdockerTests`(Testcontainers).
- **Discord OAuth 앱 1개를 공용**: provider-connect와 대시보드 로그인이 같은 `CONNECT_DISCORD_*` 앱을 쓴다 → redirect URI **둘 다** 등록 필요(`/provider/connect/callback`, `/login/oauth2/code/discord`).
