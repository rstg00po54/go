package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader blackShader, whiteShader, blackHighlight, whiteHighlight, shadowShader;
    private float shaderRadius = -1f;
    private GoBoard board = new GoBoard(19);
    private Point lastMove;
    private float[] ownershipWhite;
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

    /** Ownership from KataGo: x increases rightward, raw y=0 is the bottom of the board. */
    public void setOwnership(float[] values) {
        if (values == null || board == null || values.length != board.getSize() * board.getSize()) ownershipWhite = null;
        else ownershipWhite = values.clone();
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
        // Reserve space for the entire stone shadow, not only the grid lines.
        // The shadow extends about 0.65 cell beyond an edge intersection.
        float outerInset = dp(2f);
        float edgeCells = 0.68f;
        cellSize = n > 1 ? (size - 2f * outerInset) / (n - 1f + 2f * edgeCells) : size;
        padding = outerInset + edgeCells * cellSize;
        boardPixels = (n - 1f) * cellSize;
        drawGrid(canvas, n);
        drawStarPoints(canvas, n);
        drawOwnership(canvas, n);
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

    private void prepareStoneShaders(float radius) {
        if (Math.abs(shaderRadius - radius) < 0.01f) return;
        shaderRadius = radius;
        blackShader = new RadialGradient(-radius * 0.30f, -radius * 0.42f, radius * 1.50f,
                new int[]{Color.rgb(83, 91, 85), Color.rgb(33, 39, 36), Color.rgb(6, 8, 8)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        whiteShader = new RadialGradient(-radius * 0.30f, -radius * 0.42f, radius * 1.50f,
                new int[]{Color.rgb(255, 255, 251), Color.rgb(233, 236, 231), Color.rgb(157, 164, 160)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        blackHighlight = new RadialGradient(-radius * 0.36f, -radius * 0.49f, radius * 0.62f,
                new int[]{0x66FFFFFF, 0x18FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        whiteHighlight = new RadialGradient(-radius * 0.36f, -radius * 0.49f, radius * 0.62f,
                new int[]{0xAAFFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        shadowShader = new RadialGradient(radius * 0.13f, radius * 0.17f, radius * 1.28f,
                new int[]{0x66000000, 0x29000000, 0x00000000},
                new float[]{0f, 0.70f, 1f}, Shader.TileMode.CLAMP);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setShader(shadowShader);
        stonePaint.setStyle(Paint.Style.FILL);
        highlightPaint.setStyle(Paint.Style.FILL);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    private void drawLitStone(Canvas canvas, float cx, float cy, float radius, boolean black) {
        canvas.save();
        canvas.translate(cx, cy);

        canvas.drawCircle(radius * 0.13f, radius * 0.17f, radius * 1.28f, shadowPaint);
        stonePaint.setShader(black ? blackShader : whiteShader);
        canvas.drawCircle(0f, 0f, radius, stonePaint);

        highlightPaint.setShader(black ? blackHighlight : whiteHighlight);
        canvas.drawOval(-radius * 0.61f, -radius * 0.72f, radius * 0.12f, -radius * 0.21f, highlightPaint);

        rimPaint.setStrokeWidth(Math.max(dp(0.5f), radius * 0.044f));
        rimPaint.setColor(black ? Color.rgb(7, 10, 9) : Color.rgb(124, 134, 128));
        canvas.drawCircle(0f, 0f, radius * 0.975f, rimPaint);
        canvas.restore();
    }

    private void drawOwnership(Canvas canvas, int n) {
        if (ownershipWhite == null || ownershipWhite.length != n * n) return;
        float radius = cellSize * 0.23f;
        paint.setShader(null);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (board.get(x, y) != Intersection.EMPTY) continue;
                float whiteOwn = ownershipWhite[(n - 1 - y) * n + x];
                float strength = Math.abs(whiteOwn);
                if (strength < 0.55f) continue; // Uncertain area: leave unmarked.
                int alpha = Math.min(235, 90 + Math.round(145 * (strength - 0.55f) / 0.45f));
                boolean white = whiteOwn > 0;
                float cx = padding + x * cellSize;
                float cy = padding + y * cellSize;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(white ? Color.argb(alpha, 255, 255, 249) : Color.argb(alpha, 25, 30, 28));
                canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, dp(1.5f), dp(1.5f), paint);
                if (white) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(0.8f));
                    paint.setColor(Color.argb(140, 75, 65, 50));
                    canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, dp(1.5f), dp(1.5f), paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStones(Canvas canvas, int n) {
        DebugLog.v(TAG, "drawStones in, canvas=" + canvas + ", n=" + n + ", lastMove=" + lastMove);
        float radius = cellSize * 0.45f;
        prepareStoneShaders(radius);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                Intersection intersection = board.get(x, y);
                if (intersection == Intersection.EMPTY) continue;
                float cx = padding + x * cellSize;
                float cy = padding + y * cellSize;
                drawLitStone(canvas, cx, cy, radius, intersection == Intersection.BLACK);

                if (lastMove != null && lastMove.x == x && lastMove.y == y) {
                    paint.setShader(null);
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
