package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.engine.KataGoOpenCLProbe;
import com.badukai.engine.KataGoNative;
import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.WinRateChartView;
import com.badukai.ui.TencentHomeScaler;
import com.badukai.util.DebugLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService winRateExecutor = Executors.newSingleThreadExecutor();
    private volatile long winRateSession;
    // Accessed only on winRateExecutor, whose GTP process is independent of the playing engine.
    private boolean winRateSynchronized;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KataGoEngine engine;
    private KataGoEngine winRateEngine;
    private GoBoard board = new GoBoard(19);
    private StoneColor playerColor = StoneColor.BLACK;
    private StoneColor currentPlayer = StoneColor.BLACK;
    private int boardSize = 19;
    private int aiKyu = 12;
    // 0=even game, 1=black plays first without komi, 2..9=fixed black handicap stones.
    private int gameCondition = 0;
    // Human SL selects moves from its rank profile; search primarily assists pass/resign decisions.
    // Start with 8 visits for responsiveness; keep rank selection independent of this limit.
    private int searchVisits = 8;
    private double searchTime = 8.0;
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
    private boolean evaluating;
    private boolean gameReady;
    private String gameResultText;
    private String finalScoreText;
    private Point lastMove;
    private final List<KataGoEngine.SearchRecommendation> recommendationChoices = new ArrayList<>();
    private Point[] recommendationPoints;
    private GoBoard variationBoard;
    private int variationRank = -1;
    private String recommendationSummary;
    private final List<Float> blackWinHistory = new ArrayList<>();
    private final List<Float> whiteWinHistory = new ArrayList<>();
    private long winRateEpoch;

    private View mainPageContainer;
    private View gamePageContainer;
    private GoBoardView boardView;
    private TextView statusText;
    private TextView aiWinRateText;
    private TextView playerWinRateText;
    private TextView aiCaptureText;
    private TextView playerCaptureText;
    private View aiStoneView;
    private View playerStoneView;
    private TextView gameTitleText;
    private TextView aiDifficultyText;
    private Button aiBattleButton;
    private Button newGameButton;
    private Button backButton;
    private Button undoButton;
    private Button passButton;
    private Button resignButton;
    private Button variationButton;
    private Button newRoundButton;
    private Button situationButton;
    private Button aiSuggestionButton;
    private Button countTerritoryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DebugLog.enter(TAG, "onCreate in, savedInstanceState=" + savedInstanceState);
        super.onCreate(savedInstanceState);
        // Explicit ADB intent extra only; ordinary APP starts never run the probe.
        if (getIntent() != null && getIntent().getBooleanExtra("katago_probe", false)) {
            new Thread(() -> Log.i("KataGoOpenCLProbe", KataGoOpenCLProbe.check()),
                    "KataGo-OpenCL-Probe").start();
        }
        if (getIntent() != null && getIntent().getBooleanExtra("katago_jni", false)) {
            new Thread(() -> Log.i("KataGoJniCore", KataGoNative.checkCoreLinkage()),
                    "KataGo-JNI-Core").start();
        }
        final boolean jniGtpSmoke = getIntent() != null && getIntent().getBooleanExtra("katago_gtp", false);
        if (jniGtpSmoke) new Thread(this::runJniGtpSmoke, "KataGo-JNI-GTP").start();
        setContentView(R.layout.activity_main);
        engine = new KataGoEngine(getApplicationContext());
        winRateEngine = new KataGoEngine(getApplicationContext(), "engine_winrate");
        bindViews();
        TencentHomeScaler.install((ViewGroup) mainPageContainer);
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        aiWinRateText.setOnClickListener(v -> showWinRateChart());
        playerWinRateText.setOnClickListener(v -> showWinRateChart());
        aiBattleButton.setOnClickListener(v -> showNewGameDialog());
        newGameButton.setOnClickListener(v -> showNewGameDialog());
        backButton.setOnClickListener(v -> showMainPage());
        undoButton.setOnClickListener(v -> undo());
        passButton.setOnClickListener(v -> pass());
        resignButton.setOnClickListener(v -> resign());
        newRoundButton.setOnClickListener(v -> showNewGameDialog());
        situationButton.setOnClickListener(v -> showSituation());
        aiSuggestionButton.setOnClickListener(v -> showAiSuggestions());
        countTerritoryButton.setOnClickListener(v -> Toast.makeText(this, "数目功能待实现", Toast.LENGTH_SHORT).show());
        variationButton.setOnClickListener(v -> showVariations());
        render("正在启动 AI...");
        showMainPage();
        if (!jniGtpSmoke) startEngine();
    }

    private void runJniGtpSmoke() {
        final String tag = "KataGoJniGtp";
        try {
            File dir = new File(getFilesDir(), "jni_gtp");
            File logs = new File(dir, "gtp_logs");
            File home = new File(dir, "home");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create " + dir);
            logs.mkdirs();
            home.mkdirs();
            File model = new File(dir, "10b.bin");
            if (!model.isFile() || model.length() != 12003218L) {
                try (InputStream src = getAssets().open("engine/10b.bin");
                     FileOutputStream dst = new FileOutputStream(model)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = src.read(buf)) != -1) dst.write(buf, 0, n);
                }
            }
            File configFile = new File(dir, "default_gtp.cfg");
            String config;
            try (InputStream in = getAssets().open("engine/default_gtp.cfg")) {
                byte[] data = new byte[32768];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(data)) != -1) out.write(data, 0, n);
                config = out.toString("UTF-8");
            }
            config = config.replace("logDir = gtp_logs", "logDir = " + logs.getAbsolutePath());
            config += "\nhomeDataDir = " + home.getAbsolutePath() + "\n";
            Files.write(configFile.toPath(), config.getBytes(StandardCharsets.UTF_8));

            Log.i(tag, "Starting JNI CPU/Eigen GTP session");
            try (KataGoNative.GtpSession session = KataGoNative.createSession(model, configFile)) {
                if (session == null) throw new IllegalStateException("createSession returned null");
                StringBuilder responseBuffer = new StringBuilder();
                String[] commands = {"name", "boardsize 9", "komi 7.5", "play B D4",
                                     "genmove W", "undo", "clear_board"};
                for (String command : commands) {
                    if (!session.send(command)) throw new IllegalStateException("send failed: " + command);
                    long deadline = android.os.SystemClock.uptimeMillis() + 90000;
                    String response = null;
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        int end = responseBuffer.indexOf("\n\n");
                        if (end >= 0) {
                            response = responseBuffer.substring(0, end).trim();
                            responseBuffer.delete(0, end + 2);
                            break;
                        }
                        String chunk = session.read(1000);
                        if (chunk == null) throw new IllegalStateException("JNI GTP ended before " + command);
                        responseBuffer.append(chunk.replace("\r\n", "\n"));
                    }
                    if (response == null || !response.startsWith("="))
                        throw new IllegalStateException("GTP " + command + " failed: " + response);
                    Log.i(tag, command + " -> " + response.replace('\n', ' ').substring(0, Math.min(140, response.length())));
                }
                Log.i(tag, "PASS: JNI GTP name/boardsize/komi/play/genmove/undo/clear_board");
            }
        } catch (Exception | LinkageError e) {
            Log.e(tag, "FAIL: JNI GTP smoke test", e);
        }
    }

    private void bindViews() {
        DebugLog.enter(TAG, "bindViews in");
        mainPageContainer = findViewById(R.id.mainPageContainer);
        gamePageContainer = findViewById(R.id.gamePageContainer);
        boardView = findViewById(R.id.boardView);
        statusText = findViewById(R.id.statusText);
        aiWinRateText = findViewById(R.id.aiWinRateText);
        playerWinRateText = findViewById(R.id.playerWinRateText);
        aiCaptureText = findViewById(R.id.aiCaptureText);
        playerCaptureText = findViewById(R.id.playerCaptureText);
        aiStoneView = findViewById(R.id.aiStoneView);
        playerStoneView = findViewById(R.id.playerStoneView);
        gameTitleText = findViewById(R.id.gameTitleText);
        aiDifficultyText = findViewById(R.id.aiDifficultyText);
        aiBattleButton = findViewById(R.id.aiBattleButton);
        newGameButton = findViewById(R.id.newGameButton);
        backButton = findViewById(R.id.backButton);
        undoButton = findViewById(R.id.undoButton);
        passButton = findViewById(R.id.passButton);
        resignButton = findViewById(R.id.resignButton);
        variationButton = findViewById(R.id.variationButton);
        newRoundButton = findViewById(R.id.newRoundButton);
        situationButton = findViewById(R.id.situationButton);
        aiSuggestionButton = findViewById(R.id.aiSuggestionButton);
        countTerritoryButton = findViewById(R.id.countTerritoryButton);
    }

    private void startEngine() {
        DebugLog.enter(TAG, "startEngine in, engineStarting=" + engineStarting + ", engineReady=" + engineReady + ", boardSize=" + boardSize);
        if (engineStarting || engineReady) return;
        engineStarting = true;
        aiBattleButton.setEnabled(false);
        updateButtons();
        engineExecutor.execute(() -> {
            long started = System.nanoTime();
            boolean ok = false;
            boolean humanPreloaded = false;
            if (engine.hasHumanModel()) {
                try {
                    // Preload once on the home screen. New games then reuse this process.
                    engine.prepareHumanModel(null);
                    ok = engine.start(KataGoEngine.Model.HUMAN, true);
                    humanPreloaded = ok;
                } catch (Exception e) {
                    Log.e(TAG, "Human SL preload failed; new game will retry", e);
                }
            }
            if (!ok) {
                engine.stop();
                ok = engine.start(KataGoEngine.Model.HUMAN);
            }
            if (ok) {
                ok = engine.setBoardSize(boardSize) && engine.clearBoard()
                        && engine.setChineseRules() && engine.setKomi(komiFor(boardSize));
                if (!ok) engine.stop();
            }
            final boolean ready = ok, preloaded = humanPreloaded;
            Log.i(TAG, "Initial engine ready=" + ready + " humanSL=" + preloaded
                    + " elapsedMs=" + (System.nanoTime() - started) / 1000000L);
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = ready;
                aiBattleButton.setEnabled(true);
                render(ready ? "准备好了" : "AI 启动失败，可重试新局");
            });
        });
    }

    /** Start/reuse a separate 10b engine. This work never enters the gameplay executor. */
    private void initializeWinRateAnalysis(GoBoard snapshot, int size, float komi,
                                           List<Point> handicap, StoneColor nextToPlay) {
        final long session = winRateSession, epoch = winRateEpoch;
        final List<Point> setup = new ArrayList<>(handicap);
        winRateExecutor.execute(() -> {
            if (session != winRateSession) return;
            // Calling start twice on an already-running engine reuses its GTP process.
            boolean ready = winRateEngine.start(KataGoEngine.Model.HUMAN);
            if (ready) ready = winRateEngine.setBoardSize(size);
            if (ready) ready = winRateEngine.clearBoard();
            if (ready) ready = winRateEngine.setChineseRules();
            if (ready) ready = winRateEngine.setKomi(komi);
            if (ready && !setup.isEmpty()) ready = winRateEngine.setHandicapStones(setup, size);
            winRateSynchronized = ready;
            if (!ready) {
                Log.w(TAG, "Independent winrate engine is unavailable; play continues normally");
                return;
            }
            KataGoEngine.WinRate result = winRateEngine.evaluateWinRate(nextToPlay.toGtp());
            mainHandler.post(() -> recordWinRate(snapshot, 0, epoch, result));
        });
    }

    /** Only the analysis process sees this command; it may lag behind the real game safely. */
    private void queueWinRateMove(StoneColor played, String vertex, StoneColor next,
                                  GoBoard snapshot, int moveCount) {
        final long session = winRateSession, epoch = winRateEpoch;
        winRateExecutor.execute(() -> {
            if (session != winRateSession || !winRateSynchronized) return;
            if (!winRateEngine.playMove(played.toGtp(), vertex)) {
                winRateSynchronized = false;
                Log.w(TAG, "Independent winrate engine move sync failed; awaiting next game");
                return;
            }
            KataGoEngine.WinRate result = winRateEngine.evaluateWinRate(next.toGtp());
            mainHandler.post(() -> recordWinRate(snapshot, moveCount, epoch, result));
        });
    }

    private void queueWinRateUndo(GoBoard snapshot, int moveCount) {
        final long session = winRateSession, epoch = winRateEpoch;
        winRateExecutor.execute(() -> {
            if (session != winRateSession || !winRateSynchronized) return;
            boolean ok = winRateEngine.undo();
            ok = winRateEngine.undo() && ok;
            if (!ok) {
                winRateSynchronized = false;
                Log.w(TAG, "Independent winrate engine undo failed; awaiting next game");
                return;
            }
            KataGoEngine.WinRate result = winRateEngine.evaluateWinRate(playerColor.toGtp());
            mainHandler.post(() -> recordWinRate(snapshot, moveCount, epoch, result));
        });
    }

    private void recordWinRate(GoBoard snapshot, int moveCount, long epoch, KataGoEngine.WinRate rate) {
        if (rate == null || snapshot != board || epoch != winRateEpoch || moveCount > board.getMoveCount()) return;
        while (blackWinHistory.size() <= moveCount) {
            blackWinHistory.add(Float.NaN);
            whiteWinHistory.add(Float.NaN);
        }
        blackWinHistory.set(moveCount, rate.black);
        whiteWinHistory.set(moveCount, rate.white);
        updateWinRateLabels();
    }

    private void recordFinalWinRate(GoBoard snapshot, int moveCount, long epoch, String score) {
        if (score == null) return;
        String result = score.trim().toUpperCase(Locale.US);
        float black = result.startsWith("B+") ? 1f : result.startsWith("W+") ? 0f
                : "0".equals(result) ? 0.5f : Float.NaN;
        if (Float.isFinite(black)) recordWinRate(snapshot, moveCount, epoch,
                new KataGoEngine.WinRate(black, 1f - black));
    }

    private void updateWinRateLabels() {
        int turn = board.getMoveCount();
        boolean available = turn < blackWinHistory.size() && Float.isFinite(blackWinHistory.get(turn));
        float black = available ? blackWinHistory.get(turn) : Float.NaN;
        float white = available ? whiteWinHistory.get(turn) : Float.NaN;
        float aiRate = playerColor == StoneColor.BLACK ? white : black;
        float myRate = playerColor == StoneColor.BLACK ? black : white;
        String aiName = playerColor == StoneColor.BLACK ? "白" : "黑";
        String myName = playerColor == StoneColor.BLACK ? "黑" : "白";
        aiWinRateText.setText(Float.isFinite(aiRate)
                ? String.format(Locale.CHINA, "%s胜率 %.1f%%", aiName, aiRate * 100f) : aiName + "胜率 --%");
        playerWinRateText.setText(Float.isFinite(myRate)
                ? String.format(Locale.CHINA, "%s胜率 %.1f%%", myName, myRate * 100f) : myName + "胜率 --%");
    }

    private void showWinRateChart() {
        int samples = Math.max(board.getMoveCount() + 1, blackWinHistory.size());
        float[] black = new float[samples], white = new float[samples];
        for (int i = 0; i < samples; i++) {
            black[i] = i < blackWinHistory.size() ? blackWinHistory.get(i) : Float.NaN;
            white[i] = i < whiteWinHistory.size() ? whiteWinHistory.get(i) : Float.NaN;
        }
        WinRateChartView chart = new WinRateChartView(this);
        chart.setRates(black, white);
        new AlertDialog.Builder(this).setTitle("胜率曲线 · MCTS搜索评估")
                .setView(chart).setPositiveButton("关闭", null).show();
    }

    private void onBoardTap(int x, int y) {
        DebugLog.enter(TAG, "onBoardTap in, x=" + x + ", y=" + y + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        if (!board.isInside(x, y)) return;
        Point point = new Point(x, y);
        if (variationBoard != null) {
            exitVariationPreview();
            return;
        }
        if (!board.isLegalMove(point, currentPlayer)) return;

        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        clearRecommendations();
        board.playMove(new Move.Stone(point, color));
        lastMove = point;
        currentPlayer = color.opposite();
        render("AI 思考中...");

        String gtp = point.toGtp(boardSize);
        final GoBoard snapshot = board;
        final int moveCount = board.getMoveCount();
        engineExecutor.execute(() -> {
            boolean synced = gtp != null && engine.playMove(color.toGtp(), gtp);
            mainHandler.post(() -> {
                if (!synced) {
                    render("落子同步失败");
                    return;
                }
                queueWinRateMove(color, gtp, color.opposite(), snapshot, moveCount);
                requestAiMove();
            });
        });
    }

    private void requestAiMove() {
        DebugLog.enter(TAG, "requestAiMove in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer == playerColor || board.isGameOver()) return;
        thinking = true;
        render("AI 思考中...");
        StoneColor aiColor = currentPlayer;
        engineExecutor.execute(() -> {
            String move = engine.generateMove(aiColor.toGtp());
            mainHandler.post(() -> handleAiMove(move, aiColor));
        });
    }

    private void handleAiMove(String move, StoneColor aiColor) {
        DebugLog.enter(TAG, "handleAiMove in, move=" + move + ", aiColor=" + aiColor);
        thinking = false;
        if (move == null || move.isEmpty()) {
            render("AI 没有返回落子");
            return;
        }
        boardView.setOwnership(null);
        clearRecommendations();
        if ("pass".equalsIgnoreCase(move)) {
            board.playMove(new Move.Pass(aiColor));
            currentPlayer = aiColor.opposite();
            lastMove = null;
            if (board.isGameOver()) finishByScore(null);
            else {
                render("AI 停一手，你下");
                queueWinRateMove(aiColor, "pass", currentPlayer, board, board.getMoveCount());
            }
            return;
        }
        if ("resign".equalsIgnoreCase(move)) {
            board.playMove(new Move.Resign(aiColor));
            finishByResignation("AI认输 · 你赢了");
            return;
        }

        Point point = Point.fromGtp(move, boardSize);
        if (point == null || !board.isInside(point.x, point.y)) {
            render("AI 返回了无效坐标: " + move);
            return;
        }
        board.playMove(new Move.Stone(point, aiColor));
        lastMove = point;
        currentPlayer = aiColor.opposite();
        render("轮到你了");
        queueWinRateMove(aiColor, move, currentPlayer, board, board.getMoveCount());
    }

    private void pass() {
        DebugLog.enter(TAG, "pass in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        clearRecommendations();
        board.playMove(new Move.Pass(color));
        lastMove = null;
        if (board.isGameOver()) {
            render("正在数目...");
            finishByScore(color.toGtp());
            return;
        }
        currentPlayer = color.opposite();
        render("你停一手，AI 思考中...");
        final GoBoard snapshot = board;
        final int moveCount = board.getMoveCount();
        engineExecutor.execute(() -> {
            boolean synced = engine.playMove(color.toGtp(), "pass");
            mainHandler.post(() -> {
                if (synced) {
                    queueWinRateMove(color, "pass", color.opposite(), snapshot, moveCount);
                    requestAiMove();
                } else render("停一手同步失败");
            });
        });
    }

    private void undo() {
        DebugLog.enter(TAG, "undo in, engineReady=" + engineReady + ", thinking=" + thinking + ", moveCount=" + board.getMoveCount());
        if (!engineReady || !gameReady || thinking || evaluating || engineStarting || board.getMoveCount() < 2) return;
        boardView.setOwnership(null);
        clearRecommendations();
        board.undo();
        board.undo();
        winRateEpoch++;
        final GoBoard snapshot = board;
        final int moveCount = board.getMoveCount();
        queueWinRateUndo(snapshot, moveCount);
        while (blackWinHistory.size() > moveCount + 1) {
            blackWinHistory.remove(blackWinHistory.size() - 1);
            whiteWinHistory.remove(whiteWinHistory.size() - 1);
        }
        Move last = board.getLastMove();
        lastMove = last instanceof Move.Stone ? ((Move.Stone) last).point : null;
        currentPlayer = playerColor;
        evaluating = true;
        render("正在同步悔棋...");
        engineExecutor.execute(() -> {
            boolean first = engine.undo();
            boolean second = first && engine.undo();
            mainHandler.post(() -> {
                if (snapshot != board) return;
                evaluating = false;
                render(second ? "已悔棋，轮到你了" : "引擎悔棋同步失败");
            });
        });
    }

    private void resign() {
        DebugLog.enter(TAG, "resign in, thinking=" + thinking + ", moveCount=" + board.getMoveCount() + ", playerColor=" + playerColor);
        if (!gameReady || thinking || board.getMoveCount() == 0 || board.isGameOver()) return;
        boardView.setOwnership(null);
        clearRecommendations();
        board.playMove(new Move.Resign(playerColor));
        String winner = playerColor == StoneColor.BLACK ? "白棋" : "黑棋";
        finishByResignation("你认输了 · " + winner + "胜");
    }

    /** Synchronize a final human pass before scoring and territory estimation. */
    private void finishByScore(String pendingPassColor) {
        DebugLog.enter(TAG, "finishByScore in, pendingPassColor=" + pendingPassColor);
        if (evaluating) return;
        evaluating = true;
        winRateEpoch++;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        final long epoch = winRateEpoch;
        render("正在结算地盘...");
        engineExecutor.execute(() -> {
            boolean synced = pendingPassColor == null || engine.playMove(pendingPassColor, "pass");
            String score = synced ? engine.getFinalScore() : null;
            String deadStones = synced ? engine.getFinalDeadStones() : null;
            KataGoEngine.PositionEvaluation result = synced ? engine.evaluatePosition(size) : null;
            String outcome = formatFinalResult(score);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves) return;
                gameResultText = synced ? outcome : "对局结束 · 同步失败";
                if (result != null) territorySummary(result); // Keep ownership marks.
                recordFinalWinRate(snapshot, moves, epoch, score);
                finalScoreText = synced ? finalPointsSummary(score, deadStones) : "终局数子\n同步失败";
                render(finalScoreText);
            });
        });
    }

    private void finishByResignation(String outcome) {
        DebugLog.enter(TAG, "finishByResignation in, outcome=" + outcome);
        gameResultText = outcome;
        finalScoreText = "认输结束\n未进行数子";
        winRateEpoch++;
        StoneColor winner = outcome.startsWith("AI") ? playerColor : playerColor.opposite();
        float black = winner == StoneColor.BLACK ? 1f : 0f;
        recordWinRate(board, board.getMoveCount(), winRateEpoch, new KataGoEngine.WinRate(black, 1f - black));
        finishEndgameTerritory();
    }

    private void finishEndgameTerritory() {
        if (evaluating) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        render("正在估算地盘...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation result = engine.evaluatePosition(size);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves) return;
                if (result != null) territorySummary(result);
                render(finalScoreText == null ? "认输结束\n未进行数子" : finalScoreText);
            });
        });
    }

    private String formatFinalResult(String score) {
        if (score == null || score.trim().isEmpty()) return "对局结束 · 未得到数目结果";
        String text = score.trim();
        if (text.startsWith("B+")) return "对局结束 · 黑胜 " + text.substring(2);
        if (text.startsWith("W+")) return "对局结束 · 白胜 " + text.substring(2);
        return "对局结束 · " + text;
    }

    /**
     * Chinese area score: stones on board + enclosed empty intersections, after removal
     * of KataGo-predicted dead stones. White receives the configured komi.
     * This is AI-adjudicated scoring, not a manually confirmed tournament result.
     */
    private String finalPointsSummary(String finalScore, String deadVertices) {
        if (finalScore == null || deadVertices == null) return "终局数子\n结果不可用";
        Set<Point> deadStones = new HashSet<>();
        for (String vertex : deadVertices.trim().split("\\s+")) {
            Point point = Point.fromGtp(vertex, boardSize);
            if (point != null && board.get(point) != Intersection.EMPTY) deadStones.add(point);
        }
        GoBoard.FinalScore totals = board.countChineseScore(deadStones, komiFor(boardSize), gameCondition >= 2 ? gameCondition : 0);
        double expectedLead = Double.NaN;
        try {
            String text = finalScore.trim().toUpperCase(Locale.US);
            if ("0".equals(text)) expectedLead = 0.0;
            else if (text.startsWith("W+")) expectedLead = Double.parseDouble(text.substring(2));
            else if (text.startsWith("B+")) expectedLead = -Double.parseDouble(text.substring(2));
        } catch (NumberFormatException e) {
            Log.w(TAG, "KataGo final score does not have a numeric margin: " + finalScore, e);
        }
        double calculatedLead = totals.whitePoints - totals.blackPoints;
        boolean agrees = Double.isFinite(expectedLead) && Math.abs(expectedLead - calculatedLead) < 0.01;
        Log.i(TAG, String.format(Locale.US,
                "Chinese area score B stones=%d territory=%d, W stones=%d territory=%d, "
                        + "dead B=%d W=%d, komi=%.1f, total B=%.1f W=%.1f, lead local=%.1f engine=%.1f agrees=%s",
                totals.blackStones, totals.blackTerritory, totals.whiteStones, totals.whiteTerritory,
                totals.deadBlack, totals.deadWhite, komiFor(boardSize),
                totals.blackPoints, totals.whitePoints, calculatedLead, expectedLead, agrees));
        if (!agrees) Log.w(TAG, "Chinese area score differs from KataGo final_score; these totals are approximate");
        String prefix = agrees ? "" : "约";
        return String.format(Locale.CHINA, "黑%s%.0f目\n白%s%.1f目", prefix, totals.blackPoints, prefix, totals.whitePoints);
    }

    /** Ownership-based estimate of empty points plus projected dead-stone points, not a formal final score. */
    private String territorySummary(KataGoEngine.PositionEvaluation result) {
        int blackTerritory = 0, whiteTerritory = 0, deadBlack = 0, deadWhite = 0, uncertainEmpty = 0;
        final int size = result.size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Intersection stone = board.get(x, y);
                float whiteOwn = result.whiteOwnership[y * size + x];
                if (!Float.isFinite(whiteOwn)) continue;
                if (stone == Intersection.EMPTY) {
                    if (whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) whiteTerritory++;
                    else if (whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) blackTerritory++;
                    else uncertainEmpty++;
                } else if (stone == Intersection.BLACK && whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) {
                    // A black stone likely dies: this point becomes white territory after removal.
                    whiteTerritory++;
                    deadBlack++;
                } else if (stone == Intersection.WHITE && whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) {
                    blackTerritory++;
                    deadWhite++;
                }
            }
        }
        boardView.setOwnership(result.whiteOwnership);
        Log.i(TAG, String.format(Locale.US,
                "Ownership estimate black=%d white=%d predictedDeadBlack=%d predictedDeadWhite=%d uncertainEmpty=%d "
                        + "(threshold %.2f; no komi, prisoner count or official dead-stone confirmation)",
                blackTerritory, whiteTerritory, deadBlack, deadWhite, uncertainEmpty,
                GoBoardView.OWNERSHIP_MARK_THRESHOLD));
        return String.format(Locale.CHINA, "黑估空%d目\n白估空%d目", blackTerritory, whiteTerritory);
    }

    private void clearRecommendations() {
        variationRank = -1;
        variationBoard = null;
        boardView.setVariationPreview(null, null, null, null);
        boardView.setRecommendations(null, null);
        recommendationChoices.clear();
        recommendationPoints = null;
        recommendationSummary = null;
    }

    private void exitVariationPreview() {
        variationRank = -1;
        variationBoard = null;
        boardView.setVariationPreview(null, null, null, null);
        render(recommendationSummary == null ? "轮到你了" : recommendationSummary);
    }

    /** Preview the KataGo principal variation without changing the live game or GTP state. */
    private boolean previewRecommendedVariation(int rank) {
        if (rank < 0 || rank >= recommendationChoices.size()) return false;
        KataGoEngine.SearchRecommendation choice = recommendationChoices.get(rank);
        GoBoard hypothetical = board.copyForPreview();
        List<Point> points = new ArrayList<>();
        List<StoneColor> colors = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        StoneColor turn = currentPlayer;
        int played = 0;
        for (String vertex : choice.pvMoves) {
            if (played >= 8 || hypothetical.isGameOver()) break;
            played++;
            if ("pass".equalsIgnoreCase(vertex)) {
                hypothetical.playMove(new Move.Pass(turn));
                turn = turn.opposite();
                continue;
            }
            Point point = Point.fromGtp(vertex, boardSize);
            if (point == null || !hypothetical.isLegalMove(point, turn)) break;
            hypothetical.playMove(new Move.Stone(point, turn));
            points.add(point);
            colors.add(turn);
            numbers.add(played);
            turn = turn.opposite();
        }
        if (points.isEmpty()) return false;

        Point[] pvPoints = points.toArray(new Point[0]);
        StoneColor[] pvColors = colors.toArray(new StoneColor[0]);
        int[] pvNumbers = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) pvNumbers[i] = numbers.get(i);
        variationBoard = hypothetical;
        variationRank = rank;
        boardView.setVariationPreview(hypothetical, pvPoints, pvColors, pvNumbers);
        String[] labels = {"①", "②", "③"};
        String next = rank + 1 < recommendationChoices.size() ? "再点后续变化看下一条" : "再点后续变化退出";
        render("变化" + labels[rank] + " 共" + played + "手\n" + next);
        return true;
    }

    /**
     * Dedicated variation button: best PV -> second PV -> third PV -> return to the live game.
     * If AI recommendations have not been requested yet, search independently.
     */
    private void showVariations() {
        DebugLog.enter(TAG, "showVariations in, rank=" + variationRank + ", choices=" + recommendationChoices.size());
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating
                || currentPlayer != playerColor || board.isGameOver()) return;

        int nextRank = variationBoard == null ? 0 : variationRank + 1;
        if (variationBoard != null && nextRank >= recommendationChoices.size()) {
            exitVariationPreview();
            return;
        }
        if (!recommendationChoices.isEmpty()) {
            for (int i = nextRank; i < recommendationChoices.size(); i++) {
                if (previewRecommendedVariation(i)) return;
            }
            if (variationBoard != null) exitVariationPreview();
            else Toast.makeText(this, "没有可预览的后续变化", Toast.LENGTH_SHORT).show();
            return;
        }

        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final StoneColor color = currentPlayer;
        render("正在推演后续变化...");
        engineExecutor.execute(() -> {
            List<KataGoEngine.SearchRecommendation> candidates = engine.searchRecommendations(color.toGtp(), 96, 8.0);
            mainHandler.post(() -> {
                evaluating = false;
                if (snapshot != board || moves != board.getMoveCount() || size != boardSize || currentPlayer != color) {
                    render("棋局已变化，请重新分析");
                    return;
                }
                recommendationChoices.clear();
                for (KataGoEngine.SearchRecommendation candidate : candidates) {
                    Point point = Point.fromGtp(candidate.move, size);
                    if (point != null && board.isLegalMove(point, color) && candidate.visits > 0) {
                        recommendationChoices.add(candidate);
                        if (recommendationChoices.size() == 3) break;
                    }
                }
                for (int i = 0; i < recommendationChoices.size(); i++) {
                    if (previewRecommendedVariation(i)) return;
                }
                render("当前没有可预览的变化");
            });
        });
    }

    private void showAiSuggestions() {
        DebugLog.enter(TAG, "showAiSuggestions in, ready=" + gameReady + ", thinking=" + thinking + ", evaluating=" + evaluating);
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating || currentPlayer != playerColor || board.isGameOver()) return;

        if (boardView.hasRecommendations()) {
            clearRecommendations();
            render("推荐标记已隐藏");
            return;
        }

        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final StoneColor color = currentPlayer;
        render("高手搜索中...");
        engineExecutor.execute(() -> {
            // The 10b net performs a bounded MCTS search. The GTP board is not changed.
            List<KataGoEngine.SearchRecommendation> candidates = engine.searchRecommendations(color.toGtp(), 96, 8.0);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size || currentPlayer != color) return;
                if (candidates.isEmpty()) {
                    render("高手推荐失败，请查看日志");
                    return;
                }

                Point[] points = new Point[3];
                float[] winrates = new float[3];
                double[] leads = new double[3];
                int count = 0;
                List<KataGoEngine.SearchRecommendation> selected = new ArrayList<>();
                for (KataGoEngine.SearchRecommendation suggestion : candidates) {
                    Point point = Point.fromGtp(suggestion.move, size);
                    if (point == null || !board.isLegalMove(point, color) || suggestion.visits <= 0) continue;
                    points[count] = point;
                    winrates[count] = (float) suggestion.winrate;
                    leads[count] = suggestion.scoreLead;
                    selected.add(suggestion);
                    Log.i(TAG, String.format(Locale.US,
                            "Expert recommendation rank=%d color=%s point=%s winrate=%.3f lead=%.2f visits=%d",
                            count + 1, color.toGtp(), suggestion.move, suggestion.winrate, suggestion.scoreLead, suggestion.visits));
                    if (++count == 3) break;
                }

                if (count == 0) {
                    render("搜索完成，但没有有效候选点");
                    return;
                }
                Point[] top = new Point[count];
                float[] scores = new float[count];
                System.arraycopy(points, 0, top, 0, count);
                System.arraycopy(winrates, 0, scores, 0, count);
                boardView.setRecommendations(top, scores);
                recommendationPoints = top;
                recommendationChoices.clear();
                recommendationChoices.addAll(selected);
                String[] ranks = {"①", "②", "③"};
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) summary.append('\n');
                    summary.append(ranks[i]).append(top[i].toGtp(size)).append(" 胜")
                            .append(String.format(Locale.CHINA, "%.0f%% %+.1f目", winrates[i] * 100, leads[i]));
                }
                recommendationSummary = summary.toString();
                render(recommendationSummary);
            });
        });
    }

    private void showSituation() {
        DebugLog.enter(TAG, "showSituation in, ready=" + gameReady + ", thinking=" + thinking + ", evaluating=" + evaluating);
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating || (!board.isGameOver() && currentPlayer != playerColor)) {
            Toast.makeText(this, "请等 AI 落子结束再判断形势", Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap cycle: squares -> ownership percentages -> hidden -> squares.
        // Reuse the last neural evaluation while the board position is unchanged.
        if (boardView.hasOwnership()) {
            int nextMode = (boardView.getOwnershipMode() + 1) % 3;
            boardView.setOwnershipMode(nextMode);
            Log.i(TAG, "Situation overlay mode=" + nextMode + " (0=hidden, 1=squares, 2=percent)");
            return;
        }
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        render("正在判断形势...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation result = engine.evaluatePosition(size);
            mainHandler.post(() -> {
                evaluating = false;
                if (snapshot != board || moves != board.getMoveCount() || size != boardSize) {
                    render("棋局已变化，请重新判断形势");
                    return;
                }
                if (result == null) {
                    render("形势判断失败，请查看 KataGo 日志");
                    Toast.makeText(this, "AI 没有返回有效的形势数据", Toast.LENGTH_LONG).show();
                    return;
                }
                String estimate = territorySummary(result);
                render(board.isGameOver() ? (finalScoreText == null ? "终局数子\n结果待确认" : finalScoreText) : estimate);
            });
        });
    }

    private void showNewGameDialog() {
        DebugLog.enter(TAG, "showNewGameDialog in, boardSize=" + boardSize + ", playerColor=" + playerColor);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_new_game, null, false);
        Spinner sizeSpinner = content.findViewById(R.id.sizeSpinner);
        RadioGroup colorGroup = content.findViewById(R.id.colorGroup);
        Spinner difficultySpinner = content.findViewById(R.id.difficultySpinner);
        Spinner conditionSpinner = content.findViewById(R.id.conditionSpinner);
        Button startButton = content.findViewById(R.id.startGameButton);
        Button closeButton = content.findViewById(R.id.closeDialogButton);

        int[] boardSizes = {9, 11, 13, 15, 19};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"9路", "11路", "13路", "15路", "19路"});
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizeAdapter);
        int selectedSize = 4;
        for (int i = 0; i < boardSizes.length; i++) if (boardSizes[i] == boardSize) selectedSize = i;
        sizeSpinner.setSelection(selectedSize);

        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"分先", "让先", "让2子", "让3子", "让4子", "让5子", "让6子", "让7子", "让8子", "让9子"});
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conditionSpinner.setAdapter(conditionAdapter);
        conditionSpinner.setSelection(gameCondition);

        colorGroup.check(playerColor == StoneColor.WHITE ? R.id.whiteRadio : R.id.blackRadio);
        String[] kyuLabels = new String[18];
        for (int i = 0; i < kyuLabels.length; i++) kyuLabels[i] = (18 - i) + "级";
        ArrayAdapter<String> difficultyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, kyuLabels);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySpinner.setAdapter(difficultyAdapter);
        difficultySpinner.setSelection(18 - aiKyu);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());
        startButton.setOnClickListener(v -> {
            boardSize = boardSizes[sizeSpinner.getSelectedItemPosition()];

            int colorId = colorGroup.getCheckedRadioButtonId();
            if (colorId == R.id.whiteRadio) playerColor = StoneColor.WHITE;
            else if (colorId == R.id.randomRadio) playerColor = (System.nanoTime() & 1L) == 0L ? StoneColor.BLACK : StoneColor.WHITE;
            else playerColor = StoneColor.BLACK;

            int selectedKyu = 18 - difficultySpinner.getSelectedItemPosition();
            int selectedCondition = conditionSpinner.getSelectedItemPosition();
            Runnable begin = () -> {
                aiKyu = selectedKyu;
                gameCondition = selectedCondition;
                showGamePage();
                startNewGame();
                dialog.dismiss();
            };

            if (engine.hasHumanModel()) {
                begin.run();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("首次准备棋力模型")
                        .setMessage("18级～1级需要 KataGo Human SL 模型，约 99 MB。首次需联网下载，下载后可离线对弈。现在下载吗？")
                        .setPositiveButton("下载并开始", (confirm, which) -> begin.run())
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = getResources().getDisplayMetrics().widthPixels;
                int maxWidth = (int) (420 * getResources().getDisplayMetrics().density);
                dialog.getWindow().setLayout(Math.min((int) (width * 0.88f), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void showMainPage() {
        DebugLog.enter(TAG, "showMainPage in");
        mainPageContainer.setVisibility(View.VISIBLE);
        gamePageContainer.setVisibility(View.GONE);
    }

    private void showGamePage() {
        DebugLog.enter(TAG, "showGamePage in, boardSize=" + boardSize);
        mainPageContainer.setVisibility(View.GONE);
        gamePageContainer.setVisibility(View.VISIBLE);
        gameTitleText.setText(gameResultText == null ? boardSize + "路　" + conditionName() + "　　第" + (board.getMoveCount() + 1) + "手" : gameResultText);
    }

    private void startNewGame() {
        DebugLog.enter(TAG, "startNewGame in, engineReady=" + engineReady + ", boardSize=" + boardSize + ", kyu=" + aiKyu);
        if (engineStarting || thinking || evaluating) {
            render("AI 正在处理，请稍候...");
            return;
        }

        boardView.setOwnership(null);
        clearRecommendations();
        gameResultText = null;
        finalScoreText = null;
        winRateEpoch++;
        winRateSession++;
        blackWinHistory.clear();
        whiteWinHistory.clear();
        board = new GoBoard(boardSize);
        final int handicapCount = gameCondition >= 2 ? gameCondition : 0;
        final List<Point> handicapPoints = handicapCount > 0
                ? GoBoard.standardHandicapPoints(boardSize, handicapCount) : new ArrayList<>();
        if (handicapCount > 0 && !board.placeHandicapStones(handicapPoints)) {
            render("让子布局失败");
            return;
        }
        currentPlayer = handicapCount > 0 ? StoneColor.WHITE : StoneColor.BLACK;
        lastMove = null;
        gameReady = false;
        engineStarting = true;
        engineReady = false;
        final boolean playerFirst = currentPlayer == playerColor;
        final int size = boardSize, kyu = aiKyu, visits = searchVisits;
        final double seconds = searchTime;
        render(engine.isHumanSLRunning() ? "正在初始化棋局..." : "正在准备人类棋力模型...");

        engineExecutor.execute(() -> {
            String error = null;
            try {
                if (!engine.isHumanSLRunning()) {
                    engine.prepareHumanModel((done, total) -> {
                        int percent = (int) (done * 100 / total);
                        mainHandler.post(() -> {
                            if (engineStarting) render("正在准备棋力模型 " + percent + "%");
                        });
                    });
                    engine.stop();
                    if (!engine.start(KataGoEngine.Model.HUMAN, true)) throw new IllegalStateException("Human SL 引擎启动失败");
                } else {
                    Log.i(TAG, "Reusing running Human SL KataGo process for new game");
                }
                boolean ok = engine.setBoardSize(size);
                ok = engine.clearBoard() && ok;
                ok = engine.setChineseRules() && ok;
                ok = engine.setKomi(komiFor(size)) && ok;
                if (ok && handicapCount > 0) ok = engine.setHandicapStones(handicapPoints, size);
                if (!ok) throw new IllegalStateException("棋盘或让子初始化失败");
                if (!engine.setSearchLimits(visits, seconds)) throw new IllegalStateException("AI 搜索限制设置失败");
                if (!engine.setHumanRank(kyu)) throw new IllegalStateException("AI 棋力等级设置失败");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.e(TAG, "Human SL game initialization failed", e);
                if (!engine.isReady()) engine.start(KataGoEngine.Model.HUMAN);
            }
            boolean ready = error == null && engine.isReady();
            boolean running = engine.isReady();
            String failure = error;
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = running;
                gameReady = ready;
                if (!ready) {
                    render("棋力模型准备失败：" + failure);
                    Toast.makeText(this, "未进入对局，请重试下载或检查日志", Toast.LENGTH_LONG).show();
                    return;
                }
                initializeWinRateAnalysis(board, size, komiFor(size), handicapPoints, currentPlayer);
                if (playerFirst) render("轮到你了");
                else requestAiMove();
            });
        });
    }

    private float komiFor(int size) {
        DebugLog.enter(TAG, "komiFor in, size=" + size + ", condition=" + gameCondition);
        if (gameCondition != 0) return 0f; // Handicap/first-move games have no normal komi.
        return size <= 11 ? 5.5f : 7.5f;
    }

    private String conditionName() {
        if (gameCondition == 0) return "分先";
        if (gameCondition == 1) return "让先";
        return "让" + gameCondition + "子";
    }

    private String getDifficultyName() { return aiKyu + "级"; }

    private void render(String message) {
        DebugLog.enter(TAG, "render in, message=" + message + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer);
        statusText.setText(message);
        updateWinRateLabels();
        aiDifficultyText.setText(getDifficultyName() + " · Human SL");
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver());
        statusText.setMaxLines(boardView.hasRecommendations() ? 3 : 2);
        statusText.setTextSize(boardView.hasRecommendations() ? 10f : 13f);
        boolean playerBlack = playerColor == StoneColor.BLACK;
        playerStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_black_stone : R.drawable.txwq_white_stone);
        aiStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_white_stone : R.drawable.txwq_black_stone);
        int blackCaptures = board.getCapturedWhite();
        int whiteCaptures = board.getCapturedBlack();
        int playerCaptures = playerBlack ? blackCaptures : whiteCaptures;
        int aiCaptures = playerBlack ? whiteCaptures : blackCaptures;
        playerCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "黑" : "白", playerCaptures));
        aiCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "白" : "黑", aiCaptures));
        gameTitleText.setText(variationBoard != null ? "AI 推荐变化图 · 仅供预览"
                : gameResultText == null ? boardSize + "路　" + conditionName() + "　　第" + (board.getMoveCount() + 1) + "手" : gameResultText);
        updateButtons();
    }

    private void updateButtons() {
        DebugLog.enter(TAG, "updateButtons in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        boolean playerTurn = engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking && !engineStarting && !evaluating);
        newRoundButton.setEnabled(!thinking && !engineStarting && !evaluating);
        situationButton.setEnabled(variationBoard == null && engineReady && gameReady && !engineStarting
                && !thinking && !evaluating && (board.isGameOver() || currentPlayer == playerColor));
        aiSuggestionButton.setEnabled(playerTurn && variationBoard == null);
        variationButton.setEnabled(playerTurn);
        variationButton.setText(variationBoard == null ? "后续变化"
                : (variationRank + 1 < recommendationChoices.size() ? "下一变化" : "退出预览"));
        undoButton.setEnabled(playerTurn && variationBoard == null && board.getMoveCount() >= 2);
        passButton.setEnabled(playerTurn && variationBoard == null);
        resignButton.setEnabled(playerTurn && variationBoard == null && board.getMoveCount() > 0);
    }

    @Override
    public void onBackPressed() {
        DebugLog.enter(TAG, "onBackPressed in, gameVisible=" + (gamePageContainer.getVisibility() == View.VISIBLE));
        if (variationBoard != null) exitVariationPreview();
        else if (gamePageContainer.getVisibility() == View.VISIBLE) showMainPage();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        DebugLog.enter(TAG, "onDestroy in");
        super.onDestroy();
        engineExecutor.execute(() -> engine.stop());
        winRateExecutor.execute(() -> winRateEngine.stop());
        engineExecutor.shutdown();
        winRateExecutor.shutdown();
    }
}
