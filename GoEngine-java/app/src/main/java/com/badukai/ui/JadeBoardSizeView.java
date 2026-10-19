package com.badukai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Native, independently clickable wooden miniature Go boards for the jade
 * new-game screen. Not a screenshot: each tile depicts its true grid size.
 */
public final class JadeBoardSizeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int boardSize = 9;

    public JadeBoardSizeView(Context context) { super(context); init(); }
    public JadeBoardSizeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public JadeBoardSizeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    private void init() {
        setClickable(true);
        setFocusable(true);
        setBoardSize(9);
    }
    public void setBoardSize(int n) {
        if (n < 2 || n > 19) throw new IllegalArgumentException("Board size: " + n);
        boardSize = n;
        setContentDescription(n + "路棋盘");
        invalidate();
    }
    public int getBoardSize() { return boardSize; }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width < 8 || height < 8) return;
        boolean chosen = isSelected();
        float edge = dp(2);
        rect.set(edge, edge, width-edge, height-edge);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chosen ? Color.rgb(8, 86, 66) : Color.rgb(3, 34, 27));
        canvas.drawRoundRect(rect,dp(10),dp(10),paint);
        if (chosen) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(0x5045FFD0);
            rect.set(dp(4),dp(4),width-dp(4),height-dp(4));
            canvas.drawRoundRect(rect,dp(9),dp(9),paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(chosen ? dp(2) : dp(.8f));
        paint.setColor(chosen ? 0xFF6DFFD2 : 0xFF9D8856);
        rect.set(dp(2.5f),dp(2.5f),width-dp(2.5f),height-dp(2.5f));
        canvas.drawRoundRect(rect,dp(9),dp(9),paint);

        float board = Math.min(width - dp(13), height - dp(40));
        board = Math.max(board, dp(18));
        float x = (width - board) / 2;
        float y = dp(9);
        rect.set(x,y,x+board,y+board);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(x,y,x+board,y+board,
                0xFFF8CB80,0xFFB87732,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect,dp(4),dp(4),paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF7D4B22);
        canvas.drawRoundRect(rect,dp(4),dp(4),paint);

        float inset=board*.10f;
        float inner=board-2*inset;
        float step=inner/(boardSize-1);
        paint.setStrokeWidth(Math.max(.35f, boardSize == 19 ? dp(.38f) : dp(.5f)));
        paint.setColor(0xAD4A311A);
        for(int i=0;i<boardSize;i++) {
            float pos=inset+i*step;
            canvas.drawLine(x+pos,y+inset,x+pos,y+board-inset,paint);
            canvas.drawLine(x+inset,y+pos,x+board-inset,y+pos,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF38220C);
        canvas.drawCircle(x+board/2,y+board/2,Math.max(dp(.7f),step*.18f),paint);

        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(12));
        paint.setColor(chosen ? 0xFFF9E2A7 : 0xFFE9E1C8);
        canvas.drawText(boardSize + " × " + boardSize, width/2, height-dp(10), paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
