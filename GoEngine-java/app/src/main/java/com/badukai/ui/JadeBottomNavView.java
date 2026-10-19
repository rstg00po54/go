package com.badukai.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.badukai.R;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared five-tab navigator: the jade bar is the background, and each
 * icon + caption is an independent normal/selected PNG.
 * Do not erase or repaint the old bar image to fake active states.
 */
public final class JadeBottomNavView extends FrameLayout {
    private static final String TAG = "JadeBottomNavView";
    public static final int HOME = 0;
    public static final int PLAY = 1;
    public static final int RECORDS = 2;
    public static final int TRAINING = 3;
    public static final int SETTINGS = 4;

    private static final String BACKGROUND_ASSET = "home/navbar/bar0.png";
    private static final String[] NAMES = {"首页", "对弈", "棋谱", "训练", "设置"};
    private static final int[][] TAB_DRAWABLES = {
            {R.drawable.jade_nav_home_normal, R.drawable.jade_nav_home_selected},
            {R.drawable.jade_nav_game_normal, R.drawable.jade_nav_game_selected},
            {R.drawable.jade_nav_record_normal, R.drawable.jade_nav_record_selected},
            {R.drawable.jade_nav_training_normal, R.drawable.jade_nav_training_selected},
            {R.drawable.jade_nav_settings_normal, R.drawable.jade_nav_settings_selected}
    };

    public interface OnTabClickListener {
        void onTabClick(int tab);
    }

    private final FrameLayout[] hitTargets = new FrameLayout[NAMES.length];
    private final ImageView[] tabArtwork = new ImageView[NAMES.length];
    private OnTabClickListener callback;
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

        // A separate background layer; no icons, labels, or selection marks
        // are painted onto this bitmap.
        ImageView backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.FIT_CENTER);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        backdrop.setClickable(false);
        addView(backdrop, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        loadBackdrop(context, backdrop);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(11), 0, dp(11), 0);
        addView(row, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        for (int i = 0; i < NAMES.length; i++) {
            final int tab = i;
            FrameLayout hit = new FrameLayout(context);
            hit.setClickable(true);
            hit.setFocusable(true);
            hit.setContentDescription(NAMES[i]);
            hit.setClipChildren(false);
            hit.setClipToPadding(false);

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(false);
            image.setClickable(false);
            image.setFocusable(false);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Always use exactly the same bounds in both states:
            // no changing scale, captions, or underlines on selection.
            hit.addView(image, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            hit.setOnClickListener(v -> {
                if (callback != null) callback.onTabClick(tab);
            });

            row.addView(hit, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));
            hitTargets[i] = hit;
            tabArtwork[i] = image;
        }
        setSelectedTab(HOME);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Remove only invisible outer padding of bar0, preserving its painted pixels. */
    private static Bitmap trimTransparentMargins(Bitmap original) {
        int w = original.getWidth(), h = original.getHeight();
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                if (Color.alpha(original.getPixel(x, y)) <= 18) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) return original;
        left = Math.max(0, left - 2);
        top = Math.max(0, top - 2);
        right = Math.min(w, right + 3);
        bottom = Math.min(h, bottom + 3);
        if (left == 0 && top == 0 && right == w && bottom == h) return original;
        return Bitmap.createBitmap(original, left, top, right - left, bottom - top);
    }

    private void loadBackdrop(Context context, ImageView backdrop) {
        try (InputStream in = context.getAssets().open(BACKGROUND_ASSET)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException("Cannot decode " + BACKGROUND_ASSET);
            backdrop.setImageBitmap(trimTransparentMargins(bitmap));
        } catch (IOException e) {
            Log.e(TAG, "Missing jade navbar backdrop: " + BACKGROUND_ASSET, e);
            setBackgroundColor(0xFF09291F);
        }
    }

    public void setOnTabClickListener(OnTabClickListener onClick) {
        callback = onClick;
    }

    public void setSelectedTab(int tab) {
        if (tab < HOME || tab > SETTINGS) {
            throw new IllegalArgumentException("Invalid jade navigation tab " + tab);
        }
        selectedTab = tab;
        for (int i = 0; i < hitTargets.length; i++) {
            tabArtwork[i].setImageResource(TAB_DRAWABLES[i][i == tab ? 1 : 0]);
            hitTargets[i].setSelected(i == tab);
        }
    }

    public int getSelectedTab() {
        return selectedTab;
    }
}
