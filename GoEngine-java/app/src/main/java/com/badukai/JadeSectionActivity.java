package com.badukai;

import android.app.Activity;
import android.content.Intent;
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
        if (tab == JadeBottomNavView.SETTINGS) {
            JadeNavigation.open(this, JadeBottomNavView.SETTINGS);
            return;
        }
        if (tab < JadeBottomNavView.RECORDS || tab > JadeBottomNavView.TRAINING) {
            return;
        }
        choosingPuzzle = false;
        section = tab;
        footer.setSelectedTab(tab);
        cards.removeAllViews();
        if (tab == JadeBottomNavView.RECORDS) renderRecords();
        else renderTraining();
    }

    private void renderRecords() {
        heading.setText("棋 谱 中 心");
        subtitle.setText("复盘、收藏与导入 · 留住每一局棋");
        addHeading("选择棋谱功能");
        addCard("复盘最近一局", "查看刚刚结束或保存的对局",
                R.drawable.ic_records, () -> openReplay(null), false);
        addCard("历史棋谱", "从本地保存的对局中选择一盘",
                R.drawable.ic_book_ivory, this::openHistory, false);
        addCard("导入 SGF", "从设备文件中读取外部棋谱",
                R.drawable.ic_import_sgf, () -> openReplay("import"), false);
        addNote("棋谱分析与回放沿用现有功能；进入前不会中断当前对弈。");
    }

    private void openHistory() {
        // Choose a saved SGF first; never reveal the replay board underneath.
        Intent library = new Intent(this, SgfLibraryActivity.class);
        library.putExtra(SgfLibraryActivity.EXTRA_OPEN_REPLAY_ON_SELECT, true);
        startActivity(library);
        overridePendingTransition(0, 0);
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

    private void addHeading(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(0xFFF3D48B);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), dp(12), dp(4), dp(8));
        cards.addView(title);
    }

    private void addNote(String message) {
        TextView note = new TextView(this);
        note.setText(message);
        note.setTextColor(0xFFD7E3D5);
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(9), dp(6), dp(9), dp(9));
        cards.addView(note);
    }

    private void addCard(String title, String description, int icon,
                         Runnable action, boolean selected) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(6), dp(8), dp(6));
        line.setMinimumHeight(dp(54));
        line.setBackgroundResource(R.drawable.jade_dark_card);
        line.setClipChildren(false);

        ImageView picture = new ImageView(this);
        picture.setImageResource(icon);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        imageParams.rightMargin = dp(9);
        line.addView(picture, imageParams);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(0xFFF5E9C8);
        words.addView(name);
        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextSize(11);
        desc.setTextColor(0xFFAFCEB7);
        desc.setPadding(0, dp(2), 0, 0);
        words.addView(desc);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView marker = new TextView(this);
        marker.setText(selected ? "✓" : "›");
        marker.setTextSize(selected ? 20 : 24);
        marker.setGravity(Gravity.CENTER);
        marker.setTextColor(0xFFFFD783);
        line.addView(marker, new LinearLayout.LayoutParams(dp(22), dp(32)));
        line.setClickable(true);
        line.setFocusable(true);
        line.setContentDescription(title);
        line.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(5);
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
