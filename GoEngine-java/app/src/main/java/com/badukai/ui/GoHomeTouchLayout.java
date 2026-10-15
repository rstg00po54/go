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
        if(getWidth()==0 || getHeight()==0)return;
        place(com.badukai.R.id.homeActionPanel,0f,.60f,1f,.40f);
        place(com.badukai.R.id.homeTitle,.05f,.025f,.64f,.048f);
        place(com.badukai.R.id.homeSubtitle,.055f,.077f,.70f,.03f);
        place(com.badukai.R.id.aiBattleButton,.075f,.645f,.85f,.088f);
        place(com.badukai.R.id.homeRecordButton,.06f,.757f,.42f,.080f);
        place(com.badukai.R.id.homeTrainingButton,.52f,.757f,.42f,.080f);
        place(com.badukai.R.id.homeSettingsButton,.855f,.02f,.115f,.057f);
        place(com.badukai.R.id.homeTabButton,.015f,.905f,.182f,.066f);
        place(com.badukai.R.id.homeGameTabButton,.212f,.905f,.182f,.066f);
        place(com.badukai.R.id.homeRecordTabButton,.409f,.905f,.182f,.066f);
        place(com.badukai.R.id.homeTrainingTabButton,.606f,.905f,.182f,.066f);
        place(com.badukai.R.id.homeSettingsTabButton,.803f,.905f,.182f,.066f);
    }
}
