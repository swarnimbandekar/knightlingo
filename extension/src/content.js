// The Chrome counterpart of TipService.java: reads Duolingo's board canvas, tracks the game,
// asks the engine for a move, draws the arrow and the dock, and plays moves when Auto-play is on.
import pieces from '../../app/src/main/assets/pieces.bin';
import { BoardVision } from './vision.js';
import { GameTracker } from './tracker.js';
import { AutoPlay } from './autoplay.js';
import { explain } from './movetip.js';
import { createDock } from './dock.js';
import { createArrow } from './arrow.js';

const TAG = '[ChessTip]';
const STALE_MS = 1500;

const vision = new BoardVision(pieces.buffer.slice(pieces.byteOffset, pieces.byteOffset + pieces.byteLength));
const saved = (() => { try { return JSON.parse(sessionStorage.getItem('chesstip.game') || 'null'); } catch { return null; } })();
const tracker = new GameTracker(saved && saved.fen, saved ? saved.whiteBottom : true);
const auto = new AutoPlay();
const work = new OffscreenCanvas(1, 1);
const workContext = work.getContext('2d', { willReadFrequently: true });

let tipOn = false;
let reliable = false;
let myTurn = false;
let terminal = false;
let observedFen = null;
let observedAt = 0;
let board = null; // {left, top, size, whiteBottom, selectedCell} in viewport CSS pixels
let cached = null; // {fen, tip}
let analyzing = false;
let lastError = null;
let coachStatus = 'Open a chess match';
let unreadableSince = -1;
let issue = '';
let expanded = false;
let promotionFen = null;
let timer = 0;

const dock = createDock({
  onTip: () => setTip(!tipOn),
  onAuto: () => toggleAuto(),
  onExpand: () => { expanded = !expanded; render(); },
});
const arrow = createArrow();

function onChessPage() { return location.pathname.startsWith('/chess'); }

/** Duolingo draws the whole board on one canvas; pick the largest visible one. */
function boardCanvas() {
  let best = null, bestArea = 0;
  for (const canvas of document.querySelectorAll('canvas')) {
    const rect = canvas.getBoundingClientRect();
    const area = rect.width * rect.height;
    if (rect.width >= 200 && rect.height >= 200 && canvas.width >= 200 && area > bestArea) { best = canvas; bestArea = area; }
  }
  return best;
}

function readBoard() {
  const canvas = boardCanvas();
  if (!canvas) { reliable = false; board = null; coachStatus = 'Open a chess match'; return; }
  if (work.width !== canvas.width || work.height !== canvas.height) { work.width = canvas.width; work.height = canvas.height; }
  workContext.clearRect(0, 0, work.width, work.height);
  workContext.drawImage(canvas, 0, 0);
  let observation;
  try {
    const image = workContext.getImageData(0, 0, work.width, work.height);
    observation = vision.read({
      width: image.width, height: image.height,
      rgb: (x, y) => { const i = (y * image.width + x) * 4; const d = image.data; return (d[i] << 16) | (d[i + 1] << 8) | d[i + 2]; },
    });
  } catch (error) {
    // A tainted canvas would throw here; report it rather than failing silently.
    lastError = 'Can’t read this board';
    console.warn(TAG, 'BOARD_READ_ERROR', error);
    return;
  }
  const now = performance.now();
  observedAt = now;
  reliable = tracker.accept(observation);
  if (!reliable) {
    if (unreadableSince < 0) unreadableSince = now;
    board = null;
    issue = observation ? 'The moves on screen don’t match yet.' : vision.lastIssue;
    coachStatus = observation ? 'Confirming moves' : 'Reading the board';
    return;
  }
  unreadableSince = -1;
  issue = '';
  // Canvas pixels → viewport CSS pixels; the canvas may be scaled for the display density.
  const rect = canvas.getBoundingClientRect();
  const scaleX = rect.width / canvas.width, scaleY = rect.height / canvas.height;
  board = {
    left: rect.left + observation.left * scaleX,
    top: rect.top + observation.top * scaleY,
    size: observation.size * scaleX,
    whiteBottom: tracker.whiteBottom,
    selectedCell: observation.selectedCell,
    canvas,
  };
  myTurn = tracker.myTurn();
  const fen = tracker.fen();
  if (fen !== observedFen) {
    observedFen = fen;
    terminal = tracker.terminal();
    try { sessionStorage.setItem('chesstip.game', JSON.stringify({ fen, whiteBottom: tracker.whiteBottom })); } catch { /* private mode */ }
    console.info(TAG, 'BOARD_CONFIRMED', tracker.sideName(), fen);
  }
  coachStatus = terminal ? 'Game over' : myTurn ? 'Your turn' : 'Opponent’s turn';
  if ((tipOn || auto.enabled()) && myTurn && !terminal) requestAnalysis();
}

function requestAnalysis() {
  const fen = observedFen;
  if (!reliable || !myTurn || !fen || (cached && cached.fen === fen) || analyzing) return;
  analyzing = true;
  chrome.runtime.sendMessage({ type: 'analyze', fen }).then((result) => {
    if (!result || result.error) throw new Error(result ? result.error : 'No engine response');
    const tip = explain(fen, result);
    if (tip) { cached = { fen, tip }; lastError = null; console.info(TAG, 'ENGINE_BESTMOVE', result.move, 'depth=' + result.depth); }
    else console.warn(TAG, 'ENGINE_NO_TIP', result.move, fen);
  }).catch((error) => {
    lastError = 'Engine hiccup · Retrying';
    console.warn(TAG, 'ENGINE_ERROR', error);
  }).finally(() => { analyzing = false; runAuto(); render(); });
}

// ---- Auto-play -------------------------------------------------------------------------------

function position() {
  if (!board || !reliable) return null;
  return { fen: observedFen, whiteBottom: board.whiteBottom, myTurn, seenAt: observedAt,
    left: board.left, top: board.top, size: board.size, selectedCell: board.selectedCell, terminal };
}

/** Synthesizes the pointer sequence of a real tap on the board canvas. */
function tap(canvas, x, y) {
  const base = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, screenX: x, screenY: y, view: window };
  const pointer = { ...base, pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1 };
  canvas.dispatchEvent(new PointerEvent('pointerover', pointer));
  canvas.dispatchEvent(new PointerEvent('pointerenter', { ...pointer, bubbles: false }));
  canvas.dispatchEvent(new MouseEvent('mousemove', base));
  canvas.dispatchEvent(new PointerEvent('pointermove', pointer));
  canvas.dispatchEvent(new PointerEvent('pointerdown', { ...pointer, button: 0, buttons: 1, pressure: 0.5 }));
  canvas.dispatchEvent(new MouseEvent('mousedown', { ...base, button: 0, buttons: 1 }));
  canvas.dispatchEvent(new PointerEvent('pointerup', { ...pointer, button: 0, buttons: 0 }));
  canvas.dispatchEvent(new MouseEvent('mouseup', { ...base, button: 0, buttons: 0 }));
  canvas.dispatchEvent(new MouseEvent('click', { ...base, button: 0, buttons: 0 }));
}

function runAuto() {
  if (!auto.enabled()) return;
  const now = performance.now();
  const foreground = document.visibilityState === 'visible' && onChessPage() && !!boardCanvas();
  const analysis = cached;
  const action = auto.next(now, position(), analysis && analysis.fen, analysis && analysis.tip, foreground);
  if (!action) return;
  const canvas = board.canvas;
  console.info(TAG, 'AUTO_TAP_MOVE', action.move, 'attempt=' + action.attempt);
  const destination = () => {
    if (!auto.pending(action)) return;
    tap(canvas, action.toX, action.toY);
    auto.complete(action, true, performance.now());
    if (action.move.length === 5) promotionFen = action.fen;
    console.info(TAG, 'AUTO_MOVE_SENT', action.move);
    render();
  };
  if (action.sourceSelected) destination();
  else { tap(canvas, action.fromX, action.fromY); setTimeout(destination, 300); }
}

function toggleAuto() {
  if (auto.enabled()) {
    auto.pause('Auto-play stopped · You can play manually');
    console.info(TAG, 'AUTO_PAUSED');
  } else {
    auto.start(performance.now());
    promotionFen = null;
    console.info(TAG, 'AUTO_STARTED');
    requestAnalysis();
    runAuto();
  }
  render();
}

function setTip(enabled) {
  tipOn = enabled;
  if (enabled) requestAnalysis();
  console.info(TAG, enabled ? 'TIP_ON' : 'TIP_OFF');
  render();
}

// ---- Presentation ----------------------------------------------------------------------------

function render() {
  const now = performance.now();
  const analysis = cached;
  const fresh = now - observedAt < STALE_MS;
  const ready = tipOn && reliable && fresh && myTurn && !!analysis && observedFen === analysis.fen && !!board;
  const waiting = auto.enabled() && (auto.waitingForBoard() || auto.waitingForMove());
  const side = board ? (board.whiteBottom ? '♔ ' : '♚ ') : '';
  const label = side + (auto.waitingForNextGame() ? 'Next game' : waiting ? 'Confirming' : coachStatus);
  const unreadable = unreadableSince >= 0 && now - unreadableSince > 4000 && issue
    ? '\n' + (issue.startsWith('cell=') ? 'Some squares are unclear.' : issue) : '';
  const detail = auto.promotionPending() && promotionFen ? 'Choose the promotion piece on the board.'
    : auto.waitingForNextGame() ? 'The game is over.\nOpen a new game to continue.'
    : auto.waitingForBoard() ? 'Re-reading the board.\nAuto-play continues when it is confirmed.' + unreadable
    : auto.waitingForMove() ? 'Checking that the move was applied.\nRetrying if needed.'
    : ready ? `${analysis.tip.glyphTitle}\n${analysis.tip.feature}\n${analysis.tip.continuation}`
    : lastError ? lastError
    : !auto.enabled() && auto.note ? auto.note
    : !tipOn ? (auto.enabled() ? 'Auto-play in progress' : 'Turn on Tip for a suggestion')
    : reliable && myTurn ? 'Calculating…'
    : !reliable && onChessPage() ? 'Keep the board visible' + unreadable : '';
  const dot = lastError ? 'red' : ready ? 'green' : waiting || (tipOn && !reliable) ? 'yellow'
    : auto.enabled() ? 'blue' : tipOn ? 'green' : 'subtle';
  dock.update({ label, detail, expanded, dot, tipOn, autoOn: auto.enabled(), emphasize: ready });
  arrow.update(ready ? { board, tip: analysis.tip } : null);
}

function tick() {
  clearTimeout(timer);
  const active = onChessPage();
  dock.setVisible(active && dock.wanted());
  if (active && document.visibilityState === 'visible') {
    readBoard();
    runAuto();
  } else {
    arrow.update(null);
  }
  if (active) render();
  timer = setTimeout(tick, auto.enabled() ? 250 : 400);
}

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === 'toggle-dock') { dock.toggleWanted(); tick(); }
});
document.addEventListener('visibilitychange', tick);
window.addEventListener('resize', () => render());
window.addEventListener('scroll', () => { board = null; arrow.update(null); }, true);
tick();
