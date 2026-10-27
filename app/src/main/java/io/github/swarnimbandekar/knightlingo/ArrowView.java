package io.github.swarnimbandekar.knightlingo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class ArrowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final int[] location = new int[2];
    private BoardVision.Observation board;
    private String move;
    private boolean whiteBottom;
    private boolean capture;

    ArrowView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void update(BoardVision.Observation board, MoveTip.Tip tip, boolean whiteBottom) {
        this.board = board;
        this.move = tip == null ? null : tip.move();
        this.capture = tip != null && tip.capture();
        this.whiteBottom = whiteBottom;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (board == null || move == null || move.length() < 4) return;
        getLocationOnScreen(location);
        float cell = board.size() / 8f;
        float startX = x(move.charAt(0), cell) - location[0];
        float startY = y(move.charAt(1), cell) - location[1];
        float endX = x(move.charAt(2), cell) - location[0];
        float endY = y(move.charAt(3), cell) - location[1];
        float length = (float) Math.hypot(endX - startX, endY - startY);
        if (length < 1) return;
        float ux = (endX - startX) / length;
        float uy = (endY - startY) / length;
        float head = cell * .35f;

        int color = capture ? Ui.CAPTURE : Ui.GREEN;
        float inset = cell * .025f;
        float half = cell / 2f - inset;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(90);
        canvas.drawRoundRect(endX - half, endY - half, endX + half, endY + half,
                cell * .06f, cell * .06f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(cell * .025f);
        paint.setAlpha(190);
        canvas.drawRoundRect(endX - half, endY - half, endX + half, endY + half,
                cell * .06f, cell * .06f, paint);

        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(cell * .12f);
        canvas.drawCircle(startX, startY, cell * .25f, paint);
        canvas.drawLine(startX + ux * cell * .25f, startY + uy * cell * .25f,
                endX - ux * head * .6f, endY - uy * head * .6f, paint);
        triangle.reset();
        triangle.moveTo(endX, endY);
        triangle.lineTo(endX - ux * head - uy * head * .55f, endY - uy * head + ux * head * .55f);
        triangle.lineTo(endX - ux * head + uy * head * .55f, endY - uy * head - ux * head * .55f);
        triangle.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(triangle, paint);

    }

    private float x(char file, float cell) { return board.left() + ((whiteBottom ? file - 'a' : 'h' - file) + .5f) * cell; }
    private float y(char rank, float cell) { return board.top() + ((whiteBottom ? '8' - rank : rank - '1') + .5f) * cell; }
}
