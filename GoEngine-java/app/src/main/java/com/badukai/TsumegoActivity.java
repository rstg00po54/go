package com.badukai;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.TsumegoLessons;
import com.badukai.ui.GoBoardView;

import java.util.List;

/**
 * Offline life-and-death introduction. All positions and solutions are
 * bundled locally and have no dependency on KataGo, RKNN, or the game session.
 * Wrong answers do not mutate the board or record a completed lesson.
 */
public final class TsumegoActivity extends Activity {
    private static final String PREFS = "tsumego_progress";
    private final List<TsumegoLessons.Puzzle> puzzles = TsumegoLessons.all();
    private SharedPreferences prefs;
    private int index;
    private GoBoard board;
    private boolean solved;
    private int wrongAttempts;
    private GoBoardView boardView;
    private TextView title, progress, question, message;
    private Button previous, next, hint, reset;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        index = prefs.getInt("last_index", 0);
        if (index < 0 || index >= puzzles.size()) index = 0;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(12));
        page.setBackgroundColor(Color.rgb(242, 246, 233));

        LinearLayout top = row();
        Button back = button("← 返回", false);
        top.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        back.setOnClickListener(v -> finish());
        title = new TextView(this);
        title.setText("死活入门 · 提子练习");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(38, 96, 67));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        page.addView(top);

        progress = text(14, Color.rgb(74, 102, 83));
        progress.setGravity(Gravity.CENTER);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(30)));

        question = text(16, Color.rgb(39, 81, 59));
        question.setGravity(Gravity.CENTER);
        question.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(question, new LinearLayout.LayoutParams(-1, dp(57)));

        boardView = new GoBoardView(this);
        boardView.setContentDescription("9路死活题棋盘，点击交叉点答题");
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        boardParams.gravity = Gravity.CENTER;
        page.addView(boardView, boardParams);

        message = text(15, Color.rgb(51, 99, 68));
        message.setGravity(Gravity.CENTER);
        message.setMinLines(2);
        page.addView(message, new LinearLayout.LayoutParams(-1, dp(74)));

        LinearLayout actions = row();
        hint = button("提示", false);
        reset = button("重做", false);
        actions.addView(hint, new LinearLayout.LayoutParams(0, dp(52), 1f));
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(52), 1f));
        page.addView(actions);

        LinearLayout navigation = row();
        previous = button("上一题", false);
        next = button("下一题 →", true);
        navigation.addView(previous, new LinearLayout.LayoutParams(0, dp(54), 1f));
        navigation.addView(next, new LinearLayout.LayoutParams(0, dp(54), 1f));
        page.addView(navigation);

        hint.setOnClickListener(v -> showHint());
        reset.setOnClickListener(v -> resetPuzzle());
        previous.setOnClickListener(v -> openPuzzle(index - 1));
        next.setOnClickListener(v -> openPuzzle(index + 1));
        setContentView(page);
        openPuzzle(index);
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(float size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextAllCaps(false);
        button.setTextSize(15f);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(39, 87, 60));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? Color.rgb(56, 115, 77) : Color.rgb(220, 235, 216));
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), Color.rgb(130, 159, 133));
        button.setBackground(bg);
        return button;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openPuzzle(int nextIndex) {
        if (nextIndex < 0 || nextIndex >= puzzles.size()) return;
        index = nextIndex;
        prefs.edit().putInt("last_index", index).apply();
        resetPuzzle();
    }

    private int solvedMask() {
        return prefs.getInt("completed_mask", 0);
    }

    private int completedCount() {
        int validMask = (1 << puzzles.size()) - 1;
        return Integer.bitCount(solvedMask() & validMask);
    }

    private void resetPuzzle() {
        TsumegoLessons.Puzzle p = puzzles.get(index);
        board = p.newBoard();
        solved = false;
        wrongAttempts = 0;
        boardView.setOwnership(null);
        boardView.setRecommendations(null, null);
        boardView.setBoard(board);
        boardView.setLastMove(null);
        boardView.setInputEnabled(true);
        progress.setText("第 " + (index + 1) + " / " + puzzles.size()
                + " 题  ·  已完成 " + completedCount() + " / " + puzzles.size());
        question.setText(p.title + "\n" + p.instruction);
        message.setText("点击棋盘落子。答错可以继续尝试。");
        message.setTextColor(Color.rgb(51, 99, 68));
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < puzzles.size());
        next.setText(index == puzzles.size() - 1 ? "已到最后一题" : "下一题 →");
    }

    private void showHint() {
        if (solved) return;
        TsumegoLessons.Puzzle p = puzzles.get(index);
        boardView.setRecommendations(new Point[]{p.answer}, new float[]{1f});
        message.setText("提示：" + p.hint);
    }

    private void onBoardTap(int x, int y) {
        if (solved) return;
        TsumegoLessons.Puzzle p = puzzles.get(index);
        Point answer = new Point(x, y);
        if (board.get(answer) != Intersection.EMPTY || !board.isLegalMove(answer, p.toPlay)) {
            Toast.makeText(this, "这里不能落子", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p.answer.equals(answer)) {
            wrongAttempts++;
            message.setTextColor(Color.rgb(158, 83, 60));
            message.setText("还不对，观察整块棋剩下的气。" +
                    (wrongAttempts >= 2 ? "\n需要帮助可以点「提示」。" : ""));
            return; // Do not change the board for a wrong attempt.
        }
        int removed = board.playMove(new Move.Stone(answer, p.toPlay)).size();
        if (removed <= 0 || board.get(p.target) != Intersection.EMPTY) {
            // Data invariant: this is unreachable for the verified built-in puzzles.
            resetPuzzle();
            Toast.makeText(this, "题目数据异常，已重置", Toast.LENGTH_SHORT).show();
            return;
        }
        solved = true;
        prefs.edit().putInt("completed_mask", solvedMask() | (1 << index)).apply();
        boardView.setRecommendations(null, null);
        boardView.setLastMove(answer);
        boardView.setBoard(board);
        boardView.setInputEnabled(false);
        message.setTextColor(Color.rgb(38, 105, 56));
        message.setText("✓ 答对了！提掉 " + removed + " 子。\n" + p.explanation);
        progress.setText("第 " + (index + 1) + " / " + puzzles.size()
                + " 题  ·  已完成 " + completedCount() + " / " + puzzles.size());
        if (completedCount() == puzzles.size())
            Toast.makeText(this, "恭喜，8 道基础题已全部完成！", Toast.LENGTH_LONG).show();
    }
}
