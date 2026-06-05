---
name: discord-assistant-deployment-wizard
description: >-
  discord-assistant 운영/배포 위저드. central-server 배포(ENV_FILE·자동주입 키), 어드민 대시보드 접속(OAuth/토큰),
  provider-connect OAuth, 에이전트(데스크톱 앱) 릴리스, 라이브 점검, 함정을 정확히 안내한다.
  TRIGGER: 배포·재배포·ENV_FILE·어드민 대시보드·OAuth(디스코드 로그인)·에이전트 릴리스/인앱업데이트·
  CENTRAL_OAUTH_ENABLED·CENTRAL_DASHBOARD_ADMIN·CONNECT_DISCORD·discord-ai.yeon.world 관련 작업.
---

# discord-assistant 배포 위저드

> 단일 진실 원천(전체 레퍼런스): **`central-server/docs/DEPLOYMENT.md`** (인프라/러너는 `DEPLOY_REMOTE.md`).
> 이 스킬은 그 문서의 핵심을 빠르게 적용하기 위한 요약 + 절차다. 헷갈리면 먼저 그 문서를 Read 하라.

공개 주소: `https://discord-ai.yeon.world` (Cloudflare Tunnel → 원격 우분투 `localhost:8085`).

## 절대 오해하지 말 것 (자주 틀리는 지점)

1. **ENV_FILE 자동 주입 키** — `CENTRAL_IMAGE`·`CENTRAL_DURABLE_SECRET`·`SERVER_PORT`·`APP_PORT`는
   배포(`central-deploy.yml`)가 ENV_FILE에서 **떼어내고 자기가 채운다**. 사용자가 넣을 필요 없음(넣어도 무시).
   → "CENTRAL_IMAGE 없으면 배포 깨진다"는 **deprecated 워크플로(`central-server-deploy.yml`) 기준의 오해**.
   실제 운영은 `central-deploy.yml`.
2. **서버 API를 curl/코드로 칠 땐 `User-Agent` 필수** — WAF가 기본 `Python-urllib` UA를 403으로 막는다.
3. **릴리스 자산명은 ASCII** (`nexa-macos.zip`/`nexa-windows.exe`) — GitHub이 한글 파일명을 스트립.
4. **싱글톤 락 포트 48569** — 로컬에 앱이 떠 있으면 provider-agent 테스트가 실패. 테스트 전 `pkill -f NEXA`.
5. **워크트리 공유 위험** — 동시 에이전트의 `git checkout`이 미커밋 변경을 날린다. `origin/main` 기반 클린 브랜치로 분리.
6. **Discord OAuth 앱 1개 공용** — provider-connect와 대시보드 로그인이 같은 `CONNECT_DISCORD_*` 앱.
   redirect URI **둘 다** 필요: `/provider/connect/callback`, `/login/oauth2/code/discord`.

## 자주 하는 작업

### 어드민 대시보드 켜기 (B안: 디스코드 OAuth)
1. ENV_FILE에 추가: `CENTRAL_OAUTH_ENABLED=true`, `CENTRAL_DASHBOARD_ADMIN_USER_IDS=<디스코드 userId(콤마구분)>`.
2. 디스코드 개발자 포털 → OAuth 앱(`CONNECT_DISCORD_CLIENT_ID`) → Redirects에 `https://discord-ai.yeon.world/login/oauth2/code/discord` 추가.
3. 재배포(아래) → `https://discord-ai.yeon.world/admin/dashboard/` 접속 → 디스코드 로그인 → 허용목록이면 어드민.
- userId: 디스코드 설정→고급→개발자 모드 ON→프로필 우클릭 "사용자 ID 복사"(18~19자리).
- 대안 A안(토큰): `CENTRAL_DASHBOARD_ADMIN_TOKEN=<비밀>` → 페이지 "관리자 접근"에 입력.

### 재배포
1. **Settings → Secrets and variables → Actions → `ENV_FILE`** 수정(완전한 .env 통째 붙여넣기).
2. **Actions → "central-server CI/CD (원격 배포)" → Run workflow** (또는 `central-server/**` push).

### 라이브 점검 (User-Agent 필수)
```bash
curl -A x https://discord-ai.yeon.world/provider/connect/status      # {"enabled":true} = OAuth 켜짐
curl -A x -o /dev/null -w '%{http_code}\n' https://discord-ai.yeon.world/admin/dashboard/
```

### 에이전트 릴리스
- `provider-agent/src/**`/`pyproject.toml`를 main에 머지 → `agent-autorelease`가 SemVer bump(`feat:`=minor/`fix:`=patch) + `agent-v*` 태그 → `agent-build`(win/mac/ubuntu, GitHub 호스티드) → Release.
- 로컬 검증: `cd provider-agent && ../.venv/bin/ruff check src tests && ../.venv/bin/mypy src && ../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70`.
- 정식 mac 빌드 로컬 설치: 릴리스 `nexa-macos.zip` 다운로드 → SHA256 검증 → `ditto -x -k` → ad-hoc 서명 → `/Applications`에 `ditto` + `xattr -dr com.apple.quarantine`.

### central-server 검증 (머지 전)
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server compileKotlin ktlintMainSourceSetCheck test
```
ktlint 함정: 멀티라인 표현식/체인 메서드는 새 줄에서 시작(`chain-method-continuation`).

자세한 키 레퍼런스·코드 근거는 `central-server/docs/DEPLOYMENT.md` 참조.
