package io.github.swarnimbandekar.knightlingo;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.move.Move;

final class MoveTip {
    record Tip(String move, String title, String feature, String continuation, boolean capture) {}

    static Tip explain(String fen, StockfishEngine.Result result) {
        Board board = new Board();
        board.loadFromFen(fen);
        Move move;
        try { move = new Move(result.move(), board.getSideToMove()); }
        catch (RuntimeException error) { return null; }
        if (!board.legalMoves().contains(move)) return null;
        Piece piece = board.getPiece(move.getFrom());
        Piece captured = board.getPiece(move.getTo());
        String pieceName = name(piece.getFenSymbol().charAt(0));
        String from = result.move().substring(0, 2);
        String to = result.move().substring(2, 4);
        char type = Character.toLowerCase(piece.getFenSymbol().charAt(0));
        boolean capture = captured != Piece.NONE || (type == 'p' && from.charAt(0) != to.charAt(0));
        String feature;
        if (type == 'k' && Math.abs(from.charAt(0) - to.charAt(0)) == 2) feature = "Castle to move the king and develop the rook.";
        else if (captured != Piece.NONE) feature = "Capture the opponent’s " + name(captured.getFenSymbol().charAt(0)) + ".";
        else if (type == 'p' && from.charAt(0) != to.charAt(0)) feature = "Capture the opponent’s pawn en passant.";
        else if ((type == 'n' || type == 'b') && (from.charAt(1) == '1' || from.charAt(1) == '8')) feature = "Develop the " + pieceName + " to open up its activity.";
        else if (type == 'p' && "de".indexOf(to.charAt(0)) >= 0) feature = "Advance a pawn to a central file.";
        else feature = "The engine’s top-rated move in this position.";
        board.doMove(move);
        if (board.isMated()) feature = "This move delivers checkmate.";
        else if (board.isKingAttacked()) feature = "This move checks the opponent’s king.";
        if (result.move().length() == 5) feature = "Promote the pawn to a " + name(result.move().charAt(4)) + ".";
        String[] variation = result.variation().split(" ");
        String continuation = variation.length >= 3 ? "Expected " + variation[1] + " → " + variation[2] : "Analysis depth " + result.depth();
        return new Tip(result.move(), pieceName + " " + from + " → " + to, feature, continuation, capture);
    }

    private static String name(char piece) {
        return switch (Character.toLowerCase(piece)) {
            case 'p' -> "pawn";
            case 'n' -> "knight";
            case 'b' -> "bishop";
            case 'r' -> "rook";
            case 'q' -> "queen";
            case 'k' -> "king";
            default -> "piece";
        };
    }
}
