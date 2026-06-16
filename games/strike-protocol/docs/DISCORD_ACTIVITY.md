# Strike Protocol — Discord Activity (Embedded App) 실행 가이드

이 문서는 `games/strike-protocol` (TanStack Start · three.js FPS)을 **Discord Activity**
(음성 채널 안에서 iframe 으로 실행되는 임베드 앱)로 띄우는 **실전 단계별 방법**이다.

코드(SDK 부트스트랩 · 토큰 교환 서버 라우트 · 프록시 분기)는 **이미 구현되어 있다.**
남은 일은 전부 **너(유저)가 Discord 개발자 포털과 호스팅/시크릿을 설정**하는 작업이다.

> **역할 정리**
> - **코드 = 완료됨** (이 저장소).
> - **포털 설정 · HTTPS 호스팅 · 시크릿 입력 = 유저(너)** — 아래 체크리스트.

---

## 0. 같은 앱: NEXA 봇 = 이 Activity

이 게임은 **NEXA 봇과 동일한 Discord 애플리케이션**에 Activity 로 등록한다(하나의 앱이
봇 + 액티비티를 겸한다). 새 앱을 만들지 말고 **이미 있는 NEXA 앱**을 쓴다.

| 이 게임의 키 | = NEXA 앱의 무엇 (central-server) |
|---|---|
| `VITE_DISCORD_CLIENT_ID` (클라, 공개) | `CONNECT_DISCORD_CLIENT_ID` |
| `DISCORD_CLIENT_ID` (서버) | `CONNECT_DISCORD_CLIENT_ID` (동일 값) |
| `DISCORD_CLIENT_SECRET` (서버, 비밀) | `CONNECT_DISCORD_CLIENT_SECRET` |

즉 central-server 가 provider-connect OAuth 에 쓰는 그 자격을 그대로 쓰면 된다.

---

## 1. 동작 개요 (왜 이렇게 하나)

Discord Activity 는 `https://<APP_ID>.discordsays.com/` 아래의 iframe 으로 게임을 띄우고,
**바깥으로 나가는 모든 요청을 이 도메인으로 프록시**한다. 즉 게임 클라가 직접 외부 주소를
부를 수 없고, **Discord 가 중계**한다.

- 외부 호스트는 포털의 **URL Mappings** 에 `prefix → host` 로 등록한다.
- 클라는 그 prefix 를 **`/.proxy/<prefix>/...`** 형태로 호출하면 Discord 가 매핑된
  실제 호스트로 전달한다.

이 게임의 매핑 약속(코드에 박혀 있음, `src/discord/proxy.ts`):

| 용도 | URL Mapping (포털) | 클라가 호출하는 경로(코드) |
|---|---|---|
| 게임 클라 + SSR 서버 | `/` → (TanStack Start 호스트) | (루트) |
| 토큰 교환 라우트(HTTP) | `/api` → (위와 동일 호스트) | `/.proxy/api/token` |

> **Colyseus 같은 별도 게임 서버가 없다.** 멀티플레이는 **WebRTC P2P(peerjs)** 라
> 자체 게임 서버 호스트나 `/colyseus` 매핑이 필요 없다(crazy-nia 와의 유일한 차이).
> 토큰 라우트는 게임을 서빙하는 **TanStack Start 서버 자체**(`POST /api/token`)이므로
> `/` 와 `/api` 의 host 는 동일하다.

**Discord 밖**(일반 브라우저)에서는 `frame_id` 쿼리스트링이 없어 부트스트랩이 **no-op**
으로 통과하고, 게임은 기존과 똑같이 로드된다 — 로컬 개발/배포 경험은 전혀 바뀌지 않는다.

---

## 2. Discord 개발자 포털 설정 (유저)

1. <https://discord.com/developers/applications> → **NEXA 앱 선택**(새로 만들지 말 것).
2. 좌측 **OAuth2** → **Client ID** 와 **Client Secret** 확인.
   - **Client Secret 은 절대 노출/커밋 금지** — 서버 env 에만 넣는다(4단계).
3. 좌측 **Activities**(없으면 **App Settings → Activities**) →
   **Enable Activities / Embedded App SDK** 활성화.
4. **Activities → URL Mappings** 에 아래를 등록:
   | Prefix | Target (Host) |
   |---|---|
   | `/` | 게임(TanStack Start)이 서빙되는 HTTPS 호스트 (예: `xxxx.trycloudflare.com`) |
   | `/api` | 위와 동일 호스트 (토큰 라우트가 같은 서버에 있음) |
   - 호스트는 **HTTPS** 여야 한다(3단계 터널/호스팅).
   - prefix 이름(`/api`)은 코드와 **반드시 일치**해야 한다(위 표 / `proxy.ts`).
5. **OAuth2 → Scopes**: Activity 는 런타임에 `identify`, `guilds` 스코프를 요청한다
   (코드가 `authorize` 시 자동 요청). 앱에 OAuth2 가 켜져 있으면 된다.

---

## 3. HTTPS 호스팅 / 개발 터널 (유저)

Discord 는 HTTPS 호스트만 프록시한다. 로컬 개발 중이면 `localhost` 를 **HTTPS 터널**로
노출해 그 주소를 URL Mappings 에 넣는다. 클라와 토큰 라우트가 **한 서버**이므로 터널은
**하나면 충분**하다(crazy-nia 는 클라/서버 2개였음).

### cloudflared (권장, 무가입)

```bash
cd games/strike-protocol
DISCORD_DEV=1 npm run dev          # TanStack Start dev (HMR 를 프록시 443 으로) → http://localhost:5173

# 다른 터미널에서 한 포트만 터널로 노출
cloudflared tunnel --url http://localhost:5173   # → https://xxxx.trycloudflare.com
```

- `xxxx...trycloudflare.com` → URL Mapping `/` 와 `/api` **둘 다** (같은 호스트).

> `vite.config.ts` 의 `allowedHosts` 에 `.trycloudflare.com`, `.ngrok-free.app`,
> `.ngrok.io`, `.discordsays.com` 가 이미 허용돼 있다. 다른 터널 도메인을 쓰면 거기에
> 추가하라. **로컬 HMR 을 프록시로 쓸 때만** `DISCORD_DEV=1` 을 켠다(평소 로컬은 끈다
> — 안 끄면 HMR 이 443 으로 붙으려다 콘솔 에러).

### ngrok

```bash
ngrok http 5173
```

### 프로덕션

`npm run build` 는 Cloudflare Worker 서버 번들(`dist/server/server.js`) + 정적 클라
(`dist/client`)를 낸다. 이를 HTTPS 호스트(예: Cloudflare Workers)에 배포하고, 그 한
주소를 URL Mappings 의 `/` 와 `/api` 에 등록하면 된다. 서버 env 에
`DISCORD_CLIENT_ID`/`DISCORD_CLIENT_SECRET` 를, 빌드 시 `VITE_DISCORD_CLIENT_ID` 를 준다.

---

## 4. 환경변수 (유저)

`.env.example` 를 `.env` 로 복사해 채운다(`.env` 는 git 에 안 올라간다). 값은 전부
**NEXA 앱**의 자격(= central `CONNECT_DISCORD_CLIENT_*`)이다:

```bash
# PUBLIC — 클라에 박힘(VITE_), secret 아님
VITE_DISCORD_CLIENT_ID=<NEXA app Client ID>

# SERVER-ONLY — /api/token 라우트만 사용, 브라우저로 절대 안 감
DISCORD_CLIENT_ID=<NEXA app Client ID>          # 위와 동일 값
DISCORD_CLIENT_SECRET=<NEXA app Client Secret>  # 비밀, 절대 노출/커밋 금지
```

`npm run dev` / `npm run build` 는 같은 디렉터리의 `.env` 를 Vite 가 자동 로드한다.
`VITE_*` 는 클라 번들에, 나머지는 서버(`process.env`)에만 들어간다.

> **Client Secret 은 클라에 절대 넣지 않는다.** 클라는 Client ID(공개)만 안다. Secret 은
> 서버 `/api/token` 핸들러(`src/routes/api/token.ts`)가 토큰 교환할 때만 `process.env`
> 에서 읽는다.

---

## 5. Discord 안에서 실행 (유저)

1. 위 2~4 설정을 마친다.
2. Discord 데스크톱/웹에서 **음성 채널**에 입장한다.
3. 채널 하단의 **로켓 아이콘(🚀, Activities)** 클릭 → 목록에서 NEXA 앱을 선택
   (개발 중 앱은 테스트 길드에 보이거나, 앱 설정에서 테스터로 추가해야 보일 수 있다).
4. Activity 가 iframe 으로 뜨면서 게임이 로드된다. 부트스트랩이 인증을 마치면
   **콜사인(CALLSIGN)** 이 Discord 표시명으로 자동 채워진다. 친구끼리 같이 하려면
   메뉴의 **INVITE LINK / ROOM CODE** 를 공유한다(WebRTC P2P — 같은 룸 코드면 같은 경기).

---

## 6. 동작 점검 체크리스트

- [ ] 포털: **NEXA 앱**에서 Activities/Embedded App SDK 활성화 (새 앱 X)
- [ ] 포털: URL Mappings `/`·`/api` 등록 (HTTPS host, 둘이 같은 호스트)
- [ ] 터널/호스팅: dev 서버(5173) HTTPS 노출 (터널 1개)
- [ ] `.env`: `VITE_DISCORD_CLIENT_ID` + `DISCORD_CLIENT_ID` + `DISCORD_CLIENT_SECRET`
      (= NEXA 앱 / central `CONNECT_DISCORD_CLIENT_*`)
- [ ] 음성 채널 → 🚀 → NEXA 앱 실행 → 게임 로드 + 콜사인 자동 채워짐 확인

---

## 7. 문제 해결

| 증상 | 원인/해결 |
|---|---|
| iframe 이 안 뜸 / 흰 화면 | URL Mapping `/` 호스트가 HTTPS 인지, `allowedHosts` 에 터널 도메인이 있는지 확인 |
| "VITE_DISCORD_CLIENT_ID 가 설정되지 않았습니다" | `.env` 에 `VITE_DISCORD_CLIENT_ID` 를 넣고 dev/build 재실행 |
| "토큰 교환 실패 (5xx)" | 서버 env 의 `DISCORD_CLIENT_ID`/`DISCORD_CLIENT_SECRET`, URL Mapping `/api` host 확인 |
| 콜사인이 안 채워짐 | 인증 실패는 메뉴를 막지 않는다(콘솔의 `discord bootstrap failed` 확인) — 토큰 라우트/시크릿 점검 |
| 로컬 개발이 깨짐 | `DISCORD_DEV` 를 끄고(평소 로컬), `frame_id` 없는 환경에서 부트스트랩은 no-op |

---

## 관련 파일

- `src/discord/proxy.ts` — in-Discord 판정 · 토큰 엔드포인트 분기 (순수, 단위테스트 `proxy.test.ts`)
- `src/discord/token.ts` — Discord OAuth 토큰 교환 로직 (순수, 단위테스트 `token.test.ts`)
- `src/discord/bootstrap.ts` — SDK ready/authorize/authenticate + 세션 도출 (밖이면 no-op)
- `src/routes/api/token.ts` — TanStack Start 서버 라우트 `POST /api/token` (시크릿은 `process.env`)
- `src/routes/play.tsx` — 게임 진입 시 `bootstrapDiscord()` 호출(밖이면 통과, 안이면 콜사인 prefill)
- `vite.config.ts` — `server.allowedHosts`(터널/discordsays) + `DISCORD_DEV` HMR 포트
- `.env.example` — 키 이름(값 없음)
