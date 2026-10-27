package io.github.swarnimbandekar.knightlingo;

final class ChessScreens {
    static boolean match(String activity) {
        return "com.duolingo.chess.match.ChessMatchActivity".equals(activity)
                || "com.duolingo.chess.pvp.ChessPvpActivity".equals(activity);
    }
}
