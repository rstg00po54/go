package com.badukai.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Fits a square Go board and its surrounding controls into the visible
 * screen area above the bottom navigation bar.
 *
 * A ScrollView remains as a fallback on exceptionally short screens or
 * when system font scaling makes the text taller than the usable viewport.
 */
public final class BoardViewportFitter {
    private BoardViewportFitter() {}

    public static void attach(ScrollView viewport, JadeSquareBoardFrame boardFrame) {
        if (viewport == null || boardFrame == null || viewport.getChildCount() != 1) {
            return;
        }
        View content = viewport.getChildAt(0);
        View.OnLayoutChangeListener fitListener = (view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) ->
                fit(viewport, content, boardFrame);
        viewport.addOnLayoutChangeListener(fitListener);
        // Dynamic SGF details, puzzle hints and answer explanations can
        // change their height without changing the viewport bounds.
        content.addOnLayoutChangeListener(fitListener);
        viewport.post(() -> fit(viewport, content, boardFrame));
    }

    private static void fit(ScrollView viewport, View content,
            JadeSquareBoardFrame boardFrame) {
        if (viewport.getHeight() <= 0 || content.getWidth() <= 0
                || boardFrame.getHeight() <= 0) {
            return;
        }
        ViewGroup.LayoutParams raw = boardFrame.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;

        // All heights are actual measured pixels, including dynamic text.
        // Remove only the current board height so no gap is counted twice.
        int otherHeight = content.getHeight() - boardFrame.getHeight();
        int roomForBoard = viewport.getHeight() - otherHeight - dp(viewport, 3);
        int maxBoardWidth = content.getWidth() - params.leftMargin - params.rightMargin;
        if (maxBoardWidth <= 0) return;

        // Prefer one screen, but keep the board usable on exceptionally
        // short displays. The ScrollView is the safe fallback in that case.
        int minUsableSide = Math.min(maxBoardWidth, dp(viewport, 156));
        int side = Math.min(maxBoardWidth, Math.max(minUsableSide, roomForBoard));
        if (params.width != side || params.height != side
                || params.gravity != Gravity.CENTER_HORIZONTAL) {
            params.width = side;
            params.height = side;
            params.gravity = Gravity.CENTER_HORIZONTAL;
            boardFrame.setLayoutParams(params);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
