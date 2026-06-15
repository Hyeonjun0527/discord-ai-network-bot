# Assets

## 현재 상태: 코드 생성 플레이스홀더

이 프로토타입은 현재 **코드로 생성한 플레이스홀더 도형 스프라이트**를 사용한다
(`src/systems/textures.ts`). Phaser 의 `Graphics` 로 런타임에 타일/엔티티 텍스처를
그려 텍스처 캐시에 등록한다 — 바닥, 고정벽, 파괴블록(상자), 폭탄, 화염, 파워업 3종,
플레이어 2종.

### 이유

작업 환경에서 `kenney.nl` 에셋 zip 직접 다운로드(`curl`)가 막혀 있었다(에러 페이지 /
HTML 응답만 반환). 게임 **로직**이 핵심이므로, 다운로드에 의존하지 않고 즉시 동작하는
플레이스홀더로 진행했다. 관련 코드에 `// TODO: Kenney 에셋 교체` 표기.

## 권장 교체 에셋 (Kenney, CC0)

봄버맨/탑다운 그리드에 가장 잘 맞는 팩:

- **Sokoban** — <https://kenney.nl/assets/sokoban>
  플레이어, 상자(= 파괴블록), 벽, 바닥 타일이 격자에 최적. 1순위 추천.
- 보강용: **Tiny Town** / **Topdown Shooter** (<https://kenney.nl/assets>).

모든 Kenney 에셋은 **CC0 (Public Domain)** 이며 **출처 표기 불필요**(표기는 환영).

## 교체 방법

1. 위 팩 zip 을 받아 필요한 PNG / 타일시트만 `public/assets/` 에 둔다.
2. `src/systems/textures.ts` 의 `generateTextures()` 호출을 제거하고,
   `GameScene.preload()` 에서 `this.load.image()` / `this.load.spritesheet()` 로
   실제 PNG 를 로드한 뒤 `TEX.*` 키를 그 키로 매핑한다.
3. 이 문서의 "현재 상태" 섹션을 사용한 팩명으로 갱신한다.
