package io.github.swarnimbandekar.knightlingo;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChessScreensTest {
    @Test public void botAndFriendOrRandomMatchesPermitBoardInput() {
        assertTrue(ChessScreens.match("com.duolingo.chess.match.ChessMatchActivity"));
        assertTrue(ChessScreens.match("com.duolingo.chess.pvp.ChessPvpActivity"));
    }

    @Test public void invitationAndLobbyWaitWithoutBoardInput() {
        assertFalse(ChessScreens.match("com.duolingo.feature.chess.matchtab.inappinvite.ChessFriendPvpInAppInviteActivity"));
        assertFalse(ChessScreens.match("com.duolingo.splash.LaunchActivity"));
        assertFalse(ChessScreens.match(null));
    }
}
