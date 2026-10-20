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
import android.widget.SeekBar;
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
    public static final String EXTRA_SELECTED_SGF_FILE = "selected_sgf_file";
    public static final String EXTRA_START_STEP = "ai_analysis_start_step";
    private static final int MAX_SGF_BYTES = 2_000_000;
    private static final int EXPORT_SGF_REQUEST = 503;

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
    private String currentSgf;
    // Null means the SGF does not identify which side belongs to the user.
    private StoneColor humanSide;
    private KataGoEngine engine;
    private boolean engineReady;
    private GoBoard shownPosition;
    private final List<KataGoEngine.SearchRecommendation> visibleRecommendations =
            new ArrayList<>();

    private GoBoardView board;
    private JadeAnalysisChartView chart;
    private LinearLayout candidates;
    private TextView blackWin, lead, captures, boardMeta, status, moveLabel;
    private TextView winTitle, sideLabel;
    private ProgressBar winProgress;
    private SeekBar moveSeek;
    private Button fullButton, playButton;
    private Button trendTabButton, movesTabButton;
    private View trendPanel, movesPanel;
    private final Runnable autoplayTick = new Runnable() {
        @Override public void run() {
            if (!alive || !autoplay || game == null) return;
            if (step >= game.moves.size()) {
                stopAutoplay();
                analyzeSelectedStep();
                return;
            }
            // Playback is responsive even when the CPU analysis engine is busy.
            // Analyze the final selected position after playback stops.
            showStep(step + 1, false);
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
        // Use all the available width unless the viewport genuinely lacks
        // height. The fixed bottom navigation always remains visible.
        com.badukai.ui.BoardViewportFitter.attach(
                findViewById(R.id.analysisContentScroll),
                findViewById(R.id.analysisBoardFrame));
        board = findViewById(R.id.analysisBoard);
        board.setInputEnabled(false);
        board.setOnRecommendationClickListener(rank -> {
            if (game == null || rank < 1 || rank > visibleRecommendations.size()) return;
            previewVariation(visibleRecommendations.get(rank - 1), nextPlayer(step));
        });
        chart = findViewById(R.id.analysisChart);
        candidates = findViewById(R.id.analysisCandidateList);
        blackWin = findViewById(R.id.analysisBlackWin);
        winTitle = findViewById(R.id.analysisWinTitle);
        sideLabel = findViewById(R.id.analysisHumanSide);
        sideLabel.setOnClickListener(v -> selectHumanSide());
        lead = findViewById(R.id.analysisLead);
        captures = findViewById(R.id.analysisCaptures);
        boardMeta = findViewById(R.id.analysisBoardMeta);
        status = findViewById(R.id.analysisStatus);
        winProgress = findViewById(R.id.analysisWinProgress);
        fullButton = findViewById(R.id.analysisFullButton);
        playButton = findViewById(R.id.analysisPlay);
        moveSeek = findViewById(R.id.analysisMoveSeek);
        moveLabel = findViewById(R.id.analysisMoveLabel);
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
        findViewById(R.id.analysisExport).setOnClickListener(v -> exportSgf());
        findViewById(R.id.analysisShare).setOnClickListener(v -> shareSgf());
        findViewById(R.id.analysisPrevious).setOnClickListener(v -> {
            stopAutoplay();
            showStep(step - 1);
        });
        findViewById(R.id.analysisNext).setOnClickListener(v -> {
            stopAutoplay();
            showStep(step + 1);
        });
        findViewById(R.id.analysisCurrent).setOnClickListener(v -> {
            stopAutoplay();
            selectTab(false);
            showStep(step);
        });
        moveSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seek) {
                stopAutoplay();
            }
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                // Display the requested SGF position immediately while dragging.
                // Run the expensive search only once, when the finger is released.
                if (fromUser) showStep(progress, false);
            }
            @Override public void onStopTrackingTouch(SeekBar seek) {
                analyzeSelectedStep();
            }
        });
        playButton.setOnClickListener(v -> {
            if (game == null) return;
            if (autoplay) {
                stopAutoplay();
                analyzeSelectedStep();
            } else {
                if (step >= game.moves.size()) showStep(0, false);
                autoplay = true;
                playButton.setText("Ⅱ 暂停");
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
            humanSide = game.humanColor;
            updateHumanSideUI();
            // Validate every move before running the native engine.
            GoSgf.position(game, game.moves.size());
            currentSgf = sgf;
            rates = new float[game.moves.size() + 1];
            Arrays.fill(rates, Float.NaN);
            analyzed = new StepAnalysis[rates.length];
            moveSeek.setMax(game.moves.size());
            moveSeek.setEnabled(true);
            int initial = getIntent().getIntExtra(EXTRA_START_STEP, game.moves.size());
            showStep(Math.max(0, Math.min(initial, game.moves.size())));
        } catch (Exception error) {
            Log.e(TAG, "SGF load error", error);
            status.setText("无法打开棋谱：" + error.getMessage());
            showPlaceholder("暂无棋谱，请从棋谱中心选择棋局");
            fullButton.setEnabled(false);
            moveSeek.setEnabled(false);
        }
    }

    private void exportSgf() {
        if (currentSgf == null || game == null) {
            Toast.makeText(this, "请先打开有效棋谱", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/x-go-sgf");
        save.putExtra(Intent.EXTRA_TITLE,
                "badukai_" + game.size + "x" + game.size + ".sgf");
        startActivityForResult(save, EXPORT_SGF_REQUEST);
    }

    private void shareSgf() {
        if (currentSgf == null) {
            Toast.makeText(this, "请先打开有效棋谱", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "围棋棋谱 SGF");
        send.putExtra(Intent.EXTRA_TEXT, currentSgf);
        startActivity(Intent.createChooser(send, "分享棋谱"));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_SGF_REQUEST || result != RESULT_OK
                || data == null || data.getData() == null || currentSgf == null) return;
        try (java.io.OutputStream output =
                     getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new java.io.IOException("无法写入目标文件");
            output.write(currentSgf.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "SGF 已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "导出失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
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
            String archiveName = sourceIntent.getStringExtra(EXTRA_SELECTED_SGF_FILE);
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
        if (playButton != null) playButton.setText("▶ 自动播放");
    }

    private void selectHumanSide() {
        if (game == null) return;
        // Imported and older SGFs cannot reliably identify the user.
        // Choosing here only changes the analysis presentation of this game.
        new android.app.AlertDialog.Builder(this)
                .setTitle("这盘棋你执哪一方？")
                .setItems(new String[]{"执黑（对手执白）", "执白（对手执黑）",
                        "执子未知（显示黑方胜率）"}, (dialog, which) -> {
                    humanSide = which == 0 ? StoneColor.BLACK
                            : which == 1 ? StoneColor.WHITE : null;
                    updateHumanSideUI();
                })
                .show();
    }

    /** The engine and stored chart samples remain black-oriented. */
    private double toDisplayedRate(double blackRate) {
        return humanSide == StoneColor.WHITE ? 1.0 - blackRate : blackRate;
    }

    private void refreshChart() {
        float[] visible = new float[rates.length];
        for (int i = 0; i < rates.length; i++) {
            visible[i] = Float.isFinite(rates[i]) ? (float) toDisplayedRate(rates[i]) : Float.NaN;
        }
        chart.setData(visible, step);
    }

    private void updateHumanSideUI() {
        if (humanSide == null) {
            sideLabel.setText("执子?");
            winTitle.setText("黑方胜率");
            trendTabButton.setText("黑方胜率");
        } else if (humanSide == StoneColor.BLACK) {
            sideLabel.setText("执黑");
            winTitle.setText("我的胜率（黑）");
            trendTabButton.setText("我的胜率");
        } else {
            sideLabel.setText("执白");
            winTitle.setText("我的胜率（白）");
            trendTabButton.setText("我的胜率");
        }
        refreshChart();
        if (game != null && step < analyzed.length && analyzed[step] != null)
            showAnalysis(analyzed[step]);
    }

    private StoneColor nextPlayer(int index) {
        if (index == 0)
            return game.handicap.isEmpty() ? StoneColor.BLACK : StoneColor.WHITE;
        return game.moves.get(index - 1).color.opposite();
    }

    private void showStep(int requested) {
        showStep(requested, true);
    }

    private void showStep(int requested, boolean runAnalysis) {
        if (game == null) return;
        int next = Math.max(0, Math.min(requested, game.moves.size()));
        step = next;
        variationTab = false;
        // Updating SeekBar programmatically does not trigger the user-only
        // scrub path, so this remains safe during autoplay and chart taps.
        if (moveSeek.getProgress() != step) moveSeek.setProgress(step);
        moveLabel.setText("第 " + step + " / " + game.moves.size() + " 手");
        shownPosition = GoSgf.position(game, step);
        board.setVariationPreview(null, null, null, null);
        board.setRecommendations(null, null);
        visibleRecommendations.clear();
        board.setBoard(shownPosition);
        board.setAnalysisMoveNumbers(buildMoveNumbers(step));
        Move nextReal = step < game.moves.size() ? game.moves.get(step) : null;
        board.setActualNextMove(nextReal instanceof Move.Stone
                ? ((Move.Stone) nextReal).point : null);
        Move last = step > 0 ? game.moves.get(step - 1) : null;
        board.setLastMove(last instanceof Move.Stone ? ((Move.Stone) last).point : null);
        boardMeta.setText(game.size + "×" + game.size + "·贴" + game.komi + "\n"
                + step + "/" + game.moves.size() + "手");
        captures.setText("黑提子 " + shownPosition.getCapturedWhite()
                + "  白提子 " + shownPosition.getCapturedBlack());
        chart.selectStep(step);
        StepAnalysis saved = analyzed[step];
        if (saved == null) {
            blackWin.setText("--");
            lead.setText("待分析");
            winProgress.setProgress(0);
            showPlaceholder("第 " + step + " 手 · 等待分析");
            if (runAnalysis) analyzeSelectedStep();
            else status.setText("第 " + step + " 手 · 松开滑条或暂停播放后分析");
        } else showAnalysis(saved);
    }

    /**
     * Assign each surviving stone the SGF move that placed it. Captures,
     * recaptures and reused intersections overwrite old numbers correctly.
     * Handicap stones remain unnumbered because SGF represents them as setup.
     */
    private int[] buildMoveNumbers(int count) {
        int size = game.size;
        int[] moveNumbers = new int[size * size];
        StoneColor[] colors = new StoneColor[moveNumbers.length];
        for (int i = 0; i < count; i++) {
            Move move = game.moves.get(i);
            if (!(move instanceof Move.Stone)) continue;
            Point at = ((Move.Stone) move).point;
            int index = at.y * size + at.x;
            moveNumbers[index] = i + 1;
            colors[index] = move.color;
        }
        for (int i = 0; i < moveNumbers.length; i++) {
            if (moveNumbers[i] == 0) continue;
            Intersection expected = colors[i] == StoneColor.BLACK
                    ? Intersection.BLACK : Intersection.WHITE;
            if (shownPosition.get(i % size, i / size) != expected)
                moveNumbers[i] = 0;
        }
        return moveNumbers;
    }

    private void analyzeSelectedStep() {
        if (game == null || analyzed[step] != null) return;
        if (globalRunning) {
            status.setText("逐手分析运行中 · 可继续查看任意手");
            return;
        }
        requestQuickAnalysis();
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
        if (variation) {
            selectDetailTab(false);
            status.setText("变化预览 · 点击「返回本手」恢复原始棋盘");
        } else {
            board.setVariationPreview(null, null, null, null);
            StepAnalysis saved = game == null ? null : analyzed[step];
            if (saved != null) showAnalysis(saved);
            else if (game != null) analyzeSelectedStep();
        }
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
            double visibleWin = toDisplayedRate(estimate.blackWin);
            blackWin.setText(String.format(Locale.CHINA, "%.1f%%", visibleWin * 100));
            winProgress.setProgress((int) Math.round(visibleWin * 1000));
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
        visibleRecommendations.clear();
        int count = 0;
        List<Point> markerPoints = new ArrayList<>();
        List<Float> markerScores = new ArrayList<>();
        final StoneColor player = nextPlayer(step);
        for (KataGoEngine.SearchRecommendation candidate : result.recommendations) {
            Point point = Point.fromGtp(candidate.move, game.size);
            if (point == null || !shownPosition.isLegalMove(point, player)
                    || candidate.visits <= 0) continue;
            final int ranking = ++count;
            visibleRecommendations.add(candidate);
            markerPoints.add(point);
            markerScores.add((float) candidate.winrate);
            double blackCandidateWin = player == StoneColor.BLACK
                    ? candidate.winrate : 1.0 - candidate.winrate;
            double visibleCandidateWin = humanSide == StoneColor.WHITE
                    ? 1.0 - blackCandidateWin : blackCandidateWin;
            double blackCandidateLead = player == StoneColor.BLACK
                    ? candidate.scoreLead : -candidate.scoreLead;

            // All fields in a SINGLE line: rank, GTP, visits, win rate, lead, arrow.
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(3), 0, dp(3), 0);
            TextView rank = makeText(ranking == 1 ? "①" : ranking == 2 ? "②" : "③",
                    18, ranking == 1 ? 0xFFFFD681 : 0xFFD4D6D1, true);
            rank.setGravity(Gravity.CENTER);
            row.addView(rank, new LinearLayout.LayoutParams(dp(25), -1));

            TextView moveText = makeText(candidate.move, 14, 0xFFF9E9BE, true);
            moveText.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(moveText, new LinearLayout.LayoutParams(dp(46), -1));

            TextView visitText = makeText("搜索" + candidate.visits + "次",
                    10, 0xFFB7D3C0, false);
            visitText.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(visitText, new LinearLayout.LayoutParams(0, -1, 1f));

            TextView winText = makeText(String.format(Locale.CHINA, "%.1f%%",
                    visibleCandidateWin * 100), 12, 0xFFF7D681, true);
            winText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            row.addView(winText, new LinearLayout.LayoutParams(dp(63), -1));

            TextView scoreText = makeText(leadText(blackCandidateLead),
                    10, 0xFFC7D7C9, false);
            scoreText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            row.addView(scoreText, new LinearLayout.LayoutParams(dp(63), -1));

            TextView arrow = makeText("›", 17, 0xFFFFDA9E, false);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(15), -1));
            row.setOnClickListener(v -> previewVariation(candidate, player));
            row.setContentDescription("推荐" + ranking + "，" + candidate.move
                    + "，点击查看变化");
            candidates.addView(row, new LinearLayout.LayoutParams(-1, dp(26)));
            if (count >= 3) break;
            View separator = new View(this);
            separator.setBackgroundColor(0x58758A6C);
            candidates.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
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
        Move nextMove = step < game.moves.size() ? game.moves.get(step) : null;
        String actual = nextMove instanceof Move.Stone
                ? " · 金「实」=实战第" + (step + 1) + "手"
                : nextMove instanceof Move.Pass ? " · 实战下一手停一手" : "";
        status.setText("绿①②③=AI推荐（点数字看变化）" + actual);
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
                        refreshChart();
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
                        if (index % 3 == 0 || index == game.moves.size()) refreshChart();
                        status.setText("逐手胜率分析：" + index + " / " + game.moves.size() + " 手");
                    });
                }
                ui.post(() -> {
                    if (!alive || serial != generation.get()) return;
                    globalRunning = false;
                    fullButton.setText("重新分析");
                    refreshChart();
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
