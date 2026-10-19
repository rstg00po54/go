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
        // The shared real component replaces the old baked navbar PNG and
        // its five invisible touch hotspots. It uses the same proportions on
        // home as on replay/game/training.
        View nav = findViewById(com.badukai.R.id.homeNavBarArtwork);
        // Match the game, replay, training and new-game footers exactly:
        // 96dp high, 9dp on each side and 3dp above the window bottom.
        // Do not use a fraction of screen height: it shifts with aspect ratio.
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                Math.round(96 * getResources().getDisplayMetrics().density),
                android.view.Gravity.BOTTOM);
        int side = Math.round(9 * getResources().getDisplayMetrics().density);
        lp.leftMargin = side;
        lp.rightMargin = side;
        lp.bottomMargin = Math.round(3 * getResources().getDisplayMetrics().density);
        nav.setLayoutParams(lp);
    }
}
