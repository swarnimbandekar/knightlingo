package io.github.swarnimbandekar.knightlingo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads pixels only. It has no Android dependencies, so real screenshots can be regression tested on PC. */
final class BoardVision {
    interface Frame {
        int width();
        int height();
        int rgb(int x, int y);
    }

    /** {@code uncertain} marks cells (bit = index) whose piece could not be confirmed, e.g. a check glow. */
    record Observation(int left, int top, int size, char[] cells, double error, int selectedCell, long uncertain) {
        Observation(int left, int top, int size, char[] cells, double error, int selectedCell) { this(left, top, size, cells, error, selectedCell, 0); }
        Observation(int left, int top, int size, char[] cells, double error) { this(left, top, size, cells, error, -1); }
        boolean certain() { return uncertain == 0; }
        /** Cells with unconfirmed squares replaced by '?', used to judge frame stability. */
        String key() {
            char[] result = cells.clone();
            for (int i = 0; i < 64; i++) if ((uncertain >> i & 1) != 0) result[i] = '?';
            return new String(result);
        }
        /** Compares with a FEN placement, ignoring unconfirmed squares. */
        boolean matches(String placement, boolean whiteBottom) {
            int index = 0;
            for (char token : placement.toCharArray()) {
                if (token == '/') continue;
                int count = Character.isDigit(token) ? token - '0' : 1;
                for (int j = 0; j < count; j++, index++) {
                    int cell = whiteBottom ? index : 63 - index;
                    if ((uncertain >> cell & 1) != 0) continue;
                    if (cells[cell] != (Character.isDigit(token) ? '.' : token)) return false;
                }
            }
            return index == 64;
        }
        String placement(boolean whiteBottom) {
            StringBuilder fen = new StringBuilder();
            for (int rank = 0; rank < 8; rank++) {
                int empty = 0;
                for (int file = 0; file < 8; file++) {
                    char piece = cells[whiteBottom ? rank * 8 + file : (7 - rank) * 8 + 7 - file];
                    if (piece == '.') { empty++; continue; }
                    if (empty > 0) { fen.append(empty); empty = 0; }
                    fen.append(piece);
                }
                if (empty > 0) fen.append(empty);
                if (rank < 7) fen.append('/');
            }
            return fen.toString();
        }
    }

    private final int n;
    private final char[] names;
    private final byte[][] templates;
    private record Bounds(int left, int top, int size) {}
    private record Run(int start, int end, int color) { int length() { return end - start; } }
    private record Candidate(int left, int size, int squares) {}
    private Bounds previousBounds;
    private int previousWidth, previousHeight;
    private static final float[] PIECE_SCALES = {1f, 1.20f, 1.28f, 1.36f};
    private record Match(char piece, double error, double margin, float scale, double normalError) {
        boolean confident() { return error <= .032 && margin >= .002; }
        boolean enlarged() { return confident() && scale > 1f && error < .02 && normalError - error > .01; }
    }
    private record Selection(float left, float top, float cell, float scale) {
        boolean contains(int x, int y) {
            // Templates crop at the cell edge; selected crowns have a small additional overhang.
            float extra = cell * ((scale - 1f) / 2 + .04f);
            return x >= left - extra && x < left + cell + extra
                    && y >= top - extra && y < top + cell + extra;
        }
        boolean overlaps(float x, float y) {
            float extra = cell * ((scale - 1f) / 2 + .04f);
            return x < left + cell + extra && x + cell > left - extra
                    && y < top + cell + extra && y + cell > top - extra;
        }
        boolean source(float x, float y) { return Math.abs(x - left) < 1 && Math.abs(y - top) < 1; }
    }
    String lastIssue = "";
    private static final int MAX_UNCERTAIN = 3;

    BoardVision(InputStream input) throws IOException {
        try (DataInputStream data = new DataInputStream(input)) {
            if (data.readInt() != 0x43545032) throw new IOException("Invalid piece templates");
            n = data.readInt();
            int count = data.readInt();
            if (n != 32 || count != 12) throw new IOException("Unsupported piece templates");
            names = new char[count];
            templates = new byte[count][n * n * 3];
            for (int i = 0; i < count; i++) {
                names[i] = (char) data.readUnsignedByte();
                data.readFully(templates[i]);
            }
        }
    }

    Observation read(Frame frame) {
        lastIssue = "Could not detect the chessboard boundary";
        if (frame.width() < 320 || frame.height() < 320) return null;
        Bounds bounds = locate(frame);
        if (bounds == null) return null;
        frame = columns(frame, bounds.left(), bounds.size());
        int width = bounds.size(), bestStart = bounds.top();
        char[] cells = new char[64];
        double worst = 0;
        int whiteKings = 0, blackKings = 0;
        int[] backgrounds = backgrounds(frame, bestStart);
        float cell = width / 8f;
        Match[] matches = new Match[64];
        Selection selection = null;
        long uncertain = 0;
        // Find the enlarged source first. Its crown or base may extend into another square.
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                float x = col * cell, y = bestStart + row * cell;
                Match match = classify(frame, x, y, cell, backgrounds, null);
                matches[row * 8 + col] = match;
                if (match.enlarged()) {
                    if (selection != null) { lastIssue = "Multiple enlarged pieces"; return null; }
                    selection = new Selection(x, y, cell, match.scale());
                }
            }
        }
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                float x = col * cell, y = bestStart + row * cell;
                Match match = matches[row * 8 + col];
                if (selection != null && !selection.source(x, y) && selection.overlaps(x, y)) {
                    match = classify(frame, x, y, cell, backgrounds, selection);
                }
                cells[row * 8 + col] = match.piece();
                if (!match.confident()) {
                    // A check glow, hint tint or small overlay can spoil one square. Keep the rest of
                    // the board; GameTracker resolves the doubtful squares from the legal moves.
                    lastIssue = "cell=" + row + "," + col + " best=" + match.piece()
                            + " error=" + match.error() + " margin=" + match.margin();
                    uncertain |= 1L << (row * 8 + col);
                    if (Long.bitCount(uncertain) > MAX_UNCERTAIN) return null;
                    continue;
                }
                worst = Math.max(worst, match.error());
                if (match.piece() == 'K') whiteKings++;
                if (match.piece() == 'k') blackKings++;
            }
        }
        int open = Long.bitCount(uncertain);
        if (whiteKings > 1 || blackKings > 1 || whiteKings + blackKings + open < 2
                || whiteKings == 0 && open == 0 || blackKings == 0 && open == 0) {
            lastIssue = "Could not verify king count white=" + whiteKings + " black=" + blackKings;
            return null;
        }
        int selected = selection == null ? -1 : Math.round((selection.top() - bestStart) / cell) * 8 + Math.round(selection.left() / cell);
        return new Observation(bounds.left(), bestStart, width, cells, worst, selected, uncertain);
    }

    private Bounds locate(Frame frame) {
        // Revalidate the cached grid in the current pixels; a cache never authorizes a move.
        if (previousBounds != null && previousWidth == frame.width() && previousHeight == frame.height()) {
            Bounds bounds = refine(frame, previousBounds.left(), previousBounds.size());
            if (bounds != null) { previousBounds = bounds; return bounds; }
        }
        int shortest = Math.min(frame.width(), frame.height());
        int minimumCell = Math.max(8, shortest / 96);
        List<Candidate> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        // Equal alternating color runs reveal cell size without assuming screen width or DPI.
        for (int y = 0; y < frame.height(); y += Math.max(1, shortest / 200)) {
            List<Run> runs = new ArrayList<>();
            int start = 0, color = frame.rgb(0, y);
            for (int x = 1; x <= frame.width(); x++) {
                int next = x < frame.width() ? frame.rgb(x, y) : -1;
                if (x < frame.width() && distance(color, next) <= 6) continue;
                int length = x - start;
                if (length >= minimumCell && length <= shortest / 8f * 1.15f && channel(color, 16) <= 110)
                    runs.add(new Run(start, x, color));
                start = x; color = next;
            }
            for (int i = 0; i + 2 < runs.size(); i++) {
                Run first = runs.get(i), second = runs.get(i + 1);
                int contrast = distance(first.color(), second.color());
                if (contrast < 10 || contrast > 80) continue;
                float cell = (first.length() + second.length()) / 2f;
                int count = 1;
                while (count < 8 && i + count < runs.size()) {
                    Run run = runs.get(i + count), before = runs.get(i + count - 1);
                    int expected = count % 2 == 0 ? first.color() : second.color();
                    if (distance(run.color(), expected) > 6 || Math.abs(run.length() - cell) > Math.max(2, cell * .10f)
                            || run.start() - before.end() > Math.max(2, cell * .08f)) break;
                    count++;
                }
                if (count < 3) continue;
                cell = (runs.get(i + count - 1).end() - first.start()) / (float) count;
                int size = Math.round(cell * 8);
                if (size < 128 || size > shortest) continue;
                for (int column = 0; column <= 8 - count; column++) {
                    int left = Math.round(first.start() - column * cell);
                    if (left < 0 || left + size > frame.width()) continue;
                    long key = ((long) left << 32) | size;
                    if (seen.add(key)) candidates.add(new Candidate(left, size, count));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::squares).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::size).reversed()));
        for (Candidate candidate : candidates) {
            Bounds bounds = refine(frame, candidate.left(), candidate.size());
            if (bounds == null) continue;
            previousWidth = frame.width(); previousHeight = frame.height(); previousBounds = bounds;
            return bounds;
        }
        return null;
    }

    private static Bounds refine(Frame frame, int left, int size) {
        Frame viewport = columns(frame, left, size);
        int[] broad = checkerRun(viewport, 0, viewport.height(), null);
        if (broad[1] < size * .95) return null;
        // Avatar colors may extend the coarse run; square colors isolate the actual board.
        int paletteTop = broad[0] + Math.max(0, (broad[1] - size) / 2);
        if (paletteTop + size > frame.height()) return null;
        int[] palette = backgrounds(viewport, paletteTop);
        int[] vertical = checkerRun(viewport, broad[0], broad[0] + broad[1], palette);
        int top = vertical[0], measured = vertical[1];
        if (measured < size * .95 || measured > size * 1.05) return null;
        int boardSize = Math.min(frame.width(), measured);
        int boardLeft = Math.max(0, left + (size - boardSize) / 2);
        boardLeft = alignLeft(frame, boardLeft, top, boardSize, palette);
        if (boardLeft < 0 || boardLeft + boardSize > frame.width() || top < 0 || top + boardSize > frame.height()) return null;
        Frame board = columns(frame, boardLeft, boardSize);
        if (!gridCorners(board, top, palette)) return null;
        return new Bounds(boardLeft, top, boardSize);
    }

    private static int alignLeft(Frame frame, int guess, int top, int size, int[] palette) {
        float cell = size / 8f;
        int radius = Math.max(3, Math.round(cell * .12f)), span = Math.max(1, size / 512);
        float[] offsets = new float[7];
        int count = 0;
        for (int boundary = 1; boundary < 8; boundary++) {
            int center = Math.round(guess + boundary * cell), best = 0, sum = 0, ties = 0;
            for (int x = Math.max(span, center - radius); x <= Math.min(frame.width() - span - 1, center + radius); x++) {
                int votes = 0;
                for (int row = 0; row < 8; row++) for (int edge = 0; edge < 2; edge++) {
                    int y = top + (int) ((row + (edge == 0 ? .035f : .965f)) * cell);
                    if (y < 0 || y >= frame.height()) continue;
                    int a = frame.rgb(x - span, y), b = frame.rgb(x + span, y);
                    if (distance(a, palette[0]) <= 8 && distance(b, palette[1]) <= 8
                            || distance(a, palette[1]) <= 8 && distance(b, palette[0]) <= 8) votes++;
                }
                if (votes > best) { best = votes; sum = x; ties = 1; }
                else if (votes == best) { sum += x; ties++; }
            }
            if (best >= 4) offsets[count++] = sum / (float) ties - boundary * cell;
        }
        if (count < 3) return -1;
        java.util.Arrays.sort(offsets, 0, count);
        return Math.max(0, Math.round(offsets[count / 2]));
    }

    private static Frame columns(Frame source, int left, int width) {
        return new Frame() {
            public int width() { return width; }
            public int height() { return source.height(); }
            public int rgb(int x, int y) { return source.rgb(left + x, y); }
        };
    }

    private static boolean gridCorners(Frame board, int top, int[] palette) {
        // Every column must alternate vertically too, including the outer files. This rejects
        // a shifted window that mistakes a strip of the surrounding background for a file.
        for (int parity = 0; parity < 2; parity++) {
            int[] knownRows = new int[8], knownColumns = new int[8], correctRows = new int[8], correctColumns = new int[8];
            for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
                int pixel = board.rgb((int) ((col + .035f) * board.width() / 8), top + (int) ((row + .035f) * board.width() / 8));
                int a = distance(pixel, palette[0]), b = distance(pixel, palette[1]);
                if (Math.min(a, b) > 8) continue;
                knownRows[row]++; knownColumns[col]++;
                if ((a <= b ? 0 : 1) == (row + col + parity) % 2) { correctRows[row]++; correctColumns[col]++; }
            }
            boolean valid = true;
            for (int i = 0; i < 8; i++) if (knownRows[i] < 4 || knownColumns[i] < 4
                    || correctRows[i] * 4 < knownRows[i] * 3 || correctColumns[i] * 4 < knownColumns[i] * 3) valid = false;
            if (valid) return true;
        }
        return false;
    }

    private static int[] checkerRun(Frame frame, int from, int to, int[] palette) {
        int runStart = -1, bestStart = -1, bestLength = 0, gap = 0;
        int allowedGap = Math.max(2, frame.width() / 100);
        for (int y = from; y <= to + allowedGap; y++) {
            if (y < to && checkerRow(frame, y, palette)) {
                if (runStart < 0) runStart = y;
                gap = 0;
            } else if (runStart >= 0) {
                // Resampling blends the two square colors on rank boundaries for a few pixels.
                if (++gap <= allowedGap) continue;
                int length = y - gap + 1 - runStart;
                if (length > bestLength) { bestStart = runStart; bestLength = length; }
                runStart = -1;
                gap = 0;
            }
        }
        return new int[]{bestStart, bestLength};
    }

    private static boolean checkerRow(Frame frame, int y, int[] palette) {
        int[] samples = new int[8];
        for (int col = 0; col < 8; col++) {
            samples[col] = frame.rgb(col * frame.width() / 8 + Math.max(1, frame.width() / 280), y);
        }
        if (palette != null) return checkerPattern(samples, palette[0], palette[1])
                || checkerPattern(samples, palette[1], palette[0]);
        for (int even = 0; even < 8; even += 2) {
            for (int odd = 1; odd < 8; odd += 2) {
                int a = samples[even], b = samples[odd];
                int contrast = distance(a, b);
                if (contrast < 10 || contrast > 80 || channel(a, 16) > 110 || channel(b, 16) > 110) continue;
                if (checkerPattern(samples, a, b)) return true;
            }
        }
        return false;
    }

    private static boolean checkerPattern(int[] samples, int even, int odd) {
        int matches = 0, evenMatches = 0, oddMatches = 0;
        for (int col = 0; col < 8; col++) {
            if (distance(samples[col], col % 2 == 0 ? even : odd) <= 6) {
                matches++;
                if (col % 2 == 0) evenMatches++; else oddMatches++;
            }
        }
        // Selected sprites and capture-target tints can affect three probes on one row.
        return matches >= 5 && evenMatches >= 2 && oddMatches >= 2;
    }

    private boolean moveDot(byte[] feature, int foreground) {
        if (foreground > n * n * .06) return false;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int offset = (y * n + x) * 3;
                if (feature[offset] == 0 && feature[offset + 1] == 0 && feature[offset + 2] == 0) continue;
                if (Math.hypot(x + .5 - n / 2.0, y + .5 - n / 2.0) > n * .15) return false;
                if ((feature[offset] & 255) > (feature[offset + 1] & 255)) return false;
            }
        }
        return true;
    }

    private static int[] backgrounds(Frame frame, int top) {
        int[] samples = new int[64];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                samples[row * 8 + col] = frame.rgb(col * frame.width() / 8 + Math.max(1, frame.width() / 280),
                        top + (int) ((row + .5f) * frame.width() / 8));
            }
        }
        int first = samples[0], second = samples[1], most = -1;
        for (int candidate : samples) {
            if (channel(candidate, 16) > 110) continue;
            int count = 0;
            for (int sample : samples) if (distance(sample, candidate) <= 6) count++;
            if (count > most) { first = candidate; most = count; }
        }
        most = -1;
        for (int candidate : samples) {
            int contrast = distance(first, candidate);
            if (channel(candidate, 16) > 110 || contrast < 10 || contrast > 80) continue;
            int count = 0;
            for (int sample : samples) if (distance(sample, candidate) <= 6) count++;
            if (count > most) { second = candidate; most = count; }
        }
        return new int[]{first, second};
    }

    private Match classify(Frame frame, float left, float top, float cell, int[] backgrounds, Selection excluded) {
        int localBackground = localBackground(frame, left, top, cell, backgrounds, excluded);
        byte[] normal = sample(frame, left, top, cell, 1f, backgrounds, localBackground, excluded);
        int foreground = 0;
        for (int i = 0; i < normal.length; i += 3) if (normal[i] != 0 || normal[i + 1] != 0 || normal[i + 2] != 0) foreground++;
        if (foreground < n * n * .025 || moveDot(normal, foreground)) return new Match('.', 0, 1, 1, 0);
        double[] errors = new double[templates.length];
        float[] scales = new float[templates.length];
        java.util.Arrays.fill(errors, Double.MAX_VALUE);
        double normalError = Double.MAX_VALUE;
        for (float scale : PIECE_SCALES) {
            byte[] candidate = scale == 1f ? normal : sample(frame, left, top, cell, scale, backgrounds, localBackground, excluded);
            for (int i = 0; i < templates.length; i++) {
                long sum = 0;
                for (int j = 0; j < candidate.length; j++) {
                    int delta = (candidate[j] & 255) - (templates[i][j] & 255);
                    sum += (long) delta * delta;
                }
                double error = sum / (65025.0 * candidate.length);
                if (scale == 1f) normalError = Math.min(normalError, error);
                if (error < errors[i]) { errors[i] = error; scales[i] = scale; }
            }
        }
        int bestIndex = 0;
        double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
        for (int i = 0; i < templates.length; i++) {
            if (errors[i] < best) { second = best; best = errors[i]; bestIndex = i; }
            else if (errors[i] < second) second = errors[i];
        }
        return new Match(names[bestIndex], best, second - best, scales[bestIndex], normalError);
    }

    private static int localBackground(Frame frame, float left, float top, float cell, int[] backgrounds, Selection excluded) {
        int[] samples = new int[4];
        int count = 0;
        for (float dy : new float[]{.035f, .965f}) {
            for (float dx : new float[]{.035f, .965f}) {
                int x = (int) (left + dx * cell), y = (int) (top + dy * cell);
                if (excluded == null || !excluded.contains(x, y)) samples[count++] = frame.rgb(x, y);
            }
        }
        int result = backgrounds[0], best = -1, closest = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int votes = 0;
            for (int j = 0; j < count; j++) if (distance(samples[i], samples[j]) <= 6) votes++;
            int contrast = Math.min(distance(samples[i], backgrounds[0]), distance(samples[i], backgrounds[1]));
            if (votes > best || votes == best && contrast < closest) {
                result = samples[i]; best = votes; closest = contrast;
            }
        }
        return result;
    }

    private byte[] sample(Frame frame, float left, float top, float cell, float scale,
                          int[] backgrounds, int localBackground, Selection excluded) {
        byte[] result = new byte[n * n * 3];
        float size = cell * scale;
        left -= (size - cell) / 2;
        top -= (size - cell) / 2;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = Math.max(0, Math.min(frame.width() - 1, (int) (left + (x + .5f) * size / n)));
                int py = Math.max(0, Math.min(frame.height() - 1, (int) (top + (y + .5f) * size / n)));
                if (excluded != null && excluded.contains(px, py)) continue;
                int pixel = frame.rgb(px, py);
                if (distance(pixel, localBackground) <= 16 || scale > 1f
                        && (distance(pixel, backgrounds[0]) <= 16 || distance(pixel, backgrounds[1]) <= 16)) continue;
                int offset = (y * n + x) * 3;
                result[offset] = (byte) channel(pixel, 16);
                result[offset + 1] = (byte) channel(pixel, 8);
                result[offset + 2] = (byte) channel(pixel, 0);
            }
        }
        return result;
    }

    private static int channel(int rgb, int shift) { return (rgb >> shift) & 255; }
    private static int distance(int a, int b) {
        return Math.max(Math.abs(channel(a, 16) - channel(b, 16)),
                Math.max(Math.abs(channel(a, 8) - channel(b, 8)), Math.abs(channel(a, 0) - channel(b, 0))));
    }
}
