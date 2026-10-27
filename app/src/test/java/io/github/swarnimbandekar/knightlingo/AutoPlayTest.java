package io.github.swarnimbandekar.knightlingo;

import static org.junit.Assert.*;
import com.github.bhlangonijr.chesslib.Board;
import org.junit.Test;

public class AutoPlayTest {
    private static final String START = new Board().getFen();
    private AutoPlay.Position position(String fen, boolean white, boolean turn, long time) {
        return new AutoPlay.Position(fen, white, turn, time, 0, 100, 800, -1, false);
    }
    private AutoPlay.Position white(long time) { return position(START, true, true, time); }
    private MoveTip.Tip tip(String fen, String move) {
        return MoveTip.explain(fen, new StockfishEngine.Result(move, "", 20));
    }
    private AutoPlay.Action begin(AutoPlay auto) {
        auto.start(0);
        return auto.next(0, white(0), START, tip(START, "e2e4"), true);
    }

    @Test public void sendsImmediateMoveWithBothCoordinatesAndWaitsForCallback() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        assertNotNull(action);
        assertEquals(450, action.fromX()); assertEquals(750, action.fromY());
        assertEquals(450, action.toX()); assertEquals(550, action.toY());
        assertNull(auto.next(50, white(50), START, tip(START, "e2e4"), true));
        auto.complete(action, true, 60);
        assertNull(auto.next(300, white(300), START, tip(START, "e2e4"), true));
    }

    @Test public void ownMatingMoveReportsGameOverEvenWhenTheBoardIsCovered() {
        // From a real game: Qxg1# and Duolingo's result screen hid the final board.
        String fen = "r3kb1r/ppp2pp1/2n5/4p2P/P7/7b/1PPPPP2/R1BQK1Nq b kq - 1 10";
        AutoPlay auto = new AutoPlay(); auto.start(0);
        AutoPlay.Action action = auto.next(0, position(fen, false, true, 0), fen, tip(fen, "h1g1"), true);
        assertNotNull(action);
        auto.complete(action, true, 100);
        assertNull(auto.next(2000, null, fen, tip(fen, "h1g1"), true));
        assertTrue(auto.waitingForNextGame());
    }

    @Test public void blackCoordinatesAreRotated() {
        Board board = new Board(); board.doMove("e4"); String fen = board.getFen();
        AutoPlay auto = new AutoPlay(); auto.start(0);
        AutoPlay.Action action = auto.next(0, position(fen, false, true, 0), fen, tip(fen, "e7e5"), true);
        assertNotNull(action);
        assertEquals(350, action.fromX()); assertEquals(750, action.fromY());
        assertEquals(350, action.toX()); assertEquals(550, action.toY());
    }

    @Test public void requiresFreshFrameAndCorrectEnginePositionOnOwnTurn() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        assertNull(auto.next(2000, white(0), START, tip(START, "e2e4"), true));
        assertNull(auto.next(2100, null, START, tip(START, "e2e4"), true));
        assertNull(auto.next(2200, white(2200), "different", tip(START, "e2e4"), true));
        assertNull(auto.next(2300, position(START, true, false, 2300), START, tip(START, "e2e4"), true));
        assertNotNull(auto.next(2400, white(2400), START, tip(START, "e2e4"), true));
    }

    @Test public void analysisReadyDoesNotAddAnArtificialPreviewDelay() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        assertNull(auto.next(0, white(0), null, null, true));
        AutoPlay.Action action = auto.next(1000, white(1000), START, tip(START, "d2d4"), true);
        assertNotNull(action); assertEquals("d2d4", action.move());
    }

    @Test public void confirmsOwnMoveAndOpponentReplySkippedBetweenFrames() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        auto.complete(action, true, 60);
        Board board = new Board(); board.doMove("e4"); board.doMove("e5"); String fen = board.getFen();
        AutoPlay.Action next = auto.next(1000, position(fen, true, true, 1000), fen, tip(fen, "g1f3"), true);
        assertNotNull(next); assertEquals("g1f3", next.move());
    }

    @Test public void confirmsOwnMoveButWaitsForOpponent() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        auto.complete(action, true, 60);
        Board board = new Board(); board.doMove("e4"); String fen = board.getFen();
        assertNull(auto.next(1000, position(fen, true, false, 1000), START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled());
        assertNull(auto.next(8000, position(fen, true, false, 8000), START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled());
    }

    @Test public void confirmedManualMoveKeepsModeOnAndUsesTheNewPosition() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        auto.complete(action, true, 60);
        Board board = new Board(); board.doMove("d4"); String fen = board.getFen();
        assertNull(auto.next(1000, position(fen, true, false, 1000), START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled());
        board.doMove("d5"); fen = board.getFen();
        AutoPlay.Action next = auto.next(2000, position(fen, true, true, 2000), fen, tip(fen, "g1f3"), true);
        assertNotNull(next); assertEquals(fen, next.fen());
    }

    @Test public void leavingGameCancelsPendingTapsButKeepsModeOn() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        assertNull(auto.next(10, white(10), START, tip(START, "e2e4"), false));
        assertTrue(auto.enabled()); assertFalse(auto.pending(action));
        auto.complete(action, true, 60); assertTrue(auto.enabled());
        assertNull(auto.next(100, white(10), START, tip(START, "e2e4"), true));
        AutoPlay.Action retry = auto.next(1510, white(1510), START, tip(START, "e2e4"), true);
        assertNotNull(retry);
        auto.complete(retry, false, 1700);
        assertTrue(auto.enabled()); assertTrue(auto.waitingForBoard());
        assertNull(auto.next(1800, white(1700), START, tip(START, "e2e4"), true));
    }

    @Test public void missingCallbackWaitsAndUnchangedBoardKeepsRetryingAtTheInterval() {
        AutoPlay auto = new AutoPlay(); begin(auto);
        assertNull(auto.next(2001, white(2001), START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForMove());
        AutoPlay.Action action = begin(auto); auto.complete(action, true, 60);
        assertNull(auto.next(300, white(300), START, tip(START, "e2e4"), true));
        assertNull(auto.next(1559, white(1559), START, tip(START, "e2e4"), true));
        AutoPlay.Action retry = auto.next(1560, white(1560), START, tip(START, "e2e4"), true);
        assertNotNull(retry); assertEquals(action.move(), retry.move());
        assertNotEquals(action.id(), retry.id());
        auto.complete(retry, true, 1800);
        assertNull(auto.next(3299, white(3299), START, tip(START, "e2e4"), true));
        AutoPlay.Action again = auto.next(3300, white(3300), START, tip(START, "e2e4"), true);
        assertNotNull("A second failed attempt must not leave playback stuck", again);
        assertEquals(action.move(), again.move());
        assertNotEquals(retry.id(), again.id());
        auto.complete(again, true, 3500);
        assertNull(auto.next(4999, white(4999), START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForMove());
        assertNotNull(auto.next(5000, white(5000), START, tip(START, "e2e4"), true));
    }

    @Test public void repeatedRetryStillRequiresFreshBoardOwnTurnAndForeground() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        auto.complete(action, true, 60);
        AutoPlay.Action retry = auto.next(1560, white(1560), START, tip(START, "e2e4"), true);
        auto.complete(retry, true, 1800);
        assertNull(auto.next(4000, null, START, tip(START, "e2e4"), true));
        assertNull(auto.next(4100, white(3999), START, tip(START, "e2e4"), true));
        assertNull(auto.next(4200, position(START, true, false, 4200), START, tip(START, "e2e4"), true));
        assertNull(auto.next(4300, white(4300), START, tip(START, "e2e4"), false));
        assertNull(auto.next(4400, white(4300), START, tip(START, "e2e4"), true));
        AutoPlay.Action again = auto.next(4500, white(4500), START, tip(START, "e2e4"), true);
        assertNotNull(again);
        auto.pause("manual");
        auto.complete(again, true, 4700);
        assertNull(auto.next(10000, white(10000), START, tip(START, "e2e4"), true));
        assertFalse(auto.enabled());
    }

    @Test public void pauseAndOldCallbacksCannotResumePlayback() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action old = begin(auto);
        auto.pause("manual"); auto.complete(old, true, 60); assertFalse(auto.enabled());
        auto.start(2000); auto.complete(old, true, 2100);
        AutoPlay.Action current = auto.next(2200, white(2200), START, tip(START, "e2e4"), true);
        assertNotNull(current); assertNotEquals(old.id(), current.id());
    }

    @Test public void finishedGameKeepsModeOnThenStartsTheNextOpening() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        String mate = "7k/6Q1/6K1/8/8/8/8/8 b - - 0 40";
        AutoPlay.Position ended = new AutoPlay.Position(mate, true, false, 0, 0, 100, 800, -1, true);
        assertNull(auto.next(0, ended, null, null, true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForNextGame());
        assertNull(auto.next(10000, null, null, null, false));
        assertTrue(auto.enabled());
        assertNull(auto.next(10100, white(10100), null, null, true));
        assertFalse(auto.waitingForNextGame());
        assertNotNull(auto.next(10200, white(10200), START, tip(START, "e2e4"), true));
    }

    @Test public void promotionWaitsIndefinitelyThenConfirmsChosenPiece() {
        String fen = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1";
        AutoPlay auto = new AutoPlay(); auto.start(0);
        AutoPlay.Action action = auto.next(0, position(fen, true, true, 0), fen, tip(fen, "a7a8q"), true);
        auto.complete(action, true, 60);
        assertTrue(auto.promotionPending()); assertEquals('q', auto.promotionPiece());
        assertNull(auto.next(3000, null, fen, tip(fen, "a7a8q"), true));
        assertNull(auto.next(3600000, null, fen, tip(fen, "a7a8q"), true));
        assertTrue(auto.enabled());
        Board board = new Board(); board.loadFromFen(fen); board.doMove("a8=Q");
        assertNull(auto.next(3600100, position(board.getFen(), true, false, 3600100), fen, tip(fen, "a7a8q"), true));
        assertTrue(auto.enabled()); assertFalse(auto.promotionPending());
    }

    @Test public void temporaryUnreadableBoardResumesImmediatelyOnConfirmedFrame() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        assertNull(auto.next(600, null, START, tip(START, "e2e4"), true));
        assertNull(auto.next(7000, null, START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForBoard());
        assertNotNull(auto.next(8000, white(8000), START, tip(START, "e2e4"), true));
        assertFalse(auto.waitingForBoard());
    }

    @Test public void unreadableAnimationAfterMoveDoesNotRepeatTheMove() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action action = begin(auto);
        auto.complete(action, true, 60);
        assertNull(auto.next(300, white(300), START, tip(START, "e2e4"), true));
        assertNull(auto.next(2000, null, START, tip(START, "e2e4"), true));
        assertNull(auto.next(120000, null, START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled());
        Board board = new Board(); board.doMove("e4"); board.doMove("e5"); String fen = board.getFen();
        assertNotNull(auto.next(121000, position(fen, true, true, 121000), fen, tip(fen, "g1f3"), true));
    }

    @Test public void prolongedUnknownBoardWaitsWithoutSendingInputOrTurningOff() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        assertNull(auto.next(0, null, START, tip(START, "e2e4"), true));
        assertNull(auto.next(86400000, null, START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForBoard());
        assertNotNull(auto.next(86400100, white(86400100), START, tip(START, "e2e4"), true));
    }

    @Test public void reconnectWaitRequiresNewFrameWithoutExtraPreview() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        auto.waitForInput(2000);
        assertNull(auto.next(2100, white(1900), START, tip(START, "e2e4"), true));
        assertNull(auto.next(2200, white(2000), START, tip(START, "e2e4"), true));
        assertNotNull(auto.next(2300, white(2250), START, tip(START, "e2e4"), true));
    }

    @Test public void alreadySelectedSourceSkipsTheFirstTap() {
        AutoPlay auto = new AutoPlay(); auto.start(0);
        AutoPlay.Position selected = new AutoPlay.Position(START, true, true, 0, 0, 100, 800, 52, false);
        AutoPlay.Action action = auto.next(0, selected, START, tip(START, "e2e4"), true);
        assertNotNull(action); assertTrue(action.sourceSelected());
        assertTrue(auto.pending(action));
        auto.pause("manual"); assertFalse(auto.pending(action));
    }

    @Test public void retryUsesCurrentSelectionAndIgnoresTheOldCallback() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action first = begin(auto);
        auto.complete(first, true, 60);
        assertNull(auto.next(2000, white(1000), START, tip(START, "e2e4"), true));
        assertNull(auto.next(2100, null, START, tip(START, "e2e4"), true));
        AutoPlay.Position selected = new AutoPlay.Position(START, true, true, 2200, 0, 100, 800, 52, false);
        AutoPlay.Action retry = auto.next(2200, selected, START, tip(START, "e2e4"), true);
        assertNotNull(retry); assertTrue(retry.sourceSelected());
        auto.complete(first, false, 2250);
        assertTrue(auto.pending(retry));
        auto.pause("manual"); assertFalse(auto.pending(retry));
    }

    @Test public void confirmedProgressStartsTheNextMoveWithFreshRetryTiming() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action first = begin(auto);
        auto.complete(first, true, 60);
        AutoPlay.Action retry = auto.next(1600, white(1600), START, tip(START, "e2e4"), true);
        auto.complete(retry, true, 1800);
        Board board = new Board(); board.doMove("e4"); board.doMove("e5"); String fen = board.getFen();
        AutoPlay.Action second = auto.next(2500, position(fen, true, true, 2500), fen, tip(fen, "g1f3"), true);
        assertNotNull(second); auto.complete(second, true, 2700);
        AutoPlay.Action secondRetry = auto.next(4200, position(fen, true, true, 4200), fen, tip(fen, "g1f3"), true);
        assertNotNull(secondRetry); assertEquals("g1f3", secondRetry.move());
    }

    @Test public void newGameCanChangeColorWhileThePreviousMoveWasPending() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action old = begin(auto);
        auto.complete(old, true, 60);
        assertNull(auto.next(300, position(START, false, false, 300), null, null, true));
        assertTrue(auto.enabled()); assertTrue(auto.waitingForBoard());
        Board board = new Board(); board.doMove("e4"); String fen = board.getFen();
        assertNull(auto.next(500, position(fen, false, true, 500), START, tip(START, "e2e4"), true));
        AutoPlay.Action next = auto.next(600, position(fen, false, true, 600), fen, tip(fen, "e7e5"), true);
        assertNotNull(next); assertEquals("e7e5", next.move());
        assertEquals(350, next.fromX()); assertEquals(750, next.fromY());
        auto.complete(old, false, 650); assertTrue(auto.pending(next));
    }

    @Test public void boardRelocationWaitsForFreshCoordinatesAndContinues() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action old = begin(auto);
        auto.complete(old, true, 60);
        AutoPlay.Position shifted = new AutoPlay.Position(START, true, true, 300, 10, 120, 800, -1, false);
        assertNull(auto.next(300, shifted, START, tip(START, "e2e4"), true));
        assertTrue(auto.enabled());
        AutoPlay.Position fresh = new AutoPlay.Position(START, true, true, 400, 10, 120, 800, -1, false);
        AutoPlay.Action next = auto.next(400, fresh, START, tip(START, "e2e4"), true);
        assertNotNull(next); assertEquals(460, next.fromX()); assertEquals(770, next.fromY());
        auto.complete(old, true, 450); assertTrue(auto.pending(next));
    }

    @Test public void panelBlockedCandidateCanContinueWithoutTogglingTheMode() {
        AutoPlay auto = new AutoPlay(); AutoPlay.Action blocked = begin(auto);
        auto.defer(blocked, 100);
        assertTrue(auto.enabled()); assertFalse(auto.pending(blocked));
        assertNull(auto.next(200, white(100), START, tip(START, "e2e4"), true));
        assertNotNull(auto.next(300, white(300), START, tip(START, "e2e4"), true));
    }

    @Test public void sameColorNewGameReplacesTheOldPendingMove() {
        String later = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2";
        AutoPlay auto = new AutoPlay(); auto.start(0);
        AutoPlay.Action old = auto.next(0, position(later, true, true, 0), later, tip(later, "g1f3"), true);
        auto.complete(old, true, 60);
        AutoPlay.Action opening = auto.next(1000, white(1000), START, tip(START, "d2d4"), true);
        assertNotNull(opening); assertEquals("d2d4", opening.move());
        assertTrue(auto.enabled());
    }
}
