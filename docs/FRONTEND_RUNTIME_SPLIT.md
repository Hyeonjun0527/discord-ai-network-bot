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
- 콘솔은 `VITE_CENTRAL_API_BASE_URL` 또는 same-origin API로 central-server에 연결한다.

## 배포 형태

- `discord-ai.yeon.world`: Astro `site` build 산출물.
- `console.discord-ai.yeon.world` 또는 `/admin/dashboard/`: Vite `admin-console` build 산출물.
- `api.discord-ai.yeon.world` 또는 기존 central-server origin: Kotlin central-server.

기존 `central-server/src/main/resources/static/install.html`과 `/admin/dashboard` 정적 리소스는 새 배포가
완료될 때까지 호환 경로로 유지한다.
