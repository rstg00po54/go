package com.badukai.ui;

import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Scales the Tencent-style home page from a 750-unit reference width.
 * The Unity CanvasScaler mode is not available, so this is an Android width-fit
 * policy, not a claim that Tencent's runtime uses exactly the same algorithm.
 * Game controls and dialogs are deliberately outside this view hierarchy.
 */
public final class TencentHomeScaler {
    private static final float REFERENCE_WIDTH = 750f;

    private TencentHomeScaler() {}

    public static void install(ViewGroup root) {
        DisplayMetrics metrics = root.getResources().getDisplayMetrics();
        float density = metrics.density;
        Map<View, Base> base = new IdentityHashMap<>();
        capture(root, base);

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private int lastWidth;

            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                if (width <= 0 || width == lastWidth) return;
                lastWidth = width;
                float factor = (width / REFERENCE_WIDTH) / density;
                scale(root, base, factor);
            }
        });
        root.post(() -> {
            if (root.getWidth() > 0) {
                float factor = (root.getWidth() / REFERENCE_WIDTH) / density;
                scale(root, base, factor);
            }
        });
    }

    private static void capture(View view, Map<View, Base> values) {
        values.put(view, new Base(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) capture(group.getChildAt(i), values);
        }
    }

    private static void scale(View view, Map<View, Base> values, float factor) {
        Base original = values.get(view);
        if (original == null) return;

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = dimension(original.width, factor);
            lp.height = dimension(original.height, factor);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
                margins.leftMargin = dimension(original.leftMargin, factor);
                margins.topMargin = dimension(original.topMargin, factor);
                margins.rightMargin = dimension(original.rightMargin, factor);
                margins.bottomMargin = dimension(original.bottomMargin, factor);
            }
            view.setLayoutParams(lp);
        }

        view.setPadding(Math.round(original.paddingLeft * factor), Math.round(original.paddingTop * factor),
                Math.round(original.paddingRight * factor), Math.round(original.paddingBottom * factor));
        view.setMinimumWidth(Math.round(original.minWidth * factor));
        view.setMinimumHeight(Math.round(original.minHeight * factor));
        if (view instanceof TextView) {
            ((TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_PX, original.textSize * factor);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scale(group.getChildAt(i), values, factor);
        }
    }

    private static int dimension(int original, float factor) {
        return original < 0 ? original : Math.round(original * factor);
    }

    private static final class Base {
        final int width, height, leftMargin, topMargin, rightMargin, bottomMargin;
        final int paddingLeft, paddingTop, paddingRight, paddingBottom, minWidth, minHeight;
        final float textSize;

        Base(View view) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            width = lp == null ? -1 : lp.width;
            height = lp == null ? -1 : lp.height;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
                leftMargin = margins.leftMargin;
                topMargin = margins.topMargin;
                rightMargin = margins.rightMargin;
                bottomMargin = margins.bottomMargin;
            } else {
                leftMargin = topMargin = rightMargin = bottomMargin = 0;
            }
            paddingLeft = view.getPaddingLeft();
            paddingTop = view.getPaddingTop();
            paddingRight = view.getPaddingRight();
            paddingBottom = view.getPaddingBottom();
            minWidth = view.getMinimumWidth();
            minHeight = view.getMinimumHeight();
            textSize = view instanceof TextView ? ((TextView) view).getTextSize() : 0f;
        }
    }
}
