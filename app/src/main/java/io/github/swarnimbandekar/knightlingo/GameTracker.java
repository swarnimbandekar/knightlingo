package io.github.swarnimbandekar.knightlingo;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

/** Updates turn/castling/en-passant state only when a unique legal path matches the observed placement. */
final class GameTracker {
    private Board board;
    private boolean whiteBottom;
    private String pending;
    private int stableCount;
    private int desync;
    private static final int RESYNC_AFTER = 4;

    GameTracker() {}

    GameTracker(String savedFen, boolean savedWhiteBottom) {
        if (savedFen != null) {
            board = new Board();
            board.loadFromFen(savedFen);
            whiteBottom = savedWhiteBottom;
        }
    }

    boolean accept(BoardVision.Observation observation) {
        if (observation == null) { pending = null; stableCount = 0; return false; }
        String raw = observation.key();
        stableCount = raw.equals(pending) ? stableCount + 1 : 1;
        pending = raw;
        if (stableCount < 2) return false;
        if (board != null) {
            if (observation.matches(placement(board), whiteBottom)) { desync = 0; return true; }
            String matched = findSuccessor(board, observation, whiteBottom);
            if (matched != null) { board.loadFromFen(matched); desync = 0; return true; }
        }
        // Starting or rebuilding a game needs every square; a partial read only follows legal moves.
        if (!observation.certain()) return false;
        // A new game may begin with either the initial board or Oscar's first white move.
        for (boolean direction : new boolean[]{true, false}) {
            String observed = observation.placement(direction);
            Board start = new Board();
            if (placement(start).equals(observed)) { board = start; whiteBottom = direction; return true; }
            for (Move move : start.legalMoves()) {
                start.doMove(move);
                if (placement(start).equals(observed)) { board = start; whiteBottom = direction; return true; }
                start.undoMove();
            }
        }
        // Mid-game the tracker can fall behind (several moves between readable frames, a
        // transient misread, or an ambiguous path). Rebuild from the stable placement instead
        // of waiting forever for a legal path that no longer exists.
        if (board != null && ++desync >= RESYNC_AFTER) {
            Board rebuilt = rebuild(observation.placement(whiteBottom), whiteBottom);
            if (rebuilt != null) { board = rebuilt; desync = 0; return true; }
        }
        return false;
    }

    /** Places the observed pieces on a fresh board, guessing the turn and castling rights. */
    private static Board rebuild(String placement, boolean whiteBottom) {
        String mine = whiteBottom ? "w" : "b";
        String theirs = whiteBottom ? "b" : "w";
        for (String side : new String[]{mine, theirs}) {
            String[] ranks = placement.split("/");
            StringBuilder rights = new StringBuilder();
            if (ranks[7].endsWith("K2R")) rights.append('K');
            if (ranks[7].startsWith("R3K")) rights.append('Q');
            if (ranks[0].endsWith("k2r")) rights.append('k');
            if (ranks[0].startsWith("r3k")) rights.append('q');
            if (rights.length() == 0) rights.append('-');
            try {
                Board candidate = new Board();
                candidate.loadFromFen(placement + " " + side + " " + rights + " - 0 1");
                // The side that just moved can never be left in check.
                candidate.setSideToMove(candidate.getSideToMove().flip());
                boolean illegal = candidate.isKingAttacked();
                candidate.setSideToMove(candidate.getSideToMove().flip());
                if (!illegal && !candidate.legalMoves().isEmpty()) return candidate;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static String findSuccessor(Board current, BoardVision.Observation observed, boolean whiteBottom) {
        // A single move is preferred. With doubtful squares more than one may fit; then wait.
        String single = null;
        for (Move first : current.legalMoves()) {
            current.doMove(first);
            if (observed.matches(placement(current), whiteBottom)) {
                String fen = current.getFen();
                if (single != null && !single.equals(fen)) { current.undoMove(); return null; }
                single = fen;
            }
            current.undoMove();
        }
        if (single != null) return single;
        String match = null;
        for (Move first : current.legalMoves()) {
            current.doMove(first);
            for (Move second : current.legalMoves()) {
                current.doMove(second);
                if (observed.matches(placement(current), whiteBottom)) {
                    String fen = current.getFen();
                    if (match != null && !match.equals(fen)) {
                        current.undoMove(); current.undoMove(); return null;
                    }
                    match = fen;
                }
                current.undoMove();
            }
            current.undoMove();
        }
        return match;
    }

    String fen() { return board == null ? null : board.getFen(); }
    boolean terminal() { return board != null && board.legalMoves().isEmpty(); }
    boolean whiteBottom() { return whiteBottom; }
    boolean myTurn() { return board != null && board.getSideToMove() == (whiteBottom ? Side.WHITE : Side.BLACK); }
    String sideName() { return whiteBottom ? "White" : "Black"; }
    private static String placement(Board position) { return position.getFen().split(" ")[0]; }
}
