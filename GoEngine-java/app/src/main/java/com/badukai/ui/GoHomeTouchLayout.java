package com.badukai.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Positions independent Android controls over a green study backdrop. */
public class GoHomeTouchLayout extends FrameLayout {
    public GoHomeTouchLayout(Context c) { super(c); }
    public GoHomeTouchLayout(Context c, AttributeSet a) { super(c,a); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        post(this::positionButtons);
    }
    private void place(int id,float x,float y,float width,float height) {
        View v=findViewById(id);
        if(v==null)return;
        int w=getWidth(),h=getHeight();
        LayoutParams p=new LayoutParams(Math.round(width*w),Math.round(height*h));
        p.leftMargin=Math.round(x*w);
        p.topMargin=Math.round(y*h);
        v.setLayoutParams(p);
    }
    private void positionButtons() {
        if (getWidth() == 0 || getHeight() == 0) return;
        // The background is a full-screen scene, while controls are independently rendered.
        place(com.badukai.R.id.homeTitle,.055f,.023f,.78f,.055f);
        place(com.badukai.R.id.homeSubtitle,.06f,.082f,.65f,.032f);
        place(com.badukai.R.id.homeSettingsButton,.865f,.018f,.115f,.066f);
        place(com.badukai.R.id.aiBattleButton,.067f,.622f,.866f,.112f);
        place(com.badukai.R.id.homeRecordButton,.052f,.751f,.422f,.101f);
        place(com.badukai.R.id.homeTrainingButton,.526f,.751f,.422f,.101f);
        place(com.badukai.R.id.homeNavBarArtwork,.040f,.865f,.920f,.110f);
        place(com.badukai.R.id.homeTabButton,.055f,.878f,.160f,.082f);
        place(com.badukai.R.id.homeGameTabButton,.236f,.878f,.160f,.082f);
        place(com.badukai.R.id.homeRecordTabButton,.417f,.878f,.160f,.082f);
        place(com.badukai.R.id.homeTrainingTabButton,.598f,.878f,.160f,.082f);
        place(com.badukai.R.id.homeSettingsTabButton,.779f,.878f,.160f,.082f);
    }
}
