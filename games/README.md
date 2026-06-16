# games/ — NEXA 미니게임

이 디렉터리는 독립 실행되는 게임 서브프로젝트들이다(중앙 서버/프로바이더 에이전트 빌드와 분리).

## crazy-nia — 순수 크레이지 아케이드 (봄버맨 스타일 2D)
- 격자 맵에서 폭탄을 설치해 파괴블록을 부수고 상대를 잡는 **순수 크레이지 아케이드/봄버맨** 게임.
- 넓은 맵 + 랜덤 맵 5종, 아이템(폭탄+/사거리+/속도+), WebAudio 사운드.
- **실시간 멀티플레이**(Colyseus 권위 서버) + 부드러운 연속 이동.
- 스택: Vite + Phaser 3 + TypeScript / 서버 Node + Colyseus. 에셋 Kenney Sokoban(CC0).
- 실행: `cd crazy-nia && npm install && npm run dev:all` → http://localhost:5173
- Discord Activity 래핑 지원(`crazy-nia/docs/DISCORD_ACTIVITY.md`).

## strike-protocol — 총게임 (택티컬 FPS 3D)
- **총게임**(1인칭 슈팅, 택티컬 FPS). three.js 3D 엔진.
- 스택: TanStack Start(React + Vite + TypeScript) + three.js. 사용자 제공 소스(Higgsfield 리믹스용) 통합.
- 실행: `cd strike-protocol && npm install && npm run dev` → http://localhost:5174
- 전체 소스 편집 가능(게임 로직 `src/lib/game/engine.ts`).
