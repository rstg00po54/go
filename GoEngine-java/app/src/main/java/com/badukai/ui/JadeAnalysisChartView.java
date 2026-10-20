package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/** Gold-on-jade win-rate chart. Missing analysis points are NEVER interpolated. */
public final class JadeAnalysisChartView extends View {
    public interface OnStepSelected { void onStepSelected(int step); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] blackWins = new float[0];
    private int selectedStep;
    private boolean fromSelectedStep;
    private OnStepSelected listener;

    public JadeAnalysisChartView(Context context) { super(context); }
    public JadeAnalysisChartView(Context context, AttributeSet attrs) { super(context, attrs); }
    public JadeAnalysisChartView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }

    public void setOnStepSelected(OnStepSelected callback) { listener = callback; }
    public void setData(float[] values, int step) {
        blackWins = values == null ? new float[0] : Arrays.copyOf(values, values.length);
        selectedStep = step;
        invalidate();
    }
    public void selectStep(int step) { selectedStep = step; invalidate(); }
    public void setFromSelectedStep(boolean enabled) { fromSelectedStep = enabled; invalidate(); }
    private int firstStep() {
        return fromSelectedStep ? Math.max(0, Math.min(selectedStep, blackWins.length - 1)) : 0;
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float left() { return dp(36); }
    private float right() { return Math.max(left() + 1f, getWidth() - dp(7)); }
    private float top() { return dp(9); }
    private float bottom() { return Math.max(top() + 1f, getHeight() - dp(24)); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x0 = left(), x1 = right(), y0 = top(), y1 = bottom();
        paint.setShader(null);
        paint.setTextSize(dp(10));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.7f));
        for (int v = 0; v <= 100; v += 25) {
            float y = y1 - (y1 - y0) * v / 100f;
            paint.setColor(v == 50 ? 0xAA81A68A : 0x5576A68A);
            canvas.drawLine(x0, y, x1, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFC5D5C6);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(v + "%", x0 - dp(5), y + dp(3), paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        if (blackWins.length < 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFD2DFC9);
            canvas.drawText("暂无分析数据", x0 + dp(6), y0 + (y1 - y0) / 2f, paint);
            return;
        }
        int first = firstStep();
        int count = Math.max(1, blackWins.length - 1 - first);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFC8DAC8);
        canvas.drawText(String.valueOf(first), x0, getHeight() - dp(5), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.valueOf(count), x1, getHeight() - dp(5), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        Path curve = new Path();
        boolean active = false;
        int valid = 0;
        for (int i = first; i < blackWins.length; i++) {
            float rate = blackWins[i];
            if (!Float.isFinite(rate)) { active = false; continue; }
            float x = x0 + (x1 - x0) * (i - first) / count;
            float y = y1 - (y1 - y0) * Math.max(0f, Math.min(1f, rate));
            if (active) curve.lineTo(x, y);
            else curve.moveTo(x, y);
            valid++;
            active = true;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFFFFDA7D);
        canvas.drawPath(curve, paint);
        // A quick single-step analysis must be visible as a real sample,
        // without falsely drawing a line across unanalysed positions.
        paint.setStyle(Paint.Style.FILL);
        for (int i = first; i < blackWins.length; i++) {
            if (!Float.isFinite(blackWins[i])) continue;
            float x = x0 + (x1 - x0) * (i - first) / count;
            float y = y1 - (y1 - y0) * blackWins[i];
            canvas.drawCircle(x, y, dp(2.5f), paint);
        }
        if (valid == 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFD2DFC9);
            canvas.drawText("点击「分析全局」生成走势图", x0 + dp(5), y0 + (y1 - y0) / 2, paint);
            return;
        }
        if (selectedStep >= 0 && selectedStep < blackWins.length) {
            float x = x0 + (x1 - x0) * (selectedStep - first) / count;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x99FCE6A5);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(x, y0, x, y1, paint);
            if (Float.isFinite(blackWins[selectedStep])) {
                float y = y1 - (y1 - y0) * blackWins[selectedStep];
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFD78B);
                canvas.drawCircle(x, y, dp(4), paint);
            }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (listener == null || blackWins.length < 2) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float fraction = (event.getX() - left()) / (right() - left());
            int first = firstStep();
            int step = first + Math.round(Math.max(0f, Math.min(1f, fraction))
                    * (blackWins.length - 1 - first));
            listener.onStepSelected(step);
            performClick();
            return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
