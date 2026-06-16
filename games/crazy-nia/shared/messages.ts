// Client -> server message contract. Kept in the shared module so both ends agree
// on the wire shape (no drift). The client only ever sends input; the server owns
// all state and pushes it via @colyseus/schema sync.

export const MSG_INPUT = 'input';
export const MSG_PLACE_BOMB = 'placeBomb';

export type Direction = 'up' | 'down' | 'left' | 'right';

// `dir` set = start moving that way (held); `dir: null` = stop (key released).
export interface InputMessage {
  dir: Direction | null;
}
