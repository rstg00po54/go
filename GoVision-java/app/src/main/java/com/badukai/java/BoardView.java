package com.badukai.java;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

public class BoardView extends View {
    public interface Listener { void onPoint(int point); }
    private GoBoard board = new GoBoard(19);
    private Listener listener;
    private List<AnalysisMove> analysis = Collections.emptyList();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float left, top, step;

    public BoardView(Context c, AttributeSet a) { super(c, a); p.setTypeface(Typeface.DEFAULT_BOLD); }
    public void setBoard(GoBoard b) { board = b; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setAnalysis(List<AnalysisMove> a) { analysis = a == null ? Collections.emptyList() : a; invalidate(); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws); int h = MeasureSpec.getSize(hs);
        int s = Math.min(w, h == 0 ? w : h); setMeasuredDimension(s, s);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(Color.rgb(216,165,90));
        int n = board.getSize(); float pad = getWidth() * 0.06f;
        left = pad; top = pad; step = (getWidth() - 2 * pad) / (n - 1f);
        p.setColor(Color.rgb(55,40,25)); p.setStrokeWidth(Math.max(1f, step * 0.035f));
        for (int i = 0; i < n; i++) {
            float x = left + i * step, y = top + i * step;
            c.drawLine(left, y, left + (n-1)*step, y, p); c.drawLine(x, top, x, top + (n-1)*step, p);
        }
        float r = step * 0.44f;
        for (int i = 0; i < n*n; i++) {
            Stone s = board.get(i); if (s == null) continue;
            float x = left + (i%n)*step, y = top + (i/n)*step;
            p.setColor(s == Stone.BLACK ? Color.rgb(25,25,25) : Color.rgb(245,245,245)); c.drawCircle(x,y,r,p);
            if (s == Stone.WHITE) { p.setStyle(Paint.Style.STROKE); p.setColor(Color.LTGRAY); c.drawCircle(x,y,r,p); p.setStyle(Paint.Style.FILL); }
        }
        if (!analysis.isEmpty()) {
            int max = 1; for (AnalysisMove m : analysis) max = Math.max(max, m.visits);
            for (AnalysisMove m : analysis) {
                if (m.point < 0) continue;
                float x = left + (m.point%n)*step, y = top + (m.point/n)*step;
                float ratio = Math.min(1f, m.visits/(float)max);
                p.setColor(Color.argb(210, (int)(230*(1-ratio)), (int)(190*ratio+40), 55)); c.drawCircle(x,y,r*0.82f,p);
                p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(step*0.26f);
                c.drawText(String.format(Locale.US,"%.1f",m.winrate*100f),x,y-step*0.05f,p);
                p.setTextSize(step*0.20f); c.drawText(String.valueOf(m.visits),x,y+step*0.22f,p);
            }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP || listener == null) return true;
        int c = Math.round((e.getX()-left)/step), r = Math.round((e.getY()-top)/step), n = board.getSize();
        if (c>=0&&c<n&&r>=0&&r<n) listener.onPoint(r*n+c); return true;
    }
}
