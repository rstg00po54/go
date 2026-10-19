package com.badukai.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;

/**
 * One interactive footer with the ORIGINAL illustrated jade navbar artwork.
 *
 * home/navbar/bar.png contains the artist's original bamboo leaves, clouds,
 * gold border, glowing home icon and typography. Draw the artwork once,
 * UNCHANGED, then put real equal-width transparent touch cells above it.
 * Do not redraw the icons or text with generic Android widgets.
 *
 * The original raster highlights Home. On other pages an emerald overlay
 * dims that baked-in glow and a golden short line identifies the active tab.
 * This avoids making a new, visually different navbar.
 */
public final class JadeBottomNavView extends FrameLayout {
    public static final int HOME = 0;
    public static final int PLAY = 1;
    public static final int RECORDS = 2;
    public static final int TRAINING = 3;
    public static final int SETTINGS = 4;

    private static final String ART = "home/navbar/bar.png";
    private static final String[] LABELS = {"首页", "对弈", "棋谱", "训练", "设置"};
    private static Bitmap cachedArtwork;

    public interface OnTabClickListener {
        void onTabClick(int tab);
    }

    private final FrameLayout[] cells = new FrameLayout[5];
    private final View[] selectedLines = new View[5];
    private final View homeDimmer;
    private OnTabClickListener listener;
    private int selectedTab = HOME;

    public JadeBottomNavView(Context context) {
        this(context, null);
    }

    public JadeBottomNavView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JadeBottomNavView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipChildren(false);
        setClipToPadding(false);

        ImageView originalArtwork = new ImageView(context);
        originalArtwork.setScaleType(ImageView.ScaleType.FIT_XY);
        originalArtwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Bitmap image = getArtwork(context);
        if (image != null) {
            originalArtwork.setImageBitmap(image);
        } else {
            // Fallback only for IDE previews / resource packs without the PNG.
            GradientDrawable backup = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {0xEE09382B, 0xF0154939, 0xEE09382B});
            backup.setCornerRadius(dp(22));
            backup.setStroke(dp(1), 0xFFCEB46F);
            originalArtwork.setBackground(backup);
        }
        addView(originalArtwork, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Original bar.png has Home permanently lit. Dim that part outside
        // Home, rather than drawing a second set of artwork over the PNG.
        homeDimmer = new View(context);
        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xAA062A20, 0x82062A20, 0x88062A20});
        shade.setCornerRadius(dp(13));
        homeDimmer.setBackground(shade);
        FrameLayout.LayoutParams dimParams = new FrameLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, Gravity.START);
        addView(homeDimmer, dimParams);
        homeDimmer.setClickable(false);
        homeDimmer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        // Transparent 5-way hit area; every icon and glyph remains original.
        row.setPadding(dp(13), dp(4), dp(13), dp(4));
        addView(row, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        for (int i = 0; i < LABELS.length; i++) {
            final int tab = i;
            FrameLayout cell = new FrameLayout(context);
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setContentDescription(LABELS[i]);
            cell.setOnClickListener(v -> {
                if (listener != null) listener.onTabClick(tab);
            });
            row.addView(cell, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));

            View line = new View(context);
            GradientDrawable gold = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {0x66DDA95D, 0xFFFFDF8D, 0x66DDA95D});
            gold.setCornerRadius(dp(2));
            line.setBackground(gold);
            FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(
                    dp(25), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lineParams.bottomMargin = dp(8);
            cell.addView(line, lineParams);
            line.setClickable(false);
            line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cells[i] = cell;
            selectedLines[i] = line;
        }
        setSelectedTab(HOME);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static synchronized Bitmap getArtwork(Context context) {
        if (cachedArtwork == null) {
            try (InputStream stream = context.getAssets().open(ART)) {
                cachedArtwork = BitmapFactory.decodeStream(stream);
            } catch (Exception ignored) {
                // The backup emerald shell is only used if asset packaging fails.
            }
        }
        return cachedArtwork;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                homeDimmer.getLayoutParams();
        params.width = Math.max(1, Math.round(w * 0.22f));
        params.height = h;
        homeDimmer.setLayoutParams(params);
    }

    public void setOnTabClickListener(OnTabClickListener callback) {
        listener = callback;
    }

    public void setSelectedTab(int tab) {
        if (tab < HOME || tab > SETTINGS) {
            throw new IllegalArgumentException("Invalid jade tab " + tab);
        }
        selectedTab = tab;
        // HOME uses the exact original hand-drawn glow and underline.
        homeDimmer.setVisibility(tab == HOME ? View.GONE : View.VISIBLE);
        for (int i = 0; i < cells.length; i++) {
            cells[i].setSelected(i == tab);
            selectedLines[i].setVisibility(tab != HOME && i == tab
                    ? View.VISIBLE : View.INVISIBLE);
        }
    }

    public int getSelectedTab() {
        return selectedTab;
    }
}
