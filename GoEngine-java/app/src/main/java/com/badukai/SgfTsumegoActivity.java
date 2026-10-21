package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.game.TsumegoSgf;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeNavigation;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Published Go Game Guru SGF problems with original good/bad variations.
 * Dedicated to non-commercial offline practice; never touches the game engine.
 */
public final class SgfTsumegoActivity extends Activity {
    private static final int[] IDS = {101, 103, 104, 105, 110, 115};
    private static final String PREFS = "tsumego_sgf_progress";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int index, round, mistakes, hints, studentMoves;
    private boolean solved, responding, previewing;
    private TsumegoSgf.Puzzle puzzle;
    private TsumegoSgf.Node node;
    private GoBoard position;
    private GoBoardView board;
    private TextView progress, title, message;
    private Button previous, next, hint, reset, solution, credits;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tsumego);
        com.badukai.ui.BoardViewportFitter.attach(
                findViewById(R.id.tsumegoContentScroll),
                findViewById(R.id.tsumegoBoardFrame));
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        board = findViewById(R.id.tsumegoBoard);
        board.setOnIntersectionClickListener(this::play);
        progress = findViewById(R.id.tsumegoProgress);
        title = findViewById(R.id.tsumegoQuestion);
        message = findViewById(R.id.tsumegoMessage);
        previous = findViewById(R.id.tsumegoPrev);
        next = findViewById(R.id.tsumegoNext);
        hint = findViewById(R.id.tsumegoHint);
        reset = findViewById(R.id.tsumegoReset);
        solution = findViewById(R.id.tsumegoExplain);
        credits = findViewById(R.id.tsumegoResults);
        solution.setText("看正解");
        solution.setEnabled(true);
        credits.setText("题目来源");
        findViewById(R.id.tsumegoResultsTop).setOnClickListener(v -> showCredits());
        findViewById(R.id.tsumegoBack).setOnClickListener(v -> finish());
        hint.setOnClickListener(v -> showHint());
        reset.setOnClickListener(v -> open(index));
        solution.setOnClickListener(v -> showSolution());
        credits.setOnClickListener(v -> showCredits());
        previous.setOnClickListener(v -> open(index - 1));
        next.setOnClickListener(v -> open(index + 1));
        JadeBottomNavView nav = findViewById(R.id.tsumegoSharedNav);
        nav.setSelectedTab(JadeBottomNavView.TRAINING);
        nav.setOnTabClickListener(tab -> JadeNavigation.open(this, tab));
        int requested = getIntent().getIntExtra("sgf_puzzle_index",
                prefs.getInt("last_index", 0));
        open(requested >= 0 && requested < IDS.length ? requested : 0);
    }

    private int completed() {
        return Integer.bitCount(prefs.getInt("completed_mask", 0)
                & ((1 << IDS.length) - 1));
    }

    private static String vertex(Point at) {
        if (at == null) return "停一手";
        char column = (char) ('A' + at.x + (at.x >= 8 ? 1 : 0));
        return column + Integer.toString(19 - at.y);
    }

    private String readAsset(int id) throws Exception {
        try (InputStream in = getAssets().open("tsumego/ggg-easy-" + id + ".sgf");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void open(int requested) {
        if (requested < 0 || requested >= IDS.length) return;
        round++;
        handler.removeCallbacksAndMessages(null);
        index = requested;
        prefs.edit().putInt("last_index", index).apply();
        try {
            puzzle = TsumegoSgf.parse("ggg-easy-" + IDS[index],
                    readAsset(IDS[index]));
            node = puzzle.root;
            position = puzzle.newBoard();
            solved = responding = previewing = false;
            mistakes = hints = studentMoves = 0;
            board.setTrainingViewport(10, 10, 9);
            board.setVariationPreview(null, null, null, null);
            board.setBoard(position);
            board.setLastMove(null);
            board.setTrainingTarget(null);
            board.setOwnership(null);
            board.setRecommendations(null, null);
            board.setInputEnabled(true);
            progress.setText("真实死活题 " + (index + 1) + "/" + IDS.length
                    + " · 已完成 " + completed() + "/" + IDS.length);
            title.setText("Go Game Guru · 第 " + IDS[index] + " 题\n"
                    + (puzzle.toPlay == StoneColor.BLACK ? "黑先" : "白先")
                    + " · 19路原题，放大右下角");
            message.setText("从棋形中寻找正解。对方会按原 SGF 变化应手。");
            message.setTextColor(0xFFE4EADC);
            hint.setEnabled(true);
            hint.setText("提示");
            solution.setEnabled(true);
            solution.setText("看正解");
            previous.setEnabled(index > 0);
            next.setEnabled(index + 1 < IDS.length);
        } catch (Exception ex) {
            android.util.Log.e("SgfTsumego", "Invalid SGF problem " + IDS[index], ex);
            puzzle = null;
            board.setInputEnabled(false);
            message.setText("原题读取或正解验证失败：" + ex.getMessage());
            message.setTextColor(0xFFFFB77B);
            Toast.makeText(this, "SGF 题目无法加载", Toast.LENGTH_LONG).show();
        }
    }

    private void showHint() {
        if (puzzle == null || solved || responding || previewing) return;
        TsumegoSgf.Node best = puzzle.nextCorrect(node);
        if (best == null || best.color != puzzle.toPlay || best.point == null) {
            message.setText("当前变化暂无线索，可点「重做」。");
            return;
        }
        hints++;
        message.setTextColor(0xFFF7D58E);
        if (hints == 1) {
            message.setText("先数气、再看断点与眼位，想想对方最顽强的应手。");
            hint.setText("提示落点");
        } else {
            board.setRecommendations(new Point[]{best.point}, new float[]{1f});
            message.setText("SGF 正解候选：" + vertex(best.point) + "；再想想后续变化。");
            hint.setText("已提示");
            hint.setEnabled(false);
        }
    }

    private void play(int x, int y) {
        if (puzzle == null || solved || responding || previewing) return;
        Point at = new Point(x, y);
        StoneColor player = puzzle.toPlay;
        if (position.get(at) != Intersection.EMPTY || !position.isLegalMove(at, player)) {
            Toast.makeText(this, "这里不能落子", Toast.LENGTH_SHORT).show();
            return;
        }
        TsumegoSgf.Node selected = puzzle.correctMove(node, at, player);
        if (selected == null) {
            mistakes++;
            message.setTextColor(0xFFFFC18A);
            message.setText("这手不在原 SGF 正解变化里，请重新计算。"
                    + (mistakes >= 2 ? " 可点「提示」。" : ""));
            return;
        }
        position.playMove(new Move.Stone(at, player));
        node = selected;
        studentMoves++;
        board.setRecommendations(null, null);
        board.setLastMove(at);
        board.setBoard(position);
        if (node.correct) {
            finishPuzzle();
            return;
        }
        responding = true;
        board.setInputEnabled(false);
        message.setTextColor(0xFFDCE9D9);
        message.setText("第 " + studentMoves + " 手正确，正在展示原题应手…");
        final int expectedRound = round;
        handler.postDelayed(() -> reply(expectedRound), 470L);
    }

    private void reply(int expectedRound) {
        if (puzzle == null || expectedRound != round || isFinishing()) return;
        TsumegoSgf.Node response = puzzle.defenderReply(node, position);
        if (response == null) {
            responding = false;
            board.setInputEnabled(false);
            message.setTextColor(0xFFFFC18A);
            message.setText("SGF 正解分支缺少合法应手，请重做或查看原题。");
            return;
        }
        position.playMove(new Move.Stone(response.point, response.color));
        node = response;
        board.setLastMove(response.point);
        board.setBoard(position);
        responding = false;
        if (node.correct) {
            finishPuzzle();
            return;
        }
        TsumegoSgf.Node next = puzzle.nextCorrect(node);
        if (next == null || next.color != puzzle.toPlay) {
            board.setInputEnabled(false);
            message.setText("SGF 后续没有可继续的正解变化，请重做。");
            return;
        }
        hints = 0;
        hint.setEnabled(true);
        hint.setText("提示");
        board.setInputEnabled(true);
        message.setText("对方在 " + vertex(response.point)
                + " 应手，继续计算下一步。");
    }

    private void finishPuzzle() {
        solved = true;
        responding = false;
        prefs.edit().putInt("completed_mask",
                prefs.getInt("completed_mask", 0) | (1 << index)).apply();
        board.setInputEnabled(false);
        hint.setEnabled(false);
        progress.setText("真实死活题 " + (index + 1) + "/" + IDS.length
                + " · 已完成 " + completed() + "/" + IDS.length);
        message.setTextColor(0xFF9AE6BC);
        message.setText("✓ 已走通原 SGF 正解！自己下了 " + studentMoves
                + " 手；点击「看正解」可回看完整变化。");
    }

    private void showSolution() {
        if (puzzle == null) return;
        List<TsumegoSgf.Node> solutionMoves = puzzle.principalSolution();
        GoBoard outcome = puzzle.newBoard();
        List<Point> moves = new ArrayList<>();
        List<StoneColor> colors = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        StringBuilder sequence = new StringBuilder();
        try {
            for (TsumegoSgf.Node item : solutionMoves) {
                if (item.point == null || item.color == null
                        || !outcome.isLegalMove(item.point, item.color))
                    throw new IllegalArgumentException("原始变化存在非法着手");
                outcome.playMove(new Move.Stone(item.point, item.color));
                moves.add(item.point);
                colors.add(item.color);
                numbers.add(moves.size());
                if (sequence.length() > 0) sequence.append(" → ");
                sequence.append(item.color == StoneColor.BLACK ? "黑" : "白")
                        .append(vertex(item.point));
            }
            int[] sequenceNumbers = new int[numbers.size()];
            for (int i = 0; i < numbers.size(); i++) sequenceNumbers[i] = numbers.get(i);
            board.setVariationPreview(outcome, moves.toArray(new Point[0]),
                    colors.toArray(new StoneColor[0]), sequenceNumbers);
            board.setInputEnabled(false);
            previewing = true;
            responding = false;
            round++;
            handler.removeCallbacksAndMessages(null);
            hint.setEnabled(false);
            message.setTextColor(0xFFF2DB9B);
            message.setText("原 SGF 主正解：" + sequence + "。重做可重新挑战，查看答案不计完成。");
        } catch (Exception e) {
            message.setText("原题正解回放失败：" + e.getMessage());
        }
    }

    private void showCredits() {
        new AlertDialog.Builder(this)
                .setTitle("死活题来源与许可")
                .setMessage("原题与正解变化：Go Game Guru · Weekly Go Problems"
                        + "\n作者/维护：An Younggil、David Ormerod"
                        + "\n来源：https://github.com/gogameguru/go-problems"
                        + "\n许可：CC BY-NC-SA 4.0（署名、非商业、相同方式共享）"
                        + "\n\n仅供个人非商业练习。原始 SGF 保留在应用资源中。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override protected void onDestroy() {
        round++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
