package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Original ink-wash startup artwork and hand-painted determinate GPU progress bar. */
public final class InkLoadingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int progress;
    private String stage = "正在准备 GPU ...";
    private boolean tuning;
    private long frame;

    public InkLoadingView(Context context) {
        super(context);
        setProgress(3, stage);
    }

    public void setTuning(boolean value) { tuning = value; invalidate(); }

    public void setProgress(int value, String detail) {
        progress = Math.max(progress, Math.min(100, Math.max(0, value)));
        if (detail != null && !detail.isEmpty()) stage = detail;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        canvas.save();
        canvas.scale(getWidth() / 1000f, getHeight() / 1700f);
        drawBackground(canvas);
        drawLandscape(canvas);
        drawGoScene(canvas);
        drawTypography(canvas);
        drawProgress(canvas);
        canvas.restore();
        // The paint-sweep texture remains subtly animated even while native tuning is busy.
        if (progress < 100) { frame++; postInvalidateDelayed(250); }
    }

    private void fill(Paint.Style style, int color) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(style);
        paint.setColor(color);
    }

    private void drawBackground(Canvas c) {
        fill(Paint.Style.FILL, Color.WHITE);
        paint.setShader(new LinearGradient(0, 0, 0, 1700,
                new int[]{0xfff6fcf8,0xffedf5f2,0xffe6f0ed,0xff263e34,0xff172b25},
                new float[]{0f,0.45f,0.61f,0.76f,1f}, Shader.TileMode.CLAMP));
        c.drawRect(0,0,1000,1700,paint);
        paint.setShader(null);
        fill(Paint.Style.FILL, 0x35ffffff);
        c.drawOval(new RectF(95,165,840,790),paint);
        c.drawOval(new RectF(180,400,960,950),paint);
    }

    private void drawLandscape(Canvas c) {
        // Distant overlapping ink mountains, deliberately soft and low contrast.
        for(int i=0; i<4; i++) {
            Path mountain = new Path();
            float base = 495 + i * 55;
            mountain.moveTo(-45,base + 75);
            mountain.cubicTo(120,base - 30,205,base + 25,338,base - 92 + i * 16);
            mountain.cubicTo(424,base - 144,530,base + 24,655,base - 65);
            mountain.cubicTo(802,base - 104,903,base + 28,1045,base - 24);
            mountain.lineTo(1045,base + 125);
            mountain.lineTo(-45,base + 125);
            mountain.close();
            fill(Paint.Style.FILL, Color.argb(20 + i * 6,54,89,76));
            c.drawPath(mountain,paint);
        }
        // Bamboo brush lines and pointed leaves on the left.
        fill(Paint.Style.STROKE,0x66516b5b);
        paint.setStrokeWidth(10);
        c.drawLine(70,130,0,630,paint);
        c.drawLine(80,235,220,137,paint);
        c.drawLine(46,389,170,445,paint);
        leaf(c,87,240,219,178); leaf(c,95,250,198,253); leaf(c,72,370,175,337);
        leaf(c,63,387,137,467); leaf(c,29,494,142,534); leaf(c,41,499,98,426);
        // A few feathery brush-spray contours near the dark ground.
        fill(Paint.Style.STROKE,0x20657c6a);
        paint.setStrokeWidth(2);
        for(int i=0;i<30;i++) {
            float x=30+i*37, y=1160+20*(float)Math.sin(i*0.77);
            c.drawLine(x,y,x+35,y-12-(i%7)*3,paint);
        }
    }

    private void leaf(Canvas c, float x, float y, float tx, float ty) {
        Path leaf = new Path();
        leaf.moveTo(x,y);
        leaf.quadTo((x+tx)/2-11,(y+ty)/2-23,tx,ty);
        leaf.quadTo((x+tx)/2+24,(y+ty)/2+14,x,y);
        leaf.close();
        fill(Paint.Style.FILL,0x8d355c4c);
        c.drawPath(leaf,paint);
    }

    private void drawGoScene(Canvas c) {
        // Minimal watercolor lotus bloom.
        for(int i=0;i<6;i++) {
            c.save();
            c.rotate(i*58-15,492,1041);
            fill(Paint.Style.FILL,i%2==0?0x99d78399:0x9ad99fb0);
            c.drawOval(new RectF(468,975,515,1053),paint);
            c.restore();
        }
        fill(Paint.Style.FILL,0xffd59eaf);
        c.drawCircle(492,1041,18,paint);
        fill(Paint.Style.STROKE,0x99437a5b);
        paint.setStrokeWidth(5);
        c.drawArc(new RectF(493,1031,665,1128),35,120,false,paint);

        // Original little board and stones (not a branded splash-screen asset).
        c.save();
        c.rotate(-9,748,1075);
        fill(Paint.Style.FILL,0xff897f6b);
        c.drawRoundRect(new RectF(607,966,911,1165),17,17,paint);
        fill(Paint.Style.FILL,0xffc5b79a);
        c.drawRoundRect(new RectF(607,950,911,1148),14,14,paint);
        fill(Paint.Style.STROKE,0x8871614d);
        paint.setStrokeWidth(1.5f);
        for(int i=0;i<9;i++) {
            float x=629+i*32, y=971+i*19;
            c.drawLine(x,968,x,1126,paint);
            c.drawLine(626,y,887,y,paint);
        }
        stone(c,724,1040,16,true); stone(c,756,1078,16,false);
        stone(c,789,1021,16,false); stone(c,822,1058,16,true);
        stone(c,693,1097,16,true);
        c.restore();
    }

    private void stone(Canvas c,float x,float y,float radius,boolean black) {
        fill(Paint.Style.FILL,black?0xff1e2823:0xfff4f2e8);
        paint.setShadowLayer(5,2,3,0x99000000);
        c.drawCircle(x,y,radius,paint);
        paint.clearShadowLayer();
        fill(Paint.Style.FILL,black?0x443a6350:0x99ffffff);
        c.drawCircle(x-5,y-6,radius*0.32f,paint);
    }

    private void drawTypography(Canvas c) {
        fill(Paint.Style.FILL,0xff385d4e);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        paint.setTextSize(26);
        c.drawText("BADUK AI",58,104,paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("GPU · OPENCL",946,104,paint);
        paint.setTextAlign(Paint.Align.CENTER);

        fill(Paint.Style.FILL,0xff254e3f);
        paint.setTypeface(Typeface.create("serif",Typeface.BOLD));
        paint.setTextSize(102);
        c.drawText("围 棋",500,810,paint);
        fill(Paint.Style.FILL,0xff728f80);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        paint.setTextSize(32);
        c.drawText("落子无声 · 静候棋成",500,878,paint);
    }

    private void drawProgress(Canvas c) {
        float left=117, right=883, top=1342, bottom=1398, width=right-left;
        float end=left+width*progress/100f;
        fill(Paint.Style.FILL,0xdd0e1f1a);
        c.drawRoundRect(new RectF(left,top,right,bottom),28,28,paint);

        if(progress > 0) {
            Path stroke = new Path();
            float cap=Math.max(left+8,end);
            stroke.moveTo(left,top+14);
            stroke.cubicTo(left+75,top-15,cap-90,top+1,cap,top+7);
            stroke.quadTo(cap+17,top+23,cap,top+36);
            stroke.cubicTo(cap-60,bottom+15,left+92,bottom+8,left,bottom-11);
            stroke.quadTo(left-26,top+32,left,top+14);
            stroke.close();
            fill(Paint.Style.FILL,0xff2e9360);
            paint.setShader(new LinearGradient(left,top,Math.max(left+1,cap),bottom,
                    new int[]{0xff2a754b,0xff51b579,0xff238350},
                    null,Shader.TileMode.CLAMP));
            c.drawPath(stroke,paint);
            paint.setShader(null);

            // Fine rough ink fibers and staggered flecks create a brush-stroke edge.
            fill(Paint.Style.STROKE,0x705dd6a0);
            for(int i=0;i<65;i++) {
                float t=(i+0.5f)/65f, x=left+(cap-left)*t;
                float wave=(float)Math.sin(i*2.7+frame*0.13)*5;
                float y=(i%2==0?top+8:bottom-11)+wave;
                paint.setStrokeWidth(i%4==0?2:1);
                c.drawLine(x,y,x+5+i%11,y+(i%3-1)*3,paint);
            }
            fill(Paint.Style.FILL,0x935ec991);
            for(int i=0;i<18;i++) {
                float x=left+(cap-left)*((i*7%23)/23f);
                float y=top+(i%2==0?4:49)+(float)Math.sin(i*1.4)*8;
                c.drawCircle(x,y,1+(i%3),paint);
            }
        }
        fill(Paint.Style.FILL,0xfffcfff9);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        paint.setTextSize(39);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(progress+"%",500,1385,paint);

        fill(Paint.Style.FILL,0xffbbd0c3);
        paint.setTextSize(29);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        c.drawText(stage,500,1470,paint);
        paint.setTextSize(22);
        c.drawText(tuning?"首次 GPU 调优完成后将自动保存结果":"正在准备对弈引擎，请稍候",500,1515,paint);
    }
}
