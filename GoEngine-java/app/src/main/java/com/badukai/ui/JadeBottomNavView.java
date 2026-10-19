package com.badukai.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;

/**
 * The ORIGINAL illustrated jade bar with five actual transparent touch zones.
 *
 * The original PNG is 2172x724 and has large transparent gutters. It must
 * have its transparent margins cropped and be shown with FIT_CENTER; FIT_XY
 * into a 70dp footer visibly squashed the original artwork.
 *
 * The source PNG permanently contains a glowing selected Home underline.
 * For other tabs we prepare a second bitmap once, cloning background pixels
 * across ONLY that line and gently dimming the baked Home glow. The original
 * image bytes remain untouched; Home always uses the original artwork.
 */
public final class JadeBottomNavView extends FrameLayout {
    public static final int HOME = 0;
    public static final int PLAY = 1;
    public static final int RECORDS = 2;
    public static final int TRAINING = 3;
    public static final int SETTINGS = 4;

    private static final String ART = "home/navbar/bar.png";
    private static final String[] LABELS = {"首页", "对弈", "棋谱", "训练", "设置"};
    private static Bitmap selectedHomeArt;
    private static Bitmap otherTabArt;

    public interface OnTabClickListener {
        void onTabClick(int tab);
    }

    private final FrameLayout[] cells = new FrameLayout[5];
    private final View[] selectedLines = new View[5];
    private final ImageView art;
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

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        loadArt(context);
        if (selectedHomeArt != null) {
            art.setImageBitmap(selectedHomeArt);
        } else {
            GradientDrawable fallback = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {0xE6093026, 0xEA174737, 0xE6093026});
            fallback.setCornerRadius(dp(22));
            fallback.setStroke(dp(1), 0xFFCEB46F);
            art.setBackground(fallback);
        }
        addView(art, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        // The original artwork already contains ALL icons and text; never
        // repaint or lay a second set on top of it.
        row.setPadding(dp(12), dp(5), dp(12), dp(5));
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
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(23), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.bottomMargin = dp(15);
            cell.addView(line, params);
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

    private static int blend(int original, int replacement, float amount) {
        float inverse = 1f - amount;
        return Color.argb(
                Math.round(Color.alpha(original) * inverse + Color.alpha(replacement) * amount),
                Math.round(Color.red(original) * inverse + Color.red(replacement) * amount),
                Math.round(Color.green(original) * inverse + Color.green(replacement) * amount),
                Math.round(Color.blue(original) * inverse + Color.blue(replacement) * amount));
    }

    private static float feather(int coord, int start, int end, int edge) {
        if (coord < start || coord >= end) return 0f;
        int d = Math.min(coord - start, end - 1 - coord);
        return Math.min(1f, (d + 1f) / Math.max(1, edge));
    }

    /**
     * Remove ONLY the original Home selection line on non-Home pages by
     * feather-blending a parallel, unmarked part of the same painted jade.
     * The line is in the lower part of the first icon's region of bar.png.
     */
    private static Bitmap inactiveVersion(Bitmap source) {
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        int w = source.getWidth(), h = source.getHeight();
        int l = Math.round(w * .105f), r = Math.round(w * .182f);
        int t = Math.round(h * .542f), b = Math.round(h * .622f);
        int edge = Math.max(3, Math.round(w * .005f));
        for (int y = t; y < b; y++) {
            for (int x = l; x < r; x++) {
                float fade = feather(x, l, r, edge) * feather(y, t, b, edge);
                int sx = Math.min(w - 1, Math.round(x + w * .190f));
                int replacement = source.getPixel(sx, y);
                result.setPixel(x, y, blend(source.getPixel(x, y), replacement, fade));
            }
        }
        // Suppress the baked golden Home bloom while preserving the original
        // line work and captions. Soft radial falloff leaves no hard rectangle.
        float cx = w * .136f, cy = h * .414f, rx = w * .075f, ry = h * .205f;
        int xl = Math.max(0, (int)(cx - rx)), xr = Math.min(w, (int)(cx + rx));
        int yt = Math.max(0, (int)(cy - ry)), yb = Math.min(h, (int)(cy + ry));
        for (int y = yt; y < yb; y++) {
            for (int x = xl; x < xr; x++) {
                float dx = (x - cx) / rx, dy = (y - cy) / ry;
                float dist = dx * dx + dy * dy;
                if (dist >= 1f) continue;
                float weight = .56f * (1f - dist) * (1f - dist);
                int old = result.getPixel(x, y);
                int dark = Color.argb(Color.alpha(old), 35, 68, 52);
                result.setPixel(x, y, blend(old, dark, weight));
            }
        }
        return result;
    }

    /** Crop the transparent padding equally for both tab states. */
    private static int[] artworkBounds(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                if (Color.alpha(source.getPixel(x, y)) < 25) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) return new int[] {0, 0, w, h};
        int padX = Math.max(2, (maxX - minX) / 45);
        int padY = Math.max(2, (maxY - minY) / 15);
        int left = Math.max(0, minX - padX);
        int top = Math.max(0, minY - padY);
        int right = Math.min(w, maxX + padX + 2);
        int bottom = Math.min(h, maxY + padY + 2);
        return new int[] {left, top, right - left, bottom - top};
    }

    private static synchronized void loadArt(Context context) {
        if (selectedHomeArt != null) return;
        try (InputStream stream = context.getAssets().open(ART)) {
            Bitmap full = BitmapFactory.decodeStream(stream);
            if (full == null) return;
            int[] b = artworkBounds(full);
            Bitmap inactive = inactiveVersion(full);
            selectedHomeArt = Bitmap.createBitmap(full, b[0], b[1], b[2], b[3]);
            otherTabArt = Bitmap.createBitmap(inactive, b[0], b[1], b[2], b[3]);
        } catch (Exception ignored) {
            // Do not block a playable match if an external art asset is missing.
        }
    }

    public void setOnTabClickListener(OnTabClickListener callback) {
        listener = callback;
    }

    public void setSelectedTab(int tab) {
        if (tab < HOME || tab > SETTINGS) {
            throw new IllegalArgumentException("Invalid jade tab " + tab);
        }
        selectedTab = tab;
        if (selectedHomeArt != null && otherTabArt != null) {
            art.setImageBitmap(tab == HOME ? selectedHomeArt : otherTabArt);
        }
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
