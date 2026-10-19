package com.badukai.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;

/**
 * Unified navigation using the ORIGINAL artist-made bar.png.
 *
 * All five icons, captions, bamboo/clouds and border are already baked into
 * that picture. Never draw a second set of labels, copy pixels from adjacent
 * tabs, or put a gold line outside the image. The previous pixel-copy attempt
 * reproduced neighboring Chinese glyphs over 首页.
 */
public final class JadeBottomNavView extends FrameLayout {
    public static final int HOME = 0;
    public static final int PLAY = 1;
    public static final int RECORDS = 2;
    public static final int TRAINING = 3;
    public static final int SETTINGS = 4;

    private static final String SOURCE = "home/navbar/bar.png";
    private static final String[] NAMES = {"首页", "对弈", "棋谱", "训练", "设置"};
    private static final Bitmap[] TAB_IMAGES = new Bitmap[5];

    public interface OnTabClickListener {
        void onTabClick(int tab);
    }

    private final ImageView art;
    private final FrameLayout[] hitTargets = new FrameLayout[5];
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

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(art, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(11), dp(3), dp(11), dp(3));
        addView(row, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < NAMES.length; i++) {
            final int tab = i;
            FrameLayout hit = new FrameLayout(context);
            hit.setClickable(true);
            hit.setFocusable(true);
            hit.setContentDescription(NAMES[i]);
            hit.setOnClickListener(v -> {
                if (callback != null) callback.onTabClick(tab);
            });
            row.addView(hit, new LinearLayout.LayoutParams(
                    0, LayoutParams.MATCH_PARENT, 1f));
            hitTargets[i] = hit;
        }
        initializeArt(context);
        setSelectedTab(HOME);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Blend towards local jade green without borrowing text from other tabs. */
    private static int shade(int c, float f) {
        float remain = 1f - f;
        return Color.argb(Color.alpha(c),
                Math.round(Color.red(c) * remain + 24 * f),
                Math.round(Color.green(c) * remain + 54 * f),
                Math.round(Color.blue(c) * remain + 40 * f));
    }

    /** Smooth mask prevents hard rectangular retouch edges. */
    private static float feather(int v, int min, int max, int edge) {
        if (v < min || v > max) return 0f;
        return Math.min(1f, Math.min(v - min, max - v) / (float) Math.max(1, edge));
    }

    private static int[] artBounds(Bitmap original) {
        int w = original.getWidth(), h = original.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                if (Color.alpha(original.getPixel(x, y)) < 28) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX) return new int[]{0, 0, w, h};
        int marginX = Math.round((maxX - minX) * .015f);
        int marginY = Math.round((maxY - minY) * .09f);
        int left = Math.max(0, minX - marginX);
        int top = Math.max(0, minY - marginY);
        int right = Math.min(w, maxX + marginX + 3);
        int bottom = Math.min(h, maxY + marginY + 3);
        return new int[]{left, top, right - left, bottom - top};
    }

    /** Remove only Home's baked selected underline; don't touch 首页 glyphs. */
    private static void quietOriginalHomeUnderline(Bitmap bitmap, int[] box) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int cx = Math.round(box[0] + box[2] * .139f);
        // The original selected underline is inside the source, near 0.70H.
        int left = Math.max(0, cx - Math.round(box[2] * .051f));
        int right = Math.min(w - 1, cx + Math.round(box[2] * .051f));
        int top = Math.round(h * .645f);
        int bottom = Math.round(h * .737f);
        int featherX = Math.max(3, Math.round(w * .006f));
        int featherY = Math.max(3, Math.round(h * .008f));
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                float f = .96f * feather(x, left, right, featherX)
                        * feather(y, top, bottom, featherY);
                if (f > 0f) bitmap.setPixel(x, y, shade(bitmap.getPixel(x, y), f));
            }
        }
    }

    /** Place the only selection mark within the actual image coordinates. */
    private static void drawSelectedUnderline(Bitmap bitmap, int tab, int[] box) {
        Canvas canvas = new Canvas(bitmap);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        int cx = Math.round(box[0] + box[2] * (.139f + tab * .185f));
        float y = bitmap.getHeight() * .688f;
        float half = box[2] * .018f;
        float thickness = Math.max(4, bitmap.getHeight() * .010f);
        glow.setColor(0x70F5D27D);
        canvas.drawRoundRect(new RectF(cx-half-8,y-5,cx+half+8,
                y+thickness+5), 8, 8, glow);
        glow.setColor(0xFFFCE2A2);
        canvas.drawRoundRect(new RectF(cx-half,y,cx+half,
                y+thickness), thickness, thickness, glow);
    }

    private static synchronized void initializeArt(Context context) {
        if (TAB_IMAGES[HOME] != null) return;
        try (InputStream in = context.getAssets().open(SOURCE)) {
            Bitmap original = BitmapFactory.decodeStream(in);
            if (original == null) return;
            int[] bounds = artBounds(original);
            // Home always uses untouched original, not a reconstruction.
            TAB_IMAGES[HOME] = Bitmap.createBitmap(original,
                    bounds[0], bounds[1], bounds[2], bounds[3]);
            for (int tab = PLAY; tab <= SETTINGS; tab++) {
                Bitmap variation = original.copy(Bitmap.Config.ARGB_8888, true);
                quietOriginalHomeUnderline(variation, bounds);
                drawSelectedUnderline(variation, tab, bounds);
                TAB_IMAGES[tab] = Bitmap.createBitmap(variation,
                        bounds[0], bounds[1], bounds[2], bounds[3]);
            }
        } catch (Exception ignored) {
            // Missing optional artwork must not prevent playing Go.
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
        art.setImageBitmap(TAB_IMAGES[tab]);
        for (int i = 0; i < hitTargets.length; i++) {
            hitTargets[i].setSelected(i == tab);
        }
    }

    public int getSelectedTab() {
        return selectedTab;
    }
}
