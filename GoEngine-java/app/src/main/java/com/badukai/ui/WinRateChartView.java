package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Full-game win rate history: x is the played move number, y is win probability. */
public class WinRateChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] blackRates = new float[0];
    private float[] whiteRates = new float[0];

    private static final int DARK_GREEN = Color.rgb(39, 105, 73);
    private static final int LIGHT_BROWN = Color.rgb(174, 105, 58);
    private static final int GRID = Color.rgb(208, 221, 211);

    public WinRateChartView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(249, 251, 245));
        setMinimumHeight(dp(260));
    }

    public void setRates(float[] blacks, float[] whites) {
        blackRates = blacks == null ? new float[0] : blacks.clone();
        whiteRates = whites == null ? new float[0] : whites.clone();
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = Math.round(dp(280));
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(43), right = getWidth() - dp(13);
        float top = dp(44), bottom = getHeight() - dp(37);
        if (right <= left || bottom <= top) return;

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(13));
        paint.setColor(DARK_GREEN);
        canvas.drawText("● 黑胜率", left, dp(25), paint);
        paint.setColor(LIGHT_BROWN);
        canvas.drawText("● 白胜率", left + dp(98), dp(25), paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(dp(10));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setStrokeWidth(dp(1));
        for (int percent = 0; percent <= 100; percent += 25) {
            float y = bottom - (bottom - top) * percent / 100f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(percent == 50 ? Color.rgb(172, 188, 177) : GRID);
            canvas.drawLine(left, y, right, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(95, 116, 103));
            canvas.drawText(percent + "%", left - dp(6), y + dp(3), paint);
        }

        int lastMove = Math.max(1, blackRates.length - 1);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(95, 116, 103));
        canvas.drawText("0", left, bottom + dp(15), paint);
        canvas.drawText(String.valueOf(lastMove), right, bottom + dp(15), paint);
        canvas.drawText("手数", (left + right) / 2, getHeight() - dp(7), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (blackRates.length == 0) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(13));
            paint.setColor(Color.rgb(114, 133, 121));
            canvas.drawText("暂无胜率记录", (left + right) / 2, (top + bottom) / 2, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        drawSeries(canvas, left, right, top, bottom, lastMove, blackRates, DARK_GREEN);
        drawSeries(canvas, left, right, top, bottom, lastMove, whiteRates, LIGHT_BROWN);
    }

    private void drawSeries(Canvas canvas, float left, float right, float top, float bottom,
                            int lastMove, float[] rates, int color) {
        Path path = new Path();
        boolean started = false;
        int valid = 0;
        float lastX = 0f, lastY = 0f;
        for (int i = 0; i < rates.length; i++) {
            float rate = rates[i];
            if (!Float.isFinite(rate)) {
                started = false;
                continue;
            }
            float x = left + (right - left) * i / lastMove;
            float y = bottom - (bottom - top) * Math.max(0f, Math.min(1f, rate));
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else path.lineTo(x, y);
            lastX = x;
            lastY = y;
            valid++;
        }
        if (valid == 0) return;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(lastX, lastY, dp(3), paint);
    }
}
