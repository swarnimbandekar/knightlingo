package io.github.swarnimbandekar.knightlingo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class BoardTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = Ui.column(this);
        content.setBackgroundColor(Ui.BG);
        Ui.insets(this, content, 20, 18);
        Ui.add(content, Ui.text(this, "KNIGHTLINGO   /   TOUCH TEST", 12, Ui.GREEN, true), 0);
        Ui.add(content, Ui.text(this, "Can you tap\nunder the arrows?", 28, Ui.TEXT, true), 20);
        Ui.add(content, Ui.text(this, "Turn Tip ON and tap a square.\nThis is a test board with no real game or move analysis.", 14, Ui.MUTED, false), 10);
        TextView selected = Ui.text(this, "No square tapped yet", 17, Ui.GREEN, true);
        BoardView board = new BoardView(this, selected);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        boardParams.topMargin = Ui.dp(this, 18);
        content.addView(board, boardParams);
        Ui.add(content, selected, 8);
        Button rotate = Ui.button(this, "Flip White / Black view", false);
        rotate.setOnClickListener(view -> {
            board.whiteBottom = !board.whiteBottom;
            board.invalidate();
        });
        Ui.add(content, rotate, 18);
        Button back = Ui.button(this, "Back to connection screen", false);
        back.setOnClickListener(view -> finish());
        Ui.add(content, back, 8);
        setContentView(content);
    }

    private static final class BoardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextView status;
        private boolean whiteBottom = true;
        private float left, top, cell;
        private int selectedRow = -1, selectedColumn = -1;
        private int taps;

        BoardView(Context context, TextView status) {
            super(context);
            this.status = status;
            setContentDescription("Chessboard for touch testing");
        }

        @Override protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            cell = size / 8;
            left = (getWidth() - size) / 2;
            top = (getHeight() - size) / 2;
            String[] position = {"rnbqkbnr", "pppppppp", "........", "........", "........", "........", "PPPPPPPP", "RNBQKBNR"};
            for (int row = 0; row < 8; row++) {
                for (int column = 0; column < 8; column++) {
                    float x = left + cell * column;
                    float y = top + cell * row;
                    paint.setColor(((row + column) % 2 == 0) ? Ui.CARD : Ui.LINE);
                    if (row == selectedRow && column == selectedColumn) paint.setColor(0xff5d7845);
                    canvas.drawRect(x, y, x + cell, y + cell, paint);
                    int sourceRow = whiteBottom ? row : 7 - row;
                    int sourceColumn = whiteBottom ? column : 7 - column;
                    char piece = position[sourceRow].charAt(sourceColumn);
                    if (piece != '.') {
                        paint.setTextAlign(Paint.Align.CENTER);
                        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                        paint.setTextSize(cell * .74f);
                        paint.setColor(Character.isUpperCase(piece) ? Ui.TEXT : 0xff85b6c9);
                        String symbols = "♟♜♞♝♛♚";
                        int symbol = "prnbqk".indexOf(Character.toLowerCase(piece));
                        Paint.FontMetrics metrics = paint.getFontMetrics();
                        canvas.drawText(symbols.substring(symbol, symbol + 1), x + cell / 2,
                                y + cell / 2 - (metrics.ascent + metrics.descent) / 2, paint);
                    }
                    paint.setTextSize(cell * .18f);
                    paint.setTypeface(Typeface.DEFAULT);
                    paint.setColor(Ui.MUTED);
                    if (row == 7) {
                        paint.setTextAlign(Paint.Align.RIGHT);
                        char file = (char) ('a' + (whiteBottom ? column : 7 - column));
                        canvas.drawText(String.valueOf(file), x + cell - Ui.dp(getContext(), 3), y + cell - Ui.dp(getContext(), 3), paint);
                    }
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() == MotionEvent.ACTION_UP && cell > 0) {
                int column = (int) Math.floor((event.getX() - left) / cell);
                int row = (int) Math.floor((event.getY() - top) / cell);
                if (row >= 0 && row < 8 && column >= 0 && column < 8) {
                    selectedRow = row;
                    selectedColumn = column;
                    char file = (char) ('a' + (whiteBottom ? column : 7 - column));
                    int rank = whiteBottom ? 8 - row : row + 1;
                    taps++;
                    String square = "" + file + rank;
                    status.setText(square + " tapped · " + taps + " times");
                    Log.i("ChessTip", "BOARD_TOUCH square=" + square + " count=" + taps);
                    invalidate();
                    performClick();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
