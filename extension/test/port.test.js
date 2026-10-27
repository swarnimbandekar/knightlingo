// Runs the JS port against the same screenshots and scenarios as the Android unit tests, so both
// versions read and track boards identically.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { Chess } from 'chess.js';
import { PNG } from 'pngjs';
import { BoardVision, Observation, frameFromPixels } from '../src/vision.js';
import { GameTracker } from '../src/tracker.js';
import { AutoPlay } from '../src/autoplay.js';
import { explain } from '../src/movetip.js';

const root = fileURLToPath(new URL('../../', import.meta.url));
const E4 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR';

function vision() {
  const bytes = readFileSync(root + 'app/src/main/assets/pieces.bin');
  return new BoardVision(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length));
}

function fixture(name) {
  const data = gunzipSync(readFileSync(`${root}app/src/test/resources/${name}.rgb.gz`));
  const width = data.readInt32BE(0), height = data.readInt32BE(4);
  return frameFromPixels(width, height, data.subarray(8), 3);
}

function observation(fen, whiteBottom) {
  const cells = new Array(64).fill('.');
  let index = 0;
  for (const token of fen.split(' ')[0]) {
    if (token === '/') continue;
    const count = /\d/.test(token) ? +token : 1;
    for (let j = 0; j < count; j++, index++) cells[whiteBottom ? index : 63 - index] = /\d/.test(token) ? '.' : token;
  }
  return new Observation(0, 100, 800, cells, 0);
}

function accept(tracker, seen) {
  tracker.accept(seen);
  assert.ok(tracker.accept(seen));
}

test('reads the Android black-perspective screenshot after e4', () => {
  const detector = vision();
  const result = detector.read(fixture('duo-black-e4'));
  assert.ok(result, detector.lastIssue);
  assert.equal(result.top, 1099);
  assert.equal(result.placement(false), E4);
  const tracker = new GameTracker();
  assert.equal(tracker.accept(result), false);
  assert.ok(tracker.accept(result));
  assert.equal(tracker.whiteBottom, false);
  assert.ok(tracker.myTurn());
});

test('reads every selected-piece screenshot like the Java version', () => {
  const fen = 'r1b1kb1r/ppp2ppp/2n2n2/3qp1N1/8/2PB4/PP1P1PPP/RNBQK2R b KQkq - 1 6';
  for (const piece of 'pnbrqk') {
    for (const suffix of ['', '-half']) {
      const detector = vision();
      const name = `duo-black-selected-${piece}${suffix}`;
      const result = detector.read(fixture(name));
      assert.ok(result, `${name} ${detector.lastIssue}`);
      assert.equal(result.placement(false), fen.split(' ')[0], name);
    }
  }
});

test('the mumu QHD board keeps its exact grid', () => {
  const detector = vision();
  const result = detector.read(fixture('duo-mumu-qhd-black-e4'));
  assert.ok(result, detector.lastIssue);
  assert.deepEqual([result.left, result.top, result.size], [9, 798, 1422]);
});

test('a red check glow marks the king square doubtful instead of losing the board', () => {
  const source = fixture('duo-black-e4');
  const glowing = {
    width: source.width, height: source.height,
    rgb(x, y) {
      const pixel = source.rgb(x, y);
      if (x < 540 || x >= 720 || y < 1099 || y >= 1279) return pixel;
      const a = Math.max(0, 0.75 - 0.45 * Math.hypot(x - 630, y - 1189) / 127);
      const r = Math.trunc(((pixel >> 16) & 255) * (1 - a) + 235 * a);
      const g = Math.trunc(((pixel >> 8) & 255) * (1 - a) + 60 * a);
      const b = Math.trunc((pixel & 255) * (1 - a) + 60 * a);
      return (r << 16) | (g << 8) | b;
    },
  };
  const detector = vision();
  const result = detector.read(glowing);
  assert.ok(result, detector.lastIssue);
  assert.equal(result.certain(), false);
  const board = new Chess();
  board.move('e4');
  const tracker = new GameTracker(new Chess().fen(), false);
  tracker.accept(result);
  assert.ok(tracker.accept(result));
  assert.equal(tracker.fen(), board.fen());
});

test('rejects a blank frame', () => {
  assert.equal(vision().read(frameFromPixels(720, 1544, new Uint8Array(720 * 1544 * 3), 3)), null);
});

test('tracks two moves between frames and recovers from a lost position', () => {
  const board = new Chess();
  const tracker = new GameTracker();
  accept(tracker, observation(board.fen(), true));
  board.move('e4'); board.move('e5');
  accept(tracker, observation(board.fen(), true));
  assert.equal(tracker.fen(), board.fen());
  for (const san of ['Nf3', 'Nc6', 'Bb5']) board.move(san);
  const jumped = observation(board.fen(), true);
  let recovered = false;
  for (let i = 0; i < 8 && !recovered; i++) recovered = tracker.accept(jumped);
  assert.ok(recovered);
  assert.equal(tracker.fen().split(' ')[0], board.fen().split(' ')[0]);
});

test('an unknown mid-game position is never trusted without a history', () => {
  const board = new Chess();
  for (const san of ['e4', 'e5', 'Nf3', 'Nc6']) board.move(san);
  const tracker = new GameTracker();
  assert.equal(tracker.accept(observation(board.fen(), true)), false);
  assert.equal(tracker.accept(observation(board.fen(), true)), false);
  assert.equal(tracker.fen(), null);
});

test('tips classify captures, castling and promotions', () => {
  const cases = [
    [new Chess().fen(), 'e2e4', false],
    ['4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1', 'e4d5', true],
    ['4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1', 'e5d6', true],
    ['4k3/P7/8/8/8/8/8/4K3 w - - 0 1', 'a7a8q', false],
    ['r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1', 'e1g1', false],
  ];
  for (const [fen, move, capture] of cases) {
    const tip = explain(fen, { move, variation: '', depth: 20 });
    assert.ok(tip, move);
    assert.equal(tip.capture, capture, move);
  }
  assert.equal(explain(new Chess().fen(), { move: 'e2e5', variation: '', depth: 20 }), null);
});

test('auto-play taps rotated squares and reports its own checkmate', () => {
  const fen = 'r3kb1r/ppp2pp1/2n5/4p2P/P7/7b/1PPPPP2/R1BQK1Nq b kq - 1 10';
  const auto = new AutoPlay();
  auto.start(0);
  const position = { fen, whiteBottom: false, myTurn: true, seenAt: 0, left: 0, top: 100, size: 800, selectedCell: -1, terminal: false };
  const action = auto.next(0, position, fen, explain(fen, { move: 'h1g1', variation: '', depth: 20 }), true);
  assert.ok(action);
  assert.deepEqual([action.fromX, action.fromY, action.toX, action.toY], [50, 150, 150, 150]); // Black at the bottom: rank 1 is the top row
  auto.complete(action, true, 100);
  assert.equal(auto.next(2000, null, fen, null, true), null);
  assert.ok(auto.waitingForNextGame());
});

const web = `${root}extension/test/fixtures/web-board.png`;
test('reads the Duolingo web board canvas', { skip: !existsSync(web) }, () => {
  const png = PNG.sync.read(readFileSync(web));
  const detector = vision();
  const result = detector.read(frameFromPixels(png.width, png.height, png.data, 4));
  assert.ok(result, detector.lastIssue);
  assert.equal(result.placement(true), E4);
});
