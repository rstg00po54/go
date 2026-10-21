package com.badukai.ui;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.widget.TextView;

import com.badukai.R;

/**
 * Binds the 512x512 original RGBA jade PNG art to appropriately sized native
 * Android compound drawables. Unbounded bitmap intrinsic dimensions must NOT
 * participate in TextView measurement (512px icons otherwise overflow rows).
 *
 * The 22 PNG files are stored in drawable-nodpi; this class sets final bounds
 * in dp without changing their gold/emerald artwork or tinting them.
 */
public final class JadeIconBinder {
    private JadeIconBinder() {}

    public static void bindNewGame(View root) {
        // Section headings: restrained, consistent gold artwork.
        left(root, R.id.jadeSectionBoardSize, R.drawable.ic_board_size, 26);
        left(root, R.id.jadeSectionMode, R.drawable.ic_player, 26);
        left(root, R.id.jadeSectionRank, R.drawable.ic_ai_level, 26);
        left(root, R.id.jadeSectionColor, R.drawable.ic_turn, 26);
        left(root, R.id.jadeSectionBackend, R.drawable.ic_backend, 26);
        left(root, R.id.jadeSectionRules, R.drawable.ic_turn, 26);

        // Selection cards: transparent image is contained *inside* each tile.
        // All three selector rows use the same 8dp transparent inset. Android
        // centers icon+label together, lowering their visual group by ~4dp.
        // Keep the original transparent PNG artwork and its 38/34dp size.
        topLowered(root, R.id.modeAiButton, R.drawable.ic_mode_ai, 38, 8);
        topLowered(root, R.id.modeLocalButton, R.drawable.ic_mode_human, 38, 8);
        topLowered(root, R.id.modeReplayButton, R.drawable.ic_import_sgf, 38, 8);
        topLowered(root, R.id.blackRadio, R.drawable.ic_black_stone, 34, 8);
        topLowered(root, R.id.whiteRadio, R.drawable.ic_white_stone, 34, 8);
        topLowered(root, R.id.randomRadio, R.drawable.ic_random, 34, 8);
        topLowered(root, R.id.backendCpuButton, R.drawable.ic_cpu, 38, 8);
        topLowered(root, R.id.backendGpuButton, R.drawable.ic_gpu, 38, 8);
        topLowered(root, R.id.backendNpuButton, R.drawable.ic_npu, 38, 8);


    }

    private static void left(View root, int viewId, int resourceId, int sizeDp) {
        apply(root, viewId, resourceId, sizeDp, true);
    }

    private static void top(View root, int viewId, int resourceId, int sizeDp) {
        apply(root, viewId, resourceId, sizeDp, false);
    }

    private static void topLowered(View root, int viewId, int resourceId,
                                   int sizeDp, int topInsetDp) {
        apply(root, viewId, resourceId, sizeDp, false, topInsetDp);
    }

    private static void apply(View root, int viewId, int resourceId,
                              int sizeDp, boolean leading) {
        apply(root, viewId, resourceId, sizeDp, leading, 0);
    }

    private static void apply(View root, int viewId, int resourceId,
                              int sizeDp, boolean leading, int topInsetDp) {
        View view = root.findViewById(viewId);
        if (!(view instanceof TextView)) {
            throw new IllegalStateException("Expected icon TextView: " + viewId);
        }
        TextView target = (TextView) view;
        Drawable art = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(target.getContext(), resourceId);
        if (art == null) {
            throw new IllegalStateException("Missing icon drawable: " + resourceId);
        }
        // mutate() prevents one view's draw bounds from contaminating another.
        art = art.mutate();
        int pixels = Math.round(sizeDp * target.getResources().getDisplayMetrics().density);
        art.setBounds(0, 0, pixels, pixels);
        if (!leading && topInsetDp > 0) {
            int offset = Math.round(topInsetDp * target.getResources().getDisplayMetrics().density);
            InsetDrawable lowered = new InsetDrawable(art, 0, offset, 0, 0);
            lowered.setBounds(0, 0, pixels, pixels + offset);
            art = lowered;
        }
        if (leading) {
            target.setCompoundDrawablesRelative(art, null, null, null);
        } else {
            target.setCompoundDrawablesRelative(null, art, null, null);
        }
    }
}
