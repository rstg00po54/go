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
    public void refreshPositions() { post(this::positionButtons); }
    private void positionButtons() {
        if (getWidth() == 0 || getHeight() == 0) return;
        // The background is a full-screen scene, while controls are independently rendered.
        place(com.badukai.R.id.homeTitle,.055f,.023f,.78f,.055f);
        place(com.badukai.R.id.homeSubtitle,.06f,.082f,.65f,.032f);
        place(com.badukai.R.id.homeSettingsButton,.865f,.018f,.115f,.066f);
        place(com.badukai.R.id.aiBattleButton,.067f,.622f,.866f,.112f);
        place(com.badukai.R.id.homeRecordButton,.052f,.751f,.422f,.101f);
        place(com.badukai.R.id.homeTrainingButton,.526f,.751f,.422f,.101f);
        // Match the PNG's intrinsic aspect ratio. fitCenter inside a short box
        // previously shrank the artwork to a tiny strip.
        View nav = findViewById(com.badukai.R.id.homeNavBarArtwork);
        android.graphics.drawable.Drawable image =
                nav instanceof android.widget.ImageView
                        ? ((android.widget.ImageView) nav).getDrawable() : null;
        float ratio = image != null && image.getIntrinsicWidth() > 0
                && image.getIntrinsicHeight() > 0
                ? (float) image.getIntrinsicWidth() / image.getIntrinsicHeight() : 3.9f;
        int width = Math.round(getWidth() * .94f);
        int height = Math.round(width / ratio);
        int maxHeight = Math.round(getHeight() * .155f);
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.round(height * ratio);
        }
        int left = (getWidth() - width) / 2;
        int top = getHeight() - height - Math.round(getHeight() * .022f);
        LayoutParams lp = new LayoutParams(width, height);
        lp.leftMargin = left;
        lp.topMargin = top;
        nav.setLayoutParams(lp);
        for (int i = 0; i < 5; i++) {
            int[] ids = {com.badukai.R.id.homeTabButton,
                    com.badukai.R.id.homeGameTabButton,
                    com.badukai.R.id.homeRecordTabButton,
                    com.badukai.R.id.homeTrainingTabButton,
                    com.badukai.R.id.homeSettingsTabButton};
            View tab = findViewById(ids[i]);
            LayoutParams hit = new LayoutParams(width / 5, height);
            hit.leftMargin = left + Math.round(i * width / 5f);
            hit.topMargin = top;
            tab.setLayoutParams(hit);
        }
    }
}
