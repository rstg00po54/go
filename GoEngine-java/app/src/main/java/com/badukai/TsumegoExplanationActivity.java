package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.badukai.game.GoBoard;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.game.TsumegoLessons;
import com.badukai.ui.GoBoardView;

import java.util.List;

/** Verified offline answer view; independent of KataGo and live game state. */
public final class TsumegoExplanationActivity extends Activity {
    private final List<TsumegoLessons.Puzzle> puzzles = TsumegoLessons.all();
    private int index;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tsumego_explanation);
        index = getIntent().getIntExtra("puzzle_index", 0);
        if (index < 0 || index >= puzzles.size()) index = 0;
        TsumegoLessons.Puzzle puzzle = puzzles.get(index);
        GoBoard position = puzzle.newBoard();
        StringBuilder sequence = new StringBuilder();
        int removed = 0;
        for (int turn = 0; turn < puzzle.steps(); turn++) {
            Point answer = puzzle.answerAt(turn);
            removed += position.playMove(new Move.Stone(answer, puzzle.toPlay)).size();
            if (sequence.length() > 0) sequence.append(" → ");
            sequence.append(puzzle.toPlay == StoneColor.BLACK ? "黑" : "白")
                    .append(coordinate(answer));
            if (turn + 1 < puzzle.steps()) {
                Point response = puzzle.replyAfter(turn);
                position.playMove(new Move.Stone(response, puzzle.toPlay.opposite()));
                sequence.append(" → ")
                        .append(puzzle.toPlay == StoneColor.BLACK ? "白" : "黑")
                        .append(coordinate(response));
            }
        }

        GoBoardView board = findViewById(R.id.explainBoard);
        board.setBoard(position);
        board.setInputEnabled(false);
        board.setTrainingTarget(null);
        board.setLastMove(puzzle.answerAt(puzzle.steps() - 1));

        ((TextView)findViewById(R.id.explainPuzzleTitle)).setText(puzzle.title);
        ((TextView)findViewById(R.id.explainSide)).setText(
                (puzzle.toPlay == StoneColor.BLACK ? "黑先" : "白先")
                + " · 你走 " + puzzle.steps() + " 手 · 共提 " + removed + " 子");
        ((TextView)findViewById(R.id.explainAnswer)).setText(
                "解题顺序：" + sequence);
        ((TextView)findViewById(R.id.explainBody)).setText(
                "题目：" + puzzle.instruction + "\n\n"
                + "提示：" + puzzle.hint + "\n\n解析：" + puzzle.explanation);
        int mistakes = getIntent().getIntExtra("wrong_attempts", 0);
        ((TextView)findViewById(R.id.explainAttempts)).setText(
                mistakes == 0 ? "本题已完成 · 一次答对" : "本题已完成 · 错误尝试 " + mistakes + " 次");

        findViewById(R.id.explainBack).setOnClickListener(v -> finish());
        findViewById(R.id.explainReturn).setOnClickListener(v -> finish());
        findViewById(R.id.explainNext).setEnabled(index + 1 < puzzles.size());
        findViewById(R.id.explainNext).setOnClickListener(v -> {
            if (index + 1 >= puzzles.size()) return;
            Intent selected = new Intent().putExtra("puzzle_index", index + 1);
            setResult(RESULT_OK, selected);
            finish();
        });
    }

    private static String coordinate(Point point) {
        char column = (char) ('A' + point.x + (point.x >= 8 ? 1 : 0));
        return column + Integer.toString(TsumegoLessons.BOARD_SIZE - point.y);
    }
}
