package com.badukai.training;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.game.GoBoard;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.ui.GoBoardView;

import java.util.List;
import java.util.Locale;

/**
 * Standalone offline beginner exercise page. Does not start a KataGo engine and
 * never changes MainActivity's active game or endgame scoring configuration.
 */
public final class TsumegoTrainingActivity extends AppCompatActivity {
    private static final int TEXT = Color.rgb(39, 72, 50);
    private static final int BACKGROUND = Color.rgb(237, 247, 237);

    private final List<TsumegoProblems.Problem> problems = TsumegoProblems.all();
    private GoBoardView boardView;
    private TextView titleView, instructionView, resultView, progressView;
    private SharedPreferences preferences;
    private GoBoard board;
    private int index;
    private boolean solved, hintUsed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("tsumego_training", MODE_PRIVATE);
        index = Math.max(0, Math.min(problems.size() - 1, preferences.getInt("last_index", 0)));
        buildUi();
        showProblem();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(12), dp(12), dp(18));
        scroll.addView(page);
        setContentView(scroll);

        TextView header = label("死活棋训练 · 入门", 23, true);
        header.setGravity(Gravity.CENTER);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

        progressView = label("", 14, false);
        progressView.setGravity(Gravity.CENTER);
        page.addView(progressView);

        titleView = label("", 18, true);
        titleView.setPadding(0, dp(12), 0, dp(4));
        page.addView(titleView);

        instructionView = label("", 16, false);
        instructionView.setPadding(0, 0, 0, dp(8));
        page.addView(instructionView);

        boardView = new GoBoardView(this);
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        int boardSize = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(420));
        LinearLayout.LayoutParams boardLayout = new LinearLayout.LayoutParams(-1, Math.max(dp(260), boardSize));
        page.addView(boardView, boardLayout);

        resultView = label("", 16, false);
        resultView.setMinHeight(dp(70));
        resultView.setPadding(0, dp(10), 0, dp(8));
        page.addView(resultView);

        LinearLayout buttons1 = buttonRow(page);
        addButton(buttons1, "上一题", v -> openProblem(index - 1));
        addButton(buttons1, "提示", v -> showHint());
        addButton(buttons1, "下一题", v -> openProblem(index + 1));

        LinearLayout buttons2 = buttonRow(page);
        addButton(buttons2, "重做本题", v -> showProblem());
        addButton(buttons2, "返回对弈", v -> finish());

        TextView note = label("本版是离线一手入门题，按题库标准解判定；未收录的其他变化不代表一定错误。", 12, false);
        note.setPadding(0, dp(14), 0, 0);
        page.addView(note);
    }

    private void openProblem(int target) {
        index = (target + problems.size()) % problems.size();
        preferences.edit().putInt("last_index", index).apply();
        showProblem();
    }

    private void showProblem() {
        TsumegoProblems.Problem problem = problems.get(index);
        board = problem.createBoard();
        solved = false;
        hintUsed = false;
        boardView.setBoard(board);
        boardView.setLastMove(null);
        boardView.setRecommendations(null, null);
        boardView.setInputEnabled(true);
        titleView.setText(problem.title);
        instructionView.setText(problem.instruction);
        resultView.setText("请在棋盘上点击你的落点。");
        updateProgress();
    }

    private void onBoardTap(int x, int y) {
        if (solved) return;
        TsumegoProblems.Problem problem = problems.get(index);
        Point move = new Point(x, y);
        if (!board.isLegalMove(move, problem.turn)) {
            resultView.setText("这里不能合法落子，请换一个位置。");
            return;
        }
        if (!TsumegoProblems.matchesAnswer(problem, move)) {
            resultView.setText("这不是本题收录的标准解，请再想想。也可能存在未收录的其他变化。");
            return;
        }
        GoBoard trial = board.copyForPreview();
        int captured = trial.playMove(new Move.Stone(move, problem.turn)).size();
        if (!TsumegoProblems.goalReached(problem, trial, captured)) {
            resultView.setText("题库校验失败，未记录成绩。");
            return;
        }
        board = trial;
        solved = true;
        boardView.setBoard(board);
        boardView.setLastMove(move);
        boardView.setRecommendations(null, null);
        boardView.setInputEnabled(false);
        if (!hintUsed) preferences.edit().putBoolean("solved_" + index, true).apply();
        resultView.setText((hintUsed ? "已完成（使用提示，不计成绩）" : "回答正确！") + "\n" + problem.explanation);
        updateProgress();
    }

    private void showHint() {
        TsumegoProblems.Problem problem = problems.get(index);
        if (solved) {
            resultView.setText(problem.explanation);
            return;
        }
        hintUsed = true;
        boardView.setRecommendations(new Point[]{problem.answer}, new float[]{1f});
        resultView.setText("提示：留意 " + problem.answer.toGtp(9) + "。提示后完成本题不计入独立完成数。");
    }

    private void updateProgress() {
        int passed = 0;
        for (int i = 0; i < problems.size(); i++) {
            if (preferences.getBoolean("solved_" + i, false)) passed++;
        }
        progressView.setText(String.format(Locale.CHINA, "第 %d / %d 题 · 独立完成 %d 题", index + 1, problems.size(), passed));
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(sp);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout buttonRow(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        page.addView(row, params);
        return row;
    }

    private void addButton(LinearLayout row, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextAllCaps(false);
        button.setTextColor(Color.rgb(30, 83, 49));
        button.setTextSize(15);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
