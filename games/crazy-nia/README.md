# Crazy Nia — Bomberman Multiplayer (P1)

크레이지 아케이드 / 봄버맨 스타일 2D 게임. **P1 단계: 실시간 멀티플레이어**.
[Colyseus](https://colyseus.io) **권위 서버(authoritative server)** 가 게임 상태를
소유·시뮬레이션하고, 브라우저 클라이언트는 **입력만 전송**하고 **서버 상태를 렌더**한다
(클라 단독 시뮬 없음 → 동기화 일관성·치트 방지). 여러 명이 같은 방에 접속해 함께 플레이한다.

기존 `central-server` / `provider-agent` 빌드와 **완전히 분리**된 독립 서브프로젝트다
(이 폴더의 `package.json`·`server/package.json` 만 사용, 루트 CI/빌드 설정은 건드리지 않음).

## 구조

```
games/crazy-nia/
  shared/                공유 모듈 (서버·클라 양쪽 import, Phaser/Node 의존 없음)
    constants.ts         타일·맵 크기·타이머·시작셀 등 밸런스 상수 + 순수 맵 생성/좌표 로직 (SSOT)
    messages.ts          클라→서버 메시지 계약 (input / placeBomb)
  server/                권위 게임 서버 (Node + TypeScript)
    src/index.ts         Colyseus 서버 부트스트랩 (ws://localhost:2567, /health)
    src/CrazyRoom.ts     방 + 틱 루프(30Hz): 입력 적용·이동·폭탄·폭발·연쇄·아이템·승패·라운드 리셋
    src/state.ts         @colyseus/schema 동기 상태 (grid/players/bombs/explosions/items)
  src/                   클라이언트 (Vite + Phaser 3, 렌더 전용)
    net/NetClient.ts     colyseus.js 룸 접속 + 입력 전송 (서버와 대화하는 유일한 곳)
    scenes/GameScene.ts  서버 state 구독 → 격자/플레이어/폭탄/폭발/아이템 렌더 (위치 보간)
    config.ts            shared 상수 재노출 + 로컬 키바인딩 + 서버 주소(env)
    ui/Hud.ts            상단 상태바 (동적 플레이어 수)
    systems/textures.ts  폭탄·화염·아이템 코드 생성 텍스처
```

## 실행

```bash
cd games/crazy-nia
npm install                   # 클라 의존성
npm install --prefix server   # 서버 의존성 (최초 1회)

npm run dev:all          # 서버 + 클라 동시 기동 (concurrently)
#  ↑ 또는 따로:
npm run server           # 권위 게임 서버 (ws://localhost:2567)
npm run dev              # 클라 개발 서버 (기본 http://127.0.0.1:5173)

npm run build                  # 클라 타입체크 + 프로덕션 번들(dist/)
npm run --prefix server build  # 서버 타입체크/빌드
```

서버 주소는 `VITE_GAME_SERVER_URL` 로 바꿀 수 있다(기본 `ws://localhost:2567`):

```bash
VITE_GAME_SERVER_URL=ws://192.168.0.10:2567 npm run dev
```

## 멀티 플레이 테스트 (브라우저 2탭)

1. `npm run server` 로 게임 서버를 켠다.
2. `npm run dev` 로 클라 서버를 켜고, 같은 URL 을 **두 개의 탭(또는 두 기기)** 에서 연다.
3. 둘 다 같은 방(`crazy`)에 자동 접속한다. 한 탭에서 이동/폭탄을 하면 **다른 탭에서도
   즉시 반영**된다(서버 권위 상태 동기). 최대 4인.

### 멀티 동기 스모크 (헤드리스)

```bash
npm run server     # 터미널 1: 게임 서버
npm run dev        # 터미널 2: 클라 서버
node smoke-mp.mjs  # 터미널 3: 두 세션 접속 → A 이동/폭탄 → B 에서 동기·블록파괴 확인
                   #            (콘솔 에러 0 이어야 PASS)
```

`smoke-mp.mjs` 는 두 브라우저 컨텍스트로 같은 방에 접속해, ① 서로를 본다(2인) ②
세션 A 의 이동이 세션 B 의 동기 상태에 반영된다 ③ 폭탄으로 부순 블록이 **양쪽 모두**에서
동일하게 줄어든다 를 검증한다. `SMOKE_SHOT=shot.png` 로 스크린샷 저장.

> 단일 클라 + 게임 상태 스모크(`smoke.mjs`)는 서버가 켜진 상태에서 한 세션의 접속/렌더/
> 입력 반영을 확인한다.

## 동기화 방식 (@colyseus/schema)

서버의 `GameState`(`server/src/state.ts`)는 `@colyseus/schema` 로 정의되어, 변경분(델타)
만 자동으로 모든 클라이언트에 전송된다.

- `grid`: row-major `uint8` 배열(CellKind). 블록 파괴 시 해당 인덱스가 floor 로 바뀌어 동기.
- `players`: 맵(세션ID→상태). 격자 위치(col/row) + 보간용 픽셀 위치(px/py) + 능력치/생존.
- `bombs` / `explosions` / `items`: 배열. 서버가 추가/제거하면 클라가 스프라이트를 맞춘다.
- `status` / `roundOver`: 라운드 상태 텍스트·플래그.

클라(`GameScene`)는 매 프레임 현재 동기 상태를 읽어 스프라이트를 add/remove 하고, 플레이어
픽셀 위치는 서버 `px/py` 로 **선형 보간**해 부드럽게 그린다. **게임 로직은 0 — 전부 서버 소유.**

## 게임 메커니즘 (서버 권위)

- **격자 맵 13×11**: 외곽 고정벽 + 짝수 격자 고정 기둥 + 무작위 파괴블록. 시작 코너·인접칸은 비움.
- **격자 단위 이동**: 서버가 방향키 입력을 받아 틱마다 한 칸씩 이동(속도 아이템으로 가속).
- **폭탄**: 설치 ~2.2초 뒤 폭발. 십자(+) 사거리, 파괴블록 1칸 파괴 후 정지, 연쇄 폭발.
- **사망**: 폭발 화염 칸의 플레이어 사망. **아이템**: 블록 파괴 시 확률 드롭(폭탄/사거리/속도+1).
- **승패**: 2인 이상에서 마지막 생존자 승리 / 전멸 무승부 → 약 4초 후 자동 새 라운드.

## 조작법

| 이동 | 폭탄 |
|---|---|
| 방향키(↑↓←→) 또는 WASD | Space 또는 Shift |

각 브라우저는 **자기 캐릭터 하나만** 조작한다(나머지는 서버가 다른 접속자로 관리).

## 기술 스택

- **서버**: Colyseus (`@colyseus/core` + `@colyseus/schema` + `@colyseus/ws-transport`), Express(`/health`), tsx.
- **클라**: Vite + Phaser 3 + `colyseus.js`, TypeScript(strict).

## 에셋

`ASSETS.md` 참조. 바닥/벽/블록/플레이어는 Kenney Sokoban CC0, 폭탄/화염/아이템은 코드 생성.

## 로드맵

- **P0**: 로컬 2인 봄버맨 코어 — 브라우저 단독 플레이. ✅
- **P1**: 멀티플레이어 — Colyseus 권위 서버 상태 동기화. ✅
- **P2 (현재)**: Discord Activity (Embedded App SDK) 로 디스코드 보이스 채널 내 실행. ✅
  - `@discord/embedded-app-sdk` 부트스트랩(Discord 밖이면 no-op), 서버 `/api/token` OAuth
    토큰 교환(client_secret 서버 전용), `/.proxy/...` 프록시 경로 분기, Activity 인스턴스 =
    Colyseus 룸 매핑(`filterBy(['instanceId'])`). 포털/호스팅/시크릿 설정은
    **[`docs/DISCORD_ACTIVITY.md`](docs/DISCORD_ACTIVITY.md)** 참조.
