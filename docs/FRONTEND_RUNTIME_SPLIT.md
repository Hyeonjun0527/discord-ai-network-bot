# Nexa 프론트 런타임 분리

이 레포는 monorepo로 합치지 않고 독립 레포로 유지한다. 대신 프론트 런타임은 역할별로 분리한다.

## 역할

- `site/`: 공개 사이트. Astro static build. 설치, SEO/GEO, 약관/개인정보, 릴리즈 안내를 담당한다.
- `admin-console/`: 관리자 콘솔. Vite React SPA. 서버 설정, 채널 정책, 프리셋, 지식 관리처럼 상태가 많은 화면을 담당한다.
- `central-server/`: Kotlin Spring API와 Discord bot/relay. 장기 상태, 권한, 정책, billing, Provider relay를 계속 소유한다.
- `provider-agent/`: Python desktop/provider agent. brew/winget/scoop 릴리즈 흐름과 `agent-v*` 태그는 그대로 유지한다.

## 릴리즈 보호 규칙

- `packaging/assets.json`이 설치 명령, 릴리즈 asset 이름, 패키지 ID의 SSOT다.
- `agent-v*` 태그, `nexa-macos.dmg`, `nexa-windows.exe`, `nexa-agent-*` asset 이름을 프론트 분리 때문에 바꾸지 않는다.
- 공개 사이트는 `packaging/assets.json`을 읽어 설치 명령을 렌더링한다.
- 콘솔은 same-origin `/api/**`로 central-server에 연결하고 Discord OAuth 세션을 자동 사용한다.
  로컬 Vite 개발 서버는 `/api`, `/login`, `/oauth2`를 `VITE_DEV_API_TARGET`으로 프록시한다.
  별도 대상을 지정하지 않으면 현재 환경의 `SERVER_PORT`를 사용하고, 둘 다 없을 때만 `8080`을 사용한다.
  로컬 토큰 인증을 사용할 때는 Vite 서버가 `CENTRAL_DASHBOARD_ADMIN_TOKEN`을 프록시 요청에만
  주입하므로 토큰을 브라우저 설정이나 저장소에 노출하지 않는다.

## 배포 형태

- `discord-ai.yeon.world`: Astro `site` build 산출물.
- `/admin/console/`: Vite `admin-console` build 산출물. `central-deploy.yml`이 빌드 후 central-server JAR에 포함한다.
- `/admin/dashboard/`: 기존 운영 대시보드 호환 경로.
- `api.discord-ai.yeon.world` 또는 기존 central-server origin: Kotlin central-server.

기존 `central-server/src/main/resources/static/install.html`과 `/admin/dashboard` 정적 리소스는
새 React 콘솔과 별개로 호환 경로를 유지한다.
