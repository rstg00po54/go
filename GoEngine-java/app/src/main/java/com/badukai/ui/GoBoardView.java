package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Point;
import com.badukai.util.DebugLog;

public class GoBoardView extends View {
    private static final String TAG = "GoBoardView";

    public interface OnIntersectionClickListener {
        void onIntersectionClick(int x, int y);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private GoBoard board = new GoBoard(19);
    private Point lastMove;
    private OnIntersectionClickListener listener;
    private boolean inputEnabled = true;

    private float padding;
    private float cellSize;
    private float boardPixels;

    public GoBoardView(Context context) { super(context); DebugLog.enter(TAG, "GoBoardView in, context=" + context); init(); }
    public GoBoardView(Context context, AttributeSet attrs) { super(context, attrs); DebugLog.enter(TAG, "GoBoardView in, context=" + context + ", attrs=" + attrs); init(); }
    public GoBoardView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); DebugLog.enter(TAG, "GoBoardView in, context=" + context + ", attrs=" + attrs + ", defStyleAttr=" + defStyleAttr); init(); }

    private void init() {
        DebugLog.enter(TAG, "init in");
        setBackgroundColor(Color.rgb(222, 184, 135));
        setFocusable(true);
    }

    public void setBoard(GoBoard board) {
        DebugLog.enter(TAG, "setBoard in, board=" + board);
        this.board = board == null ? new GoBoard(19) : board;
        invalidate();
    }

    public void setLastMove(Point point) {
        DebugLog.enter(TAG, "setLastMove in, point=" + point);
        lastMove = point;
        invalidate();
    }

    public void setInputEnabled(boolean enabled) {
        DebugLog.enter(TAG, "setInputEnabled in, enabled=" + enabled);
        inputEnabled = enabled;
    }

    public void setOnIntersectionClickListener(OnIntersectionClickListener listener) {
        DebugLog.enter(TAG, "setOnIntersectionClickListener in, listener=" + listener);
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        DebugLog.v(TAG, "onMeasure in, widthMeasureSpec=" + widthMeasureSpec + ", heightMeasureSpec=" + heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(width, height > 0 ? height : width);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        DebugLog.v(TAG, "onDraw in, canvas=" + canvas + ", board=" + board);
        super.onDraw(canvas);
        if (board == null) return;
        int n = board.getSize();
        float size = Math.min(getWidth(), getHeight());
        padding = size * 0.045f;
        boardPixels = size - 2f * padding;
        cellSize = n > 1 ? boardPixels / (n - 1f) : boardPixels;
        drawGrid(canvas, n);
        drawStarPoints(canvas, n);
        drawStones(canvas, n);
    }

    private void drawGrid(Canvas canvas, int n) {
        DebugLog.v(TAG, "drawGrid in, canvas=" + canvas + ", n=" + n);
        paint.setColor(Color.rgb(45, 45, 45));
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < n; i++) {
            float p = padding + i * cellSize;
            canvas.drawLine(p, padding, p, padding + (n - 1) * cellSize, paint);
            canvas.drawLine(padding, p, padding + (n - 1) * cellSize, p, paint);
        }
    }

    private void drawStarPoints(Canvas canvas, int n) {
        DebugLog.v(TAG, "drawStarPoints in, canvas=" + canvas + ", n=" + n);
        int[][] points = starPoints(n);
        paint.setColor(Color.rgb(45, 45, 45));
        paint.setStyle(Paint.Style.FILL);
        float r = Math.max(dp(2.2f), cellSize * 0.09f);
        for (int[] p : points) canvas.drawCircle(padding + p[0] * cellSize, padding + p[1] * cellSize, r, paint);
    }

    private int[][] starPoints(int n) {
        DebugLog.v(TAG, "starPoints in, n=" + n);
        if (n == 19) return grid3(3, 9, 15);
        if (n == 15) return grid3(3, 7, 11);
        if (n == 13) return grid3(3, 6, 9);
        if (n == 11) return grid3(2, 5, 8);
        if (n == 9) return grid3(2, 4, 6);
        return new int[0][0];
    }

    private int[][] grid3(int a, int b, int c) {
        DebugLog.v(TAG, "grid3 in, a=" + a + ", b=" + b + ", c=" + c);
        return new int[][]{{a,a},{b,a},{c,a},{a,b},{b,b},{c,b},{a,c},{b,c},{c,c}};
    }

    private void drawStones(Canvas canvas, int n) {
        DebugLog.v(TAG, "drawStones in, canvas=" + canvas + ", n=" + n + ", lastMove=" + lastMove);
        float radius = cellSize * 0.45f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                Intersection intersection = board.get(x, y);
                if (intersection == Intersection.EMPTY) continue;
                float cx = padding + x * cellSize;
                float cy = padding + y * cellSize;

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(intersection == Intersection.BLACK ? Color.rgb(24,24,24) : Color.rgb(247,247,247));
                canvas.drawCircle(cx, cy, radius, paint);
                if (intersection == Intersection.WHITE) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1));
                    paint.setColor(Color.rgb(105,105,105));
                    canvas.drawCircle(cx, cy, radius, paint);
                }

                if (lastMove != null && lastMove.x == x && lastMove.y == y) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(dp(2), radius * 0.10f));
                    paint.setColor(Color.rgb(220, 48, 48));
                    canvas.drawCircle(cx, cy, radius * 0.30f, paint);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        DebugLog.v(TAG, "onTouchEvent in, action=" + event.getAction() + ", x=" + event.getX() + ", y=" + event.getY() + ", inputEnabled=" + inputEnabled);
        if (!inputEnabled || listener == null || board == null) return true;
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (cellSize <= 0) return true;
        int n = board.getSize();
        int x = Math.round((event.getX() - padding) / cellSize);
        int y = Math.round((event.getY() - padding) / cellSize);
        if (x < 0 || x >= n || y < 0 || y >= n) return true;
        float cx = padding + x * cellSize;
        float cy = padding + y * cellSize;
        float dx = event.getX() - cx;
        float dy = event.getY() - cy;
        if (dx * dx + dy * dy > cellSize * cellSize * 0.45f) return true;
        listener.onIntersectionClick(x, y);
        performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        DebugLog.enter(TAG, "performClick in");
        super.performClick();
        return true;
    }

    private float dp(float value) {
        DebugLog.v(TAG, "dp in, value=" + value);
        return value * getResources().getDisplayMetrics().density;
    }
}
