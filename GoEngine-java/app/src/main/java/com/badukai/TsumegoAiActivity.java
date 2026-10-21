package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.engine.KataGoEngine;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Free-form life/death sparring against real KataGo 10b MCTS on full-board SGF
 * setup. Only the SEARCH root is restricted to the local problem. A wrong move
 * is playable; it does not automatically mean the target group has died.
 * Capturing the explicitly selected group is the sole terminal certification.
 */
public final class TsumegoAiActivity extends Activity {
    private static final int[] IDS = {101, 103, 104, 105, 110, 115};
    private static final int CORNER_START = 10;
    private static final int CORNER_SIZE = 9;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private KataGoEngine engine;
    private volatile boolean ready;
    private boolean destroyed, thinking, ended, killing;
    private int index, ownMoves, aiMoves, selectedPuzzleId;
    private int pendingTryX = -1, pendingTryY = -1, pendingTryIndex = -1;
    private TsumegoSgf.Puzzle puzzle;
    private GoBoard position;
    private GoBoardView board;
    private Point targetAnchor;
    private List<Point> watchedStones = new ArrayList<>();
    private TextView progress, title, message;
    private Button prev, next, hint, reset, back, goal;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tsumego);
        com.badukai.ui.BoardViewportFitter.attach(
                findViewById(R.id.tsumegoContentScroll),
                findViewById(R.id.tsumegoBoardFrame));
        board = findViewById(R.id.tsumegoBoard);
        progress = findViewById(R.id.tsumegoProgress);
        title = findViewById(R.id.tsumegoQuestion);
        message = findViewById(R.id.tsumegoMessage);
        prev = findViewById(R.id.tsumegoPrev);
        next = findViewById(R.id.tsumegoNext);
        hint = findViewById(R.id.tsumegoHint);
        reset = findViewById(R.id.tsumegoReset);
        back = findViewById(R.id.tsumegoExplain);
        goal = findViewById(R.id.tsumegoResults);

        message.setMaxLines(4);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        board.setOnIntersectionClickListener(this::onIntersection);
        hint.setText("AI推荐");
        reset.setText("重新挑战");
        back.setText("返回原题");
        back.setEnabled(true);
        goal.setText("切换目标");
        findViewById(R.id.tsumegoBack).setOnClickListener(v -> finish());
        findViewById(R.id.tsumegoResultsTop).setOnClickListener(v -> showSource());
        hint.setOnClickListener(v -> recommend());
        reset.setOnClickListener(v -> open(index));
        back.setOnClickListener(v -> finish());
        goal.setOnClickListener(v -> {
            killing = !killing;
            open(index);
        });
        prev.setOnClickListener(v -> open(index - 1));
        next.setOnClickListener(v -> open(index + 1));

        JadeBottomNavView nav = findViewById(R.id.tsumegoSharedNav);
        nav.setSelectedTab(JadeBottomNavView.TRAINING);
        nav.setOnTabClickListener(tab -> JadeNavigation.open(this, tab));

        SharedPreferences pref = getSharedPreferences("tsumego_sgf_progress", MODE_PRIVATE);
        int requested = getIntent().getIntExtra("sgf_puzzle_index", pref.getInt("last_index", 0));
        killing = getIntent().getBooleanExtra("tsumego_killing", false);
        pendingTryX = getIntent().getIntExtra("tsumego_try_x", -1);
        pendingTryY = getIntent().getIntExtra("tsumego_try_y", -1);
        pendingTryIndex = requested;
        open(requested >= 0 && requested < IDS.length ? requested : 0);
        startEngine();
    }

    private String readSgf(int number) throws Exception {
        try (InputStream input = getAssets().open("tsumego/ggg-easy-" + number + ".sgf");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int n;
            while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private StoneColor player() { return puzzle.toPlay; }
    private StoneColor opponent() { return player().opposite(); }
    private StoneColor trackedColor() { return killing ? opponent() : player(); }

    private void open(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= IDS.length) return;
        generation.incrementAndGet();
        index = targetIndex;
        selectedPuzzleId = IDS[index];
        ownMoves = aiMoves = 0;
        thinking = ended = false;
        board.setInputEnabled(false);
        board.setRecommendations(null, null);
        board.setAnalysisMoveNumbers(null);
        board.setVariationPreview(null, null, null, null);
        try {
            puzzle = TsumegoSgf.parse("ggg-easy-" + selectedPuzzleId,
                    readSgf(selectedPuzzleId));
            position = puzzle.newBoard();
            board.setBoard(position);
            board.setTrainingViewport(CORNER_START, CORNER_START, CORNER_SIZE);
            board.setLastMove(null);
            chooseDefaultTarget();
            renderHeading();
            prev.setEnabled(index > 0);
            next.setEnabled(index < IDS.length - 1);
            hint.setEnabled(ready);
            message.setText("目标：让" + (trackedColor() == StoneColor.BLACK ? "黑" : "白")
                    + "棋目标棋块" + (killing ? "被提掉。" : "活下来。")
                    + " 这是可切换的练习目标，并非原题标注。"
                    + " 点棋子可指定目标，点空点落子。"
                    + (ready ? " AI已准备好。" : " 正在启动KataGo…"));
            message.setTextColor(0xFFE4EADC);
            board.setInputEnabled(ready);
        } catch (Exception e) {
            puzzle = null;
            position = null;
            ended = true;
            message.setText("无法读取真实死活题：" + e.getMessage());
            message.setTextColor(0xFFFFC18A);
            android.util.Log.e("TsumegoAi", "SGF parse failed", e);
        }
    }

    private void renderHeading() {
        String color = player() == StoneColor.BLACK ? "黑" : "白";
        title.setText("Go Game Guru · 第 " + selectedPuzzleId + " 题\n"
                + color + "先 · AI陪练 · " + (killing ? "杀棋" : "做活") + "（可切换）");
        progress.setText("AI局部对练 " + (index + 1) + "/" + IDS.length
                + " · 你" + ownMoves + "手 AI" + aiMoves + "手");
    }

    private void chooseDefaultTarget() {
        Point nearby = null;
        List<TsumegoSgf.Node> principal = puzzle.principalSolution();
        if (!principal.isEmpty()) nearby = principal.get(0).point;
        if (nearby == null) nearby = new Point(16, 16);
        Point closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int y = CORNER_START; y < 19; y++)
            for (int x = CORNER_START; x < 19; x++) {
                Point stone = new Point(x, y);
                if (!sameColorStone(stone, trackedColor())) continue;
                int distance = Math.abs(x - nearby.x) + Math.abs(y - nearby.y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    closest = stone;
                }
            }
        setTarget(closest);
    }

    private boolean sameColorStone(Point at, StoneColor color) {
        Intersection expected = color == StoneColor.BLACK
                ? Intersection.BLACK : Intersection.WHITE;
        return position != null && position.get(at) == expected;
    }

    /** Select the full connected starting group, not a random individual stone. */
    private void setTarget(Point stone) {
        targetAnchor = stone;
        watchedStones = new ArrayList<>();
        if (stone == null) {
            board.setTrainingTarget(null);
            return;
        }
        ArrayDeque<Point> queue = new ArrayDeque<>();
        Set<Point> visited = new HashSet<>();
        queue.add(stone);
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            if (!position.isInside(p.x, p.y) || !visited.add(p)
                    || !sameColorStone(p, trackedColor())) continue;
            watchedStones.add(p);
            queue.add(new Point(p.x - 1, p.y));
            queue.add(new Point(p.x + 1, p.y));
            queue.add(new Point(p.x, p.y - 1));
            queue.add(new Point(p.x, p.y + 1));
        }
        board.setTrainingTarget(stone);
    }

    private void startEngine() {
        worker.execute(() -> {
            try {
                KataGoEngine session = new KataGoEngine(getApplicationContext(), "engine_tsumego_ai");
                engine = session;
                session.setStartupBoardSize(19);
                if (!session.start(KataGoEngine.Model.HUMAN)
                        || !session.setBoardSize(19)
                        || !session.setChineseRules()
                        || !session.setKomi(0f))
                    throw new IllegalStateException("KataGo 10b 未能初始化19路训练引擎");
                ready = true;
                ui.post(() -> {
                    if (destroyed || puzzle == null) return;
                    board.setInputEnabled(!ended && !thinking);
                    hint.setEnabled(!ended);
                    message.setText("KataGo 10b 已就绪（独立CPU引擎）。"
                            + " 你可以试任意合法的局部着法，对方会持续应手。");
                    if (pendingTryIndex == index && pendingTryX >= CORNER_START
                            && pendingTryY >= CORNER_START && pendingTryX < 19
                            && pendingTryY < 19) {
                        int x = pendingTryX, y = pendingTryY;
                        pendingTryX = pendingTryY = -1;
                        onIntersection(x, y);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("TsumegoAi", "Engine failed to start", e);
                ready = false;
                ui.post(() -> {
                    if (destroyed) return;
                    board.setInputEnabled(false);
                    hint.setEnabled(false);
                    message.setText("AI 引擎启动失败：" + e.getMessage()
                            + "。原题模式仍可使用。");
                });
            }
        });
    }

    private void onIntersection(int x, int y) {
        if (puzzle == null || !ready || thinking || ended) return;
        Point at = new Point(x, y);
        if (position.get(at) != Intersection.EMPTY) {
            if (sameColorStone(at, trackedColor())) {
                setTarget(at);
                message.setText("已选择金圈处的" + (killing ? "待杀" : "待活")
                        + "棋块。现在试着下棋。");
            } else {
                Toast.makeText(this, "此处有棋子，不能落子", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!position.isLegalMove(at, player())) {
            message.setText(at.equals(position.getSimpleKoPoint())
                    ? "这是劫点，不能立即回提。" : "此处违反落子规则（例如自杀），不能下。");
            return;
        }
        position.playMove(new Move.Stone(at, player()));
        ownMoves++;
        board.setLastMove(at);
        board.setBoard(position);
        renderHeading();
        if (checkCaptured()) return;
        scheduleAiReply();
    }

    /**
     * Proven terminal state: selected initial target group was actually
     * captured. Two-eye life, seki and unconditional death are NOT inferred
     * from whole-board win rate or NN ownership estimates.
     */
    private boolean checkCaptured() {
        if (watchedStones.isEmpty()) return false;
        for (Point original : watchedStones)
            if (sameColorStone(original, trackedColor())) return false;
        ended = true;
        board.setInputEnabled(false);
        hint.setEnabled(false);
        message.setTextColor(0xFF9AE6BC);
        String target = trackedColor() == StoneColor.BLACK ? "黑" : "白";
        message.setText("目标" + target + "棋块已被全部提掉。"
                + (killing ? "杀棋挑战成功！" : "做活挑战失败。")
                + " 这是实际提子结果，不是胜率推测。");
        return true;
    }

    /** Current liberties of the surviving marked string, not a life/death proof. */
    private int trackedLiberties() {
        if (position == null) return -1;
        Point surviving = null;
        for (Point p : watchedStones)
            if (sameColorStone(p, trackedColor())) { surviving = p; break; }
        if (surviving == null) return 0;
        Set<Point> seen = new HashSet<>(), liberties = new HashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        queue.add(surviving);
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            if (!position.isInside(p.x, p.y) || !seen.add(p)
                    || !sameColorStone(p, trackedColor())) continue;
            Point[] around = {new Point(p.x - 1, p.y), new Point(p.x + 1, p.y),
                    new Point(p.x, p.y - 1), new Point(p.x, p.y + 1)};
            for (Point other : around) {
                if (!position.isInside(other.x, other.y)) continue;
                if (position.get(other) == Intersection.EMPTY) liberties.add(other);
                else if (sameColorStone(other, trackedColor()) && !seen.contains(other))
                    queue.add(other);
            }
        }
        return liberties.size();
    }

    private void scheduleAiReply() {
        if (!ready || puzzle == null) return;
        thinking = true;
        board.setInputEnabled(false);
        hint.setEnabled(false);
        message.setTextColor(0xFFE4EADC);
        message.setText("你的落子已保留，KataGo 正在搜索局部应手…");
        final int serial = generation.get();
        final GoBoard snapshot = position.copyForPreview();
        final StoneColor side = opponent();
        worker.execute(() -> {
            String error = null;
            Point choice = null;
            try {
                if (serial != generation.get() || destroyed) return;
                if (!engine.setTrainingPosition(snapshot))
                    throw new IllegalStateException("无法向 KataGo 装载死活棋形");
                List<KataGoEngine.SearchRecommendation> search =
                        engine.searchLocalRecommendations(side.toGtp(), 96, 5.0,
                                snapshot, CORNER_START, CORNER_START, CORNER_SIZE);
                for (KataGoEngine.SearchRecommendation rec : search) {
                    Point candidate = Point.fromGtp(rec.move, 19);
                    if (candidate != null
                            && snapshot.isLegalMove(candidate, side)
                            && candidate.x >= CORNER_START && candidate.y >= CORNER_START) {
                        choice = candidate;
                        break;
                    }
                }
                if (choice == null) error = "AI没有找到合法的局部应手，不能据此断言死活。";
            } catch (Exception e) {
                error = e.getMessage();
                android.util.Log.e("TsumegoAi", "KataGo local search failed", e);
            }
            final Point move = choice;
            final String failure = error;
            ui.post(() -> {
                if (destroyed || serial != generation.get() || puzzle == null) return;
                thinking = false;
                if (move == null || !position.isLegalMove(move, side)) {
                    message.setTextColor(0xFFFFC18A);
                    message.setText("AI应手暂不可用：" + failure
                            + " 可重开题目重试。");
                    board.setInputEnabled(false);
                    hint.setEnabled(false);
                    return;
                }
                position.playMove(new Move.Stone(move, side));
                aiMoves++;
                board.setLastMove(move);
                board.setBoard(position);
                renderHeading();
                if (checkCaptured()) return;
                board.setInputEnabled(true);
                hint.setEnabled(true);
                message.setTextColor(0xFFE4EADC);
                int liberties = trackedLiberties();
                message.setText("AI在 " + move.toGtp(19) + " 应手。目标棋块当前约有 "
                        + liberties + " 口气"
                        + (liberties == 1 ? "，已被打吃！" : "。")
                        + " 继续试下；气数并不能直接证明死活。");
            });
        });
    }

    private void recommend() {
        if (puzzle == null || !ready || thinking || ended) return;
        thinking = true;
        board.setInputEnabled(false);
        hint.setEnabled(false);
        message.setText("KataGo 正在搜索你的局部候选着法…");
        final int serial = generation.get();
        GoBoard snapshot = position.copyForPreview();
        StoneColor turn = player();
        worker.execute(() -> {
            Point best = null;
            try {
                if (serial != generation.get() || destroyed) return;
                if (!engine.setTrainingPosition(snapshot))
                    throw new IllegalStateException("死活局面同步失败");
                List<KataGoEngine.SearchRecommendation> recs =
                        engine.searchLocalRecommendations(turn.toGtp(), 128, 6.0,
                                snapshot, CORNER_START, CORNER_START, CORNER_SIZE);
                for (KataGoEngine.SearchRecommendation rec : recs) {
                    Point p = Point.fromGtp(rec.move, 19);
                    if (p != null && snapshot.isLegalMove(p, turn)) {
                        best = p;
                        break;
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("TsumegoAi", "Hint failed", e);
            }
            final Point suggestion = best;
            ui.post(() -> {
                if (destroyed || serial != generation.get() || puzzle == null) return;
                thinking = false;
                if (suggestion != null) {
                    board.setRecommendations(new Point[]{suggestion}, new float[]{1f});
                    message.setText("AI建议尝试 " + suggestion.toGtp(19)
                            + "。这是局部搜索推荐，不代表已证明能做活/杀棋。");
                } else {
                    message.setText("KataGo未返回可用的局部候选手。");
                }
                board.setInputEnabled(true);
                hint.setEnabled(true);
            });
        });
    }

    private void showSource() {
        new AlertDialog.Builder(this)
                .setTitle("AI死活陪练")
                .setMessage("原始棋形来自 Go Game Guru Weekly Go Problems"
                        + "\n许可：CC BY-NC-SA 4.0，仅限个人非商业使用。"
                        + "\n\n对手由独立 KataGo 10b CPU 搜索生成，不是原题作者的正解。"
                        + " AI受局部搜索和访问次数限制，因此它的判断不等同于数学证明。"
                        + "\n金圈标出当前追踪的目标棋块，点击同色棋子可重新选择。")
                .setPositiveButton("知道了", null).show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        generation.incrementAndGet();
        worker.execute(() -> { if (engine != null) engine.stop(); });
        worker.shutdown();
        super.onDestroy();
    }
}
