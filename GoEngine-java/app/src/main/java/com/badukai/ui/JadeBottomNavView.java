package com.badukai.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.badukai.R;

/**
 * Shared jade tab bar for every main Go page.
 *
 * Visual order ALWAYS follows the original homepage concept:
 * 首页 / 对弈 / 棋谱 / 训练 / 设置. Unlike the old all-in-one navbar PNG,
 * the gold highlight is real state and moves when the active tab changes.
 *
 * No navigation destination is hardcoded here; each host wires its existing
 * actions through OnTabClickListener, so replay, live game and training state
 * never get reset just to draw the footer.
 */
public final class JadeBottomNavView extends FrameLayout {
    public static final int HOME = 0;
    public static final int PLAY = 1;
    public static final int RECORDS = 2;
    public static final int TRAINING = 3;
    public static final int SETTINGS = 4;

    public interface OnTabClickListener {
        void onTabClick(int tab);
    }

    private static final String[] LABELS = {"首页", "对弈", "棋谱", "训练", "设置"};
    private static final int[] ICONS = {
            R.drawable.ic_home_ivory,
            R.drawable.ic_board_grid_gold,
            R.drawable.ic_book_ivory,
            R.drawable.ic_training_ivory,
            R.drawable.ic_settings_ivory
    };

    private final LinearLayout[] cells = new LinearLayout[5];
    private final TextView[] labels = new TextView[5];
    private final View[] underlines = new View[5];
    private int selectedTab = HOME;
    private OnTabClickListener listener;

    public JadeBottomNavView(Context context) {
        super(context);
        init();
    }

    public JadeBottomNavView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JadeBottomNavView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shell() {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF6093026, 0xF21A4A38, 0xF6093026});
        shape.setCornerRadius(dp(24));
        shape.setStroke(dp(1.4f), 0xDDBEA766);
        return shape;
    }

    private GradientDrawable selectedBackground() {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x38CEBB6E, 0x08705836});
        shape.setCornerRadius(dp(16));
        return shape;
    }

    private GradientDrawable goldLine() {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x66D4A24E, 0xFFFFDB8E, 0x66D4A24E});
        shape.setCornerRadius(dp(2));
        return shape;
    }

    private void init() {
        setBackground(shell());
        setClipToPadding(false);
        setPadding(dp(7), dp(4), dp(7), dp(4));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addView(row, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        for (int i = 0; i < LABELS.length; i++) {
            final int tab = i;
            LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(1), dp(2), dp(1), dp(1));
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setContentDescription(LABELS[i]);
            cell.setOnClickListener(v -> {
                if (listener != null) listener.onTabClick(tab);
            });
            row.addView(cell, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));

            ImageView icon = new ImageView(getContext());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageResource(ICONS[i]);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams imageParams =
                    new LinearLayout.LayoutParams(dp(28), dp(28));
            cell.addView(icon, imageParams);

            TextView label = new TextView(getContext());
            label.setText(LABELS[i]);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setTextSize(11);
            label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            LinearLayout.LayoutParams labelParams =
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(19));
            labelParams.topMargin = dp(1);
            cell.addView(label, labelParams);

            View underline = new View(getContext());
            underline.setBackground(goldLine());
            LinearLayout.LayoutParams lineParams =
                    new LinearLayout.LayoutParams(dp(23), dp(2));
            lineParams.topMargin = dp(2);
            cell.addView(underline, lineParams);

            cells[i] = cell;
            labels[i] = label;
            underlines[i] = underline;
        }
        setSelectedTab(HOME);
    }

    public void setOnTabClickListener(OnTabClickListener callback) {
        listener = callback;
    }

    public void setSelectedTab(int tab) {
        if (tab < HOME || tab > SETTINGS)
            throw new IllegalArgumentException("Invalid jade tab " + tab);
        selectedTab = tab;
        for (int i = 0; i < cells.length; i++) {
            boolean active = i == tab;
            cells[i].setSelected(active);
            cells[i].setBackground(active ? selectedBackground() : null);
            labels[i].setTextColor(active ? 0xFFFFDF95 : 0xFFE1E9D8);
            labels[i].setTypeface(Typeface.DEFAULT,
                    active ? Typeface.BOLD : Typeface.NORMAL);
            underlines[i].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public int getSelectedTab() {
        return selectedTab;
    }
}
