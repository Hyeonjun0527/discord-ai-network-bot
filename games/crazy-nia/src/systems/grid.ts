import Phaser from 'phaser';
import { Cell, COLS, ROWS, TILE, HUD_HEIGHT, BLOCK_FILL_CHANCE, PLAYERS } from '../config';

// Logical map grid + helpers for pixel<->cell conversion and tile queries.
// The grid is the single source of truth for what blocks movement and explosions.

export class Grid {
  readonly cells: Cell[][]; // [row][col]
  private readonly blockSprites = new Map<string, Phaser.GameObjects.Image>();

  constructor(private readonly scene: Phaser.Scene) {
    this.cells = Grid.generate();
  }

  static key(col: number, row: number): string {
    return `${col},${row}`;
  }

  // Pixel position of a cell center (HUD occupies the top strip).
  static cellToX(col: number): number {
    return col * TILE + TILE / 2;
  }
  static cellToY(row: number): number {
    return HUD_HEIGHT + row * TILE + TILE / 2;
  }
  static xToCol(x: number): number {
    return Math.floor(x / TILE);
  }
  static yToRow(y: number): number {
    return Math.floor((y - HUD_HEIGHT) / TILE);
  }

  inBounds(col: number, row: number): boolean {
    return col >= 0 && col < COLS && row >= 0 && row < ROWS;
  }

  at(col: number, row: number): Cell {
    if (!this.inBounds(col, row)) return Cell.SolidWall;
    return this.cells[row][col];
  }

  isWalkable(col: number, row: number): boolean {
    return this.at(col, row) === Cell.Floor;
  }

  blockCount(): number {
    let n = 0;
    for (const line of this.cells) for (const c of line) if (c === Cell.Block) n++;
    return n;
  }

  /** Removes a destructible block (logic + sprite). Returns true if a block was there. */
  destroyBlock(col: number, row: number): boolean {
    if (this.at(col, row) !== Cell.Block) return false;
    this.cells[row][col] = Cell.Floor;
    const k = Grid.key(col, row);
    this.blockSprites.get(k)?.destroy();
    this.blockSprites.delete(k);
    return true;
  }

  /** Generates the classic lattice: solid border, pillar grid, random crates, clear corners. */
  private static generate(): Cell[][] {
    const grid: Cell[][] = [];
    const reserved = Grid.reservedCells();

    for (let row = 0; row < ROWS; row++) {
      const line: Cell[] = [];
      for (let col = 0; col < COLS; col++) {
        if (col === 0 || row === 0 || col === COLS - 1 || row === ROWS - 1) {
          line.push(Cell.SolidWall); // outer border
        } else if (col % 2 === 0 && row % 2 === 0) {
          line.push(Cell.SolidWall); // interior pillars
        } else if (reserved.has(Grid.key(col, row))) {
          line.push(Cell.Floor); // keep spawns + their elbows clear
        } else {
          line.push(Math.random() < BLOCK_FILL_CHANCE ? Cell.Block : Cell.Floor);
        }
      }
      grid.push(line);
    }
    return grid;
  }

  // Spawn corner plus its two adjacent cells stay open so players aren't boxed in.
  private static reservedCells(): Set<string> {
    const set = new Set<string>();
    for (const p of PLAYERS) {
      const { col, row } = p.spawn;
      const dc = col === 1 ? 1 : -1;
      const dr = row === 1 ? 1 : -1;
      set.add(Grid.key(col, row));
      set.add(Grid.key(col + dc, row));
      set.add(Grid.key(col, row + dr));
    }
    return set;
  }

  /** Renders floor under everything, walls, and crates. Call once after textures exist. */
  render(texFloor: string, texWall: string, texBlock: string): void {
    for (let row = 0; row < ROWS; row++) {
      for (let col = 0; col < COLS; col++) {
        const x = Grid.cellToX(col);
        const y = Grid.cellToY(row);
        // PNG 원본(64×64)을 TILE(48px) 격자에 맞게 스케일.
        this.scene.add.image(x, y, texFloor).setDisplaySize(TILE, TILE).setDepth(0);
        const cell = this.cells[row][col];
        if (cell === Cell.SolidWall) {
          this.scene.add.image(x, y, texWall).setDisplaySize(TILE, TILE).setDepth(2);
        } else if (cell === Cell.Block) {
          const img = this.scene.add.image(x, y, texBlock).setDisplaySize(TILE, TILE).setDepth(2);
          this.blockSprites.set(Grid.key(col, row), img);
        }
      }
    }
  }
}
