package com.badukai.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A square wood border for the live Go board.
 *
 * Android's vertical LinearLayout weight used to give the board whatever
 * height happened to be left after all controls. On short screens this
 * reduced an 11x11 board to a tiny square in a wide empty panel.
 *
 * Inside the game ScrollView, use the measured available WIDTH to set
 * an exact equal HEIGHT, then let GoBoardView size itself to the content.
 * This does not affect its touch coordinate math or board state.
 */
public final class JadeSquareBoardFrame extends FrameLayout {
    public JadeSquareBoardFrame(Context context) {
        super(context);
    }

    public JadeSquareBoardFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public JadeSquareBoardFrame(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
    }
}
