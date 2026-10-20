package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
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
    private Button previous, next, hint, reset, explain, results;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        index = getIntent().getIntExtra("puzzle_index", prefs.getInt("last_index", 0));
        if (index < 0 || index >= puzzles.size()) index = 0;
        setContentView(R.layout.activity_tsumego);
        com.badukai.ui.BoardViewportFitter.attach(
                findViewById(R.id.tsumegoContentScroll),
                findViewById(R.id.tsumegoBoardFrame));

        title = findViewById(R.id.tsumegoQuestion);
        progress = findViewById(R.id.tsumegoProgress);
        question = title;
        message = findViewById(R.id.tsumegoMessage);
        boardView = findViewById(R.id.tsumegoBoard);
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        hint = findViewById(R.id.tsumegoHint);
        reset = findViewById(R.id.tsumegoReset);
        explain = findViewById(R.id.tsumegoExplain);
        results = findViewById(R.id.tsumegoResults);
        previous = findViewById(R.id.tsumegoPrev);
        next = findViewById(R.id.tsumegoNext);
        com.badukai.ui.JadeBottomNavView nav = findViewById(R.id.tsumegoSharedNav);
        nav.setSelectedTab(com.badukai.ui.JadeBottomNavView.TRAINING);
        nav.setOnTabClickListener(tab ->
                com.badukai.ui.JadeNavigation.open(this, tab));

        hint.setOnClickListener(v -> showHint());
        reset.setOnClickListener(v -> resetPuzzle());
        previous.setOnClickListener(v -> openPuzzle(index - 1));
        next.setOnClickListener(v -> openPuzzle(index + 1));
        explain.setOnClickListener(v -> {
            if (!solved) return;
            startActivityForResult(new Intent(this, TsumegoExplanationActivity.class)
                    .putExtra("puzzle_index", index)
                    .putExtra("wrong_attempts", wrongAttempts), 201);
        });
        results.setOnClickListener(v -> showResults());
        findViewById(R.id.tsumegoResultsTop).setOnClickListener(v -> showResults());
        findViewById(R.id.tsumegoBack).setOnClickListener(v -> finish());

        openPuzzle(index);
    }

    private void showResults() {
        startActivityForResult(new Intent(this, TsumegoResultsActivity.class), 202);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == 201 || request == 202) && result == RESULT_OK && data != null) {
            int target = data.getIntExtra("puzzle_index", -1);
            if (target >= 0 && target < puzzles.size()) openPuzzle(target);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Allow the shared Training choice menu to jump without recreating this screen. */
    public void openPuzzleFromNavigation(int puzzleIndex) {
        openPuzzle(puzzleIndex);
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
        boardView.setTrainingTarget(p.target);
        boardView.setLastMove(null);
        boardView.setInputEnabled(true);
        explain.setEnabled(false);
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
        explain.setEnabled(true);
        message.setTextColor(Color.rgb(38, 105, 56));
        message.setText("✓ 答对了！提掉 " + removed + " 子。\n" + p.explanation);
        progress.setText("第 " + (index + 1) + " / " + puzzles.size()
                + " 题  ·  已完成 " + completedCount() + " / " + puzzles.size());
        if (completedCount() == puzzles.size())
            Toast.makeText(this, "恭喜，8 道基础题已全部完成！", Toast.LENGTH_LONG).show();
    }
}
