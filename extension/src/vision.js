// Port of app/src/main/java/com/juhyeon/chesstip/BoardVision.java. Reads pixels only, so the same
// code runs on Duolingo's board canvas in Chrome and on the Android screenshots in Node tests.

const PIECE_SCALES = [1, 1.20, 1.28, 1.36];
const MAX_UNCERTAIN = 3;
/** Web boards can be smaller than a phone screenshot; below this no 32px template is meaningful. */
const MIN_FRAME = 240;

/** A frame over RGBA bytes (canvas ImageData) or RGB bytes (test fixtures). */
export function frameFromPixels(width, height, data, channels = 4) {
  return {
    width, height,
    rgb(x, y) {
      const i = (y * width + x) * channels;
      return (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
    },
  };
}

export class Observation {
  /** {@code uncertain} marks cells whose piece could not be confirmed, e.g. a check glow. */
  constructor(left, top, size, cells, error, selectedCell = -1, uncertain = new Array(64).fill(false)) {
    Object.assign(this, { left, top, size, cells, error, selectedCell, uncertain });
  }

  certain() { return !this.uncertain.includes(true); }

  placement(whiteBottom) {
    let fen = '';
    for (let rank = 0; rank < 8; rank++) {
      let empty = 0;
      for (let file = 0; file < 8; file++) {
        const piece = this.cells[whiteBottom ? rank * 8 + file : (7 - rank) * 8 + 7 - file];
        if (piece === '.') { empty++; continue; }
        if (empty > 0) { fen += empty; empty = 0; }
        fen += piece;
      }
      if (empty > 0) fen += empty;
      if (rank < 7) fen += '/';
    }
    return fen;
  }

  /** Cells with unconfirmed squares replaced by '?', used to judge frame stability. */
  key() { return this.cells.map((c, i) => (this.uncertain[i] ? '?' : c)).join(''); }

  /** Compares with a FEN placement, ignoring unconfirmed squares. */
  matches(placement, whiteBottom) {
    let index = 0;
    for (const token of placement) {
      if (token === '/') continue;
      const digit = token >= '1' && token <= '8';
      const count = digit ? +token : 1;
      for (let j = 0; j < count; j++, index++) {
        const cell = whiteBottom ? index : 63 - index;
        if (this.uncertain[cell]) continue;
        if (this.cells[cell] !== (digit ? '.' : token)) return false;
      }
    }
    return index === 64;
  }
}

const channel = (rgb, shift) => (rgb >> shift) & 255;
const distance = (a, b) => Math.max(Math.abs(channel(a, 16) - channel(b, 16)),
  Math.abs(channel(a, 8) - channel(b, 8)), Math.abs(channel(a, 0) - channel(b, 0)));

function columns(source, left, width) {
  return { width, height: source.height, rgb: (x, y) => source.rgb(left + x, y) };
}

class Match {
  constructor(piece, error, margin, scale, normalError) { Object.assign(this, { piece, error, margin, scale, normalError }); }
  confident() { return this.error <= 0.032 && this.margin >= 0.002; }
  enlarged() { return this.confident() && this.scale > 1 && this.error < 0.02 && this.normalError - this.error > 0.01; }
}

class Selection {
  constructor(left, top, cell, scale) { Object.assign(this, { left, top, cell, scale }); }
  extra() { return this.cell * ((this.scale - 1) / 2 + 0.04); }
  contains(x, y) {
    // Templates crop at the cell edge; selected crowns have a small additional overhang.
    const extra = this.extra();
    return x >= this.left - extra && x < this.left + this.cell + extra
      && y >= this.top - extra && y < this.top + this.cell + extra;
  }
  overlaps(x, y) {
    const extra = this.extra();
    return x < this.left + this.cell + extra && x + this.cell > this.left - extra
      && y < this.top + this.cell + extra && y + this.cell > this.top - extra;
  }
  source(x, y) { return Math.abs(x - this.left) < 1 && Math.abs(y - this.top) < 1; }
}

export class BoardVision {
  /** @param {ArrayBuffer} buffer contents of pieces.bin */
  constructor(buffer) {
    const view = new DataView(buffer);
    if (view.getInt32(0) !== 0x43545032) throw new Error('Invalid piece templates');
    this.n = view.getInt32(4);
    const count = view.getInt32(8);
    if (this.n !== 32 || count !== 12) throw new Error('Unsupported piece templates');
    this.names = [];
    this.templates = [];
    let offset = 12;
    const length = this.n * this.n * 3;
    for (let i = 0; i < count; i++) {
      this.names.push(String.fromCharCode(view.getUint8(offset++)));
      this.templates.push(new Uint8Array(buffer.slice(offset, offset + length)));
      offset += length;
    }
    this.previousBounds = null;
    this.previousWidth = 0;
    this.previousHeight = 0;
    this.lastIssue = '';
  }

  read(frame) {
    this.lastIssue = 'Could not detect the chessboard boundary';
    if (frame.width < MIN_FRAME || frame.height < MIN_FRAME) return null;
    const bounds = this.locate(frame);
    if (!bounds) return null;
    frame = columns(frame, bounds.left, bounds.size);
    const width = bounds.size, bestStart = bounds.top;
    const cells = new Array(64);
    const uncertain = new Array(64).fill(false);
    let worst = 0, whiteKings = 0, blackKings = 0, open = 0;
    const backgrounds = BoardVision.backgrounds(frame, bestStart);
    const cell = width / 8;
    const matches = new Array(64);
    let selection = null;
    // Find the enlarged source first. Its crown or base may extend into another square.
    for (let row = 0; row < 8; row++) {
      for (let col = 0; col < 8; col++) {
        const x = col * cell, y = bestStart + row * cell;
        const match = this.classify(frame, x, y, cell, backgrounds, null);
        matches[row * 8 + col] = match;
        if (match.enlarged()) {
          if (selection) { this.lastIssue = 'Multiple enlarged pieces'; return null; }
          selection = new Selection(x, y, cell, match.scale);
        }
      }
    }
    for (let row = 0; row < 8; row++) {
      for (let col = 0; col < 8; col++) {
        const x = col * cell, y = bestStart + row * cell;
        let match = matches[row * 8 + col];
        if (selection && !selection.source(x, y) && selection.overlaps(x, y)) {
          match = this.classify(frame, x, y, cell, backgrounds, selection);
        }
        cells[row * 8 + col] = match.piece;
        if (!match.confident()) {
          // A check glow, hint tint or small overlay can spoil one square. Keep the rest of the
          // board; the tracker resolves the doubtful squares from the legal moves.
          this.lastIssue = `cell=${row},${col} best=${match.piece} error=${match.error} margin=${match.margin}`;
          uncertain[row * 8 + col] = true;
          if (++open > MAX_UNCERTAIN) return null;
          continue;
        }
        worst = Math.max(worst, match.error);
        if (match.piece === 'K') whiteKings++;
        if (match.piece === 'k') blackKings++;
      }
    }
    if (whiteKings > 1 || blackKings > 1 || whiteKings + blackKings + open < 2
        || (whiteKings === 0 && open === 0) || (blackKings === 0 && open === 0)) {
      this.lastIssue = `Could not verify king count white=${whiteKings} black=${blackKings}`;
      return null;
    }
    const selected = !selection ? -1
      : Math.round((selection.top - bestStart) / cell) * 8 + Math.round(selection.left / cell);
    return new Observation(bounds.left, bestStart, width, cells, worst, selected, uncertain);
  }

  locate(frame) {
    // Revalidate the cached grid in the current pixels; a cache never authorizes a move.
    if (this.previousBounds && this.previousWidth === frame.width && this.previousHeight === frame.height) {
      const bounds = BoardVision.refine(frame, this.previousBounds.left, this.previousBounds.size);
      if (bounds) { this.previousBounds = bounds; return bounds; }
    }
    const shortest = Math.min(frame.width, frame.height);
    const minimumCell = Math.max(8, Math.trunc(shortest / 96));
    const candidates = [];
    const seen = new Set();
    // Equal alternating color runs reveal cell size without assuming screen width or DPI.
    for (let y = 0; y < frame.height; y += Math.max(1, Math.trunc(shortest / 200))) {
      const runs = [];
      let start = 0, color = frame.rgb(0, y);
      for (let x = 1; x <= frame.width; x++) {
        const next = x < frame.width ? frame.rgb(x, y) : -1;
        if (x < frame.width && distance(color, next) <= 6) continue;
        const length = x - start;
        if (length >= minimumCell && length <= shortest / 8 * 1.15 && channel(color, 16) <= 110) {
          runs.push({ start, end: x, color, length });
        }
        start = x; color = next;
      }
      for (let i = 0; i + 2 < runs.length; i++) {
        const first = runs[i], second = runs[i + 1];
        const contrast = distance(first.color, second.color);
        if (contrast < 10 || contrast > 80) continue;
        let cell = (first.length + second.length) / 2;
        let count = 1;
        while (count < 8 && i + count < runs.length) {
          const run = runs[i + count], before = runs[i + count - 1];
          const expected = count % 2 === 0 ? first.color : second.color;
          if (distance(run.color, expected) > 6 || Math.abs(run.length - cell) > Math.max(2, cell * 0.10)
              || run.start - before.end > Math.max(2, cell * 0.08)) break;
          count++;
        }
        if (count < 3) continue;
        cell = (runs[i + count - 1].end - first.start) / count;
        const size = Math.round(cell * 8);
        if (size < 128 || size > shortest) continue;
        for (let column = 0; column <= 8 - count; column++) {
          const left = Math.round(first.start - column * cell);
          if (left < 0 || left + size > frame.width) continue;
          const key = left * 100000 + size;
          if (!seen.has(key)) { seen.add(key); candidates.push({ left, size, squares: count }); }
        }
      }
    }
    candidates.sort((a, b) => b.squares - a.squares || b.size - a.size);
    for (const candidate of candidates) {
      const bounds = BoardVision.refine(frame, candidate.left, candidate.size);
      if (!bounds) continue;
      this.previousWidth = frame.width; this.previousHeight = frame.height; this.previousBounds = bounds;
      return bounds;
    }
    return null;
  }

  static refine(frame, left, size) {
    const viewport = columns(frame, left, size);
    const broad = BoardVision.checkerRun(viewport, 0, viewport.height, null);
    if (broad[1] < size * 0.95) return null;
    // Avatar colors may extend the coarse run; square colors isolate the actual board.
    const paletteTop = broad[0] + Math.max(0, Math.trunc((broad[1] - size) / 2));
    if (paletteTop + size > frame.height) return null;
    const palette = BoardVision.backgrounds(viewport, paletteTop);
    const vertical = BoardVision.checkerRun(viewport, broad[0], broad[0] + broad[1], palette);
    const top = vertical[0], measured = vertical[1];
    if (measured < size * 0.95 || measured > size * 1.05) return null;
    const boardSize = Math.min(frame.width, measured);
    let boardLeft = Math.max(0, left + Math.trunc((size - boardSize) / 2));
    boardLeft = BoardVision.alignLeft(frame, boardLeft, top, boardSize, palette);
    if (boardLeft < 0 || boardLeft + boardSize > frame.width || top < 0 || top + boardSize > frame.height) return null;
    const board = columns(frame, boardLeft, boardSize);
    if (!BoardVision.gridCorners(board, top, palette)) return null;
    return { left: boardLeft, top, size: boardSize };
  }

  static alignLeft(frame, guess, top, size, palette) {
    const cell = size / 8;
    const radius = Math.max(3, Math.round(cell * 0.12)), span = Math.max(1, Math.trunc(size / 512));
    const offsets = [];
    for (let boundary = 1; boundary < 8; boundary++) {
      const center = Math.round(guess + boundary * cell);
      let best = 0, sum = 0, ties = 0;
      for (let x = Math.max(span, center - radius); x <= Math.min(frame.width - span - 1, center + radius); x++) {
        let votes = 0;
        for (let row = 0; row < 8; row++) for (let edge = 0; edge < 2; edge++) {
          const y = top + Math.trunc((row + (edge === 0 ? 0.035 : 0.965)) * cell);
          if (y < 0 || y >= frame.height) continue;
          const a = frame.rgb(x - span, y), b = frame.rgb(x + span, y);
          if ((distance(a, palette[0]) <= 8 && distance(b, palette[1]) <= 8)
              || (distance(a, palette[1]) <= 8 && distance(b, palette[0]) <= 8)) votes++;
        }
        if (votes > best) { best = votes; sum = x; ties = 1; } else if (votes === best) { sum += x; ties++; }
      }
      if (best >= 4) offsets.push(sum / ties - boundary * cell);
    }
    if (offsets.length < 3) return -1;
    offsets.sort((a, b) => a - b);
    return Math.max(0, Math.round(offsets[Math.trunc(offsets.length / 2)]));
  }

  static gridCorners(board, top, palette) {
    // Every column must alternate vertically too, including the outer files. This rejects
    // a shifted window that mistakes a strip of the surrounding background for a file.
    for (let parity = 0; parity < 2; parity++) {
      const knownRows = new Array(8).fill(0), knownColumns = new Array(8).fill(0);
      const correctRows = new Array(8).fill(0), correctColumns = new Array(8).fill(0);
      for (let row = 0; row < 8; row++) for (let col = 0; col < 8; col++) {
        const pixel = board.rgb(Math.trunc((col + 0.035) * board.width / 8), top + Math.trunc((row + 0.035) * board.width / 8));
        const a = distance(pixel, palette[0]), b = distance(pixel, palette[1]);
        if (Math.min(a, b) > 8) continue;
        knownRows[row]++; knownColumns[col]++;
        if ((a <= b ? 0 : 1) === (row + col + parity) % 2) { correctRows[row]++; correctColumns[col]++; }
      }
      let valid = true;
      for (let i = 0; i < 8; i++) {
        if (knownRows[i] < 4 || knownColumns[i] < 4
            || correctRows[i] * 4 < knownRows[i] * 3 || correctColumns[i] * 4 < knownColumns[i] * 3) valid = false;
      }
      if (valid) return true;
    }
    return false;
  }

  static checkerRun(frame, from, to, palette) {
    let runStart = -1, bestStart = -1, bestLength = 0, gap = 0;
    const allowedGap = Math.max(2, Math.trunc(frame.width / 100));
    for (let y = from; y <= to + allowedGap; y++) {
      if (y < to && BoardVision.checkerRow(frame, y, palette)) {
        if (runStart < 0) runStart = y;
        gap = 0;
      } else if (runStart >= 0) {
        // Resampling blends the two square colors on rank boundaries for a few pixels.
        if (++gap <= allowedGap) continue;
        const length = y - gap + 1 - runStart;
        if (length > bestLength) { bestStart = runStart; bestLength = length; }
        runStart = -1;
        gap = 0;
      }
    }
    return [bestStart, bestLength];
  }

  static checkerRow(frame, y, palette) {
    if (y < 0 || y >= frame.height) return false;
    const samples = new Array(8);
    for (let col = 0; col < 8; col++) {
      samples[col] = frame.rgb(Math.trunc(col * frame.width / 8) + Math.max(1, Math.trunc(frame.width / 280)), y);
    }
    if (palette) return BoardVision.checkerPattern(samples, palette[0], palette[1])
      || BoardVision.checkerPattern(samples, palette[1], palette[0]);
    for (let even = 0; even < 8; even += 2) {
      for (let odd = 1; odd < 8; odd += 2) {
        const a = samples[even], b = samples[odd];
        const contrast = distance(a, b);
        if (contrast < 10 || contrast > 80 || channel(a, 16) > 110 || channel(b, 16) > 110) continue;
        if (BoardVision.checkerPattern(samples, a, b)) return true;
      }
    }
    return false;
  }

  static checkerPattern(samples, even, odd) {
    let matches = 0, evenMatches = 0, oddMatches = 0;
    for (let col = 0; col < 8; col++) {
      if (distance(samples[col], col % 2 === 0 ? even : odd) <= 6) {
        matches++;
        if (col % 2 === 0) evenMatches++; else oddMatches++;
      }
    }
    // Selected sprites and capture-target tints can affect three probes on one row.
    return matches >= 5 && evenMatches >= 2 && oddMatches >= 2;
  }

  moveDot(feature, foreground) {
    const n = this.n;
    if (foreground > n * n * 0.06) return false;
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const offset = (y * n + x) * 3;
        if (feature[offset] === 0 && feature[offset + 1] === 0 && feature[offset + 2] === 0) continue;
        if (Math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) > n * 0.15) return false;
        if (feature[offset] > feature[offset + 1]) return false;
      }
    }
    return true;
  }

  static backgrounds(frame, top) {
    const samples = new Array(64);
    for (let row = 0; row < 8; row++) {
      for (let col = 0; col < 8; col++) {
        const y = Math.min(frame.height - 1, top + Math.trunc((row + 0.5) * frame.width / 8));
        samples[row * 8 + col] = frame.rgb(Math.trunc(col * frame.width / 8) + Math.max(1, Math.trunc(frame.width / 280)), y);
      }
    }
    let first = samples[0], second = samples[1], most = -1;
    for (const candidate of samples) {
      if (channel(candidate, 16) > 110) continue;
      let count = 0;
      for (const sample of samples) if (distance(sample, candidate) <= 6) count++;
      if (count > most) { first = candidate; most = count; }
    }
    most = -1;
    for (const candidate of samples) {
      const contrast = distance(first, candidate);
      if (channel(candidate, 16) > 110 || contrast < 10 || contrast > 80) continue;
      let count = 0;
      for (const sample of samples) if (distance(sample, candidate) <= 6) count++;
      if (count > most) { second = candidate; most = count; }
    }
    return [first, second];
  }

  classify(frame, left, top, cell, backgrounds, excluded) {
    const n = this.n;
    const local = BoardVision.localBackground(frame, left, top, cell, backgrounds, excluded);
    const normal = this.sample(frame, left, top, cell, 1, backgrounds, local, excluded);
    let foreground = 0;
    for (let i = 0; i < normal.length; i += 3) if (normal[i] || normal[i + 1] || normal[i + 2]) foreground++;
    if (foreground < n * n * 0.025 || this.moveDot(normal, foreground)) return new Match('.', 0, 1, 1, 0);
    const errors = new Array(this.templates.length).fill(Number.MAX_VALUE);
    const scales = new Array(this.templates.length).fill(1);
    let normalError = Number.MAX_VALUE;
    for (const scale of PIECE_SCALES) {
      const candidate = scale === 1 ? normal : this.sample(frame, left, top, cell, scale, backgrounds, local, excluded);
      for (let i = 0; i < this.templates.length; i++) {
        const template = this.templates[i];
        let sum = 0;
        for (let j = 0; j < candidate.length; j++) {
          const delta = candidate[j] - template[j];
          sum += delta * delta;
        }
        const error = sum / (65025 * candidate.length);
        if (scale === 1) normalError = Math.min(normalError, error);
        if (error < errors[i]) { errors[i] = error; scales[i] = scale; }
      }
    }
    let bestIndex = 0, best = Number.MAX_VALUE, second = Number.MAX_VALUE;
    for (let i = 0; i < this.templates.length; i++) {
      if (errors[i] < best) { second = best; best = errors[i]; bestIndex = i; } else if (errors[i] < second) second = errors[i];
    }
    return new Match(this.names[bestIndex], best, second - best, scales[bestIndex], normalError);
  }

  static localBackground(frame, left, top, cell, backgrounds, excluded) {
    const samples = [];
    for (const dy of [0.035, 0.965]) {
      for (const dx of [0.035, 0.965]) {
        const x = Math.trunc(left + dx * cell), y = Math.trunc(top + dy * cell);
        if (!excluded || !excluded.contains(x, y)) samples.push(frame.rgb(x, Math.min(frame.height - 1, y)));
      }
    }
    let result = backgrounds[0], best = -1, closest = Number.MAX_VALUE;
    for (const sample of samples) {
      let votes = 0;
      for (const other of samples) if (distance(sample, other) <= 6) votes++;
      const contrast = Math.min(distance(sample, backgrounds[0]), distance(sample, backgrounds[1]));
      if (votes > best || (votes === best && contrast < closest)) { result = sample; best = votes; closest = contrast; }
    }
    return result;
  }

  sample(frame, left, top, cell, scale, backgrounds, local, excluded) {
    const n = this.n;
    const result = new Uint8Array(n * n * 3);
    const size = cell * scale;
    left -= (size - cell) / 2;
    top -= (size - cell) / 2;
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const px = Math.max(0, Math.min(frame.width - 1, Math.trunc(left + (x + 0.5) * size / n)));
        const py = Math.max(0, Math.min(frame.height - 1, Math.trunc(top + (y + 0.5) * size / n)));
        if (excluded && excluded.contains(px, py)) continue;
        const pixel = frame.rgb(px, py);
        if (distance(pixel, local) <= 16 || (scale > 1
            && (distance(pixel, backgrounds[0]) <= 16 || distance(pixel, backgrounds[1]) <= 16))) continue;
        const offset = (y * n + x) * 3;
        result[offset] = channel(pixel, 16);
        result[offset + 1] = channel(pixel, 8);
        result[offset + 2] = channel(pixel, 0);
      }
    }
    return result;
  }
}
