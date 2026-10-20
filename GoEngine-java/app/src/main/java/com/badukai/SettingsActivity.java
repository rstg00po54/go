package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeNavigation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Jade settings: eight real, separately navigable categories.
 * Options only claim to change gameplay when a consumer implements them.
 */
public final class SettingsActivity extends Activity {
    private static final int GOLD = Color.rgb(246, 214, 143);
    private static final int IVORY = Color.rgb(245, 239, 211);
    private static final int MUTED = Color.rgb(178, 205, 184);
    private static final int GREEN = 0xFF38E5B2;
    private static final int[] ICONS = {
            R.drawable.ic_settings_ivory, R.drawable.ic_board_size,
            R.drawable.ic_player, R.drawable.ic_ai_level,
            android.R.drawable.ic_lock_silent_mode_off,
            R.drawable.ic_book_ivory, R.drawable.ic_training_ivory,
            android.R.drawable.ic_dialog_info
    };
    private static final String[] CATEGORIES = {
            "通用设置", "对弈设置", "棋盘外观", "AI 设置",
            "声音设置", "棋谱设置", "训练设置", "关于"
    };
    private SharedPreferences prefs;
    private LinearLayout sidebar, content;
    private ScrollView contentScroll;
    private ImageView backdrop;
    private Bitmap themePreview;
    private final List<View> sidebarViews = new ArrayList<>();
    private int category;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("katago_settings", MODE_PRIVATE);
        setContentView(R.layout.activity_jade_settings);
        sidebar = findViewById(R.id.settingsSidebar);
        content = findViewById(R.id.settingsContent);
        contentScroll = findViewById(R.id.settingsScroll);
        backdrop = findViewById(R.id.settingsBackdrop);

        try (InputStream input = getAssets().open("home/background.png")) {
            backdrop.setImageBitmap(BitmapFactory.decodeStream(input));
        } catch (Exception ignored) { /* dark jade fallback stays visible */ }
        try (InputStream input = getAssets().open("home/background.png")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 8;
            themePreview = BitmapFactory.decodeStream(input, null, options);
        } catch (Exception ignored) { /* solid color thumbnails remain usable */ }
        findViewById(R.id.settingsBack).setOnClickListener(v -> finish());
        JadeBottomNavView nav = findViewById(R.id.settingsSharedNav);
        nav.setSelectedTab(JadeBottomNavView.SETTINGS);
        nav.setOnTabClickListener(tab -> {
            if (tab != JadeBottomNavView.SETTINGS) JadeNavigation.open(this, tab);
        });
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int target = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(7), 0, dp(3), 0);
            ImageView picture = new ImageView(this);
            picture.setImageResource(ICONS[i]);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(picture, new LinearLayout.LayoutParams(dp(21), dp(21)));
            TextView label = text(CATEGORIES[i], 12, IVORY, true);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(5), 0, 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, dp(48));
            margin.bottomMargin = dp(2);
            sidebar.addView(item, margin);
            item.setOnClickListener(v -> selectCategory(target));
            item.setContentDescription(CATEGORIES[i]);
            sidebarViews.add(item);
        }
        selectCategory(0);
    }

    private void selectCategory(int selected) {
        category = selected;
        for (int i = 0; i < sidebarViews.size(); i++) {
            View view = sidebarViews.get(i);
            view.setBackground(panel(i == selected, 10));
            view.setSelected(i == selected);
        }
        content.removeAllViews();
        contentScroll.scrollTo(0, 0);
        switch (selected) {
            case 0: general(); break;
            case 1: game(); break;
            case 2: board(); break;
            case 3: ai(); break;
            case 4: sounds(); break;
            case 5: records(); break;
            case 6: training(); break;
            default: about();
        }
    }

    private void general() {
        LinearLayout language = group("语言与界面", R.drawable.ic_settings_ivory);
        lineLabel(language, "语言");
        choices(language, new String[]{"简体中文", "English"}, 0, picked -> {
            if (picked == 1) toast("完整英文界面尚未适配，暂不切换语言");
        }, new boolean[]{true, false});
        lineLabel(language, "主题风格");
        themeChoices(language);
        lineLabel(language, "界面缩放");
        choices(language, new String[]{"小", "标准", "大"}, prefs.getInt("settings_scale", 1),
                picked -> {
                    prefs.edit().putInt("settings_scale", picked).apply();
                    toast("已调整设置页预览比例");
                    selectCategory(0);
                }, null);
        note(language, "目前缩放作用于设置页，其他页面的布局保持不变。");

        LinearLayout display = group("显示设置", R.drawable.ic_board_size);
        toggle(display, "显示坐标", "在棋盘上显示字母与数字坐标",
                "show_coordinates", true, true);
        toggle(display, "显示最后一手", "标记对局中的最后一步棋",
                "show_last_move", true, true);
        toggle(display, "显示胜率", "在对弈页显示实时胜率信息",
                "show_winrate", true, true);
        toggle(display, "显示目数估计", "显示已有的 NN 形势估计结果",
                "show_score_estimate", true, true);
        lineLabel(display, "最后一手样式");
        choices(display, new String[]{"金圈高亮", "简约方框"}, prefs.getInt("last_move_style", 0),
                picked -> { prefs.edit().putInt("last_move_style", picked).apply(); selectCategory(0); },
                null);
        lineLabel(display, "提示点样式");
        choices(display, new String[]{"绿色光点", "金色标记"}, prefs.getInt("hint_style", 0),
                picked -> { prefs.edit().putInt("hint_style", picked).apply(); selectCategory(0); },
                null);

        LinearLayout interfaceGroup = group("界面显示", R.drawable.ic_settings_ivory);
        toggle(interfaceGroup, "动画效果", "控制首页按钮的缩放动效",
                "ui_animations", true, true);
        toggle(interfaceGroup, "高对比显示", "增强棋盘网格和坐标对比度",
                "high_contrast", false, true);
        toggle(interfaceGroup, "系统深色适配", "全应用系统色切换尚未实现",
                "system_dark", false, false);
    }

    private void game() {
        LinearLayout setup = group("对局偏好", R.drawable.ic_board_size);
        note(setup, "默认棋盘在下次新开对局时生效；不会改变当前棋局。");
        lineLabel(setup, "默认棋盘大小");
        choices(setup, new String[]{"9路", "11路", "13路", "15路", "19路"},
                sizeIndex(prefs.getInt("board_size", 9)), picked -> {
                    prefs.edit().putInt("board_size", new int[]{9, 11, 13, 15, 19}[picked]).apply();
                    selectCategory(1);
                }, null);
        action(setup, "新局参数", "棋力、执子方、让子条件", R.drawable.ic_player,
                () -> JadeNavigation.openNewGameSettings(this));
        LinearLayout state = group("当前默认配置", R.drawable.ic_settings_ivory);
        note(state, "棋力：" + prefs.getInt("ai_kyu", 12) + "级");
        note(state, "执子：" + ("WHITE".equals(prefs.getString("player_color", "BLACK")) ? "白棋" : "黑棋"));
        note(state, "对局条件：" + (prefs.getInt("game_condition", 0) == 0
                ? "分先" : "让子/让先"));
    }

    private void board() {
        LinearLayout marks = group("棋盘标记", R.drawable.ic_board_size);
        toggle(marks, "显示棋盘坐标", "支持 9～19 路棋盘",
                "show_coordinates", true, true);
        toggle(marks, "突出显示最后一手", "支持对弈、复盘和训练",
                "show_last_move", true, true);
        toggle(marks, "高对比棋盘", "增强网格与文字清晰度",
                "high_contrast", false, true);
        lineLabel(marks, "最后一手标记");
        choices(marks, new String[]{"金圈", "方框"}, prefs.getInt("last_move_style", 0),
                picked -> { prefs.edit().putInt("last_move_style", picked).apply(); selectCategory(2); }, null);
        lineLabel(marks, "AI 提示点");
        choices(marks, new String[]{"绿色", "金色"}, prefs.getInt("hint_style", 0),
                picked -> { prefs.edit().putInt("hint_style", picked).apply(); selectCategory(2); }, null);
        LinearLayout view = group("棋盘主题", R.drawable.ic_player);
        themeChoices(view);
    }

    private void ai() {
        LinearLayout backend = group("KataGo 推理", R.drawable.ic_ai_level);
        note(backend, "CPU、GPU、NPU 的可用性因设备、模型与安装包不同而异。");
        note(backend, "上次使用：" + prefs.getString("backend", "CPU"));
        action(backend, "选择 AI 后端", "在新建对局页检查设备支持情况",
                R.drawable.ic_backend, () -> JadeNavigation.openNewGameSettings(this));
        action(backend, "人类棋力等级", "从 18 级到 1 级，开始新对局时设置",
                R.drawable.ic_ai_level, () -> JadeNavigation.openNewGameSettings(this));
    }

    private void sounds() {
        LinearLayout sound = group("声音设置", android.R.drawable.ic_lock_silent_mode_off);
        note(sound, "当前对弈引擎没有接入独立音效播放器。");
        toggle(sound, "落子音效", "待接入播放功能后开放",
                "sound_move", false, false);
        toggle(sound, "按钮反馈音", "待接入播放功能后开放",
                "sound_buttons", false, false);
        toggle(sound, "触觉反馈", "待接入后开放",
                "haptic_feedback", false, false);
    }

    private void records() {
        LinearLayout group = group("棋谱管理", R.drawable.ic_book_ivory);
        action(group, "历史棋谱", "先选择棋谱，再进入复盘棋盘",
                R.drawable.ic_book_ivory, () -> {
                    Intent intent = new Intent(this, SgfLibraryActivity.class);
                    intent.putExtra(SgfLibraryActivity.EXTRA_OPEN_REPLAY_ON_SELECT, true);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                });
        action(group, "复盘最近一局", "查看上次保存的对局",
                R.drawable.ic_records, () -> {
                    startActivity(new Intent(this, ReplayActivity.class));
                    overridePendingTransition(0, 0);
                });
        action(group, "导入 SGF", "从手机文件中选择棋谱",
                R.drawable.ic_import_sgf, () -> {
                    Intent intent = new Intent(this, ReplayActivity.class);
                    intent.putExtra("replay_entry_action", "import");
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                });
    }

    private void training() {
        LinearLayout group = group("死活训练", R.drawable.ic_training_ivory);
        action(group, "继续练习", "从上次停留的题目继续",
                R.drawable.ic_training_ivory, () -> {
                    startActivity(new Intent(this, TsumegoActivity.class));
                    overridePendingTransition(0, 0);
                });
        action(group, "从第一题开始", "重新练习基础死活题",
                R.drawable.ic_board_size, () -> {
                    startActivity(new Intent(this, TsumegoActivity.class)
                            .putExtra("puzzle_index", 0));
                    overridePendingTransition(0, 0);
                });
        action(group, "查看训练成绩", "已完成题目和练习进度",
                R.drawable.ic_ai_level, () -> {
                    startActivity(new Intent(this, TsumegoResultsActivity.class));
                    overridePendingTransition(0, 0);
                });
    }

    private void about() {
        LinearLayout group = group("关于", android.R.drawable.ic_dialog_info);
        note(group, "竹隐 · 围棋");
        note(group, "KataGo · 本地 AI 围棋");
        try {
            note(group, "版本：" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
        note(group, "Android 原生界面。引擎在设备端运行，具体 CPU / GPU / NPU 支持依赖机型和安装包。");
    }

    private void themeChoices(LinearLayout target) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        int selected = prefs.getInt("jade_theme", 0);
        String[] labels = {"竹影翠绿", "墨韵深色", "素雅浅色"};
        int[] colors = {0xFF065943, 0xFF1C2725, 0xFFE5DBC7};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout option = new LinearLayout(this);
            option.setOrientation(LinearLayout.VERTICAL);
            option.setGravity(Gravity.CENTER_HORIZONTAL);
            option.setPadding(dp(2), dp(3), dp(2), dp(2));
            FrameLayout preview = new FrameLayout(this);
            preview.setBackground(swatch(colors[i], i == selected));
            preview.setClipToOutline(true);
            if (themePreview != null) {
                ImageView picture = new ImageView(this);
                picture.setImageBitmap(themePreview);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (i == 1) picture.setColorFilter(0xFF475253, PorterDuff.Mode.MULTIPLY);
                else if (i == 2) picture.setColorFilter(0xFFFAEDDB, PorterDuff.Mode.SCREEN);
                FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(-1, -1);
                imageParams.setMargins(dp(2), dp(2), dp(2), dp(2));
                preview.addView(picture, imageParams);
            } else {
                TextView glyph = text("●", 19, GOLD, true);
                glyph.setGravity(Gravity.CENTER);
                preview.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
            }
            option.addView(preview, new LinearLayout.LayoutParams(-1, dp(48)));
            TextView caption = text(labels[i], 10, i == selected ? GOLD : MUTED, false);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine(true);
            caption.setTextSize(10);
            option.addView(caption, new LinearLayout.LayoutParams(-1, dp(23)));
            row.addView(option, new LinearLayout.LayoutParams(0, -2, 1f));
            option.setOnClickListener(v -> {
                prefs.edit().putInt("jade_theme", index).apply();
                selectCategory(category);
            });
        }
        target.addView(row, new LinearLayout.LayoutParams(-1, -2));
        note(target, "主题选项当前调整棋盘底色；其他页面背景保留原素材。");
    }

    private interface Selected { void onSelect(int selected); }

    private void choices(LinearLayout target, String[] labels, int selected,
            Selected handler, boolean[] enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean active = enabled == null || enabled[i];
            TextView item = text(labels[i], labels.length > 3 ? 11 : 12,
                    i == selected ? IVORY : MUTED, i == selected);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setBackground(panel(i == selected && active, 9));
            item.setAlpha(active ? 1f : .52f);
            item.setPadding(dp(2), 0, dp(2), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i != 0) params.leftMargin = dp(4);
            row.addView(item, params);
            item.setOnClickListener(v -> {
                if (!active) {
                    if (handler != null) handler.onSelect(index);
                    return;
                }
                if (handler != null) handler.onSelect(index);
            });
        }
        target.addView(row, new LinearLayout.LayoutParams(-1, dp(43)));
    }

    private void toggle(LinearLayout target, String name, String detail,
            String key, boolean defaultValue, boolean supported) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView nameText = text(name, 13, IVORY, true);
        labels.addView(nameText);
        TextView desc = text(supported ? detail : detail + " · 暂不可用", 10, MUTED, false);
        desc.setMaxLines(2);
        labels.addView(desc);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(supported && prefs.getBoolean(key, defaultValue));
        toggle.setEnabled(supported);
        toggle.setThumbTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.WHITE, 0xFFE7E8E0}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF25CAA0, 0xFF606962}));
        toggle.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(v -> { if (supported) toggle.setChecked(!toggle.isChecked()); });
        target.addView(row, new LinearLayout.LayoutParams(-1, -2));
        divider(target);
    }

    private void action(LinearLayout target, String title, String subtitle, int drawable,
            Runnable callback) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), 0, dp(5));
        row.setMinimumHeight(dp(48));
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(8), 0, dp(3), 0);
        words.addView(text(title, 14, IVORY, true));
        words.addView(text(subtitle, 10, MUTED, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = text("›", 26, GOLD, false);
        row.addView(arrow);
        target.addView(row);
        row.setOnClickListener(v -> callback.run());
        divider(target);
    }

    private LinearLayout group(String title, int iconRes) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(10), dp(10), dp(10), dp(9));
        group.setBackground(panel(false, 12));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView label = text(title, 16, GOLD, true);
        label.setPadding(dp(8), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        group.addView(heading, new LinearLayout.LayoutParams(-1, dp(38)));
        divider(group);
        LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
        margins.bottomMargin = dp(10);
        content.addView(group, margins);
        return group;
    }

    private void lineLabel(LinearLayout parent, String label) {
        TextView view = text(label, 13, IVORY, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        params.bottomMargin = dp(5);
        parent.addView(view, params);
    }

    private void note(LinearLayout parent, String message) {
        TextView note = text(message, 11, MUTED, false);
        note.setPadding(dp(1), dp(6), dp(1), dp(8));
        parent.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private void divider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(0x4DABAF8F);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(2);
        params.bottomMargin = dp(3);
        parent.addView(divider, params);
    }

    private TextView text(String label, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        float scale = prefs == null ? 1f
                : new float[]{.92f, 1f, 1.10f}[Math.max(0,
                    Math.min(2, prefs.getInt("settings_scale", 1)))];
        view.setTextSize(sp * scale);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable panel(boolean selected, int radius) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                selected ? new int[]{0xEE127760, 0xEB06372B} :
                        new int[]{0xEF052B24, 0xF208211B});
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(selected ? 2 : 1), selected ? GREEN : 0xFF8D8252);
        return background;
    }

    private GradientDrawable swatch(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(selected ? 2 : 1),
                selected ? GREEN : 0xFFA48A54);
        return drawable;
    }

    private int sizeIndex(int size) {
        int[] sizes = {9, 11, 13, 15, 19};
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == size) return i;
        return 0;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
