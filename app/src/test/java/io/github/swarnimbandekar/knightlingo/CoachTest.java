package io.github.swarnimbandekar.knightlingo;

import static org.junit.Assert.*;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

public class CoachTest {
    private static final String E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR";

    private BoardVision vision() throws Exception {
        File file = new File("src/main/assets/pieces.bin");
        if (!file.exists()) file = new File("app/src/main/assets/pieces.bin");
        return new BoardVision(new FileInputStream(file));
    }

    private BoardVision.Frame fixture(String name) throws Exception {
        DataInputStream input = new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/" + name + ".rgb.gz")));
        int width = input.readInt(), height = input.readInt();
        byte[] pixels = new byte[width * height * 3];
        input.readFully(pixels);
        input.close();
        return new BoardVision.Frame() {
            public int width() { return width; }
            public int height() { return height; }
            public int rgb(int x, int y) {
                int i = (y * width + x) * 3;
                return (pixels[i] & 255) << 16 | (pixels[i + 1] & 255) << 8 | pixels[i + 2] & 255;
            }
        };
    }

    private static BoardVision.Observation observation(String fen, boolean whiteBottom) {
        char[] cells = new char[64];
        String placement = fen.split(" ")[0];
        int index = 0;
        for (char token : placement.toCharArray()) {
            if (token == '/') continue;
            int count = Character.isDigit(token) ? token - '0' : 1;
            for (int j = 0; j < count; j++) {
                int target = whiteBottom ? index : 63 - index;
                cells[target] = Character.isDigit(token) ? '.' : token;
                index++;
            }
        }
        return new BoardVision.Observation(0, 100, 800, cells, 0);
    }

    private static void accept(GameTracker tracker, BoardVision.Observation observation) {
        tracker.accept(observation);
        assertTrue(tracker.accept(observation));
    }

    @Test public void readsActualBlackPerspectiveAfterE4() throws Exception {
        BoardVision.Observation result = vision().read(fixture("duo-black-e4"));
        assertNotNull(result);
        assertEquals(1099, result.top());
        assertEquals(E4, result.placement(false));
        GameTracker tracker = new GameTracker();
        assertFalse(tracker.accept(result));
        assertTrue(tracker.accept(result));
        assertFalse(tracker.whiteBottom());
        assertTrue(tracker.myTurn());
        assertEquals("b", tracker.fen().split(" ")[1]);
    }

    @Test public void scalesScreenshotAndRejectsNonBoard() throws Exception {
        BoardVision detector = vision();
        BoardVision.Observation result = detector.read(fixture("duo-black-e4-half"));
        assertNotNull(detector.lastIssue, result);
        assertEquals(E4, result.placement(false));
        assertNull(vision().read(new BoardVision.Frame() {
            public int width() { return 720; }
            public int height() { return 1544; }
            public int rgb(int x, int y) { return 0; }
        }));
    }

    @Test public void avatarAbovePvpBoardDoesNotPreventAutomaticBlackReply() throws Exception {
        BoardVision detector = vision();
        BoardVision.Observation result = detector.read(fixture("duo-pvp-black-e4-wait"));
        assertNotNull(detector.lastIssue, result);
        assertEquals(E4, result.placement(false));
        assertTrue("The board begins below the avatar", Math.abs(result.top() - 414) <= 2);
        GameTracker tracker = new GameTracker();
        assertFalse(tracker.accept(result));
        assertTrue(tracker.accept(result));
        assertFalse(tracker.whiteBottom());
        assertTrue(tracker.myTurn());
        AutoPlay auto = new AutoPlay();
        auto.start(0);
        AutoPlay.Position position = new AutoPlay.Position(tracker.fen(), tracker.whiteBottom(), tracker.myTurn(),
                0, result.left(), result.top(), result.size(), result.selectedCell(), false);
        MoveTip.Tip tip = MoveTip.explain(tracker.fen(), new StockfishEngine.Result("e7e5", "", 20));
        AutoPlay.Action action = auto.next(0, position, tracker.fen(), tip, true);
        assertNotNull("A readable opening must allow the automatic reply", action);
        assertEquals("e7e5", action.move());
    }

    @Test public void mumuQhdBoardUsesItsInsetGridForReadingAndTouchCoordinates() throws Exception {
        BoardVision detector = vision();
        BoardVision.Observation result = detector.read(fixture("duo-mumu-qhd-black-e4"));
        assertNotNull(detector.lastIssue, result);
        assertEquals(E4, result.placement(false));
        assertEquals(9, result.left());
        assertEquals(798, result.top());
        assertEquals(1422, result.size());
        GameTracker tracker = new GameTracker();
        accept(tracker, result);
        AutoPlay auto = new AutoPlay(); auto.start(0);
        MoveTip.Tip tip = MoveTip.explain(tracker.fen(), new StockfishEngine.Result("e7e5", "", 20));
        AutoPlay.Position position = new AutoPlay.Position(tracker.fen(), tracker.whiteBottom(), tracker.myTurn(),
                0, result.left(), result.top(), result.size(), result.selectedCell(), false);
        AutoPlay.Action action = auto.next(0, position, tracker.fen(), tip, true);
        assertNotNull(action);
        assertEquals(631, action.fromX()); assertEquals(1953, action.fromY());
        assertEquals(631, action.toX()); assertEquals(1598, action.toY());
    }

    @Test public void insetBoardAlsoReadsAtHdAnd4kImageSizes() throws Exception {
        BoardVision.Frame source = fixture("duo-mumu-qhd-black-e4");
        for (float scale : new float[]{.5f, 1.5f}) {
            BoardVision.Frame resized = new BoardVision.Frame() {
                public int width() { return Math.round(source.width() * scale); }
                public int height() { return Math.round(source.height() * scale); }
                public int rgb(int x, int y) { return source.rgb(Math.min(source.width() - 1, (int) (x / scale)),
                        Math.min(source.height() - 1, (int) (y / scale))); }
            };
            BoardVision detector = vision();
            BoardVision.Observation result = detector.read(resized);
            assertNotNull(resized.width() + "px " + detector.lastIssue, result);
            assertEquals(E4, result.placement(false));
            assertTrue(Math.abs(result.left() - 9 * scale) <= 1);
            assertTrue(Math.abs(result.top() - 798 * scale) <= 1);
            assertTrue(Math.abs(result.size() - 1422 * scale) <= 2);
        }
    }

    @Test public void followsOffCenterBoardAndRelocationWithinTheSameLandscapeFrame() throws Exception {
        BoardVision.Frame source = fixture("duo-mumu-qhd-black-e4");
        BoardVision detector = vision();
        for (int left : new int[]{60, 420}) {
            BoardVision.Frame moved = new BoardVision.Frame() {
                public int width() { return 1600; }
                public int height() { return 1000; }
                public int rgb(int x, int y) {
                    // Outside pixels deliberately match a square color, so a shifted grid
                    // must not mistake a margin for an extra file.
                    if (x < left || x >= left + 800 || y < 120 || y >= 920) return 0x131f24;
                    return source.rgb(9 + (x - left) * 1422 / 800, 798 + (y - 120) * 1422 / 800);
                }
            };
            BoardVision.Observation result = detector.read(moved);
            assertNotNull(left + "px " + detector.lastIssue, result);
            assertEquals(E4, result.placement(false));
            assertTrue(Math.abs(result.left() - left) <= 1);
            assertTrue(Math.abs(result.top() - 120) <= 1);
            assertTrue(Math.abs(result.size() - 800) <= 2);
        }
    }

    @Test public void discardedGridIsNotReusedWhenTheBoardDisappears() throws Exception {
        BoardVision detector = vision();
        BoardVision.Frame source = fixture("duo-mumu-qhd-black-e4");
        assertNotNull(detector.read(source));
        assertNull(detector.read(new BoardVision.Frame() {
            public int width() { return source.width(); }
            public int height() { return source.height(); }
            public int rgb(int x, int y) { return 0x131f24; }
        }));
        assertNotNull("A missing frame must not prevent reacquiring the board", detector.read(source));
    }

    @Test public void tracksBothTurnsEvenWhenTwoMovesOccurBetweenFrames() {
        Board board = new Board();
        GameTracker tracker = new GameTracker();
        accept(tracker, observation(board.getFen(), true));
        assertTrue(tracker.myTurn());
        board.doMove("e4");
        board.doMove("e5");
        accept(tracker, observation(board.getFen(), true));
        assertEquals(board.getFen(), tracker.fen());
        assertTrue(tracker.myTurn());
        assertTrue(tracker.accept(observation(board.getFen(), true)));
        assertTrue(tracker.myTurn());
    }

    @Test public void recoversWhenMoreMovesHappenThanTheTrackerCanFollow() {
        Board board = new Board();
        GameTracker tracker = new GameTracker();
        accept(tracker, observation(board.getFen(), true));
        for (String move : new String[]{"e4", "e5", "Nf3", "Nc6", "Bb5"}) board.doMove(move);
        BoardVision.Observation jumped = observation(board.getFen(), true);
        boolean recovered = false;
        for (int i = 0; i < 8 && !recovered; i++) recovered = tracker.accept(jumped);
        assertTrue("A lost position must be rebuilt, not stay stuck", recovered);
        assertEquals(board.getFen().split(" ")[0], tracker.fen().split(" ")[0]);
    }

    @Test public void finishedGameAndColorChangesReinitializeAtTheNextOpening() {
        GameTracker tracker = new GameTracker("7k/6Q1/6K1/8/8/8/8/8 b - - 0 40", true);
        assertTrue(tracker.terminal());
        assertFalse(tracker.accept(null));
        Board board = new Board();
        accept(tracker, observation(board.getFen(), true));
        assertFalse(tracker.terminal()); assertTrue(tracker.myTurn());
        board.doMove("e4"); board.doMove("e5");
        accept(tracker, observation(board.getFen(), true));
        board = new Board();
        accept(tracker, observation(board.getFen(), false));
        assertFalse(tracker.whiteBottom()); assertFalse(tracker.myTurn());
        board.doMove("d4"); accept(tracker, observation(board.getFen(), false));
        assertTrue(tracker.myTurn()); assertEquals(board.getFen(), tracker.fen());
    }

    @Test public void resumesCastlingAndEnPassantAndPromotionWithoutInventingState() {
        String[] positions = {
                "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
                "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
                "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"
        };
        String[] moves = {"e1g1", "e5d6", "a7a8q"};
        for (int i = 0; i < positions.length; i++) {
            Board board = new Board();
            board.loadFromFen(positions[i]);
            GameTracker tracker = new GameTracker(positions[i], true);
            board.doMove(new Move(moves[i], board.getSideToMove()));
            accept(tracker, observation(board.getFen(), true));
            assertEquals(board.getFen(), tracker.fen());
        }
    }

    @Test public void selectionAndMoveDotsKeepTheConfirmedPosition() throws Exception {
        String fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2";
        for (String name : new String[]{"duo-black-selected", "duo-black-selected-half"}) {
            BoardVision detector = vision();
            BoardVision.Observation selected = detector.read(fixture(name));
            assertNotNull(name + " " + detector.lastIssue, selected);
            assertEquals(fen.split(" ")[0], selected.placement(false));
            GameTracker tracker = new GameTracker(fen, false);
            accept(tracker, observation(fen, false));
            assertTrue("Selecting a piece must not interrupt a confirmed position", tracker.accept(selected));
            assertEquals(fen, tracker.fen());
            assertTrue(tracker.myTurn());
        }
    }

    @Test public void pieceSelectionFramesPreservePositionIncludingCrownOverhang() throws Exception {
        String fen = "r1b1kb1r/ppp2ppp/2n2n2/3qp1N1/8/2PB4/PP1P1PPP/RNBQK2R b KQkq - 1 6";
        GameTracker tracker = new GameTracker(fen, false);
        accept(tracker, observation(fen, false));
        for (char piece : "pnbrqk".toCharArray()) {
            for (String suffix : new String[]{"", "-half"}) {
                BoardVision detector = vision();
                String name = "duo-black-selected-" + piece + suffix;
                BoardVision.Observation selected = detector.read(fixture(name));
                assertNotNull(name + " " + detector.lastIssue, selected);
                assertEquals(name, fen.split(" ")[0], selected.placement(false));
                assertTrue(name, tracker.accept(selected));
                assertEquals(fen, tracker.fen());
            }
        }
    }

    @Test public void queenCaptureTargetsDoNotBreakBoardBoundary() throws Exception {
        String fen = "r1b1kb1r/ppp2ppp/2n2n2/4p1N1/5q1P/2PB4/PP1PQPP1/RNB1K2R b KQkq h3 0 8";
        for (String suffix : new String[]{"", "-half"}) {
            BoardVision detector = vision();
            BoardVision.Observation selected = detector.read(fixture("duo-black-selected-q-captures" + suffix));
            assertNotNull(detector.lastIssue, selected);
            assertEquals(fen.split(" ")[0], selected.placement(false));
            GameTracker tracker = new GameTracker(fen, false);
            accept(tracker, observation(fen, false));
            assertTrue(tracker.accept(selected));
            assertEquals(fen, tracker.fen());
        }
    }

    @Test public void selectedPawnAndItsMoveDotsKeepInitialBlackPosition() throws Exception {
        for (String suffix : new String[]{"", "-half"}) {
            BoardVision detector = vision();
            BoardVision.Observation selected = detector.read(fixture("duo-black-e7-selected" + suffix));
            assertNotNull(detector.lastIssue, selected);
            assertEquals(E4, selected.placement(false));
            assertEquals("The selected source is e7 on a black-oriented board", 51, selected.selectedCell());
            GameTracker tracker = new GameTracker();
            tracker.accept(selected);
            assertTrue(tracker.accept(selected));
            assertTrue(tracker.myTurn());
            assertFalse(tracker.whiteBottom());
        }
    }

    @Test public void selectionDoesNotConcealOcclusionElsewhereOnTheBoard() throws Exception {
        BoardVision.Frame selected = fixture("duo-black-selected-q");
        BoardVision.Frame obscured = new BoardVision.Frame() {
            public int width() { return selected.width(); }
            public int height() { return selected.height(); }
            public int rgb(int x, int y) {
                // Cover the white king, far from the selected queen and its overhang.
                return x >= 540 && x < 720 && y >= 1099 && y < 1279 ? 0 : selected.rgb(x, y);
            }
        };
        assertNull(vision().read(obscured));
    }

    @Test public void checkGlowOnTheKingDoesNotStopTheBoardFromBeingRead() throws Exception {
        BoardVision.Frame source = fixture("duo-black-e4");
        BoardVision.Frame glowing = new BoardVision.Frame() {
            public int width() { return source.width(); }
            public int height() { return source.height(); }
            public int rgb(int x, int y) {
                int pixel = source.rgb(x, y);
                if (x < 540 || x >= 720 || y < 1099 || y >= 1279) return pixel;
                // A red check highlight blended over the white king's square.
                double d = Math.hypot(x - 630, y - 1189) / 127.0, a = Math.max(0, .75 - .45 * d);
                int r = (int) (((pixel >> 16) & 255) * (1 - a) + 235 * a);
                int g = (int) (((pixel >> 8) & 255) * (1 - a) + 60 * a);
                int b = (int) ((pixel & 255) * (1 - a) + 60 * a);
                return r << 16 | g << 8 | b;
            }
        };
        BoardVision detector = vision();
        BoardVision.Observation result = detector.read(glowing);
        assertNotNull(detector.lastIssue, result);
        assertFalse("The glowing square must be marked doubtful, not guessed", result.certain());
        Board board = new Board();
        board.doMove("e4");
        assertTrue(result.matches(board.getFen().split(" ")[0], false));
        GameTracker tracker = new GameTracker(new Board().getFen(), false);
        tracker.accept(result);
        assertTrue(tracker.accept(result));
        assertEquals(board.getFen(), tracker.fen());
    }

    @Test public void doubtfulSquaresFollowLegalMovesButNeverStartAGame() {
        Board board = new Board();
        for (String san : new String[]{"e4", "e5", "Nf3"}) board.doMove(san);
        BoardVision.Observation exact = observation(board.getFen(), true);
        char[] cells = exact.cells().clone();
        cells[4] = 'q'; // e8, the black king's square, misread
        BoardVision.Observation doubtful = new BoardVision.Observation(0, 100, 800, cells, 0, -1, 1L << 4);
        GameTracker fresh = new GameTracker();
        fresh.accept(doubtful);
        assertFalse(fresh.accept(doubtful));
        Board before = new Board();
        before.doMove("e4"); before.doMove("e5");
        GameTracker tracker = new GameTracker(before.getFen(), true);
        tracker.accept(doubtful);
        assertTrue(tracker.accept(doubtful));
        assertEquals(board.getFen(), tracker.fen());
    }

    @Test public void classifiesQuietMovesAndAllCaptureTypes() {
        String[] positions = {
                new Board().getFen(),
                "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1",
                "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
                "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
                "1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        };
        String[] moves = {"e2e4", "e4d5", "e5d6", "a7a8q", "a7b8q", "e1g1"};
        boolean[] captures = {false, true, true, false, true, false};
        for (int i = 0; i < positions.length; i++) {
            MoveTip.Tip tip = MoveTip.explain(positions[i], new StockfishEngine.Result(moves[i], "", 20));
            assertNotNull(moves[i], tip);
            assertEquals(moves[i], captures[i], tip.capture());
        }
    }

    @Test public void unknownMidgameAndIllegalMoveNeverProduceTips() {
        GameTracker tracker = new GameTracker();
        Board board = new Board();
        for (String san : new String[]{"e4", "e5", "Nf3", "Nc6"}) board.doMove(san);
        assertFalse(tracker.accept(observation(board.getFen(), true)));
        assertFalse(tracker.accept(observation(board.getFen(), true)));
        assertNull(tracker.fen());
        assertNull(MoveTip.explain(new Board().getFen(), new StockfishEngine.Result("e2e5", "", 20)));
    }
}
