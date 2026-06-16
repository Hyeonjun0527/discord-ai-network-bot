# Crazy Nia — Discord Activity (Embedded App) 실행 가이드

이 문서는 `games/crazy-nia` 멀티플레이 게임을 **Discord Activity**(음성 채널 안에서
iframe 으로 실행되는 임베드 앱)로 띄우는 **실전 단계별 방법**이다.

코드(SDK 부트스트랩 · 토큰 교환 백엔드 · 프록시 분기 · 룸 매핑)는 **이미 구현되어 있다.**
남은 일은 전부 **너(유저)가 Discord 개발자 포털과 호스팅/시크릿을 설정**하는 작업이다.

> **역할 정리**
> - **코드 = 완료됨** (이 저장소).
> - **포털 설정 · HTTPS 호스팅 · 시크릿 입력 = 유저(너)** — 아래 체크리스트.

---

## 0. 동작 개요 (왜 이렇게 하나)

Discord Activity 는 `https://<APP_ID>.discordsays.com/` 아래의 iframe 으로 게임을 띄우고,
**바깥으로 나가는 모든 요청을 이 도메인으로 프록시**한다. 즉 게임 클라가 직접 외부
주소(`ws://localhost:2567`, 토큰 서버 등)를 부를 수 없고, **Discord 가 중계**한다.

- 외부 호스트는 포털의 **URL Mappings** 에 `prefix → host` 로 등록한다.
- 클라는 그 prefix 를 **`/.proxy/<prefix>/...`** 형태로 호출하면 Discord 가 매핑된
  실제 호스트로 전달한다.

이 게임의 매핑 약속(코드에 박혀 있음, `src/discord/proxy.ts`):

| 용도 | URL Mapping (포털) | 클라가 호출하는 경로(코드) |
|---|---|---|
| 게임 클라 정적 호스팅 | `/` → (Vite 빌드/터널 호스트) | (루트) |
| Colyseus 게임 서버(WS) | `/colyseus` → (게임 서버 호스트:2567) | `wss://<host>/.proxy/colyseus` |
| 토큰 교환 백엔드(HTTP) | `/api` → (게임 서버 호스트:2567) | `/.proxy/api/token` |

> 토큰 서버와 Colyseus 서버는 **같은 프로세스**(`server/`)다 — 포트 하나(기본 2567)에
> `/api/token`(HTTP) 과 WebSocket 이 함께 떠 있으므로 `/colyseus` 와 `/api` 매핑의
> host 는 동일하다.

**Discord 밖**(일반 브라우저/로컬 멀티)에서는 `frame_id` 쿼리스트링이 없어 부트스트랩이
**no-op** 으로 통과하고, 클라는 기존처럼 `ws://localhost:2567` 에 직접 붙는다 — 로컬 개발
경험은 전혀 바뀌지 않는다.

---

## 1. Discord 개발자 포털 설정 (유저)

1. <https://discord.com/developers/applications> → **New Application** (또는 기존 앱 선택).
2. 좌측 **OAuth2** → **Client ID** 와 **Client Secret** 확인.
   - **Client Secret 은 절대 노출/커밋 금지** — 서버 env 에만 넣는다(3단계).
3. 좌측 **Activities**(없으면 **App Settings → Activities**) →
   **Enable Activities / Embedded App SDK** 활성화.
4. **Activities → URL Mappings** 에 아래를 등록:
   | Prefix | Target (Host) |
   |---|---|
   | `/` | 게임 클라(Vite) 가 서빙되는 HTTPS 호스트 (예: `crazy-nia.터널.trycloudflare.com`) |
   | `/colyseus` | 게임 서버 호스트 (예: `game.터널.trycloudflare.com`) |
   | `/api` | 게임 서버 호스트 (위와 동일) |
   - 호스트는 **HTTPS** 여야 한다(2단계 터널/호스팅).
   - prefix 이름(`/colyseus`, `/api`)은 코드와 **반드시 일치**해야 한다(위 표/`proxy.ts`).
5. **OAuth2 → Scopes**: Activity 는 런타임에 `identify`, `guilds` 스코프를 요청한다
   (코드가 `authorize` 시 자동 요청). 포털에서 별도 redirect URL 등록은 Activity 흐름엔
   불필요하지만, 앱에 OAuth2 가 켜져 있어야 한다.

---

## 2. HTTPS 호스팅 / 개발 터널 (유저)

Discord 는 HTTPS 호스트만 프록시한다. 로컬 개발 중이면 `localhost` 를 **HTTPS 터널**로
노출해 그 주소를 URL Mappings 에 넣는다.

### cloudflared (권장, 무가입)

```bash
# 1) 게임 서버 + 클라 dev 동시 기동 (별도 터미널 가능)
cd games/crazy-nia
DISCORD_DEV=1 npm run dev        # 클라 dev (HMR 를 프록시 443 으로) → http://127.0.0.1:5173
npm run server                   # 게임 서버(HTTP+WS) → http://localhost:2567

# 2) 두 포트를 각각 터널로 노출
cloudflared tunnel --url http://127.0.0.1:5173   # → https://xxxx.trycloudflare.com  (클라)
cloudflared tunnel --url http://localhost:2567   # → https://yyyy.trycloudflare.com  (서버)
```

- `xxxx...trycloudflare.com` → URL Mapping `/`
- `yyyy...trycloudflare.com` → URL Mapping `/colyseus` 와 `/api`

> `vite.config.ts` 의 `allowedHosts` 에 `.trycloudflare.com`, `.ngrok-free.app`,
> `.ngrok.io`, `.discordsays.com` 가 이미 허용돼 있다. 다른 터널 도메인을 쓰면 거기에
> 추가하라. **로컬 HMR 을 프록시로 쓸 때만** `DISCORD_DEV=1` 을 켠다(평소 로컬/스모크는
> 끈다 — 안 끄면 HMR 이 443 으로 붙으려다 콘솔 에러).

### ngrok

```bash
ngrok http 5173    # 클라
ngrok http 2567    # 서버
```

### 프로덕션

클라는 `npm run build` 산출물(`dist/`)을 임의의 HTTPS 정적 호스트에 올리고, 게임 서버는
`/api/token` + WebSocket 을 함께 서빙하는 HTTPS 호스트에 배포한 뒤, 두 주소를 URL
Mappings 에 등록하면 된다.

---

## 3. 환경변수 (유저)

### 게임 서버 (`games/crazy-nia/server/.env`)

`server/.env.example` 를 복사해 채운다(`.env` 는 git 에 안 올라간다):

```bash
DISCORD_CLIENT_ID=<앱 Client ID>
DISCORD_CLIENT_SECRET=<앱 Client Secret>   # 서버 전용 비밀, 절대 노출/커밋 금지
# PORT=2567
```

서버 `start`/`dev` 는 `--env-file-if-exists=.env` 로 이 파일을 자동 로드한다(없으면 그냥
통과 — 로컬 멀티는 키 없이도 동작).

### 게임 클라 (빌드/실행 시)

`VITE_DISCORD_CLIENT_ID` 로 **Client ID**(공개값, secret 아님)를 주입한다:

```bash
VITE_DISCORD_CLIENT_ID=<앱 Client ID> npm run build   # 또는 npm run dev
```

> **Client Secret 은 클라에 절대 넣지 않는다.** 클라는 Client ID(공개)만 안다. Secret 은
> 서버 `/api/token` 핸들러가 토큰 교환할 때만 쓴다.

---

## 4. Discord 안에서 실행 (유저)

1. 위 1~3 설정을 마친다.
2. Discord 데스크톱/웹에서 **음성 채널**에 입장한다.
3. 채널 하단의 **로켓 아이콘(🚀, Activities)** 클릭 → 목록에서 만든 앱을 선택
   (개발 중 앱은 테스트 길드에 보이거나, 앱 설정에서 테스터로 추가해야 보일 수 있다).
4. Activity 가 iframe 으로 뜨면서 게임이 로드된다. 같은 Activity 에 들어온 사람들은
   **같은 Colyseus 룸**에서 함께 플레이한다(아래 5번).

---

## 5. 룸 매핑 (같은 Activity = 같은 방)

- 클라 부트스트랩(`src/discord/bootstrap.ts`)이 `sdk.instanceId`(없으면 `channelId`)를
  Colyseus `joinOrCreate` 옵션 `instanceId` 로 넘긴다.
- 서버(`server/src/index.ts`)는 `gameServer.define('crazy', CrazyRoom).filterBy(['instanceId'])`
  로 **같은 `instanceId` 를 가진 접속자끼리 같은 방**에 매칭한다.
- Discord 밖(로컬/웹)에서는 `instanceId` 가 없어 모두 기본 방으로 매칭된다(기존 동작).
- 인증된 유저의 **표시명(global_name/username)** 은 join 옵션 `name` 으로 전달되어 서버
  `PlayerState.name` 에 동기화되고, 플레이어 라벨/HUD 에 `P1` 대신 표시된다.

---

## 6. 동작 점검 체크리스트

- [ ] 포털: Activities/Embedded App SDK 활성화
- [ ] 포털: URL Mappings `/`·`/colyseus`·`/api` 등록 (HTTPS host)
- [ ] 터널/호스팅: 클라(5173)·서버(2567) 둘 다 HTTPS 노출
- [ ] 서버 `.env`: `DISCORD_CLIENT_ID` + `DISCORD_CLIENT_SECRET`
- [ ] 클라 빌드/실행: `VITE_DISCORD_CLIENT_ID` 주입
- [ ] 음성 채널 → 🚀 → 앱 실행 → 게임 로드 + 같은 방에서 멀티 확인

---

## 7. 문제 해결

| 증상 | 원인/해결 |
|---|---|
| iframe 이 안 뜸 / 흰 화면 | URL Mapping `/` 호스트가 HTTPS 인지, `allowedHosts` 에 터널 도메인이 있는지 확인 |
| "VITE_DISCORD_CLIENT_ID 가 설정되지 않았습니다" | 클라를 `VITE_DISCORD_CLIENT_ID=...` 로 빌드/실행 |
| "토큰 교환 실패 (5xx)" | 서버 `.env` 의 CLIENT_ID/SECRET, URL Mapping `/api` host 확인 |
| 서로 다른 방에 들어감 | URL Mapping `/colyseus` host 가 토큰 서버와 동일한지, 서버 `filterBy(['instanceId'])` 동작 확인 |
| 로컬 개발이 깨짐 | `DISCORD_DEV` 를 끄고(평소 로컬), `frame_id` 없는 환경에서 부트스트랩은 no-op |
| WS 연결 실패 | Discord 는 WS 만 프록시한다. `wss://<host>/.proxy/colyseus` 로 가는지(코드 자동), host HTTPS 인지 확인 |

---

## 관련 파일

- `src/discord/proxy.ts` — in-Discord 판정 · 프록시 경로 · 룸 id 도출 (순수, 단위테스트 `proxy.test.ts`)
- `src/discord/bootstrap.ts` — SDK ready/authorize/authenticate + 세션 도출
- `src/main.ts` — 게임 시작 전 `bootstrapDiscord()` 호출(밖이면 통과)
- `server/src/token.ts` — Discord OAuth 토큰 교환 (단위테스트 `token.test.ts`)
- `server/src/index.ts` — `/api/token` 라우트 + `filterBy(['instanceId'])`
- `server/.env.example` — 서버 시크릿 키 이름
