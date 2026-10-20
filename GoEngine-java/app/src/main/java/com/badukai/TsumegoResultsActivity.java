package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.badukai.game.TsumegoLessons;

import java.util.List;

/** Real eight-puzzle completion report using the existing saved bitmask. */
public final class TsumegoResultsActivity extends Activity {
    private final List<TsumegoLessons.Puzzle> puzzles = TsumegoLessons.all();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tsumego_results);
        render();
        findViewById(R.id.resultsBack).setOnClickListener(v -> finish());
        findViewById(R.id.resultsReturn).setOnClickListener(v -> finish());
        findViewById(R.id.resultsContinue).setOnClickListener(v -> {
            int mask = completionMask();
            int firstPending = 0;
            while (firstPending < puzzles.size() && (mask & (1 << firstPending)) != 0)
                firstPending++;
            choose(firstPending < puzzles.size() ? firstPending : 0);
        });
    }

    private int completionMask() {
        SharedPreferences prefs = getSharedPreferences("tsumego_progress", MODE_PRIVATE);
        return prefs.getInt("completed_mask", 0);
    }

    private int dp(float x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private void choose(int index) {
        if (index < 0 || index >= puzzles.size()) return;
        setResult(RESULT_OK, new Intent().putExtra("puzzle_index", index));
        finish();
    }

    private void render() {
        int mask = completionMask();
        int completed = 0;
        for (int i = 0; i < puzzles.size(); i++)
            if ((mask & (1 << i)) != 0) completed++;
        ((TextView) findViewById(R.id.resultsScore)).setText(
                completed + " / " + puzzles.size());
        ((ProgressBar) findViewById(R.id.resultsProgress))
                .setProgress(Math.round(completed * 100f / puzzles.size()));
        ((TextView) findViewById(R.id.resultsSummary)).setText(
                completed == puzzles.size() ? "全部完成！可以返回练习巩固。"
                : "已完成 " + completed + " 题，还有 " + (puzzles.size() - completed) + " 题待练习");

        LinearLayout list = findViewById(R.id.resultsPuzzleList);
        list.removeAllViews();
        for (int i = 0; i < puzzles.size(); i++) {
            final int index = i;
            final boolean done = (mask & (1 << i)) != 0;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(10), dp(2), dp(10), dp(2));
            row.setBackgroundResource(R.drawable.jade_dark_card);

            TextView mark = new TextView(this);
            mark.setGravity(Gravity.CENTER);
            mark.setText(done ? "✓" : Integer.toString(i + 1));
            mark.setTextColor(done ? 0xFF4DE1AC : 0xFFF2D285);
            mark.setTextSize(18);
            row.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(36)));

            TextView label = new TextView(this);
            label.setText(puzzles.get(i).title);
            label.setTextColor(Color.rgb(232, 241, 222));
            label.setTextSize(13);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1f));

            TextView state = new TextView(this);
            state.setText(done ? "已完成" : "待挑战");
            state.setTextColor(done ? 0xFF79E3B7 : 0xFFEAC783);
            state.setGravity(Gravity.CENTER);
            state.setTextSize(11);
            row.addView(state, new LinearLayout.LayoutParams(dp(55), dp(36)));

            row.setOnClickListener(v -> choose(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            params.bottomMargin = dp(4);
            list.addView(row, params);
        }
    }
}
