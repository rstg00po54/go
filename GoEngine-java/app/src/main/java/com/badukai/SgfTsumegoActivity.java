package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Rect;
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
import com.badukai.engine.KataGoEngine;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeNavigation;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * Published Go Game Guru SGF problems with original good/bad variations.
 * Dedicated to non-commercial offline practice; never touches the game engine.
 */
public final class SgfTsumegoActivity extends Activity {
    private static final int[] IDS = {101, 103, 104, 105, 110, 115};
    private static final String PREFS = "tsumego_sgf_progress";
    private static final int LOCAL_LEFT = 10, LOCAL_TOP = 10;
    private final ExecutorService aiWorker = Executors.newSingleThreadExecutor();
    private KataGoEngine aiEngine;
    private volatile boolean destroyed;
    private Rect fightArea = new Rect(10, 10, 19, 19);
    private boolean analyzing, aiVariation;
    private String aiRunInfo = "";
    private volatile int aiSerial;
    private Point wrongAttempt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int index, round, mistakes, hints, studentMoves;
    private boolean solved, responding, previewing, reviewingWrongMove;
    private GoBoard beforeWrongMove;
    private TsumegoSgf.Node beforeWrongNode;
    private int beforeWrongStudentMoves, originalWrongStep;
    private final List<TsumegoSgf.Node> wrongVariation = new ArrayList<>();
    private final StringBuilder wrongNotes = new StringBuilder();
    private final StringBuilder wrongCaptured = new StringBuilder();
    private String wrongExplanation = "";
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
        credits.setText("AI分析");
        findViewById(R.id.tsumegoResultsTop).setOnClickListener(v -> showCredits());
        findViewById(R.id.tsumegoBack).setOnClickListener(v -> finish());
        hint.setOnClickListener(v -> showHint());
        reset.setOnClickListener(v -> retryOrRestart());
        message.setOnClickListener(v -> {
            if (reviewingWrongMove && !wrongExplanation.isEmpty())
                new AlertDialog.Builder(this).setTitle("这手为什么不合适？")
                        .setMessage(wrongExplanation)
                        .setPositiveButton("返回棋盘", null).show();
        });
        message.setMaxLines(4);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        solution.setOnClickListener(v -> showSolution());
        credits.setOnClickListener(v -> startAiInPlace());
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
        aiSerial++;
        analyzing = false;
        handler.removeCallbacksAndMessages(null);
        index = requested;
        prefs.edit().putInt("last_index", index).apply();
        try {
            puzzle = TsumegoSgf.parse("ggg-easy-" + IDS[index],
                    readAsset(IDS[index]));
            node = puzzle.root;
            position = puzzle.newBoard();
            fightArea = deriveFightArea(puzzle);
            solved = responding = previewing = reviewingWrongMove = aiVariation = false;
            beforeWrongMove = null;
            wrongAttempt = null;
            wrongVariation.clear();
            wrongNotes.setLength(0);
            wrongCaptured.setLength(0);
            wrongExplanation = "";
            aiRunInfo = "";
            mistakes = hints = studentMoves = 0;
            board.setTrainingViewport(10, 10, 9);
            board.setVariationPreview(null, null, null, null);
            board.setBoard(position);
            board.setLastMove(null);
            board.setAnalysisMoveNumbers(null);
            board.setTrainingTarget(null);
            board.setOwnership(null);
            board.setRecommendations(null, null);
            board.setInputEnabled(true);
            credits.setText("AI分析");
            credits.setEnabled(true);
            progress.setText("真实死活题 " + (index + 1) + "/" + IDS.length
                    + " · 已完成 " + completed() + "/" + IDS.length);
            title.setText("Go Game Guru · 第 " + IDS[index] + " 题\n"
                    + (puzzle.toPlay == StoneColor.BLACK ? "黑先" : "白先")
                    + " · 研究做活/杀棋的正确变化");
            message.setText("目标：找出能达成死活目的的着法。原题只注明先手方，"
                    + "未具体标注做活或杀棋；可对照原 SGF 正解。");
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
        if (puzzle == null || solved || responding || previewing || analyzing) return;
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

    /**
     * A legal but non-solution move must still be visible on the board.
     * Explain it from the author's real refutation branch where available.
     */
    private void play(int x, int y) {
        if (puzzle == null || solved || responding || previewing || reviewingWrongMove) return;
        Point at = new Point(x, y);
        StoneColor player = puzzle.toPlay;
        if (position.get(at) != Intersection.EMPTY) {
            showRuleError("此处已经有棋子，不能重复落子。");
            return;
        }
        if (!position.isLegalMove(at, player)) {
            if (at.equals(position.getSimpleKoPoint()))
                showRuleError("这里是劫争的立即回提点，须先在别处走一手。");
            else
                showRuleError("这手违反当前棋盘规则（例如无气自杀），因此不能落子。");
            return;
        }

        TsumegoSgf.Node original = puzzle.recordedMove(node, at, player);
        if (original == null || !original.leadsToCorrect) {
            startWrongVariation(at, player, original);
            return;
        }

        position.playMove(new Move.Stone(at, player));
        node = original;
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
        message.setText("第 " + studentMoves + " 手走在正解变化里，正在展示对方应手…");
        final int expectedRound = round;
        handler.postDelayed(() -> reply(expectedRound), 470L);
    }

    private void showRuleError(String reason) {
        message.setTextColor(0xFFFFC18A);
        message.setText(reason);
    }

    private static String moveLabel(TsumegoSgf.Node item) {
        if (item == null || item.point == null) return "";
        return (item.color == StoneColor.BLACK ? "黑" : "白") + vertex(item.point);
    }

    /** Translate recurring published comments without inventing an analysis. */
    private static String publishedComment(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String content = raw.trim();
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("correct")) return "原题标注：正确变化。";
        if (lower.contains("shortage of liberties"))
            return "原作者：这手造成黑棋气紧，容易被对方利用。";
        if (lower.contains("double ko"))
            return "原作者：这里会形成双劫，黑棋反而被吃。";
        if (lower.contains("bent four in the corner"))
            return "原作者：这会形成盘角曲四，不能按普通两眼活棋处理。";
        if (lower.contains("it's a ko") || lower.contains("it's ko"))
            return "原作者：此变化会形成劫争，但黑棋还有更好的下法。";
        if (lower.contains("seki"))
            return "原作者：这条变化可形成双活，但不是本题的最佳下法。";
        if (lower.contains("better move") || lower.contains("better way"))
            return "原作者：这条变化可能成立，但有更好的走法。";
        if (lower.contains("snapback"))
            return "原作者：这里存在倒扑，要注意被反提的手筋。";
        // Preserve original author text for cases without a reliable translation.
        return "原作者英文注释：" + content.replace('\n', ' ').replace('\r', ' ');
    }

    private void addWrongNote(String raw) {
        String detail = publishedComment(raw);
        if (detail.isEmpty()) return;
        if (wrongNotes.indexOf(detail) >= 0) return;
        if (wrongNotes.length() > 0) wrongNotes.append("\n");
        wrongNotes.append(detail);
    }

    private void startWrongVariation(Point at, StoneColor player, TsumegoSgf.Node recorded) {
        round++;
        aiSerial++;
        analyzing = false;
        handler.removeCallbacksAndMessages(null);
        mistakes++;
        beforeWrongMove = position.copyForPreview();
        beforeWrongNode = node;
        wrongAttempt = at;
        beforeWrongStudentMoves = studentMoves;
        originalWrongStep = studentMoves + 1;
        reviewingWrongMove = true;
        responding = true;
        wrongVariation.clear();
        wrongNotes.setLength(0);
        wrongCaptured.setLength(0);
        aiVariation = false;
        int removedNow = position.playMove(new Move.Stone(at, player)).size();
        if (removedNow > 0) appendCaptured(player, at, removedNow);
        board.setRecommendations(null, null);
        board.setLastMove(at);
        board.setBoard(position);
        board.setInputEnabled(false);
        hint.setEnabled(false);
        reset.setText("重试此手");
        if (recorded == null) {
            message.setTextColor(0xFFF8D192);
            message.setText("原 SGF 未收录这手。正在本页调用 KataGo 搜索局部应手…");
            startAiWrongAnalysis(at);
            return;
        }
        node = recorded;
        wrongVariation.add(recorded);
        addWrongNote(recorded.comment);
        renderWrongNumbers();
        message.setTextColor(0xFFFFC18A);
        message.setText("已试下 " + moveLabel(recorded)
                + "，正在演示原 SGF 收录的后续变化…");
        final int expected = round;
        handler.postDelayed(() -> advanceWrongVariation(expected, 0), 550L);
    }

    /** Auto-play ONLY the original SGF's recorded continuation, not fake AI moves. */
    private void advanceWrongVariation(int expectedRound, int depth) {
        if (expectedRound != round || isFinishing() || !reviewingWrongMove) return;
        if (depth >= 16) {
            finishWrongVariation("原题变化较长，已演示前 17 手。");
            return;
        }
        StoneColor nextPlayer = node.color == StoneColor.BLACK
                ? StoneColor.WHITE : StoneColor.BLACK;
        TsumegoSgf.Node next = puzzle.recordedReply(node, nextPlayer, position);
        if (next == null) {
            finishWrongVariation("");
            return;
        }
        int removedNow = position.playMove(new Move.Stone(next.point, nextPlayer)).size();
        if (removedNow > 0) appendCaptured(nextPlayer, next.point, removedNow);
        node = next;
        wrongVariation.add(next);
        addWrongNote(next.comment);
        board.setLastMove(next.point);
        board.setBoard(position);
        renderWrongNumbers();
        String msg = "原题反驳手顺：" + wrongSequence() + "…";
        message.setTextColor(0xFFFFC18A);
        message.setText(msg);
        handler.postDelayed(() -> advanceWrongVariation(expectedRound, depth + 1), 600L);
    }

    private void appendCaptured(StoneColor player, Point at, int removed) {
        if (wrongCaptured.length() > 0) wrongCaptured.append("\n");
        wrongCaptured.append(player == StoneColor.BLACK ? "黑" : "白")
                .append(vertex(at)).append(" 提掉 ")
                .append(removed).append(" 颗")
                .append(player == StoneColor.BLACK ? "白" : "黑").append("子。");
    }

    private String wrongSequence() {
        StringBuilder seq = new StringBuilder();
        for (TsumegoSgf.Node item : wrongVariation) {
            if (seq.length() > 0) seq.append(" → ");
            seq.append(moveLabel(item));
        }
        return seq.toString();
    }

    private void renderWrongNumbers() {
        int[] numbers = new int[19 * 19];
        for (int i = 0; i < wrongVariation.size(); i++) {
            Point at = wrongVariation.get(i).point;
            if (at != null) numbers[at.y * 19 + at.x] = i + 1;
        }
        board.setAnalysisMoveNumbers(numbers);
    }

    private void finishWrongVariation(String qualifier) {
        responding = false;
        analyzing = false;
        credits.setEnabled(true);
        credits.setText("AI分析");
        board.setInputEnabled(false);
        StringBuilder explanation = new StringBuilder();
        explanation.append("试下的第").append(originalWrongStep)
                .append("手不在原 SGF 标注的正解分支中。");
        if (wrongVariation.size() > 1) {
            explanation.append(aiVariation
                    ? "\nKataGo局部搜索的参考变化：" : "\n原题实际收录的变化：")
                    .append(wrongSequence()).append("。");
        }
        if (wrongCaptured.length() > 0)
            explanation.append("\n棋盘实际发生的提子：").append(wrongCaptured);
        if (wrongNotes.length() > 0)
            explanation.append("\n").append(wrongNotes);
        if (aiVariation && !aiRunInfo.isEmpty())
            explanation.append("\n本次分析：").append(aiRunInfo);
        if (!qualifier.isEmpty())
            explanation.append("\n").append(qualifier);
        else if (wrongNotes.length() == 0)
            explanation.append(aiVariation
                    ? "\nAI提供的是局部参考应手，不代表已证明这手必败；"
                      + "也不能用整盘胜率直接判定死活。"
                    : "\n原作者没有对这条变化给出明确的文字解释。"
                      + "可以对照正解棋形，但不能只凭此变化断言必死或必活。");
        TsumegoSgf.Node alternative = puzzle.nextCorrect(beforeWrongNode);
        if (alternative != null && alternative.point != null)
            explanation.append("\n同一局面原题的正解首手为：")
                    .append(moveLabel(alternative)).append("（只是对照，不是对试下棋的独立证明）。");
        explanation.append("\n点击「重试此手」恢复刚才的局面。");
        wrongExplanation = explanation.toString();
        message.setTextColor(0xFFFFC18A);
        message.setText(wrongExplanation);
    }

    private void retryOrRestart() {
        if (!reviewingWrongMove || beforeWrongMove == null) {
            open(index);
            return;
        }
        round++;
        aiSerial++;
        analyzing = false;
        handler.removeCallbacksAndMessages(null);
        position = beforeWrongMove;
        node = beforeWrongNode;
        studentMoves = beforeWrongStudentMoves;
        beforeWrongMove = null;
        wrongAttempt = null;
        reviewingWrongMove = false;
        responding = false;
        wrongVariation.clear();
        wrongNotes.setLength(0);
        wrongCaptured.setLength(0);
        wrongExplanation = "";
        aiRunInfo = "";
        aiVariation = false;
        credits.setEnabled(true);
        credits.setText("AI分析");
        board.setVariationPreview(null, null, null, null);
        previewing = false;
        board.setBoard(position);
        board.setLastMove(null);
        board.setAnalysisMoveNumbers(null);
        board.setRecommendations(null, null);
        board.setInputEnabled(true);
        reset.setText("重做");
        hint.setEnabled(true);
        hint.setText("提示");
        hints = 0;
        message.setTextColor(0xFFE4EADC);
        message.setText("已回到第 " + originalWrongStep + " 手之前。换个落点再试。");
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
        aiSerial++;
        analyzing = false;
        credits.setEnabled(false);
        prefs.edit().putInt("completed_mask",
                prefs.getInt("completed_mask", 0) | (1 << index)).apply();
        board.setInputEnabled(false);
        hint.setEnabled(false);
        reset.setText("重做");
        progress.setText("真实死活题 " + (index + 1) + "/" + IDS.length
                + " · 已完成 " + completed() + "/" + IDS.length);
        message.setTextColor(0xFF9AE6BC);
        message.setText("✓ 已走通原 SGF 正解！自己下了 " + studentMoves
                + " 手；点击「看正解」可回看完整变化。");
    }

    private void showSolution() {
        if (puzzle == null) return;
        // An in-progress AI search must never overwrite the answer overlay.
        aiSerial++;
        analyzing = false;
        credits.setText("AI分析");
        credits.setEnabled(false);
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

    /**
     * Derive a restricted combat rectangle from the author's SGF principal
     * sequence. The board remains a full 19x19 position; no UI crop is used
     * to change capture or ko rules.
     */
    private static Rect deriveFightArea(TsumegoSgf.Puzzle problem) {
        int minX = 19, minY = 19, maxX = -1, maxY = -1;
        for (TsumegoSgf.Node move : problem.principalSolution()) {
            if (move.point == null) continue;
            minX = Math.min(minX, move.point.x);
            minY = Math.min(minY, move.point.y);
            maxX = Math.max(maxX, move.point.x);
            maxY = Math.max(maxY, move.point.y);
        }
        if (maxX < minX || maxY < minY)
            throw new IllegalArgumentException("原题缺少可用的正解变化");
        return new Rect(Math.max(LOCAL_LEFT, minX - 2),
                Math.max(LOCAL_TOP, minY - 2),
                Math.min(19, maxX + 3), Math.min(19, maxY + 3));
    }

    private static TsumegoSgf.Node syntheticMove(Point point, StoneColor color) {
        TsumegoSgf.Node move = new TsumegoSgf.Node();
        move.point = point;
        move.color = color;
        return move;
    }

    /**
     * Explicit AI help remains inside this same Activity. While reviewing an
     * off-book first move, analyze THAT move from its actual starting board.
     * Otherwise show a non-playing, locally searched hint for the next turn.
     */
    private void startAiInPlace() {
        if (puzzle == null || analyzing) return;
        if (previewing || solved) {
            message.setText("先点「重做」回到题目，才可以请求 AI 提示。");
            return;
        }
        if (reviewingWrongMove && wrongAttempt != null && beforeWrongMove != null) {
            startAiWrongAnalysis(wrongAttempt);
            return;
        }
        if (responding) {
            message.setText("原 SGF 正在演示应手，请稍候或点「重做」。");
            return;
        }
        final int serial = ++aiSerial;
        final int expectedRound = round;
        final GoBoard snapshot = position.copyForPreview();
        final StoneColor side = puzzle.toPlay;
        final Rect area = new Rect(fightArea);
        analyzing = true;
        board.setInputEnabled(false);
        credits.setEnabled(false);
        credits.setText("分析中");
        message.setTextColor(0xFFE4EADC);
        message.setText("正在让 KataGo 10b 搜索当前题目的局部建议…");
        aiWorker.execute(() -> {
            Point suggestion = null;
            String error = null;
            try {
                KataGoEngine engine = ensureAiEngine();
                if (serial != aiSerial || destroyed) return;
                if (!engine.setTrainingPosition(snapshot))
                    throw new IllegalStateException("KataGo 未能同步当前棋形");
                List<KataGoEngine.SearchRecommendation> recommendations =
                        engine.searchLocalRecommendations(side.toGtp(), 96, 5.0,
                                snapshot, area.left, area.top, area.right, area.bottom);
                for (KataGoEngine.SearchRecommendation rec : recommendations) {
                    Point at = Point.fromGtp(rec.move, 19);
                    if (at != null && area.contains(at.x, at.y)
                            && snapshot.isLegalMove(at, side)) {
                        suggestion = at;
                        break;
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
                android.util.Log.w("SgfTsumego", "Inline AI hint failed", e);
            }
            final Point best = suggestion;
            final String failure = error;
            handler.post(() -> {
                if (destroyed || serial != aiSerial || expectedRound != round) return;
                analyzing = false;
                board.setInputEnabled(!solved && !previewing && !responding
                        && !reviewingWrongMove);
                credits.setEnabled(true);
                credits.setText("AI分析");
                if (best != null) {
                    board.setRecommendations(new Point[]{best}, new float[]{1f});
                    TsumegoSgf.Node authored = puzzle.nextCorrect(node);
                    message.setText("KataGo局部建议：" + vertex(best)
                            + (authored != null && authored.point != null
                                ? "；原 SGF 正解候选：" + vertex(authored.point) : "")
                            + "。AI建议不等于已证明的死活答案。");
                    message.setTextColor(0xFFF7D58E);
                } else {
                    message.setText("AI没有返回可用的局部着法"
                            + (failure == null ? "。" : "：" + failure));
                    message.setTextColor(0xFFFFC18A);
                }
            });
        });
    }

    /** Lazily reuse one isolated CPU PIE GTP process only when AI is needed. */
    private KataGoEngine ensureAiEngine() throws Exception {
        if (aiEngine != null && aiEngine.isReady()) return aiEngine;
        if (aiEngine != null) aiEngine.stop();
        KataGoEngine session = new KataGoEngine(getApplicationContext(), "engine_tsumego_ai");
        aiEngine = session;
        session.setStartupBoardSize(19);
        if (!session.start(KataGoEngine.Model.HUMAN)
                || !session.setBoardSize(19)
                || !session.setChineseRules()
                || !session.setKomi(0f))
            throw new IllegalStateException("KataGo 10b CPU 引擎启动失败");
        return session;
    }

    /**
     * The student's wrong move has ALREADY been placed on the board.
     * Compare it with ONE KataGo continuation, max four additional plies.
     * Never resume unrestricted sparring after displaying that continuation.
     */
    private void startAiWrongAnalysis(Point attempted) {
        if (puzzle == null || beforeWrongMove == null || attempted == null) return;
        round++;
        handler.removeCallbacksAndMessages(null);
        final int expectedRound = round;
        final int serial = ++aiSerial;
        final GoBoard snapshot = beforeWrongMove.copyForPreview();
        final StoneColor human = puzzle.toPlay;
        final StoneColor defender = human.opposite();
        if (!snapshot.isLegalMove(attempted, human)) {
            finishWrongVariation("该落点在当前局面已不合法，无法搜索反驳。");
            return;
        }
        int captured = snapshot.playMove(new Move.Stone(attempted, human)).size();
        final Rect area = new Rect(fightArea);
        position = snapshot.copyForPreview();
        node = beforeWrongNode;
        reviewingWrongMove = true;
        aiVariation = true;
        responding = true;
        analyzing = true;
        wrongVariation.clear();
        wrongVariation.add(syntheticMove(attempted, human));
        wrongNotes.setLength(0);
        wrongCaptured.setLength(0);
        wrongExplanation = "";
        if (captured > 0) appendCaptured(human, attempted, captured);
        board.setVariationPreview(null, null, null, null);
        board.setRecommendations(null, null);
        board.setBoard(position);
        board.setLastMove(attempted);
        renderWrongNumbers();
        board.setInputEnabled(false);
        hint.setEnabled(false);
        reset.setText("重试此手");
        credits.setText("分析中");
        credits.setEnabled(false);
        message.setTextColor(0xFFF8D192);
        message.setText("已保留试下的 " + vertex(attempted)
                + "，KataGo 正在寻找局部反驳（最多演示后续4手）…");

        // Do not encourage an unrelated corner fight when the attempted move
        // itself lies outside the source problem's local fight.
        if (!area.contains(attempted.x, attempted.y)) {
            finishWrongVariation("这手位于原题核心攻防区之外。"
                    + "程序不允许 AI 跑到远处编造反驳，请对照原 SGF 正解。");
            return;
        }
        aiWorker.execute(() -> {
            String failure = null;
            List<Point> planned = new ArrayList<>();
            List<StoneColor> sides = new ArrayList<>();
            try {
                KataGoEngine engine = ensureAiEngine();
                if (destroyed || serial != aiSerial) return;
                if (!engine.setTrainingPosition(snapshot))
                    throw new IllegalStateException("KataGo 未能装载你的试下局面");
                List<KataGoEngine.SearchRecommendation> options =
                        engine.searchLocalRecommendations(defender.toGtp(), 112, 5.0,
                                snapshot, area.left, area.top, area.right, area.bottom);
                for (KataGoEngine.SearchRecommendation option : options) {
                    GoBoard trial = snapshot.copyForPreview();
                    List<String> pv = option.pvMoves;
                    for (int i = 0; i < pv.size() && i < 4; i++) {
                        Point point = Point.fromGtp(pv.get(i), 19);
                        StoneColor turn = (i % 2 == 0) ? defender : human;
                        if (point == null || !area.contains(point.x, point.y)
                                || !trial.isLegalMove(point, turn)) break;
                        trial.playMove(new Move.Stone(point, turn));
                        planned.add(point);
                        sides.add(turn);
                    }
                    if (!planned.isEmpty()) break;
                }
                if (planned.isEmpty())
                    failure = "AI没有找到可用的局部反驳；不能据此判断该手必败。";
            } catch (Exception e) {
                failure = "AI搜索未完成：" + e.getMessage();
                android.util.Log.w("SgfTsumego", "Inline AI refutation failed", e);
            }
            final String result = failure;
            handler.post(() -> {
                if (destroyed || serial != aiSerial || expectedRound != round) return;
                if (planned.isEmpty()) {
                    finishWrongVariation(result == null ? "AI没有返回合法应手。" : result);
                } else {
                    animateAiLine(expectedRound, serial, planned, sides, 0);
                }
            });
        });
    }

    private void animateAiLine(int expectedRound, int serial, List<Point> moves,
            List<StoneColor> colors, int step) {
        if (destroyed || expectedRound != round || serial != aiSerial
                || !reviewingWrongMove) return;
        if (step >= moves.size()) {
            finishWrongVariation("这只是 KataGo 10b 局部搜索的参考应手（非原 SGF 分支），"
                    + "不代表已证明你的着法必死或必活。"
                    + "如需查看详细结论，可点上方说明文字。");
            return;
        }
        Point point = moves.get(step);
        StoneColor color = colors.get(step);
        if (!position.isLegalMove(point, color)) {
            finishWrongVariation("AI演示到此停止：后续着法在当前棋盘不合法。");
            return;
        }
        int captured = position.playMove(new Move.Stone(point, color)).size();
        if (captured > 0) appendCaptured(color, point, captured);
        wrongVariation.add(syntheticMove(point, color));
        board.setBoard(position);
        board.setLastMove(point);
        renderWrongNumbers();
        message.setText("KataGo参考变化：" + wrongSequence() + "…");
        message.setTextColor(0xFFF8D192);
        handler.postDelayed(() -> animateAiLine(expectedRound, serial,
                moves, colors, step + 1), 500L);
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
        destroyed = true;
        round++;
        aiSerial++;
        handler.removeCallbacksAndMessages(null);
        aiWorker.execute(() -> {
            if (aiEngine != null) aiEngine.stop();
        });
        aiWorker.shutdown();
        super.onDestroy();
    }
}
