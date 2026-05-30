# central-server 보안 (ADR 0002/0003)

커뮤니티 Provider Pool 중앙 서버의 보안 원칙과 구현 지점.

## 위협 모델 & 원칙
- **외부 유저는 프로바이더 PC 에 직접 접근할 수 없다.** 경로는 `Discord → 중앙 서버 →
  인증된 WebSocket → Provider Agent → localhost Ollama` 뿐이다.
- **중앙 서버는 임의 URL 로 나가지 않는다(SSRF 불가).** 추론은 이미 인증된 WS 연결로만
  내려보낸다. 서버가 프로바이더 PC 로 inbound 접속하지 않는다(에이전트가 outbound 로 붙음).
- **Agent 금지 행위**(에이전트 쪽 규약): 임의 shell/파일/URL 실행 금지, 중앙 서버 요청 외
  처리 금지, inbound 포트 미개방.

## 구현 지점
| 항목 | 위치 |
|---|---|
| 일회용 토큰(SHA-256 해시 저장·평문 미저장·단발성·TTL·revoke) | `provider/TokenService` |
| 토큰 마스킹(toString) | `relay/protocol/Frame.AuthFrame` |
| 프레임 화이트리스트(알 수 없는 type → ProtocolException) | `relay/protocol/FrameCodec` |
| 프레임 크기 상한(`MAX_FRAME_BYTES`)·프롬프트 길이 상한(`MAX_PROMPT_CHARS`) | `relay/protocol` |
| 인증 강제(첫 프레임=auth, 타임아웃) | `relay/RelayWebSocketHandler` |
| 옵션 화이트리스트 | `relay/protocol.filterOptions` |
| rate limit(분당 고정 윈도우) | `discord/RateLimiter` (ask 적용) |
| 권한 가드(관리자) | `discord/CommandService.adminOnly` |
| 역할/채널 정책(권한 상승 방지) | `policy/PolicyService` |
| provider 간 격리(providerId 키, 교차 접근 불가) | `relay/ConnectionRegistry` |
| 프롬프트/토큰 로그 미기록 | 전반(내용 최소 로깅) |

## 운영 전제
- TLS/`wss` 는 앞단 리버스 프록시(nginx/cloudflare 등)에서 종단한다.
- Discord 봇 토큰·DB 비밀번호·SECRET 은 환경변수로 주입하고 절대 커밋하지 않는다.
- 프로바이더 토큰은 발급 시 1회만 노출(DM), 해시만 저장된다.
