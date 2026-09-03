# 운영 가이드 — central-server (커뮤니티 Provider Pool)

## 배포
운영 배포의 SSOT는 [DEPLOYMENT.md](DEPLOYMENT.md) / [DEPLOY_REMOTE.md](DEPLOY_REMOTE.md)이다.
현재 운영 호스트는 `ssh.yeon.world`, 공개 주소는 `https://discord-ai.yeon.world`,
컨테이너 포트는 `127.0.0.1:8085`다.

```bash
# 운영 호스트에서:
cd /srv/central-server
docker compose ps
./ops_runtime_secret_audit.sh
EXTERNAL_BASE_URL=https://discord-ai.yeon.world ./ops_healthcheck.sh
DISCORD_GUILD_ID=all ./ops_policy_audit.sh
```
- 스키마는 Flyway 가 자동 적용. DB 는 compose 의 Postgres(볼륨 `pgdata`).
- 롤백: 이전 이미지 태그로 `docker compose up -d` (또는 `docs/ROLLBACK`).

## 관리자(서버 운영자)
- Discord 에서는 `/settings` 또는 `/menu` → 관리자 버튼으로 웹 대시보드에 들어간다.
- 채널 허용, 역할 정책, 자동승인, 니아 자동 채널 생성은 웹 대시보드/버튼 UI가 현재 운영 표면이다.
- 자동응답 채널에서 `이 채널에서는 LLM 을 사용할 수 없습니다.`가 나오면 [RUNBOOK.md](RUNBOOK.md)의
  `ops_policy_audit.sh` 절차로 `channel_ai.auto_respond`와 `allowed_channel` 불일치를 확인한다.

## 프로바이더(기여 유저) 온보딩
1. Discord 에서 `/provider-join` → (수동 승인 서버면) 관리자가 `/settings` 웹 대시보드에서 승인 → **토큰 발급**.
2. 자기 PC 에서 에이전트 실행:
   ```bash
   pip install -e provider-agent        # 또는 배포 실행파일
   nexa --token <발급토큰> \
       --relay-url wss://discord-ai.yeon.world/agent --model exaone3.5:7.8b
   ```
3. 에이전트가 풀에 등록되면 그 서버의 `/ask` 일부가 이 PC 에서 처리된다.
4. 데스크톱 앱에서 일시정지·재개·한도·시간대를 언제든 조절.

## 일반 유저
- `/ask <질문>` — 풀이 처리. `/my-usage`·`/privacy` 로 내 사용량과 처리 안내를 확인.
- **프라이버시**: 질문이 프로바이더 PC 로 전송될 수 있음(민감정보 금지 고지).

## 모니터링
- 헬스: `GET /actuator/health`.
- 풀 요약: `GET /api/metrics/pool` (`activeProviders`, `inFlightTotal`, `guildPoolSizes`).
- Prometheus: `GET /actuator/prometheus`.
- 로그: `docker compose logs -f central-server`.
- 운영 정기 감사: GitHub Actions `central ops audit`가 6시간마다 runtime-secret/health/policy를 읽기 전용으로 확인한다.

## 보안 점검(기본)
- 토큰: 일회용·해시 저장·TTL. WS: outbound only, 프레임 화이트리스트·크기 상한.
- `CENTRAL_DEV_ENABLED` 는 운영에서 **반드시 false**(/dev/* 엔드포인트 차단). 자세히는 `SECURITY.md`.

## 대시보드 관리자 인증 — Discord OAuth2 (차수 14 #196/#197)
기본은 공개 읽기 경로를 보존하지만, `audience=admin` 조회와 AI Network 쓰기/민감 읽기 API 는 **관리자 접근**이 필요하다. 현재 운영은 평문 env 슬롯을 두지 않고 Discord OAuth만 사용한다. `CENTRAL_DASHBOARD_ADMIN_TOKEN` 방식은 로컬/스테이징 전용이다. 운영에서 정식 관리자 인증을 켜려면:

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
