# Crazy Nia — Bomberman Prototype (P0)

크레이지 아케이드 / 봄버맨 스타일 2D 게임의 **P0 프로토타입**. 브라우저에서 로컬
2인 대전을 즐길 수 있다. Discord 연동은 후속 단계이며, 이번 단계는 **게임성(코어
메커니즘)** 만 다룬다.

기존 `central-server` / `provider-agent` 빌드와 **완전히 분리**된 독립 서브프로젝트다
(이 폴더의 `package.json` 만 사용, 루트 CI/빌드 설정은 건드리지 않음).

## 실행

```bash
cd games/crazy-nia
npm install
npm run dev      # 개발 서버 (기본 http://127.0.0.1:5173)
npm run build    # 타입체크 + 프로덕션 번들(dist/)
npm run preview  # 빌드 결과 미리보기
```

### 스모크 테스트 (헤드리스)

```bash
npm run dev        # 한 터미널에서 dev 서버 기동
npm run smoke      # 다른 터미널에서: 캔버스 렌더 + 콘솔에러0 + 이동 + 폭탄 폭발로
                   #                  블록 파괴를 헤드리스로 검증 (Playwright)
```

`smoke.mjs` 는 `window.__crazyNia` 로 노출된 씬의 `debugSnapshot()` 을 읽어
실제 게임 상태(블록 수·플레이어 위치)를 확인한다. `SMOKE_SHOT=shot.png npm run smoke`
로 스크린샷도 저장할 수 있다.

## 조작법 (로컬 2인)

| | 이동 | 폭탄 |
|---|---|---|
| **P1** | 방향키 (↑↓←→) | Space |
| **P2** | W A S D | Shift |

라운드 재시작: **R**

## 게임 메커니즘 (구현됨)

- **격자 맵 13×11**: 외곽 고정벽 + 짝수 격자 고정 기둥 + 무작위 파괴블록 + 바닥.
  4개 시작 코너와 그 인접 칸은 비워둠.
- **격자 단위 이동**: 한 칸씩 트윈 이동. 벽·블록·폭탄은 통과 불가.
- **폭탄**: 설치 후 약 2.2초 뒤 폭발. 십자(+) 범위(사거리만큼), 파괴블록 1칸 파괴
  후 정지. 폭탄 동시 보유 개수는 능력치로 제한. 폭발 범위에 닿은 폭탄은 연쇄 폭발.
- **사망**: 폭발 화염 칸에 있던 플레이어는 사망.
- **아이템 드롭**: 파괴블록 파괴 시 확률로 드롭 — 폭탄+1 / 사거리+1 / 속도+1.
  먹으면 즉시 능력치 상승(상한 있음).
- **승패**: 마지막 생존자 승리 / 둘 다 죽으면 무승부 → HUD 표시 → R 재시작.
- **HUD**: 각 플레이어 폭탄·사거리·생존 상태 + 중앙 라운드 상태 텍스트.

## 기술 스택

- **Vite** (개발 서버 + 프로덕션 번들)
- **Phaser 3** (2D 게임 엔진)
- **TypeScript** (strict)

### 모듈 구조

```
src/
  main.ts              Phaser 게임 부트스트랩
  config.ts            그리드/밸런스/플레이어 상수 (SSOT)
  scenes/GameScene.ts  씬: 입력→이동·폭탄, 승패 판정, HUD 갱신
  entities/
    Player.ts          격자 락 플레이어(이동·능력치·사망)
    Bomb.ts            폭탄(퓨즈 타이머·연쇄)
    Item.ts            파워업 아이템
  systems/
    grid.ts            논리 그리드 + 맵 생성 + 셀↔픽셀 변환
    BombManager.ts     폭발·화염·블록파괴·아이템드롭·사망판정 코어
    textures.ts        플레이스홀더 텍스처 코드 생성 (에셋 참고)
  ui/Hud.ts            상단 상태바
```

## 에셋

`ASSETS.md` 참조. 현재는 **코드 생성 플레이스홀더 도형 스프라이트**를 사용한다
(Kenney CC0 팩 다운로드가 환경에서 막혀, 게임 로직을 우선 동작시키기 위함).

## 로드맵

- **P0 (현재)**: 로컬 2인 봄버맨 코어 — 브라우저 단독 플레이.
- **P1**: 멀티플레이어 — [Colyseus](https://colyseus.io) 권위 서버로 상태 동기화.
- **P2**: Discord Activity (Embedded App SDK) 로 디스코드 보이스 채널 내 실행.
