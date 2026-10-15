package com.badukai.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Positions transparent touch targets over the fixed artwork's painted buttons. */
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
        place(com.badukai.R.id.aiBattleButton,.065f,.622f,.87f,.105f);
        place(com.badukai.R.id.homeRecordButton,.055f,.741f,.43f,.096f);
        place(com.badukai.R.id.homeTrainingButton,.525f,.741f,.42f,.096f);
        place(com.badukai.R.id.homeSettingsButton,.845f,.020f,.13f,.065f);
        place(com.badukai.R.id.homeTabButton,.025f,.898f,.18f,.089f);
        place(com.badukai.R.id.homeGameTabButton,.225f,.898f,.18f,.089f);
        place(com.badukai.R.id.homeRecordTabButton,.425f,.898f,.18f,.089f);
        place(com.badukai.R.id.homeTrainingTabButton,.625f,.898f,.18f,.089f);
        place(com.badukai.R.id.homeSettingsTabButton,.825f,.898f,.17f,.089f);
    }
}
