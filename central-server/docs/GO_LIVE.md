# central-server 라이브 배포 가이드 (Discord 봇 띄우기)

기존 Python 봇과 별개로 **central-server(Provider Pool) 봇**을 같은(또는 별도) Discord 서버에
띄워 `/ask` 라우팅과 인터랙티브 기능을 라이브로 검증한다.

## 0. 준비물
- 이 머신에 JDK 21 + Docker(이미 검증됨), Ollama(테스트용은 mock 가능).
- 인터넷에 프로바이더 PC 가 붙으려면 central 호스트가 **외부에서 접근 가능**해야 한다
  (LAN 테스트면 LAN IP, 외부면 공인 IP/도메인 + 가급적 `wss://`).

## 1. Discord 봇 애플리케이션 생성
1. https://discord.com/developers/applications → **New Application**.
2. 좌측 **Bot** → **Reset Token** → 토큰 복사(절대 커밋 금지).
3. **Installation**(또는 OAuth2 → URL Generator):
   - Scopes: `bot`, `applications.commands`
   - Bot Permissions: `Send Messages`, `Use Slash Commands`(Embed Links 권장)
4. 생성된 초대 URL 로 **봇을 서버에 초대**.

> 기존 Python 봇과 **다른 애플리케이션/토큰**을 쓰면 두 봇이 한 서버에 공존 가능.
> central 만 쓸 거면 기존 봇은 서버에서 제거하면 된다.

## 2. central-server 기동 (봇 ON)
`central-server/` 에서:
```bash
# 운영 권장: docker compose (Postgres+서버)
DISCORD_ENABLED=true \
DISCORD_BOT_TOKEN='붙여넣기' \
CENTRAL_DEV_ENABLED=false \
docker compose up -d --build

# 헬스 확인
curl -s localhost:8085/actuator/health   # {"status":"UP"}
```
로그에 `Discord(JDA) 기동 완료` 가 보이면 봇 온라인.

**슬래시 명령 즉시 반영(권장, 단일/테스트 서버):** `DISCORD_GUILD_ID` 를 주면 해당 서버에 명령을
**즉시 등록**한다(글로벌은 전파에 최대 ~1h). 서버 ID 는 Discord 개발자 모드 켠 뒤 서버명 우클릭
→ "서버 ID 복사".
```bash
DISCORD_ENABLED=true DISCORD_BOT_TOKEN='토큰' DISCORD_GUILD_ID='서버ID' CENTRAL_DEV_ENABLED=false \
  docker compose up -d --build
```
> 길드 ID 미설정 시 글로벌 등록(여러 서버 배포용, 전파 지연 있음).

## 3. 프로바이더 온보딩 (내 PC LLM 을 풀에 연결)
유저(프로바이더)가 자기 PC 에서:
1. Discord 에서 `/provider-join` → (자동승인 OFF면 관리자 `/provider-approve @유저`) → **1회용 토큰** 수령.
2. 그 PC 에서 Ollama 실행 + 모델:
   ```bash
   ollama serve &
   ollama pull llama3.1:8b      # 원하는 모델
   ```
3. 에이전트 실행:
   ```bash
   pip install -e provider-agent            # 또는 배포물/Docker(packaging/README.md)
discord-ai-provider-agent --token <토큰> --relay-url wss://discord-ai.yeon.world/agent
   ```
   → 풀에 등록되면 `/providers`(관리자)·`/catalog` 에 나타난다.

## 4. 라이브 검증 체크리스트
**첫 사용자는 `/menu` 하나만 기억하면 됩니다** (버튼/드롭다운으로 전부 처리):
- **`/menu`** → 시작 패널: `💬질문하기`(모달)·`🖥️내 PC 기여`·`📊내 상태`·`❓도움말`·(관리자)`⚙️설정`
- 봇이 서버에 처음 들어오면 시스템 채널에 **자동 온보딩 패널** 게시(클릭만으로 시작).
- `⚙️설정`(관리자) → **드롭다운**으로 언어·기본모델·허용채널 + **버튼** 자동승인 토글 → 즉시 적용.

명령으로도 가능(슬래시 입력창에 `/` 치면 설명 자동 표시):
- `/ask 안녕` → 풀 라우팅 → 응답(끝에 프라이버시 고지). ✅ 핵심
- `/help` `/models` `/catalog` `/community-stats`, `/provider-join`.
- `/ask-long` → 모달, 메시지 우클릭 → 앱 → `AI에게 질문`(컨텍스트 메뉴).
- `/provider-approve @유저` → 대상에게 토큰 DM, `/fairness` `/provider-schedule`(관리자).

## 5. 알아둘 점
- **리액션 만족도(#171)**: `GUILD_MESSAGE_REACTIONS` 인텐트를 코드에서 활성화해 👍/👎 수집이
  바로 동작한다. Developer Portal 의 Privileged Intents 토글은 불필요(이 인텐트는 비특권).
  나머지 인터랙션(슬래시/버튼/모달/컨텍스트 메뉴/DM)도 추가 설정 없이 동작.
- **운영 보안**: `CENTRAL_DEV_ENABLED=false`(/dev/* 차단), 토큰은 env 로만 주입.
- **관리자 인증 대시보드**(선택): `CENTRAL_OAUTH_ENABLED=true` + Discord OAuth2(OPERATIONS.md).
- **로그**: `docker compose logs -f central-server`.
