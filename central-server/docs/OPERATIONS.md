# 운영 가이드 — central-server (커뮤니티 Provider Pool)

## 배포
```bash
# 호스트(서버)에서:
cd central-server
cp .env.example .env        # DISCORD_BOT_TOKEN, DB_PASSWORD 채우기, DISCORD_ENABLED=true
docker compose up -d --build
curl -s localhost:8080/actuator/health   # {"status":"UP"} 확인
```
- 스키마는 Flyway 가 자동 적용. DB 는 compose 의 Postgres(볼륨 `pgdata`).
- 롤백: 이전 이미지 태그로 `docker compose up -d` (또는 `docs/ROLLBACK`).

## 관리자(서버 운영자) — Discord 슬래시 명령
| 명령 | 용도 |
|---|---|
| `/llm-allow-channel #ai-help` | LLM 사용 채널 허용 |
| `/llm-deny-channel #채널` | 채널 금지 |
| `/llm-role-policy @역할 level limit` | 역할별 허용 모델 수준·일일 한도 |
| `/providers` | 풀 상태·승인 대기·기여량 |
| `/provider-approve @유저` | 프로바이더 등록 승인(토큰 발급) |
| `/provider-remove @유저` | 풀에서 제거 |

## 프로바이더(기여 유저) 온보딩
1. Discord 에서 `/provider-join` → (수동 승인 서버면) 관리자가 `/provider-approve` → **토큰 발급**.
2. 자기 PC 에서 에이전트 실행:
   ```bash
   pip install -e provider-agent        # 또는 배포 실행파일
   discord-ai-provider-agent --token <발급토큰> \
       --relay-url ws://<서버>:8080/agent --model llama3.1:8b
   ```
3. 에이전트가 풀에 등록되면 그 서버의 `/ask` 일부가 이 PC 에서 처리된다.
4. `/provider-pause`·`/provider-resume`·`/provider-leave` 로 언제든 조절.

## 일반 유저
- `/ask <질문>` — 풀이 처리. `/models`·`/my-usage`·`/privacy`.
- **프라이버시**: 질문이 프로바이더 PC 로 전송될 수 있음(민감정보 금지 고지).

## 모니터링
- 헬스: `GET /actuator/health` (`providerPool.activeProviderConnections` 포함).
- 메트릭: `GET /actuator/metrics`.
- 로그: `docker compose logs -f central-server`.

## 보안 점검(기본)
- 토큰: 일회용·해시 저장·TTL. WS: outbound only, 프레임 화이트리스트·크기 상한.
- `CENTRAL_DEV_ENABLED` 는 운영에서 **반드시 false**(/dev/* 엔드포인트 차단). 자세히는 `SECURITY.md`.

## 대시보드 관리자 인증 — Discord OAuth2 (차수 14 #196/#197)
기본은 공개 읽기 경로를 보존하지만, `audience=admin` 조회와 AI Network 쓰기/민감 읽기 API 는 **관리자 접근**이 필요하다. OAuth 전환 전에는 `CENTRAL_DASHBOARD_ADMIN_TOKEN` 을 운영 `ENV_FILE` 에 넣고 요청에 `X-Dashboard-Admin-Token` 헤더를 붙인다. 운영에서 정식 관리자 인증을 켜려면:

1. Discord 개발자 포털에서 OAuth2 앱 생성 → Redirect URI `https://<호스트>/login/oauth2/code/discord`.
2. 환경변수 주입:
```bash
CENTRAL_OAUTH_ENABLED=true
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DISCORD_CLIENT_ID=<client-id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DISCORD_CLIENT_SECRET=<secret>   # 커밋 금지
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DISCORD_SCOPE=identify
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DISCORD_AUTHORIZATION_GRANT_TYPE=authorization_code
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DISCORD_REDIRECT_URI={baseUrl}/login/oauth2/code/discord
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DISCORD_AUTHORIZATION_URI=https://discord.com/oauth2/authorize
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DISCORD_TOKEN_URI=https://discord.com/api/oauth2/token
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DISCORD_USER_INFO_URI=https://discord.com/api/users/@me
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DISCORD_USER_NAME_ATTRIBUTE=id
```
3. 활성 시: 정적 대시보드·헬스·메트릭·에이전트 WS·로그인은 공개, **대시보드 데이터/쓰기 API 는 인증 필요**(세션 #197).
4. 공개 프리셋 카탈로그(`/api/ai-network/presets/catalog*`)와 최소 공개 액션(좋아요/신고)은 계속 공개된다.
5. 쓰기 API(#203/#204)는 `central.oauth.enabled=true` 일 때만 노출(`DashboardWriteController`).

> 구현: `web/SecurityConfig.kt`(기본 permitAll / 활성 시 oauth2Login). 길드 관리자 권한 매핑은 후속(현재 인증=접근).

## 슬래시 명령 등록 전략 (차수 13 #185)
- **현재: 글로벌 등록**(`jda.updateCommands()`). 한 번 등록하면 봇이 있는 모든 서버에 노출.
  - 장점: 운영 단순(서버별 등록 불필요). 단점: 변경 전파에 최대 ~1시간 캐시 지연 가능.
- **개발/베타 권장: 길드 등록**(`guild.updateCommands()`)으로 전환하면 **즉시 반영**되어 반복이 빠름.
  - 베타는 특정 길드에만 초대하므로 길드 등록이 유리. 운영 확장 시 글로벌로 승격.
- **권한 노출**: 관리자 명령은 `DefaultMemberPermissions(MANAGE_SERVER)` 로 비관리자 UI 에서 숨김(#186).
- **드리프트 가드**: 등록↔디스패치 일치를 `CommandRegistrationDriftTest` 가 강제(#193).
