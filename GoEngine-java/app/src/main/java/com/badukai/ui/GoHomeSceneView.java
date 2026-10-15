package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Decorative home illustration only; gameplay still uses GoBoardView. */
public class GoHomeSceneView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    public GoHomeSceneView(Context c) { super(c); }
    public GoHomeSceneView(Context c, AttributeSet a) { super(c,a); }
    private void color(int c){ p.setShader(null); p.setColor(c); p.setStyle(Paint.Style.FILL); }
    private void rect(Canvas c,float l,float t,float r,float b,int col){ color(col);c.drawRect(l,t,r,b,p); }
    private void circle(Canvas c,float x,float y,float r,int col){color(col);c.drawCircle(x,y,r,p);}
    @Override protected void onDraw(Canvas original) {
        super.onDraw(original);
        Canvas c=original;
        int save=c.save(); c.scale(getWidth()/360f,getHeight()/425f);
        p.setShader(new LinearGradient(0,0,0,425,0xffcba36a,0xff63412c,Shader.TileMode.CLAMP));
        c.drawRect(0,0,360,425,p);p.setShader(null);
        // Bamboo window, with distant hills and sunlight.
        rect(c,27,16,333,216,0xff563823);
        p.setShader(new LinearGradient(0,20,0,211,0xfffae4a8,0xff87ad88,Shader.TileMode.CLAMP));
        c.drawRoundRect(35,24,325,208,16,16,p);p.setShader(null);
        Path mountain=new Path();mountain.moveTo(35,177);mountain.cubicTo(93,92,139,173,196,131);
        mountain.cubicTo(263,71,302,137,325,114);mountain.lineTo(325,208);mountain.lineTo(35,208);mountain.close();
        color(0xff66896e);c.drawPath(mountain,p);
        for(int i=0;i<8;i++){int x=43+i*40;rect(c,x,23,x+5,202,0x66658552);circle(c,x+13,65+(i%3)*22,23,0x44648650);}
        rect(c,19,11,341,21,0xffa27140);rect(c,16,206,345,220,0xff976338);
        // Wooden table and a decorative tilted-looking, readable 9x9 Go board.
        color(0xff4f3024);c.drawRoundRect(9,241,351,398,23,23,p);
        color(0xffb37d47);c.drawRoundRect(19,225,341,379,14,14,p);
        color(0xffe4b777);c.drawRoundRect(29,231,331,367,8,8,p);
        for(int i=0;i<9;i++){
            float x=50+i*32.5f,y=247+i*13f;
            p.setShader(null);p.setColor(0xff8b623d);p.setStrokeWidth(0.9f);
            c.drawLine(x,247,x,351,p);c.drawLine(50,y,310,y,p);
        }
        for(int[] b:new int[][]{{2,2},{3,5},{5,3},{6,6},{1,7},{7,1}}){
            float x=50+b[0]*32.5f, y=247+b[1]*13f;
            circle(c,x+1,y+2,7.5f,0x553b2218);
            p.setShader(new RadialGradient(x-3,y-3,14,new int[]{0xff6b6b65,0xff201c19,0xff090909},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,7.3f,p);p.setShader(null);
        }
        for(int[] b:new int[][]{{3,2},{4,4},{2,6},{5,6},{7,4}}){
            float x=50+b[0]*32.5f,y=247+b[1]*13f;
            circle(c,x+1,y+2,7.5f,0x553b2218);
            p.setShader(new RadialGradient(x-3,y-3,14,new int[]{0xffffffff,0xffe8e5dc,0xffa7a59a},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,7.3f,p);p.setShader(null);
        }
        p.setShader(new LinearGradient(0,100,350,330,0x33fff3be,0x00ffffff,Shader.TileMode.CLAMP));
        c.drawRect(0,0,360,425,p);p.setShader(null);
        c.restoreToCount(save);
    }
}