// Port of GameTracker.java: updates turn/castling/en-passant state only when a unique legal path
// matches the observed placement, and rebuilds the position if it ever loses track.
import { Chess } from 'chess.js';

const RESYNC_AFTER = 4;
const START = new Chess().fen();
const placement = (fen) => fen.split(' ')[0];

export class GameTracker {
  constructor(savedFen = null, savedWhiteBottom = true) {
    this.board = null;
    this.whiteBottom = savedWhiteBottom;
    this.pending = null;
    this.stableCount = 0;
    this.desync = 0;
    if (savedFen) {
      try { this.board = new Chess(savedFen); } catch { this.board = null; }
    }
  }

  accept(observation) {
    if (!observation) { this.pending = null; this.stableCount = 0; return false; }
    const raw = observation.key();
    this.stableCount = raw === this.pending ? this.stableCount + 1 : 1;
    this.pending = raw;
    if (this.stableCount < 2) return false;
    if (this.board) {
      if (observation.matches(placement(this.board.fen()), this.whiteBottom)) { this.desync = 0; return true; }
      const matched = findSuccessor(this.board, observation, this.whiteBottom);
      if (matched) { this.board = new Chess(matched); this.desync = 0; return true; }
    }
    // Starting or rebuilding a game needs every square; a partial read only follows legal moves.
    if (!observation.certain()) return false;
    // A new game may begin with either the initial board or Oscar's first white move.
    for (const direction of [true, false]) {
      const observed = observation.placement(direction);
      const start = new Chess();
      if (placement(start.fen()) === observed) { this.board = start; this.whiteBottom = direction; return true; }
      for (const move of start.moves({ verbose: true })) {
        start.move(move);
        if (placement(start.fen()) === observed) { this.board = start; this.whiteBottom = direction; return true; }
        start.undo();
      }
    }
    // Mid-game the tracker can fall behind (several moves between readable frames, a transient
    // misread, or an ambiguous path). Rebuild from the stable placement instead of waiting forever.
    if (this.board && ++this.desync >= RESYNC_AFTER) {
      const rebuilt = rebuild(observation.placement(this.whiteBottom), this.whiteBottom);
      if (rebuilt) { this.board = rebuilt; this.desync = 0; return true; }
    }
    return false;
  }

  fen() { return this.board ? this.board.fen() : null; }
  terminal() { return !!this.board && this.board.moves().length === 0; }
  myTurn() { return !!this.board && this.board.turn() === (this.whiteBottom ? 'w' : 'b'); }
  sideName() { return this.whiteBottom ? 'White' : 'Black'; }
}

/** Places the observed pieces on a fresh board, guessing the turn and castling rights. */
function rebuild(observed, whiteBottom) {
  const mine = whiteBottom ? 'w' : 'b';
  const theirs = whiteBottom ? 'b' : 'w';
  const ranks = observed.split('/');
  let rights = '';
  if (ranks[7].endsWith('K2R')) rights += 'K';
  if (ranks[7].startsWith('R3K')) rights += 'Q';
  if (ranks[0].endsWith('k2r')) rights += 'k';
  if (ranks[0].startsWith('r3k')) rights += 'q';
  for (const side of [mine, theirs]) {
    try {
      // The side that just moved can never be left in check.
      const moved = new Chess(`${observed} ${side === 'w' ? 'b' : 'w'} - - 0 1`, { skipValidation: true });
      if (moved.inCheck()) continue;
      const candidate = new Chess(`${observed} ${side} ${rights || '-'} - 0 1`);
      if (candidate.moves().length) return candidate;
    } catch { /* not a legal position with this side to move */ }
  }
  return null;
}

function findSuccessor(current, observed, whiteBottom) {
  const board = new Chess(current.fen());
  // A single move is preferred. With doubtful squares more than one may fit; then wait.
  let single = null;
  for (const first of board.moves({ verbose: true })) {
    board.move(first);
    if (observed.matches(placement(board.fen()), whiteBottom)) {
      const fen = board.fen();
      if (single && single !== fen) return null;
      single = fen;
    }
    board.undo();
  }
  if (single) return single;
  let match = null;
  for (const first of board.moves({ verbose: true })) {
    board.move(first);
    for (const second of board.moves({ verbose: true })) {
      board.move(second);
      if (observed.matches(placement(board.fen()), whiteBottom)) {
        const fen = board.fen();
        if (match && match !== fen) return null;
        match = fen;
      }
      board.undo();
    }
    board.undo();
  }
  return match;
}

export { START };
