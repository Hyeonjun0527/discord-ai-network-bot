# NEXA Domain Migration TODO

NEXA 리브랜딩 이후에도 현재 운영 주소(`discord-ai.yeon.world`)와 GitHub repo 경로
(`Hyeonjun0527/discord-ai-network-bot`)는 즉시 바꾸지 않는다. 운영 연결, 릴리스 다운로드,
OAuth redirect, attestation 검증이 모두 이 값에 묶여 있기 때문이다.

## 추후 결정

- 새 공개 도메인 확정: 예) `nexa.yeon.world`
- 기존 `discord-ai.yeon.world` 유지 기간과 301/302 리다이렉트 정책 확정
- GitHub repo rename 여부 확정: 예) `Hyeonjun0527/nexa`
- 패키지 매니저 tap/bucket/winget 기존 ID deprecation 정책 확정

## 바꿀 위치

- 운영 env: `RELAY_PUBLIC_URL`, `CONNECT_PUBLIC_BASE_URL`, Discord OAuth redirect URI
- 중앙 서버 문서/가이드: `/install`, `/provider/connect/*`, `/download/*`
- Discord Developer Portal: bot invite URL, OAuth callback URL, application display assets
- Cloudflare: DNS record, tunnel route, Access/WAF 예외, TLS 상태
- GitHub Release/attestation 안내: `--repo` 값과 다운로드 URL
- Homebrew/Scoop/winget: homepage, checkver, manifest URL, 기존 package alias
- Provider Agent 기본/저장 relay URL: 기존 사용자 config migration 여부

## 전환 검증

- `https://<new-domain>/install` 정상 응답
- `/download/nexa-macos.dmg`, `/download/nexa-windows.exe`, `/download/nexa-macos.zip` 정상 다운로드
- `/provider/connect/status` 및 Discord OAuth callback 정상
- provider-agent WebSocket `wss://<new-domain>/agent` 연결 정상
- 기존 `discord-ai.yeon.world` 사용자에게 리다이렉트 또는 하위호환 안내가 동작
