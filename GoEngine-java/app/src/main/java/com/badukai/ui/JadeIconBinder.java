package com.badukai.ui;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
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

        // Bottom tabs use a smaller 4dp inset to preserve the 60dp navbar.
        topLowered(root, R.id.newGameHomeTab, R.drawable.ic_home, 29, 4);
        topLowered(root, R.id.newGameRecordTab, R.drawable.ic_records, 29, 4);
        topLowered(root, R.id.newGamePlayTab, R.drawable.ic_game_tab, 29, 4);
        topLowered(root, R.id.newGameTrainingTab, R.drawable.ic_training, 29, 4);
        topLowered(root, R.id.newGameSettingsTab, R.drawable.ic_settings_tab, 29, 4);
    }

    /** Fit the provided 25 game art PNGs to compact, clickable native widgets. */
    public static void bindGame(View root) {
        // Game summary icons are independent 21dp ImageViews in the layout,
        // not compound text drawables. This keeps their values aligned.
        // Inline image spans participate in TextView's centered text layout.
        // drawableStart would pin the art against the *left edge* of each button.
        centeredAction(root, R.id.passButton, R.drawable.ic_pass_jade, 21);
        centeredAction(root, R.id.undoButton, R.drawable.ic_undo_jade, 21);
        centeredAction(root, R.id.situationButton, R.drawable.ic_territory_jade, 21);
        centeredAction(root, R.id.aiSuggestionButton, R.drawable.ic_hint_gold, 21);
        centeredAction(root, R.id.gameAnalysisButton, R.drawable.ic_analysis_jade, 21);
        top(root, R.id.gameNavHome, R.drawable.ic_home_ivory, 26);
        top(root, R.id.gameNavRecords, R.drawable.ic_book_ivory, 26);
        top(root, R.id.gameNavPlay, R.drawable.ic_game_gold, 26);
        top(root, R.id.gameNavTraining, R.drawable.ic_training_ivory, 26);
        top(root, R.id.gameNavSettings, R.drawable.ic_settings_ivory, 26);
    }

    /**
     * Centers the icon and text as a single string. Android compound
     * drawableStart always stays at the button edge even with gravity=center.
     * ImageSpan puts the real PNG into the text flow so varying label lengths
     * still produce a centered icon+label group, with no custom touch handling.
     */
    private static void centeredAction(View root, int viewId, int resourceId, int sizeDp) {
        View view = root.findViewById(viewId);
        if (!(view instanceof TextView)) {
            throw new IllegalStateException("Expected game action button: " + viewId);
        }
        TextView target = (TextView) view;
        Drawable icon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(target.getContext(), resourceId);
        if (icon == null) {
            throw new IllegalStateException("Missing game action icon: " + resourceId);
        }
        icon = icon.mutate();
        int px = Math.round(sizeDp * target.getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, px, px);
        // Strip the XML compound drawable, or the artwork would appear twice.
        target.setCompoundDrawablesRelative(null, null, null, null);
        String text = target.getText().toString();
        SpannableStringBuilder label = new SpannableStringBuilder("  ");
        label.append(text);
        label.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_CENTER),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setText(label, TextView.BufferType.SPANNABLE);
        target.setGravity(Gravity.CENTER);
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
