package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.engine.KataGoEngine;
import com.badukai.game.GoBoard;
import com.badukai.game.GoSgf;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.SgfArchive;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.JadeAnalysisChartView;
import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeNavigation;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A genuine, read-only KataGo 10b SGF analysis page.
 * All GTP commands belong to a separate PIE engine on analysisExecutor.
 * Nothing here mutates the in-progress MainActivity game.
 */
public final class AiAnalysisActivity extends Activity {
    private static final String TAG = "AiAnalysis";
    public static final String EXTRA_CACHE_SGF = "ai_analysis_cache_file";
    public static final String EXTRA_START_STEP = "ai_analysis_start_step";
    private static final int MAX_SGF_BYTES = 2_000_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean alive = true;
    private boolean globalRunning;
    private boolean autoplay;
    private boolean variationTab;
    private boolean trendsVisible;
    private int step;
    private float[] rates = new float[0];
    private StepAnalysis[] analyzed = new StepAnalysis[0];
    private GoSgf.Game game;
    private KataGoEngine engine;
    private boolean engineReady;
    private GoBoard shownPosition;

    private GoBoardView board;
    private JadeAnalysisChartView chart;
    private LinearLayout candidates;
    private TextView blackWin, lead, captures, boardMeta, status;
    private ProgressBar winProgress;
    private Button fullButton, playButton, quickTabButton, variationTabButton;
    private Button trendTabButton, movesTabButton;
    private View trendPanel, movesPanel;
    private final Runnable autoplayTick = new Runnable() {
        @Override public void run() {
            if (!alive || !autoplay || game == null) return;
            if (step >= game.moves.size()) {
                stopAutoplay();
                return;
            }
            showStep(step + 1);
            ui.postDelayed(this, 1350);
        }
    };

    private static final class StepAnalysis {
        final KataGoEngine.PositionEvaluation evaluation;
        final List<KataGoEngine.SearchRecommendation> recommendations;
        StepAnalysis(KataGoEngine.PositionEvaluation eval,
                List<KataGoEngine.SearchRecommendation> choices) {
            evaluation = eval;
            recommendations = choices;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_ai_analysis);
        com.badukai.ui.BoardViewportFitter.attach(
                findViewById(R.id.analysisContentScroll),
                findViewById(R.id.analysisBoardFrame));
        board = findViewById(R.id.analysisBoard);
        board.setInputEnabled(false);
        chart = findViewById(R.id.analysisChart);
        candidates = findViewById(R.id.analysisCandidateList);
        blackWin = findViewById(R.id.analysisBlackWin);
        lead = findViewById(R.id.analysisLead);
        captures = findViewById(R.id.analysisCaptures);
        boardMeta = findViewById(R.id.analysisBoardMeta);
        status = findViewById(R.id.analysisStatus);
        winProgress = findViewById(R.id.analysisWinProgress);
        fullButton = findViewById(R.id.analysisFullButton);
        playButton = findViewById(R.id.analysisPlay);
        quickTabButton = findViewById(R.id.analysisQuickTab);
        variationTabButton = findViewById(R.id.analysisVariationTab);
        trendTabButton = findViewById(R.id.analysisTrendTab);
        movesTabButton = findViewById(R.id.analysisMovesTab);
        trendPanel = findViewById(R.id.analysisTrendPanel);
        movesPanel = findViewById(R.id.analysisMovesPanel);
        trendTabButton.setOnClickListener(v -> selectDetailTab(true));
        movesTabButton.setOnClickListener(v -> selectDetailTab(false));
        selectDetailTab(false);
        board.setBoard(new GoBoard(9));

        try (InputStream stream = getAssets().open("home/background.png")) {
            ((ImageView) findViewById(R.id.analysisBackdrop))
                    .setImageBitmap(BitmapFactory.decodeStream(stream));
        } catch (Exception ignored) { /* dark jade fallback */ }

        JadeBottomNavView nav = findViewById(R.id.analysisSharedNav);
        nav.setSelectedTab(JadeBottomNavView.RECORDS);
        nav.setOnTabClickListener(tab -> JadeNavigation.open(this, tab));
        findViewById(R.id.analysisBack).setOnClickListener(v -> finish());
        findViewById(R.id.analysisPrevious).setOnClickListener(v -> {
            stopAutoplay();
            if (game == null) return;
            showStep(step < game.moves.size() ? step + 1 : 0);
        });
        findViewById(R.id.analysisCurrent).setOnClickListener(v -> {
            stopAutoplay();
            if (game == null) return;
            selectTab(false);
            showStep(step);
        });
        playButton.setOnClickListener(v -> {
            if (game == null) return;
            if (autoplay) stopAutoplay();
            else {
                if (step >= game.moves.size()) showStep(0);
                autoplay = true;
                playButton.setText("Ⅱ 暂停播放");
                ui.postDelayed(autoplayTick, 500);
            }
        });
        chart.setOnStepSelected(selected -> {
            stopAutoplay();
            showStep(selected);
        });
        ((android.widget.Switch) findViewById(R.id.analysisFromStep))
                .setOnCheckedChangeListener((button, enabled) ->
                        chart.setFromSelectedStep(enabled));
        quickTabButton.setOnClickListener(v -> selectTab(false));
        variationTabButton.setOnClickListener(v -> selectTab(true));
        fullButton.setOnClickListener(v -> {
            if (globalRunning) {
                generation.incrementAndGet();
                globalRunning = false;
                fullButton.setText("分析全局");
                status.setText("已停止逐手分析，已完成的胜率点仍然保留");
            } else startFullAnalysis();
        });

        try {
            String sgf = loadSource(getIntent());
            game = GoSgf.decode(sgf);
            // Validate every move before running the native engine.
            GoSgf.position(game, game.moves.size());
            rates = new float[game.moves.size() + 1];
            Arrays.fill(rates, Float.NaN);
            analyzed = new StepAnalysis[rates.length];
            int initial = getIntent().getIntExtra(EXTRA_START_STEP, game.moves.size());
            showStep(Math.max(0, Math.min(initial, game.moves.size())));
        } catch (Exception error) {
            Log.e(TAG, "SGF load error", error);
            status.setText("无法打开棋谱：" + error.getMessage());
            showPlaceholder("暂无棋谱，请从棋谱中心选择棋局");
            fullButton.setEnabled(false);
        }
    }

    private String loadSource(Intent sourceIntent) throws Exception {
        String cacheName = sourceIntent.getStringExtra(EXTRA_CACHE_SGF);
        File selected;
        if (cacheName != null) {
            if (!cacheName.matches("analysis_[0-9]+\\.sgf"))
                throw new IllegalArgumentException("无效的临时棋谱名");
            selected = new File(getCacheDir(), cacheName);
        } else {
            String archiveName = sourceIntent.getStringExtra(ReplayActivity.EXTRA_SELECTED_SGF_FILE);
            if (archiveName != null) {
                if (!archiveName.matches("[0-9a-f]{24}\\.sgf"))
                    throw new IllegalArgumentException("无效的棋谱编号");
                selected = new File(SgfArchive.directory(this), archiveName);
            } else selected = new File(getFilesDir(), "last_game.sgf");
        }
        if (!selected.isFile() || selected.length() <= 0 || selected.length() > MAX_SGF_BYTES)
            throw new IllegalArgumentException("没有找到可分析的棋谱");
        return new String(Files.readAllBytes(selected.toPath()), StandardCharsets.UTF_8);
    }

    /** Write the current replay SGF to private cache, avoiding a large Binder extra. */
    public static Intent fromReplay(Activity activity, String sgf, int currentStep) throws Exception {
        if (sgf == null || sgf.isEmpty() || sgf.getBytes(StandardCharsets.UTF_8).length > MAX_SGF_BYTES)
            throw new IllegalArgumentException("请先打开有效棋谱");
        // Source was already decoded by ReplayActivity; keep it private.
        String filename = "analysis_" + Long.toUnsignedString(System.nanoTime()) + ".sgf";
        Files.write(new File(activity.getCacheDir(), filename).toPath(),
                sgf.getBytes(StandardCharsets.UTF_8));
        return new Intent(activity, AiAnalysisActivity.class)
                .putExtra(EXTRA_CACHE_SGF, filename)
                .putExtra(EXTRA_START_STEP, currentStep);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void stopAutoplay() {
        autoplay = false;
        ui.removeCallbacks(autoplayTick);
        if (playButton != null) playButton.setText("▶▶ 自动播放");
    }

    private StoneColor nextPlayer(int index) {
        if (index == 0)
            return game.handicap.isEmpty() ? StoneColor.BLACK : StoneColor.WHITE;
        return game.moves.get(index - 1).color.opposite();
    }

    private void showStep(int requested) {
        if (game == null) return;
        int next = Math.max(0, Math.min(requested, game.moves.size()));
        step = next;
        shownPosition = GoSgf.position(game, step);
        board.setVariationPreview(null, null, null, null);
        board.setRecommendations(null, null);
        board.setBoard(shownPosition);
        Move last = step > 0 ? game.moves.get(step - 1) : null;
        board.setLastMove(last instanceof Move.Stone ? ((Move.Stone) last).point : null);
        boardMeta.setText(game.size + "×" + game.size + " · 贴目 " + game.komi + "\n第 " + step
                + "/" + game.moves.size() + " 手");
        captures.setText("黑提子 " + shownPosition.getCapturedWhite()
                + "  白提子 " + shownPosition.getCapturedBlack());
        chart.selectStep(step);
        StepAnalysis saved = analyzed[step];
        if (saved == null) {
            blackWin.setText("--");
            lead.setText("待分析");
            winProgress.setProgress(0);
            showPlaceholder("本手尚未分析");
            requestQuickAnalysis();
        } else showAnalysis(saved);
        if (variationTab) status.setText("变化图：点击下方推荐着法预览 PV");
    }

    /** Display only one compact detail panel so analysis fits in one screen. */
    private void selectDetailTab(boolean showTrends) {
        trendsVisible = showTrends;
        trendPanel.setVisibility(showTrends ? View.VISIBLE : View.GONE);
        movesPanel.setVisibility(showTrends ? View.GONE : View.VISIBLE);
        trendTabButton.setBackgroundResource(showTrends ? R.drawable.jade_dark_action
                : R.drawable.jade_dark_card);
        movesTabButton.setBackgroundResource(showTrends ? R.drawable.jade_dark_card
                : R.drawable.jade_dark_action);
        trendTabButton.setAlpha(showTrends ? 1f : .75f);
        movesTabButton.setAlpha(showTrends ? .75f : 1f);
    }

    private void selectTab(boolean variation) {
        variationTab = variation;
        if (variation) selectDetailTab(false);
        quickTabButton.setAlpha(variation ? .55f : 1f);
        variationTabButton.setAlpha(variation ? 1f : .55f);
        quickTabButton.setBackgroundResource(variation ? R.drawable.jade_dark_card
                : R.drawable.jade_dark_action);
        variationTabButton.setBackgroundResource(variation ? R.drawable.jade_dark_action
                : R.drawable.jade_dark_card);
        if (!variation) {
            board.setVariationPreview(null, null, null, null);
            StepAnalysis saved = game == null ? null : analyzed[step];
            if (saved != null) showAnalysis(saved);
        } else status.setText("变化图：点击下方推荐着法预览 AI 变化");
    }

    private void showPlaceholder(String text) {
        candidates.removeAllViews();
        TextView message = makeText(text, 12, 0xFFD4DBC7, false);
        message.setGravity(Gravity.CENTER_VERTICAL);
        message.setPadding(dp(8), dp(8), dp(8), dp(8));
        candidates.addView(message);
    }

    private void showAnalysis(StepAnalysis result) {
        KataGoEngine.PositionEvaluation estimate = result.evaluation;
        if (estimate != null) {
            blackWin.setText(String.format(Locale.CHINA, "%.1f%%", estimate.blackWin * 100));
            winProgress.setProgress((int) Math.round(estimate.blackWin * 1000));
            lead.setText(leadText(-estimate.whiteLead));
            // Count only confidently owned EMPTY points. This is an NN
            // territory estimate, not an official final-score calculation.
            int blackEmpty = 0, whiteEmpty = 0;
            float[] ownership = estimate.whiteOwnership;
            if (ownership != null && ownership.length == game.size * game.size) {
                for (int y = 0; y < game.size; y++) {
                    for (int x = 0; x < game.size; x++) {
                        if (shownPosition.get(x, y) != Intersection.EMPTY) continue;
                        float white = ownership[y * game.size + x];
                        if (white <= -0.55f) blackEmpty++;
                        else if (white >= 0.55f) whiteEmpty++;
                    }
                }
            }
            captures.setText("黑估空 " + blackEmpty + "  白估空 " + whiteEmpty
                    + "\n黑提子 " + shownPosition.getCapturedWhite()
                    + "  白提子 " + shownPosition.getCapturedBlack());
        } else {
            blackWin.setText("--");
            lead.setText("未取得评估");
            winProgress.setProgress(0);
        }
        candidates.removeAllViews();
        int count = 0;
        List<Point> markerPoints = new ArrayList<>();
        List<Float> markerScores = new ArrayList<>();
        final StoneColor player = nextPlayer(step);
        for (KataGoEngine.SearchRecommendation candidate : result.recommendations) {
            Point point = Point.fromGtp(candidate.move, game.size);
            if (point == null || !shownPosition.isLegalMove(point, player)
                    || candidate.visits <= 0) continue;
            final int ranking = ++count;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(3), dp(1), dp(3), dp(1));
            TextView rank = makeText(ranking == 1 ? "①" : ranking == 2 ? "②" : "③",
                    19, ranking == 1 ? 0xFFFFD681 : 0xFFD4D6D1, true);
            rank.setGravity(Gravity.CENTER);
            row.addView(rank, new LinearLayout.LayoutParams(dp(27), dp(31)));
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_board_size);
            row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout texts = new LinearLayout(this);
            texts.setPadding(dp(5), 0, dp(2), 0);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(makeText(candidate.move, 13, 0xFFF9E9BE, true));
            texts.addView(makeText("搜索 " + candidate.visits + " 次 · 点击查看变化",
                    9, 0xFFB7D3C0, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            double blackCandidateWin = player == StoneColor.BLACK
                    ? candidate.winrate : 1.0 - candidate.winrate;
            double blackCandidateLead = player == StoneColor.BLACK
                    ? candidate.scoreLead : -candidate.scoreLead;
            markerPoints.add(point);
            markerScores.add((float) candidate.winrate);
            LinearLayout numeric = new LinearLayout(this);
            numeric.setOrientation(LinearLayout.VERTICAL);
            numeric.setGravity(Gravity.END);
            TextView win = makeText(String.format(Locale.CHINA, "%.1f%%", blackCandidateWin * 100),
                    12, 0xFFF7D681, true);
            win.setGravity(Gravity.END);
            numeric.addView(win);
            TextView score = makeText(leadText(blackCandidateLead), 10, 0xFFC7D7C9, false);
            score.setGravity(Gravity.END);
            numeric.addView(score);
            row.addView(numeric, new LinearLayout.LayoutParams(dp(73), -2));
            row.setOnClickListener(v -> previewVariation(candidate, player));
            row.setContentDescription("推荐着法 " + candidate.move + "，查看变化图");
            candidates.addView(row, new LinearLayout.LayoutParams(-1, dp(34)));
            if (count >= 3) break;
            View divider = new View(this);
            divider.setBackgroundColor(0x58758A6C);
            candidates.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        if (count == 0) {
            board.setRecommendations(null, null);
            showPlaceholder("当前局面没有有效推荐着法");
        } else {
            Point[] points = markerPoints.toArray(new Point[0]);
            float[] values = new float[markerScores.size()];
            for (int i = 0; i < values.length; i++) values[i] = markerScores.get(i);
            board.setRecommendations(points, values);
        }
        status.setText("第 " + step + " 手 · 10b 实际评估 · "
                + (count > 0 ? count + " 条搜索候选" : "暂无搜索候选"));
    }

    private String leadText(double blackLead) {
        if (!Double.isFinite(blackLead)) return "--";
        return String.format(Locale.CHINA, "%s %.1f", blackLead >= 0 ? "黑 +" : "白 +",
                Math.abs(blackLead));
    }

    private TextView makeText(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private void previewVariation(KataGoEngine.SearchRecommendation candidate, StoneColor next) {
        if (shownPosition == null) return;
        GoBoard copy = shownPosition.copyForPreview();
        List<Point> points = new ArrayList<>();
        List<StoneColor> colors = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        StoneColor turn = next;
        int sequence = 0;
        for (String move : candidate.pvMoves) {
            if (sequence >= 8 || copy.isGameOver()) break;
            sequence++;
            if ("pass".equalsIgnoreCase(move)) {
                copy.playMove(new Move.Pass(turn));
            } else {
                Point point = Point.fromGtp(move, game.size);
                if (point == null || !copy.isLegalMove(point, turn)) break;
                copy.playMove(new Move.Stone(point, turn));
                points.add(point);
                colors.add(turn);
                numbers.add(sequence);
            }
            turn = turn.opposite();
        }
        if (points.isEmpty()) {
            Toast.makeText(this, "该候选没有可用的变化序列", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] indices = new int[numbers.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = numbers.get(i);
        board.setVariationPreview(copy, points.toArray(new Point[0]),
                colors.toArray(new StoneColor[0]), indices);
        selectTab(true);
        status.setText("变化图：" + candidate.move + " · AI 推演 " + sequence + " 手（预览，不落子）");
        ((android.widget.ScrollView) findViewById(R.id.analysisContentScroll))
                .smoothScrollTo(0, 0);
    }

    private void requestQuickAnalysis() {
        if (game == null) return;
        final int targetStep = step;
        final int serial = generation.incrementAndGet();
        if (globalRunning) {
            globalRunning = false;
            fullButton.setText("分析全局");
        }
        status.setText("第 " + targetStep + " 手 · KataGo 正在分析…");
        analysisExecutor.execute(() -> {
            try {
                if (!alive || serial != generation.get()) return;
                preparePosition(targetStep);
                if (!alive || serial != generation.get()) return;
                KataGoEngine.PositionEvaluation estimate = engine.evaluatePosition(game.size);
                if (!alive || serial != generation.get()) return;
                List<KataGoEngine.SearchRecommendation> raw =
                        engine.searchRecommendations(nextPlayer(targetStep).toGtp(), 96, 8.0);
                if (!alive || serial != generation.get()) return;
                StepAnalysis result = new StepAnalysis(estimate, raw);
                ui.post(() -> {
                    if (!alive || serial != generation.get() || step != targetStep) return;
                    analyzed[targetStep] = result;
                    if (estimate != null) {
                        rates[targetStep] = (float) estimate.blackWin;
                        chart.setData(rates, step);
                    }
                    showAnalysis(result);
                });
            } catch (Exception ex) {
                Log.e(TAG, "Quick analysis failed at step " + targetStep, ex);
                ui.post(() -> {
                    if (alive && serial == generation.get())
                        status.setText("第 " + targetStep + " 手分析失败：" + ex.getMessage());
                });
            }
        });
    }

    private void initEngine() {
        if (engineReady) return;
        if (engine == null)
            engine = new KataGoEngine(getApplicationContext(), "engine_winrate");
        // Uses the separate 10b/CPU GTP process, not MainActivity's running engine.
        engine.setStartupBoardSize(game.size);
        if (!engine.start(KataGoEngine.Model.HUMAN))
            throw new IllegalStateException("10b 引擎无法启动，请检查模型与安装包");
        if (!engine.setBoardSize(game.size) || !engine.clearBoard()
                || !engine.setChineseRules() || !engine.setKomi(game.komi))
            throw new IllegalStateException("KataGo 棋盘与规则初始化失败");
        engineReady = true;
    }

    private void preparePosition(int index) {
        initEngine();
        if (!engine.clearBoard() || !engine.setKomi(game.komi))
            throw new IllegalStateException("无法清空分析棋盘");
        if (!game.handicap.isEmpty()
                && !engine.setHandicapStones(game.handicap, game.size))
            throw new IllegalStateException("无法设置让子");
        for (int i = 0; i < index; i++) {
            Move move = game.moves.get(i);
            String vertex = move instanceof Move.Stone
                    ? ((Move.Stone) move).point.toGtp(game.size) : "pass";
            if (!engine.playMove(move.color.toGtp(), vertex))
                throw new IllegalStateException("无法重放第 " + (i + 1) + " 手");
        }
    }

    /** Analyze every SGF position with REAL raw 10b NN outputs, not fabricated chart points. */
    private void startFullAnalysis() {
        if (game == null || globalRunning) return;
        stopAutoplay();
        final int serial = generation.incrementAndGet();
        globalRunning = true;
        fullButton.setText("停止分析");
        status.setText("逐手计算真实胜率，可能需要一些时间…");
        analysisExecutor.execute(() -> {
            try {
                preparePosition(0);
                for (int i = 0; i <= game.moves.size(); i++) {
                    if (!alive || serial != generation.get()) return;
                    if (i > 0) {
                        Move move = game.moves.get(i - 1);
                        String vertex = move instanceof Move.Stone
                                ? ((Move.Stone) move).point.toGtp(game.size) : "pass";
                        if (!engine.playMove(move.color.toGtp(), vertex))
                            throw new IllegalStateException("无法重放第 " + i + " 手");
                    }
                    KataGoEngine.PositionEvaluation value = engine.evaluatePosition(game.size);
                    final int index = i;
                    final float rate = value == null ? Float.NaN : (float) value.blackWin;
                    ui.post(() -> {
                        if (!alive || serial != generation.get()) return;
                        rates[index] = rate;
                        if (index % 3 == 0 || index == game.moves.size()) chart.setData(rates, step);
                        status.setText("逐手胜率分析：" + index + " / " + game.moves.size() + " 手");
                    });
                }
                ui.post(() -> {
                    if (!alive || serial != generation.get()) return;
                    globalRunning = false;
                    fullButton.setText("重新分析");
                    chart.setData(rates, step);
                    status.setText("逐手分析完成 · 点击曲线查看对应棋局");
                    requestQuickAnalysis();
                });
            } catch (Exception ex) {
                Log.e(TAG, "Full SGF analysis failed", ex);
                ui.post(() -> {
                    if (!alive || serial != generation.get()) return;
                    globalRunning = false;
                    fullButton.setText("重新分析");
                    status.setText("逐手分析中断：" + ex.getMessage());
                });
            }
        });
    }

    @Override protected void onDestroy() {
        alive = false;
        generation.incrementAndGet();
        stopAutoplay();
        analysisExecutor.execute(() -> { if (engine != null) engine.stop(); });
        analysisExecutor.shutdown();
        super.onDestroy();
    }
}
