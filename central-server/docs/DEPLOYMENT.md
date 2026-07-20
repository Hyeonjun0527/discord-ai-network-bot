# Deployment Wizard — discord-assistant 운영 종합 가이드

central-server(서버·봇) 배포, 어드민 대시보드, OAuth, 에이전트(데스크톱 앱) 릴리스,
운영 시크릿 키 레퍼런스를 한곳에 정리한다. 인프라/러너 세부는 [`DEPLOY_REMOTE.md`](./DEPLOY_REMOTE.md) 참조.

공개 주소: **`https://discord-ai.yeon.world`** (Cloudflare Tunnel → 원격 우분투 `localhost:8085`).

---

## 0. 시스템 한눈에

- **central-server** (Kotlin/Spring Boot + JDA) — Provider Pool 중앙 서버 + 디스코드 봇. GHCR 이미지로 원격 배포.
- **provider-agent** ("Nexa", Python) — 유저 PC의 로컬 Ollama를 풀에 연결하는 데스크톱 앱(GUI `.app`/`.exe` + CLI). GitHub Release로 배포, 인앱 자동 업데이트.

---

## A. central-server 배포 (CI/CD)

- 워크플로: **`central-deploy.yml`** = "central-server CI/CD (원격 배포)".
  - 트리거: `main`의 `central-server/**` push, 또는 **수동**(`workflow_dispatch`).
  - build(self-hosted `yeon-arm`): `gradlew bootJar` → docker build → **GHCR push**(`:latest`, `:sha`).
  - deploy(self-hosted `yeon-arm`, `ssh.yeon.world`): `deploy/compose.remote.yml`로 GHCR 이미지 **pull + up**, 헬스 `:8085/actuator/health == UP`.
- 참고: `central-server-deploy.yml`은 **deprecated**(소스 빌드, `docker-compose.yml`). 실제 운영은 위 `central-deploy.yml`.

### GitHub `production` Environment가 운영 값의 SSOT

운영 값은 **Settings → Environments → `production` → Environment secrets**에서 키별로 관리한다.
배포 잡은 민감 값을 Docker Compose secret으로 전달하고, central-server는
`configtree:/run/secrets/`, Postgres는 `POSTGRES_PASSWORD_FILE`로 읽는다. 운영 호스트와 배포
디렉터리에 `.env*` 또는 `.durable-secret*`을 남기지 않으며, 렌더링된 `compose.yml`과 컨테이너
환경변수에도 평문 시크릿을 넣지 않는다.

**Docker secret file로 전달하는 필수 시크릿:**

| 키 | 필수 | 설명 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | 봇 라이브에 필수 | 디스코드 봇 토큰 |
| `CENTRAL_DB_PASSWORD` | 필수 | Postgres 비밀번호(컨테이너와 일치) |
| `CENTRAL_DURABLE_SECRET` | 필수 | durable 프로바이더 토큰 HMAC 키. 기존 토큰 유지를 위해 값을 회전하지 않는다. |
| `NEXA_FIELD_ENC_KEY` | 필수 | NEXA raw context 필드 암호화 키 |
| `OPENAI_API_KEY` | 필수 | Luna 무료질문·NEXA 판단·발화 키 |
| `CONNECT_DISCORD_CLIENT_SECRET` | OAuth용 | 디스코드 OAuth 앱 client secret |

**현재 같은 Environment에 개별 Secret으로 보관하고 secret file로 전달하는 운영 설정:**

| 키 | 설명 |
|---|---|
| `DISCORD_ENABLED` | `true`면 봇 연결 |
| `RELAY_PUBLIC_URL` | `wss://discord-ai.yeon.world/agent` |
| `CONNECT_DISCORD_CLIENT_ID` | provider-connect와 대시보드가 공용으로 쓰는 Discord OAuth 앱 ID |
| `CENTRAL_OAUTH_ENABLED` | `true`면 대시보드 Discord 로그인 게이트 활성화 |
| `CENTRAL_DASHBOARD_ADMIN_USER_IDS` | 콤마 구분 Discord userId 관리자 허용목록 |

`CENTRAL_IMAGE`, `SERVER_PORT`, `APP_PORT`는 워크플로가 배포마다 주입한다. 운영 설정을 추가할 때는
한 파일에 뭉친 시크릿을 다시 만들지 말고, 필요한 키를 `production` Environment에 개별 등록한다.

### 재배포 절차
1. **Settings → Environments → `production`**에서 변경할 개별 Environment Secret만 수정한다.
2. **Actions → "central-server CI/CD (원격 배포)" → Run workflow** (또는 `central-server/**` push).
3. 배포 잡의 runtime-secret 감사에서 `active_env=absent`, `runtime secret files present`,
   `inline secret env absent`를 확인한다.

---

## B. 어드민 대시보드 접속

- 새 React 콘솔: **`https://discord-ai.yeon.world/admin/console/`**.
  - **대화 데이터**: NIA가 참고할 실제 대화 예시와 다음 답변을 편집·저장하고 검증 후 적용한다.
  - **실행 기록**: Discord 서버와 채널을 먼저 고른 뒤, 실행별 현재 대화·참고한 대화(최대 2개)·NIA의 최종
    답변만 확인한다. 실행 기록은 현재 서버 메모리 기반이라 재시작하면 초기화된다.
- 기존 운영 대시보드: **`https://discord-ai.yeon.world/admin/dashboard/`**. 기존 AI Network 운영 기능을 보존한다.
- 두 화면 모두 **서버(길드)별** API(`/api/dashboard/{guildId}/...`)를 사용하며 Discord OAuth 보호를 받는다.
- 인증 방식 2가지:
  - **B안 — Discord OAuth (권장)**: `CENTRAL_OAUTH_ENABLED=true` + `CENTRAL_DASHBOARD_ADMIN_USER_IDS=<userId>`. 디스코드 OAuth 앱 redirect에 **`https://discord-ai.yeon.world/login/oauth2/code/discord`** 추가 등록.
    - **OAuth 켜지면 `/admin/dashboard/`는 인증 필요** → 미로그인 접속 시 **디스코드 로그인으로 자동 리디렉트**(SecurityConfig가 `/admin/dashboard/**`를 authenticated로 두고, 단일 OAuth 클라이언트라 `/oauth2/authorization/discord`로 직행). 로그인 후 허용목록 계정만 어드민.
    - 페이지 헤더에 로그인 상태/**로그아웃** 버튼(`/api/me`로 상태 조회, `/logout` POST). 수동 시작 URL: `https://discord-ai.yeon.world/oauth2/authorization/discord`.
    - 코드: `SecurityConfig`가 `central.oauth.enabled=true`일 때만 Discord OAuth2 `ClientRegistration`을 **코드로** 생성(빈 client-id로 application.yml에 두면 부팅이 깨짐). `CONNECT_DISCORD_*` 앱 재사용.
  - **A안 — 관리자 토큰(로컬/스테이징 전용)**: `CENTRAL_DASHBOARD_ADMIN_TOKEN=<비밀>` → 페이지 상단 "관리자 접근"에 입력(이후 `X-Dashboard-Admin-Token` 자동 첨부). 현재 운영 Compose에는 평문 env 방지를 위해 이 선택 키를 연결하지 않는다.
    - 정책 쓰기(`/api/dashboard/{id}/welcome`·`auto-approve`·`role-policy`)도 **OAuth 없이 토큰만으로 동작**한다(`DashboardWriteController`는 항상 등록, 인증은 `AiNetworkApiSecurityFilter`가 토큰/OAuth 허용목록으로 강제 — 둘 다 없으면 403). 로컬(`localhost:8080`)에서도 `CENTRAL_DASHBOARD_ADMIN_TOKEN`만 주면 전 기능 테스트 가능.
- **디스코드 userId 얻기**: 디스코드 설정 → 고급 → **개발자 모드 ON** → 내 프로필 우클릭 → **"사용자 ID 복사"**(18~19자리 숫자).
- 로컬 React 콘솔: `http://localhost:5174/admin/console/`.

`central-deploy.yml`은 `admin-console`을 빌드한 뒤 산출물을 central-server JAR의
`/static/admin/console/`에 포함한다. 로컬에서 같은 산출물을 확인하려면 `make sync-admin-console`을 실행한다.

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
  - CLI: `nexa-agent-{linux,macos,windows.exe}`
  - GUI(네이티브 창): mac **`nexa-macos.zip`**(`.app`), win **`nexa-windows.exe`**
- **자산명은 반드시 ASCII** (GitHub Release가 한글 파일명을 스트립 → 인앱 업데이터가 못 찾음). 빌드 산출물(`Nexa.app`/`.exe`)·앱 표시이름은 한글 유지.
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

### 운영 정책 감사

자동응답 채널 또는 "니아 채널 자동 만들기"로 만든 `ai채팅`/`ai그림`이 LLM allow-list와 어긋나면
핀 가이드와 달리 `이 채널에서는 LLM 을 사용할 수 없습니다.`가 발생한다. 배포 후 또는 채널 정책 변경 후에는
읽기 전용 감사 스크립트로 확인한다.

```bash
ssh ssh.yeon.world
cd ~/deploy/central-server
DISCORD_GUILD_ID=all ./ops_policy_audit.sh
./ops_runtime_secret_audit.sh
```

`DISCORD_GUILD_ID=all`은 봇이 들어간 모든 서버의 Discord 채널 목록을 조회해 ko/en/ja 니아 기능 카테고리 아래
`ai채팅`/`ai그림` 계열 채널이 LLM allow-list에 들어 있는지 대조한다. 특정 서버만 보려면
`DISCORD_GUILD_ID=<guild_id>`를 준다. 토큰 값은 출력하지 않는다.
`ops_runtime_secret_audit.sh`는 운영 배포 트리의 `.env*` 부재, secret file 존재, 평문 시크릿 환경변수 부재를 검사한다.

---

## 함정 (반복 주의)

- **WAF/CDN이 기본 `Python-urllib` UA를 403으로 막는다** → 서버 API를 코드/curl로 칠 때 **User-Agent 필수**. (인앱 connect-status probe 실패의 원인이었음)
- **릴리스 자산명 ASCII 필수** (GitHub 한글 스트립).
- **싱글톤 락 포트 48569** — 앱은 머신당 1인스턴스. 로컬에서 앱이 떠 있으면 그 락 때문에 provider-agent 테스트(에이전트 start)가 실패한다. 테스트 전 `pkill -f Nexa` + 48569 점유 PID kill.
- **워크트리 공유 위험**: 동시 실행되는 다른 에이전트가 공유 워킹트리에서 `git checkout`으로 브랜치를 전환하면 **미커밋 변경이 유실**된다. 작업은 `origin/main` 기반 클린 브랜치로 분리하고, 오염 시 내 파일만 추출해 다시 커밋.
- **JDK 21 필요**: `export JAVA_HOME=.../amazon-corretto-21.jdk/Contents/Home`; `central-server/gradlew -p central-server build`. ktlint(`ktlintMainSourceSetCheck`)·Kover·ArchUnit 게이트, integration은 `-PdockerTests`(Testcontainers).
- **Discord OAuth 앱 1개를 공용**: provider-connect와 대시보드 로그인이 같은 `CONNECT_DISCORD_*` 앱을 쓴다 → redirect URI **둘 다** 등록 필요(`/provider/connect/callback`, `/login/oauth2/code/discord`).
