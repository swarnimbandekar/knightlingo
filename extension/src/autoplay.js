// Port of AutoPlay.java: acts on positions already confirmed by GameTracker.
import { endsGame } from './movetip.js';

const OFF = 'off', READY = 'ready', MOVING = 'moving', WAITING_CHANGE = 'waiting';

/**
 * position: {fen, whiteBottom, myTurn, seenAt, left, top, size, selectedCell, terminal}, in the
 * page's CSS pixels. Actions carry tap points in the same coordinates.
 */
export class AutoPlay {
  constructor() {
    this.stage = OFF;
    this.origin = null;
    this.pendingAction = null;
    this.move = null;
    this.id = 0;
    this.phaseAt = 0;
    this.resumeAfter = -1;
    this.boardUnavailable = false;
    this.finished = false;
    this.attempts = 0;
    this.note = '';
  }

  enabled() { return this.stage !== OFF; }
  waitingForBoard() { return this.enabled() && this.boardUnavailable; }
  waitingForMove() { return this.stage === WAITING_CHANGE; }
  waitingForNextGame() { return this.enabled() && this.finished; }
  promotionPending() { return this.stage === WAITING_CHANGE && this.move != null && this.move.length === 5; }
  pending(action) { return !!action && this.enabled() && !!this.pendingAction && this.pendingAction.id === action.id; }

  waitForInput(now) {
    this.resumeAfter = now;
    this.boardUnavailable = true;
    if (this.stage === MOVING) this.complete(this.pendingAction, false, now);
  }

  start(now) {
    this.resetMove();
    this.phaseAt = now;
    this.resumeAfter = -1;
    this.boardUnavailable = false;
    this.finished = false;
    this.note = '';
  }

  pause(reason) {
    this.stage = OFF;
    this.pendingAction = null;
    this.note = reason;
  }

  next(now, position, analyzedFen, tip, foreground) {
    if (!this.enabled()) return null;
    if (!foreground) { this.waitForInput(now); return null; }
    if (this.stage === MOVING) {
      if (now - this.phaseAt > 2000) this.complete(this.pendingAction, false, now);
      return null;
    }
    if (!position || now - position.seenAt > 750 || position.seenAt <= this.resumeAfter) {
      this.boardUnavailable = true;
      if (!position || now - position.seenAt > 750) this.resumeAfter = now;
      return null;
    }
    this.boardUnavailable = false;
    if (position.terminal) { this.resetMove(); this.finished = true; return null; }
    this.finished = false;
    const origin = this.origin;
    if (origin && (position.whiteBottom !== origin.whiteBottom || Math.abs(position.left - origin.left) > 2
        || Math.abs(position.top - origin.top) > 2 || Math.abs(position.size - origin.size) > 2)) {
      this.resetMove();
      this.waitForInput(now);
      return null;
    }
    if (this.stage === WAITING_CHANGE) {
      if (position.seenAt <= this.phaseAt + 120) return null;
      if (position.fen === origin.fen) {
        if (!this.promotionPending() && position.myTurn && position.seenAt - this.phaseAt >= 1500) {
          return this.issue(position, now);
        }
        return null;
      }
      // The tracker validates legal moves, manual moves and a new game's opening.
      this.resetMove();
    }
    if (!position.myTurn || !tip || position.fen !== analyzedFen) return null;
    // The tracker has already confirmed stable legal frames. Act as soon as analysis is ready.
    this.move = tip.move;
    return this.issue(position, now);
  }

  resetMove() {
    this.stage = READY;
    this.origin = null;
    this.pendingAction = null;
    this.move = null;
    this.attempts = 0;
  }

  issue(position, now) {
    this.origin = position;
    const from = this.point(0), to = this.point(2);
    const cell = position.size / 8;
    const source = Math.trunc((from.y - position.top) / cell) * 8 + Math.trunc((from.x - position.left) / cell);
    this.pendingAction = {
      id: ++this.id, fen: position.fen, move: this.move,
      fromX: from.x, fromY: from.y, toX: to.x, toY: to.y,
      sourceSelected: position.selectedCell === source, attempt: ++this.attempts,
    };
    this.stage = MOVING;
    this.phaseAt = now;
    return this.pendingAction;
  }

  point(offset) {
    const o = this.origin;
    const file = this.move.charCodeAt(offset) - 97;
    const rank = this.move.charCodeAt(offset + 1) - 49;
    return {
      x: o.left + ((o.whiteBottom ? file : 7 - file) + 0.5) * o.size / 8,
      y: o.top + ((o.whiteBottom ? 7 - rank : rank) + 0.5) * o.size / 8,
    };
  }

  complete(action, success, now) {
    if (!this.pending(action)) return;
    this.pendingAction = null;
    this.stage = WAITING_CHANGE;
    this.phaseAt = now;
    if (!success) { this.resumeAfter = now; this.boardUnavailable = true; return; }
    // Duolingo covers the board with the result screen right after a mate, so the final position
    // may never be read. Recognize a game-ending move from the move itself.
    if (endsGame(action.fen, action.move)) this.finished = true;
  }
}
