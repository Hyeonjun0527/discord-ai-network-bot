# NEXA 데스크톱 앱 — 프로토타입

서버 연결 없이 목데이터로 동작하는 데스크톱 앱 프로토타입(정적 HTML + ES 모듈).
실제 앱 전환 시 `adapter.js`의 `USE_MOCK = false` 한 줄로 백엔드(webui.py)에 붙는다.

## 구조

| 파일 | 역할 |
|------|------|
| `index.html` | UI 마크업·렌더·CSS(디자인 언어 SSOT 주석 포함) |
| `state.js` | **상태머신 SSOT** — stage/authed/connectOrigin + 불변식 가드 (`docs/NEXA_STATE_MACHINE.md`) |
| `contract.js` | 백엔드 계약 미러(ProviderState·ENDPOINTS·Wire) |
| `adapter.js` | 데이터 접근(mock ↔ http), `USE_MOCK` |
| `presenter.js` | 도메인 상태 → UI 표현 매핑 |
| `toast.js` | 토스트(진행바·닫기) |
| `install.js` | 런타임/모델 설치(진행 = 토스트 SSOT) |

## 실행

```bash
npm run serve     # http://127.0.0.1:8777/index.html (정적 서버)
```

우하단 `PROTO` 패널(단축키 `P`)로 stage·상태를 점프해 모든 화면/분기를 검증할 수 있다.

## URL(Hash) 라우팅 — 화면 딥링크

화면마다 URL 이 있어, 주소로 직접 진입·북마크·새로고침 유지가 된다(정적 서버 호환). 화면 전환 시 URL 도
자동 갱신되고(양방향), 브라우저 뒤로/앞으로도 동작한다. 네비게이션 흐름의 보조 SSOT.

| URL | 화면 |
|-----|------|
| `#/home` `#/models` `#/servers` `#/local` `#/logs` `#/settings` | 메인 탭 |
| `#/servers/:guildId` | 서버 상세(기부자 관점) |
| `#/servers/:guildId/manage` | 서버 관리(관리자, Provider 탭) |
| `#/onboarding[/step]` | 온보딩 마법사(단계) |
| `#/connect[/sub]` | Discord 연결(login·select·result) |

예: `http://127.0.0.1:8777/index.html#/servers/1001/manage` 로 바로 관리 화면 진입.

## 테스트 (Playwright)

상태 불변식(I1~I3)과 핵심 흐름을 자동 검증한다. 시스템 Chrome 사용(브라우저 다운로드 없음).

```bash
npm test          # 전체 E2E (정적 서버 자동 기동)
npm run test:ui   # UI 모드(클릭별 추적)
```

- `tests/state-machine.spec.js` — 상태 불변식(인증된 상태에서 로그인 노출 금지 등)
- `tests/models.spec.js` — 모델 화면·설치 토스트(pointer-events 회귀 방지)
- `tests/flows.spec.js` — 온보딩·연결·서버·제공 상태·서버 관리
- `tests/routing.spec.js` — URL(hash) 딥링크·화면↔URL 양방향·히스토리

> 관련 문서: `docs/NEXA_USER_FLOWS.md`(플로우 인벤토리), `docs/NEXA_STATE_MACHINE.md`(상태머신), `docs/NEXA_DESKTOP_SCREENS.md`(화면정의).
