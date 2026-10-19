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
    private void placeArt(int id, float x, float centerY, float widthFraction, float maxHeightFraction) {
        View v = findViewById(id);
        if (v == null) return;
        android.graphics.drawable.Drawable drawable = v.getBackground();
        int sourceWidth = drawable == null ? 0 : drawable.getIntrinsicWidth();
        int sourceHeight = drawable == null ? 0 : drawable.getIntrinsicHeight();
        float ratio = sourceWidth > 0 && sourceHeight > 0
                ? (float) sourceWidth / sourceHeight : 4.5f;
        int width = Math.round(getWidth() * widthFraction);
        int height = Math.round(width / ratio);
        int limit = Math.round(getHeight() * maxHeightFraction);
        if (height > limit) {
            height = limit;
            width = Math.round(height * ratio);
        }
        LayoutParams params = new LayoutParams(width, height);
        params.leftMargin = Math.round(getWidth() * x)
                + (Math.round(getWidth() * widthFraction) - width) / 2;
        params.topMargin = Math.round(getHeight() * centerY) - height / 2;
        v.setLayoutParams(params);
    }
    public void refreshPositions() { post(this::positionButtons); }
    private void positionButtons() {
        if (getWidth() == 0 || getHeight() == 0) return;
        // Layer a subtle tint behind the title, never behind the jade artwork.
        place(com.badukai.R.id.homeTopShade, 0f, 0f, 1f, .25f);
        place(com.badukai.R.id.homeTitle, .067f, .041f, .79f, .058f);
        place(com.badukai.R.id.homeSubtitle, .072f, .104f, .82f, .035f);
        place(com.badukai.R.id.homeLocalBadge, .07f, .155f, .57f, .040f);
        place(com.badukai.R.id.homeSettingsButton, .855f, .025f, .125f, .065f);
        // Preserve each artwork's intrinsic aspect ratio and center around its original position.
        placeArt(com.badukai.R.id.aiBattleButton, .067f, .662f, .866f, .112f);
        placeArt(com.badukai.R.id.homeRecordButton, .052f, .787f, .422f, .101f);
        placeArt(com.badukai.R.id.homeTrainingButton, .526f, .787f, .422f, .101f);
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
