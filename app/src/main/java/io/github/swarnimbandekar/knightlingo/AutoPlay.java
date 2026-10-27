package io.github.swarnimbandekar.knightlingo;

/** Acts on positions already confirmed by GameTracker. Main thread only. */
final class AutoPlay {
    record Position(String fen, boolean whiteBottom, boolean myTurn, long seenAt,
                    int left, int top, int size, int selectedCell, boolean terminal) {}
    record Action(long id, String fen, String move, int fromX, int fromY, int toX, int toY, boolean sourceSelected, int attempt) {}
    private enum Stage { OFF, READY, MOVING, WAITING_CHANGE }
    private Stage stage = Stage.OFF;
    private Position origin;
    private Action pending;
    private String move;
    private long id, phaseAt, resumeAfter = -1;
    private boolean boardUnavailable, finished;
    private int attempts;
    private String note = "";

    boolean enabled() { return stage != Stage.OFF; }
    String note() { return note; }
    boolean waitingForBoard() { return enabled() && boardUnavailable; }
    boolean waitingForMove() { return stage == Stage.WAITING_CHANGE; }
    boolean waitingForNextGame() { return enabled() && finished; }
    boolean promotionPending() { return stage == Stage.WAITING_CHANGE && move != null && move.length() == 5; }
    char promotionPiece() { return promotionPending() ? move.charAt(4) : '?'; }
    String pendingFen() { return origin == null ? null : origin.fen(); }
    boolean pending(Action action) { return action != null && enabled() && pending != null && pending.id() == action.id(); }

    void waitForInput(long now) {
        resumeAfter = now;
        boardUnavailable = true;
        if (stage == Stage.MOVING) complete(pending, false, now);
    }

    void start(long now) {
        resetMove();
        phaseAt = now;
        resumeAfter = -1;
        boardUnavailable = false;
        finished = false;
        note = "";
    }

    void pause(String reason) {
        stage = Stage.OFF;
        pending = null;
        note = reason;
    }

    // A candidate blocked by the panel has not sent any touch yet.
    void defer(Action action, long now) {
        if (!pending(action)) return;
        resetMove();
        waitForInput(now);
    }

    Action next(long now, Position position, String analyzedFen, MoveTip.Tip tip, boolean foreground) {
        if (!enabled()) return null;
        if (!foreground) { waitForInput(now); return null; }
        if (stage == Stage.MOVING) {
            if (now - phaseAt > 2000) complete(pending, false, now);
            return null;
        }
        if (position == null || now - position.seenAt() > 750 || position.seenAt() <= resumeAfter) {
            boardUnavailable = true;
            if (position == null || now - position.seenAt() > 750) resumeAfter = now;
            return null;
        }
        boardUnavailable = false;
        if (position.terminal()) { resetMove(); finished = true; return null; }
        finished = false;
        if (origin != null && (position.whiteBottom() != origin.whiteBottom() || position.left() != origin.left()
                || position.top() != origin.top() || position.size() != origin.size())) {
            resetMove();
            waitForInput(now);
            return null;
        }
        if (stage == Stage.WAITING_CHANGE) {
            if (position.seenAt() <= phaseAt + 120) return null;
            if (position.fen().equals(origin.fen())) {
                if (!promotionPending() && position.myTurn() && position.seenAt() - phaseAt >= 1500) {
                    return issue(position, now);
                }
                return null;
            }
            // The tracker validates legal moves, manual moves and a new game's opening.
            resetMove();
        }
        if (!position.myTurn() || tip == null || !position.fen().equals(analyzedFen)) {
            return null;
        }
        // The tracker has already confirmed stable legal frames. Act as soon as analysis is ready.
        move = tip.move();
        return issue(position, now);
    }

    private void resetMove() {
        stage = Stage.READY;
        origin = null;
        pending = null;
        move = null;
        attempts = 0;
    }

    private Action issue(Position position, long now) {
        origin = position;
        int source = ((y(0) - origin.top()) * 8 / origin.size()) * 8 + (x(0) - origin.left()) * 8 / origin.size();
        pending = new Action(++id, origin.fen(), move, x(0), y(0), x(2), y(2), origin.selectedCell() == source, ++attempts);
        stage = Stage.MOVING;
        phaseAt = now;
        return pending;
    }

    private int x(int offset) {
        int file = move.charAt(offset) - 'a';
        return Math.round(origin.left() + ((origin.whiteBottom() ? file : 7 - file) + .5f) * origin.size() / 8);
    }

    private int y(int offset) {
        int rank = move.charAt(offset + 1) - '1';
        return Math.round(origin.top() + ((origin.whiteBottom() ? 7 - rank : rank) + .5f) * origin.size() / 8);
    }

    void complete(Action action, boolean success, long now) {
        if (!pending(action)) return;
        pending = null;
        stage = Stage.WAITING_CHANGE;
        phaseAt = now;
        if (!success) { resumeAfter = now; boardUnavailable = true; return; }
        // Duolingo covers the board with the result screen right after a mate, so the final
        // position may never be read. Recognize a game-ending move from the move itself.
        if (endsGame(action.fen(), action.move())) finished = true;
    }

    private static boolean endsGame(String fen, String move) {
        try {
            com.github.bhlangonijr.chesslib.Board board = new com.github.bhlangonijr.chesslib.Board();
            board.loadFromFen(fen);
            board.doMove(new com.github.bhlangonijr.chesslib.move.Move(move, board.getSideToMove()));
            return board.legalMoves().isEmpty();
        } catch (RuntimeException error) {
            return false;
        }
    }
}
