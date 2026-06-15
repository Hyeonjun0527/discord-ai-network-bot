# Assets

## Kenney Sokoban (CC0) 사용

`public/assets/sokoban/` 에 포함된 PNG 는 **Kenney "Sokoban" 팩**에서 가져온 것이다.

| 역할 | 파일 | 설명 |
|---|---|---|
| 바닥(floor) | `Ground/ground_01.png` | 베이지 격자 타일 |
| 고정 벽(wall) | `Blocks/block_01.png` | 빨간 벽돌 블록 |
| 파괴블록(crate) | `Crates/crate_42.png` | 나무 상자 (대각선 무늬) |
| P1 플레이어 | `Player/player_01.png` | 파란 캐릭터 |
| P2 플레이어 | `Player/player_05.png` | 초록 모자 캐릭터 |

PNG 원본 크기 64×64, 게임 타일 48px — `setDisplaySize(48, 48)` 로 격자에 맞춘다.

### 출처 및 라이선스

- **팩**: Kenney "Sokoban" — <https://kenney.nl/assets/sokoban>
- **라이선스**: **CC0 1.0 Universal (Public Domain)** — 출처 표기 불필요(표기는 환영)
- **제작**: Kenney (<https://kenney.nl>)

## 코드 생성 요소 (Sokoban 팩에 없는 것)

폭탄, 화염, 파워업(폭탄+1 / 사거리+1 / 속도+1)은 Sokoban 팩에 해당 스프라이트가 없으므로
`src/systems/textures.ts` 에서 Phaser Graphics 로 런타임 생성한다.

## 포함된 전체 파일 (103 PNG)

```
Blocks/       block_01 ~ block_08
Crates/       crate_01 ~ crate_45
Environment/  environment_01 ~ environment_16
Ground/       ground_01 ~ ground_06
Player/       player_01 ~ player_24
              playerFace.png, playerFace_dark.png, playerFace_outline.png
sokoban_tilesheet.png
```
