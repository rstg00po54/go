package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
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
    private int stage;
    private int totalCaptured;
    private int hintLevel;
    private int roundId;
    private boolean responding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

    private void updateProgress() {
        progress.setText("第 " + (index + 1) + " / " + puzzles.size()
                + " 题 · 已完成 " + completedCount() + " / " + puzzles.size());
    }

    private void resetPuzzle() {
        // Invalidate a scheduled defender move if the learner resets/navigates.
        roundId++;
        TsumegoLessons.Puzzle p = puzzles.get(index);
        board = p.newBoard();
        solved = false;
        responding = false;
        stage = 0;
        wrongAttempts = 0;
        hintLevel = 0;
        totalCaptured = 0;
        boardView.setOwnership(null);
        boardView.setRecommendations(null, null);
        boardView.setBoard(board);
        boardView.setTrainingTarget(p.target);
        boardView.setLastMove(null);
        boardView.setInputEnabled(true);
        hint.setEnabled(true);
        hint.setText("提示");
        explain.setEnabled(false);
        updateProgress();
        question.setText(p.title + "\n" + p.instruction);
        message.setText(p.steps() > 1
                ? "需要连续走对 " + p.steps() + " 手，轮到对方时会自动应手。"
                : "点击棋盘落子。答错可以继续尝试。");
        message.setTextColor(0xFFE4EADC);
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < puzzles.size());
        next.setText(index == puzzles.size() - 1 ? "已到最后一题" : "下一题 →");
    }

    private void showHint() {
        if (solved || responding) return;
        TsumegoLessons.Puzzle p = puzzles.get(index);
        hintLevel++;
        if (hintLevel == 1) {
            message.setText("提示：" + p.hint);
            hint.setText("看落点");
        } else {
            Point answer = p.answerAt(stage);
            boardView.setRecommendations(new Point[]{answer}, new float[]{1f});
            message.setText("落点提示：" + answer.toGtp(TsumegoLessons.BOARD_SIZE)
                    + " · 想一想为什么。");
            hint.setText("已提示");
            hint.setEnabled(false);
        }
        message.setTextColor(0xFFF3D58E);
    }

    private void onBoardTap(int x, int y) {
        if (solved || responding) return;
        TsumegoLessons.Puzzle p = puzzles.get(index);
        Point played = new Point(x, y);
        if (board.get(played) != Intersection.EMPTY || !board.isLegalMove(played, p.toPlay)) {
            Toast.makeText(this, "这里不能落子", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p.answerAt(stage).equals(played)) {
            wrongAttempts++;
            message.setTextColor(0xFFFFC190);
            message.setText("这手不对，看看目标棋块剩下的气。"
                    + (wrongAttempts >= 2 ? " 可以点「提示」。" : ""));
            return; // Wrong attempts never alter the board.
        }

        totalCaptured += board.playMove(new Move.Stone(played, p.toPlay)).size();
        boardView.setRecommendations(null, null);
        boardView.setLastMove(played);
        boardView.setBoard(board);

        if (stage + 1 == p.steps()) {
            if (board.get(p.target) != Intersection.EMPTY || totalCaptured == 0) {
                resetPuzzle();
                Toast.makeText(this, "题目数据异常，已重置", Toast.LENGTH_SHORT).show();
                return;
            }
            solved = true;
            prefs.edit().putInt("completed_mask", solvedMask() | (1 << index)).apply();
            boardView.setTrainingTarget(null);
            boardView.setInputEnabled(false);
            explain.setEnabled(true);
            hint.setEnabled(false);
            message.setTextColor(0xFF9AE6BC);
            message.setText("✓ 答对了！共 " + p.steps() + " 手提掉 "
                    + totalCaptured + " 子。点击「答案解析」查看完整变化。");
            updateProgress();
            if (completedCount() == puzzles.size())
                Toast.makeText(this, "全部 " + puzzles.size() + " 道基础题已完成！",
                        Toast.LENGTH_LONG).show();
            return;
        }

        responding = true;
        boardView.setInputEnabled(false);
        final int expectedRound = roundId;
        final int replyForStage = stage;
        message.setTextColor(0xFFE4EADC);
        message.setText("第 " + (stage + 1) + " 手正确，对方正在应手…");
        mainHandler.postDelayed(() -> {
            if (expectedRound != roundId || isFinishing() || solved) return;
            Point reply = p.replyAfter(replyForStage);
            StoneColor defender = p.toPlay.opposite();
            if (!board.isLegalMove(reply, defender)) {
                resetPuzzle();
                Toast.makeText(this, "应手数据异常，已重置", Toast.LENGTH_SHORT).show();
                return;
            }
            board.playMove(new Move.Stone(reply, defender));
            stage++;
            responding = false;
            hintLevel = 0;
            hint.setText("提示");
            hint.setEnabled(true);
            boardView.setLastMove(reply);
            boardView.setBoard(board);
            boardView.setInputEnabled(true);
            message.setText("对方下在 " + reply.toGtp(TsumegoLessons.BOARD_SIZE)
                    + "，现在轮到你走第 " + (stage + 1) + " 手。");
        }, 450L);
    }

    @Override protected void onDestroy() {
        roundId++;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
