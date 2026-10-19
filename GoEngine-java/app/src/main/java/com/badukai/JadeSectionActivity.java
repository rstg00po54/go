package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.badukai.game.TsumegoLessons;
import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeNavigation;

import java.io.InputStream;
import java.util.List;

/**
 * Full-screen jade section pages for Records, Training and Settings.
 * Navigation stays within this page; no pop-up choice menu and no Home flash.
 * Replay and Tsumego run only after the user chooses a real action.
 */
public final class JadeSectionActivity extends Activity {
    public static final String EXTRA_SECTION = "jade_section";
    private int section = JadeBottomNavView.RECORDS;
    private boolean choosingPuzzle;
    private LinearLayout cards;
    private TextView heading, subtitle;
    private JadeBottomNavView footer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_jade_section);
        heading = findViewById(R.id.jadeSectionTitle);
        subtitle = findViewById(R.id.jadeSectionSubtitle);
        cards = findViewById(R.id.jadeSectionCards);
        footer = findViewById(R.id.jadeSectionNav);

        try (InputStream input = getAssets().open("home/background.png")) {
            ((ImageView) findViewById(R.id.jadeSectionBackdrop))
                    .setImageBitmap(BitmapFactory.decodeStream(input));
        } catch (Exception ignored) {
            // The layout already contains a dark jade background.
        }

        findViewById(R.id.jadeSectionBack).setOnClickListener(v -> back());
        footer.setOnTabClickListener(tab -> JadeNavigation.open(this, tab));
        showSection(getIntent().getIntExtra(EXTRA_SECTION, JadeBottomNavView.RECORDS));
    }

    public void showSection(int tab) {
        if (tab < JadeBottomNavView.RECORDS || tab > JadeBottomNavView.SETTINGS) {
            return;
        }
        choosingPuzzle = false;
        section = tab;
        footer.setSelectedTab(tab);
        cards.removeAllViews();
        if (tab == JadeBottomNavView.RECORDS) renderRecords();
        else if (tab == JadeBottomNavView.TRAINING) renderTraining();
        else renderSettings();
    }

    private void renderRecords() {
        heading.setText("棋 谱 中 心");
        subtitle.setText("复盘、收藏与导入 · 留住每一局棋");
        addHeading("选择棋谱功能");
        addCard("复盘最近一局", "查看刚刚结束或保存的对局",
                R.drawable.ic_records, () -> openReplay(null), false);
        addCard("历史棋谱", "从本地保存的对局中选择一盘",
                R.drawable.ic_book_ivory, () -> openReplay("history"), false);
        addCard("导入 SGF", "从设备文件中读取外部棋谱",
                R.drawable.ic_import_sgf, () -> openReplay("import"), false);
        addNote("棋谱分析与回放沿用现有功能；进入前不会中断当前对弈。");
    }

    private void openReplay(String action) {
        Intent intent = new Intent(this, ReplayActivity.class);
        if (action != null) intent.putExtra("replay_entry_action", action);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void renderTraining() {
        heading.setText("死 活 训 练");
        subtitle.setText("循序练习 · 提升棋形与计算能力");
        addHeading("选择训练内容");
        addCard("继续上次练习", "从上次停留的题目继续",
                R.drawable.ic_training_ivory, () -> openPuzzle(-1), false);
        addCard("从第一题开始", "重温基础死活与手筋",
                R.drawable.ic_board_size, () -> openPuzzle(0), false);
        addCard("选择练习题目", "浏览现有题库，指定一道题",
                R.drawable.ic_book_ivory, this::renderPuzzleChoices, false);
        addCard("查看练习成绩", "查看已完成题目和学习进度",
                R.drawable.ic_ai_level, () -> {
                    startActivity(new Intent(this, TsumegoResultsActivity.class));
                    overridePendingTransition(0, 0);
                }, false);
    }

    private void renderPuzzleChoices() {
        choosingPuzzle = true;
        heading.setText("选 择 题 目");
        subtitle.setText("选择一道题，开始本地死活练习");
        cards.removeAllViews();
        addHeading("全部死活题");
        List<TsumegoLessons.Puzzle> puzzles = TsumegoLessons.all();
        for (int i = 0; i < puzzles.size(); i++) {
            final int index = i;
            addCard("第 " + (i + 1) + " 题 · " + puzzles.get(i).title,
                    "点击进入练习", R.drawable.ic_board_size,
                    () -> openPuzzle(index), false);
        }
    }

    private void openPuzzle(int index) {
        Intent intent = new Intent(this, TsumegoActivity.class);
        if (index >= 0) intent.putExtra("puzzle_index", index);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void renderSettings() {
        heading.setText("设 置");
        subtitle.setText("棋局偏好 · 本地模型 · 应用信息");
        SharedPreferences prefs = getSharedPreferences("katago_settings", MODE_PRIVATE);
        int size = prefs.getInt("board_size", 9);
        String backend = prefs.getString("backend", "CPU");

        addHeading("对局设置");
        addCard("新局参数", "调整棋力等级、后端、棋色和规则",
                R.drawable.ic_settings_ivory, () -> JadeNavigation.openNewGameSettings(this), false);
        addNote("上次使用的计算后端：" + backend
                + "。实际可用的 CPU / GPU / NPU 在新局设置中确认。");

        addHeading("默认棋盘大小");
        addNote("以下设置会在下次重新启动应用时作为默认棋盘，不修改正在进行的对局。");
        addCard("9 × 9", "入门和快速对弈",
                R.drawable.ic_board_size, () -> saveBoardSize(9), size == 9);
        addCard("13 × 13", "中盘与全局练习",
                R.drawable.ic_board_size, () -> saveBoardSize(13), size == 13);
        addCard("19 × 19", "标准围棋棋盘",
                R.drawable.ic_board_size, () -> saveBoardSize(19), size == 19);

        addHeading("更多");
        addCard("棋谱管理", "查看历史棋谱及导入 SGF",
                R.drawable.ic_book_ivory, () -> showSection(JadeBottomNavView.RECORDS), false);
        addCard("死活训练", "选择题目或查看练习进度",
                R.drawable.ic_training_ivory, () -> showSection(JadeBottomNavView.TRAINING), false);
        addNote("本地围棋 · KataGo AI。对局及模型运行均在设备端完成。");
    }

    private void saveBoardSize(int boardSize) {
        getSharedPreferences("katago_settings", MODE_PRIVATE)
                .edit().putInt("board_size", boardSize).apply();
        renderSettings();
    }

    private void addHeading(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(0xFFF3D48B);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), dp(18), dp(4), dp(12));
        cards.addView(title);
    }

    private void addNote(String message) {
        TextView note = new TextView(this);
        note.setText(message);
        note.setTextColor(0xFFD7E3D5);
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(9), dp(10), dp(9), dp(14));
        cards.addView(note);
    }

    private void addCard(String title, String description, int icon,
                         Runnable action, boolean selected) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(14), dp(15), dp(12), dp(15));
        line.setBackgroundResource(R.drawable.jade_dark_card);
        line.setClipChildren(false);

        ImageView picture = new ImageView(this);
        picture.setImageResource(icon);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        imageParams.rightMargin = dp(13);
        line.addView(picture, imageParams);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(17);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(0xFFF5E9C8);
        words.addView(name);
        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextSize(12);
        desc.setTextColor(0xFFAFCEB7);
        desc.setPadding(0, dp(4), 0, 0);
        words.addView(desc);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView marker = new TextView(this);
        marker.setText(selected ? "✓" : "›");
        marker.setTextSize(selected ? 24 : 30);
        marker.setGravity(Gravity.CENTER);
        marker.setTextColor(0xFFFFD783);
        line.addView(marker, new LinearLayout.LayoutParams(dp(28), dp(40)));
        line.setClickable(true);
        line.setFocusable(true);
        line.setContentDescription(title);
        line.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        cards.addView(line, lp);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void back() {
        if (choosingPuzzle) showSection(JadeBottomNavView.TRAINING);
        else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override public void onBackPressed() {
        back();
    }
}
