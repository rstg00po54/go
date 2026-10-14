package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.engine.GpuTuneCache;
import com.badukai.engine.GpuStartupLogProgress;
import com.badukai.engine.KataGoOpenCLProbe;
import com.badukai.engine.KataGoNative;
import com.badukai.engine.KataGoGpuNative;
import com.badukai.game.GoBoard;
import com.badukai.game.NnAreaScore;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.InkLoadingView;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int DEFAULT_BOARD_SIZE = 9;
    private static final int[] BOARD_SIZES = {9, 11, 13, 15, 19};

    private static boolean isSupportedBoardSize(int size) {
        for (int supported : BOARD_SIZES) if (size == supported) return true;
        return false;
    }
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService winRateExecutor = Executors.newSingleThreadExecutor();
    private volatile long winRateSession;
    // Accessed only on winRateExecutor, whose GTP process is independent of the playing engine.
    private boolean winRateSynchronized;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KataGoEngine engine;
    private KataGoEngine winRateEngine;
    private GoBoard board = new GoBoard(DEFAULT_BOARD_SIZE);
    private StoneColor playerColor = StoneColor.BLACK;
    private StoneColor currentPlayer = StoneColor.BLACK;
    private int boardSize = DEFAULT_BOARD_SIZE;
    private int aiKyu = 12;
    private KataGoEngine.BackendPreference selectedBackend = KataGoEngine.BackendPreference.CPU;
    private boolean forceLegacyBackend;
    // 0=even game, 1=black plays first without komi, 2..9=fixed black handicap stones.
    private int gameCondition = 0;
    // Human SL selects moves from its rank profile; search primarily assists pass/resign decisions.
    // Start with 8 visits for responsiveness; keep rank selection independent of this limit.
    private int searchVisits = 8;
    private double searchTime = 8.0;
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
    private long lastAiMoveTimeMs = -1;
    private boolean evaluating;
    private boolean gameReady;
    private String gameResultText;
    private String finalScoreText;
    private KataGoEngine.PositionEvaluation endgameEvaluation;
    private GoBoard nnAreaScoreBoard;
    private int nnAreaScoreMoves = -1;
    private String nnAreaScoreMessage;
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
    private TextView nnAreaScoreText;
    private InkLoadingView gpuStartupScreen;
    private GpuStartupLogProgress gpuProgressTracker;
    private int gpuStartupGeneration;
    private long gpuStartupStartedMs;
    private TextView aiWinRateText;
    private TextView aiMoveTimeText;
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
    private boolean hasBundledNpuModel(int size) {
        if (size != 9 && size != 13 && size != 19) return false;
        String asset = "engine/katago_10b_" + size + "x" + size + ".rknn";
        try (InputStream in = getAssets().open(asset)) { return in.read() != -1; }
        catch (java.io.IOException e) { return false; }
    }

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
        final boolean jniGtpRepeat = getIntent() != null && getIntent().getBooleanExtra("katago_gtp_repeat", false);
        if (jniGtpSmoke || jniGtpRepeat)
            new Thread(() -> runJniGtpSmoke(jniGtpRepeat), "KataGo-JNI-GTP").start();
        setContentView(R.layout.activity_main);
        // Main-game CPU/Human SL prefers JNI when libkatago.so is bundled.
        // Rescue switch: adb shell am start ... --ez katago_legacy true
        boolean forceLegacy = getIntent() != null && getIntent().getBooleanExtra("katago_legacy", false);
        forceLegacyBackend = forceLegacy;
        // Preload the same board dimensions as the user's last confirmed game.
        android.content.SharedPreferences settings = getSharedPreferences("katago_settings", MODE_PRIVATE);
        int savedBoardSize = settings.getInt("board_size", DEFAULT_BOARD_SIZE);
        boardSize = isSupportedBoardSize(savedBoardSize) ? savedBoardSize : DEFAULT_BOARD_SIZE;
        board = new GoBoard(boardSize);
        engine = new KataGoEngine(getApplicationContext(), "engine", !forceLegacy);
        engine.setStartupBoardSize(boardSize);
        String savedBackend = settings.getString("backend", "CPU");
        selectedBackend = "NPU".equals(savedBackend) && hasBundledNpuModel(boardSize)
                ? KataGoEngine.BackendPreference.NPU
                : "GPU".equals(savedBackend) ? KataGoEngine.BackendPreference.GPU : KataGoEngine.BackendPreference.CPU;
        if (forceLegacy) selectedBackend = KataGoEngine.BackendPreference.CPU;
        engine.setBackendPreference(selectedBackend);
        winRateEngine = new KataGoEngine(getApplicationContext(), "engine_winrate");
        Log.i(TAG, "Game engine preference=" + (forceLegacy ? "PIE forced" : "JNI if bundled")
                + "; preload boardSize=" + boardSize + "; backend=" + selectedBackend + "; winrate engine=PIE");
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
        countTerritoryButton.setOnClickListener(v -> showEndgameScoringOptions());
        variationButton.setOnClickListener(v -> showVariations());
        render("正在启动 AI...");
        showMainPage();
        if (isRefereeSmokeIntent()) {
            startDebugRefereeSmoke();
        } else if (!jniGtpSmoke && !jniGtpRepeat) {
            showGpuStartupScreen(boardSize, selectedBackend);
            startEngine();
        }
    }

    // Explicit Debug-APK-only ADB smoke. Production APKs cannot enter this path.
    private boolean isRefereeSmokeIntent() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null && getIntent().getBooleanExtra("referee_smoke", false);
    }

    private String refereeSmokeId() {
        String raw = getIntent() == null ? "" : getIntent().getStringExtra("referee_smoke_id");
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.substring(0,Math.min(cleaned.length(),40));
    }

    private void startDebugRefereeSmoke() {
        final String id = refereeSmokeId();
        final String game = getIntent().getStringExtra("referee_smoke_game") == null
                ? "cgos9_expand_006_12" : getIntent().getStringExtra("referee_smoke_game");
        // Literal allowlist prevents ADB extras from reading arbitrary assets.
        if (!("cgos9_1".equals(game) || "cgos9_2".equals(game)
                || "cgos9_5".equals(game) || "cgos9_expand_006_12".equals(game))) {
            Log.e("RefereeSmoke","FAIL id=" + id + " stage=fixture unknown=" + game);
            return;
        }
        final String asset = "cgos9_expand_006_12".equals(game)
                ? "referee_smoke_006_12.sgf" : "referee_smoke_" + game + ".sgf";
        final String backend = getIntent().getStringExtra("referee_smoke_backend");
        final boolean gpu = "gpu".equalsIgnoreCase(backend);
        final boolean midgame = getIntent().getBooleanExtra("referee_smoke_midgame",false);
        Log.i("RefereeSmoke","START id=" + id + " game=" + game
                + " requested=" + (gpu ? "gpu" : "cpu") + " midgame=" + midgame);
        render("自动测试：准备重放真实终局棋谱...");
        engineExecutor.execute(() -> {
            try {
                engine.setStartupBoardSize(9);
                engine.setBackendPreference(gpu ? KataGoEngine.BackendPreference.GPU
                        : KataGoEngine.BackendPreference.CPU);
                if (!engine.start(KataGoEngine.Model.HUMAN,false))
                    throw new IllegalStateException("10b KataGo engine startup failed");
                if (gpu && !engine.getBackendName().startsWith("GPU"))
                    throw new IllegalStateException("GPU requested but actual backend is " + engine.getBackendName());
                // Verify the new native proof is genuinely callable and can return
                // both results, rather than silently reporting "unconfirmed" for all
                // positions because an old JNI library was packaged.
                byte[] proven = new byte[81];
                java.util.Arrays.fill(proven,(byte)1);
                proven[2*9+2] = 0;
                proven[6*9+6] = 0; // Two independent one-point eyes in a single black chain.
                byte[] empty = new byte[81];
                boolean positive = gpu ? KataGoGpuNative.isAllPassAlive(9,proven)
                        : KataGoNative.isAllPassAlive(9,proven);
                boolean negative = gpu ? KataGoGpuNative.isAllPassAlive(9,empty)
                        : KataGoNative.isAllPassAlive(9,empty);
                if (!positive || negative)
                    throw new IllegalStateException("Strict KataGo pass-alive native self-test failed: true="
                            + positive + " false=" + negative + " (rebuild both JNI libraries)");
                Log.i("RefereeSmoke","PASS_ALIVE_SELFTEST id=" + id + " positive=" + positive
                        + " negative=" + negative + " backend=" + engine.getBackendName().replace(" ","_"));
                if (!engine.setBoardSize(9) || !engine.clearBoard() || !engine.setChineseRules()
                        || !engine.setKomi(7f) || !engine.setSearchLimits(48,1.0))
                    throw new IllegalStateException("9x9 Chinese/komi/search setup failed");

                StringBuilder source = new StringBuilder();
                try (java.io.InputStream input = getAssets().open(asset)) {
                    byte[] buffer = new byte[4096];
                    int got;
                    while ((got = input.read(buffer)) != -1)
                        source.append(new String(buffer,0,got,StandardCharsets.UTF_8));
                }
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        ";([BW])\\[([a-i]{0,2})\\]").matcher(source);
                GoBoard replay = new GoBoard(9);
                int played = 0;
                while (matcher.find()) {
                    StoneColor color = "B".equals(matcher.group(1)) ? StoneColor.BLACK : StoneColor.WHITE;
                    String vertex = matcher.group(2);
                    String gtp = "pass";
                    Move move;
                    if (vertex.isEmpty()) move = new Move.Pass(color);
                    else {
                        if (vertex.length() != 2) throw new IllegalStateException("Invalid SGF vertex " + vertex);
                        Point point = new Point(vertex.charAt(0)-'a',vertex.charAt(1)-'a');
                        if (!replay.isLegalMove(point,color))
                            throw new IllegalStateException("Illegal fixture move " + played + " " + vertex);
                        move = new Move.Stone(point,color);
                        gtp = point.toGtp(9);
                    }
                    if (!engine.playMove(color.toGtp(),gtp))
                        throw new IllegalStateException("KataGo rejected fixture move " + played + " " + gtp);
                    replay.playMove(move);
                    played++;
                }
                if (played < 4 || !replay.isGameOver())
                    throw new IllegalStateException("Fixture must end with two passes; moves=" + played);
                if (midgame) {
                    // Replay the same real SGF but strip its TWO terminal passes
                    // from both the KataGo process and Java board. The referee
                    // must preserve this still-active position on dismissal.
                    boolean first = engine.undo();
                    if (!first || !engine.undo() || !replay.resumeAfterPasses())
                        throw new IllegalStateException("Cannot prepare ongoing midgame SGF");
                }
                mainHandler.post(() -> {
                    board = replay;
                    boardSize = 9;
                    gameCondition = 0;
                    playerColor = StoneColor.BLACK;
                    currentPlayer = StoneColor.BLACK;
                    engineReady = true;
                    engineStarting = false;
                    gameReady = true;
                    thinking = false;
                    evaluating = false;
                    gameResultText = null;
                    endgameEvaluation = null;
                    nnAreaScoreBoard = null;
                    nnAreaScoreMessage = null;
                    showGamePage();
                    render("自动测试：SGF 已加载，正在快速 NN 估分...");
                    showQuickNnRefereeEstimate();
                });
            } catch (Exception ex) {
                Log.e("RefereeSmoke","FAIL id=" + id + " stage=replay reason=" + ex,ex);
                mainHandler.post(() -> render("智能裁判自动测试失败，查看 RefereeSmoke 日志"));
            }
        });
    }

    private void runJniGtpSmoke(boolean repeat) {
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


            Log.i(tag, "Starting JNI CPU/Eigen GTP session, repeat=" + repeat);
            int[] boardSizes = repeat ? new int[]{9, 13, 19} : new int[]{9};
            for (int boardSize : boardSizes) {
                Log.i(tag, "Creating JNI session for " + boardSize + "x" + boardSize);
                try (KataGoNative.GtpSession session = KataGoNative.createSession(model, configFile)) {
                    if (session == null) throw new IllegalStateException("createSession returned null for boardSize=" + boardSize);
                    Log.i(tag, "JNI session created, model=" + model.length() + " bytes");
                    StringBuilder responseBuffer = new StringBuilder();
                    String[] commands = {"name", "boardsize " + boardSize, "komi 7.5", "play B D4",
                                         "genmove W", "undo", "clear_board"};
                    for (String command : commands) {
                        Log.i(tag, "Sending " + command);
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
                    Log.i(tag, "Cycle OK: board=" + boardSize + "x" + boardSize + "; closing session");
                }
                Log.i(tag, "Session destroyed: board=" + boardSize + "x" + boardSize);
            }
            Log.i(tag, repeat ? "PASS: JNI GTP repeated sessions 9x9, 13x13, 19x19" :
                                  "PASS: JNI GTP name/boardsize/komi/play/genmove/undo/clear_board");
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
        nnAreaScoreText = findViewById(R.id.nnAreaScoreText);
        aiWinRateText = findViewById(R.id.aiWinRateText);
        aiMoveTimeText = findViewById(R.id.aiMoveTimeText);
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
                    long phaseNs = System.nanoTime();
                    engine.prepareHumanModel(null);
                    Log.i(TAG, "Initial engine phase=human_model_prepare elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L);
                    advanceGpuStartup(11, "模型文件准备完成");
                    phaseNs = System.nanoTime();
                    ok = engine.start(KataGoEngine.Model.HUMAN, true);
                    Log.i(TAG, "Initial engine phase=engine_start elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                    if (ok) advanceGpuStartup(96, "正在初始化棋盘规则...");
                    humanPreloaded = ok;
                } catch (Exception e) {
                    Log.e(TAG, "Human SL preload failed; new game will retry", e);
                }
            }
            if (!ok) {
                engine.stop();
                long phaseNs = System.nanoTime();
                ok = engine.start(KataGoEngine.Model.HUMAN);
                Log.i(TAG, "Initial engine phase=fallback_start elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
            }
            if (ok) {
                long phaseNs = System.nanoTime();
                ok = engine.setBoardSize(boardSize) && engine.clearBoard()
                        && engine.setChineseRules() && engine.setKomi(komiFor(boardSize));
                Log.i(TAG, "Initial engine phase=gtp_board_setup elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                if (!ok) engine.stop();
            }
            final boolean ready = ok, preloaded = humanPreloaded;
            Log.i(TAG, "Initial engine ready=" + ready + " humanSL=" + preloaded
                    + " elapsedMs=" + (System.nanoTime() - started) / 1000000L);
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = ready;
                aiBattleButton.setEnabled(true);
                dismissGpuTuning(ready);
                if (ready && selectedBackend != KataGoEngine.BackendPreference.CPU
                        && !engine.getBackendName().startsWith(selectedBackend.name()))
                    Toast.makeText(this, selectedBackend.name() + " 初始化失败，已使用 " + engine.getBackendName(), Toast.LENGTH_LONG).show();
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
            winRateEngine.setStartupBoardSize(size);
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
            if (session != winRateSession) return; // No expensive analysis after game end.
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
                    if (!engine.isReady()) { engineReady = false; gameReady = false; }
                    render("落子同步失败，请重新开局");
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
            long startedMs = SystemClock.elapsedRealtime();
            String move = engine.generateMove(aiColor.toGtp());
            long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
            mainHandler.post(() -> handleAiMove(move, aiColor, elapsedMs));
        });
    }

    private void handleAiMove(String move, StoneColor aiColor, long elapsedMs) {
        DebugLog.enter(TAG, "handleAiMove in, move=" + move + ", aiColor=" + aiColor + ", elapsedMs=" + elapsedMs);
        thinking = false;
        if (move == null || move.isEmpty()) {
            if (!engine.isReady()) {
                engineReady = false;
                gameReady = false;
                render("AI 引擎已退出，请重新开局");
            } else {
                render("AI 没有返回落子");
            }
            return;
        }
        lastAiMoveTimeMs = Math.max(0, elapsedMs);
        Log.i(TAG, "AI_MOVE_TIME move=" + move + " elapsedMs=" + lastAiMoveTimeMs);
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
        lastAiMoveTimeMs = -1;
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

    /** Double pass triggers one raw NN evaluation, with no manual death adjudication. */
    private void finishByScore(String pendingPassColor) {
        DebugLog.enter(TAG, "finishByScore in, pendingPassColor=" + pendingPassColor);
        if (evaluating) return;
        evaluating = true;
        endgameEvaluation = null;
        winRateEpoch++;
        winRateSession++;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        final long epoch = winRateEpoch;
        render("正在快速 NN 估分...");
        engineExecutor.execute(() -> {
            boolean synced = pendingPassColor == null || engine.playMove(pendingPassColor, "pass");
            long startedMs = SystemClock.elapsedRealtime();
            KataGoEngine.PositionEvaluation nn = synced ? engine.evaluatePosition(size) : null;
            long nnMs = SystemClock.elapsedRealtime() - startedMs;
            final KataGoEngine.PositionEvaluation result = nn;
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || winRateEpoch != epoch) return;
                evaluating = false;
                if (result == null || result.whiteOwnership == null || result.whiteOwnership.length != size*size
                        || !Double.isFinite(result.whiteLead) || !Double.isFinite(result.blackWin)
                        || !Double.isFinite(result.whiteWin)) {
                    finalScoreText = "快速 NN 估分失败，请重试";
                    Log.w(TAG, "ENDGAME_NN_FAILED synced=" + synced + " elapsedMs=" + nnMs);
                    render(finalScoreText);
                    return;
                }
                endgameEvaluation = result;
                gameResultText = formatFastEndgameResult(result.whiteLead);
                finalScoreText = String.format(Locale.CHINA, "快速 NN 预估\n%s",
                        Math.abs(result.whiteLead) < 0.05 ? "接近平局"
                        : String.format(Locale.CHINA, "%s领先约%.1f目",
                                result.whiteLead > 0 ? "白" : "黑",Math.abs(result.whiteLead)));
                try {
                    NnAreaScore area = NnAreaScore.estimate(result.whiteOwnership,size,komiFor(size),
                            gameCondition >= 2 ? gameCondition : 0);
                    nnAreaScoreBoard = snapshot;
                    nnAreaScoreMoves = moves;
                    nnAreaScoreMessage = String.format(Locale.CHINA,"黑 %.1f · 白 %.1f",
                            area.blackPoints,area.whitePoints);
                } catch (IllegalArgumentException ex) {
                    nnAreaScoreBoard = null;
                    nnAreaScoreMessage = null;
                    Log.w(TAG,"Endgame NN area unavailable",ex);
                }
                recordWinRate(snapshot,moves,epoch,
                        new KataGoEngine.WinRate((float)result.blackWin,(float)result.whiteWin));
                Log.i(TAG,String.format(Locale.US,"ENDGAME_NN_RESULT moves=%d lead=%.3f elapsedMs=%d",
                        moves,result.whiteLead,nnMs));
                render(finalScoreText);
            });
        });
    }

    private String formatFastEndgameResult(double lead) {
        if (Math.abs(lead) < 0.05) return "对局结束 · 快速AI预估接近平局";
        return String.format(Locale.CHINA, "对局结束 · 快速AI预估%s领先约%.1f目",
                lead > 0 ? "白" : "黑", Math.abs(lead));
    }

    /** Smart referee is read-only NN inference, never manual scoring. */
    private void showEndgameScoringOptions() {
        if (!engineReady || !gameReady || evaluating || thinking
                || board.getLastMove() instanceof Move.Resign) return;
        showQuickNnRefereeEstimate();
    }

    /**
     * One raw KataGo NN inference, no MCTS, no pass moves and no board changes.
     * Midgame estimates are informational, not proof of life, death or a final score.
     */
    private void showQuickNnRefereeEstimate() {
        if (!engineReady || !gameReady || evaluating || thinking || variationBoard != null) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final boolean gameEnded = board.isGameOver();
        final KataGoEngine.PositionEvaluation cached = gameEnded ? endgameEvaluation : null;
        render("智能裁判：正在快速 NN 估分...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation nn = cached;
            long startedMs = SystemClock.elapsedRealtime();
            try {
                if (nn == null || nn.size != size) nn = engine.evaluatePosition(size);
            } catch (RuntimeException e) {
                Log.w(TAG,"Quick referee NN evaluation failed",e);
                nn = null;
            }
            final long nnMs = SystemClock.elapsedRealtime() - startedMs;
            // KataGo rules/Benson proof is separate from NN score prediction and
            // checks a temporary native board without changing the live game.
            final boolean passAliveCertified = engine.isStrictPassAliveComplete(snapshot);
            final KataGoEngine.PositionEvaluation result = nn;
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size) {
                    evaluating = false;
                    return;
                }
                evaluating = false;
                if (result == null || !Double.isFinite(result.whiteLead)
                        || !Double.isFinite(result.blackWin) || !Double.isFinite(result.whiteWin)) {
                    render("快速 NN 估分失败，请重试");
                    if (isRefereeSmokeIntent()) Log.e("RefereeSmoke","FAIL id=" + refereeSmokeId()
                            + " game=" + getIntent().getStringExtra("referee_smoke_game")
                            + " stage=nn_dialog reason=nn_invalid");
                    return;
                }
                String leader = Math.abs(result.whiteLead) < 0.05 ? "双方持平"
                        : String.format(Locale.CHINA,"%s领先 %.1f目",
                                result.whiteLead > 0 ? "白棋" : "黑棋",Math.abs(result.whiteLead));
                NnAreaScore area = null;
                try {
                    area = NnAreaScore.estimate(result.whiteOwnership,size,komiFor(size),
                            gameCondition >= 2 ? gameCondition : 0);
                } catch (IllegalArgumentException ex) {
                    Log.w(TAG,"NN area totals unavailable",ex);
                }
                if (isRefereeSmokeIntent() && (area == null || result.whiteOwnership == null
                        || result.whiteOwnership.length != size*size || !Double.isFinite(area.blackPoints)
                        || !Double.isFinite(area.whitePoints) || result.blackWin < 0 || result.blackWin > 1
                        || result.whiteWin < 0 || result.whiteWin > 1)) {
                    Log.e("RefereeSmoke","FAIL id=" + refereeSmokeId() + " game="
                            + getIntent().getStringExtra("referee_smoke_game") + " stage=nn_dialog reason=nn_values");
                    render("自动测试：NN 数值不合法");
                    return;
                }
                String areaLine = area == null ? "" : String.format(Locale.CHINA,
                        "黑棋 %.1f目 · 白棋 %.1f目",area.blackPoints,area.whitePoints);
                nnAreaScoreBoard = area == null ? null : snapshot;
                nnAreaScoreMoves = moves;
                nnAreaScoreMessage = area == null ? null : String.format(Locale.CHINA,
                        "黑 %.1f · 白 %.1f",area.blackPoints,area.whitePoints);
                String summary = String.format(Locale.CHINA,
                        "%s%s\n胜率 黑 %.1f%% · 白 %.1f%%\n收官：%s",
                        leader,area == null ? "" : "\n" + areaLine,
                        result.blackWin * 100.0,result.whiteWin * 100.0,
                        passAliveCertified ? "已完成" : "未确认");
                render("快速 NN 预估：" + leader);
                Log.i(TAG,String.format(Locale.US,
                        "REFEREE_FAST_NN moves=%d terminal=%s whiteLead=%.3f blackWin=%.4f whiteWin=%.4f",
                        moves,gameEnded,result.whiteLead,result.blackWin,result.whiteWin));
                new AlertDialog.Builder(this).setTitle("智能裁判 · NN预估")
                        .setMessage(summary)
                        .setPositiveButton(gameEnded ? "关闭" : "继续下棋",(dialog,which) -> {
                            if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","NN_DISMISS id=" + refereeSmokeId()
                                    + " moves=" + board.getMoveCount() + " terminal=" + board.isGameOver());
                        }).show();
                if (isRefereeSmokeIntent()) Log.i("RefereeSmoke",String.format(Locale.US,
                        "PASS id=%s game=%s stage=nn_dialog backend=%s moves=%d terminal=%s "
                        + "whiteLead=%.3f blackWin=%.4f whiteWin=%.4f blackPoints=%.3f whitePoints=%.3f nnMs=%d passAlive=%s",
                        refereeSmokeId(),getIntent().getStringExtra("referee_smoke_game"),
                        engine.getBackendName().replace(" ","_"),
                        moves,gameEnded,result.whiteLead,result.blackWin,result.whiteWin,
                        area.blackPoints,area.whitePoints,nnMs,passAliveCertified));
            });
        });
    }

    private void finishByResignation(String outcome) {
        DebugLog.enter(TAG, "finishByResignation in, outcome=" + outcome);
        gameResultText = outcome;
        finalScoreText = "认输结束\n未进行数子";
        winRateEpoch++;
        winRateSession++; // No stale win-rate work after resignation.
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

    /** Display NN-estimated empty territory only; do not infer dead or living stones. */
    private String territorySummary(KataGoEngine.PositionEvaluation result) {
        int blackTerritory = 0, whiteTerritory = 0, uncertainEmpty = 0;
        final int size = result.size;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (board.get(x,y) != Intersection.EMPTY) continue;
            float whiteOwn = result.whiteOwnership[y*size+x];
            if (!Float.isFinite(whiteOwn)) continue;
            if (whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) whiteTerritory++;
            else if (whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) blackTerritory++;
            else uncertainEmpty++;
        }
        boardView.setOwnership(result.whiteOwnership);
        Log.i(TAG,String.format(Locale.US,"NN empty territory B=%d W=%d uncertain=%d",
                blackTerritory,whiteTerritory,uncertainEmpty));
        return String.format(Locale.CHINA,"黑估空%d目\n白估空%d目",blackTerritory,whiteTerritory);
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
        Spinner backendSpinner = content.findViewById(R.id.backendSpinner);
        Button startButton = content.findViewById(R.id.startGameButton);
        Button closeButton = content.findViewById(R.id.closeDialogButton);

        int[] boardSizes = BOARD_SIZES;
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"9路", "11路", "13路", "15路", "19路"});
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizeAdapter);
        int selectedSize = 0;
        for (int i = 0; i < boardSizes.length; i++) if (boardSizes[i] == boardSize) selectedSize = i;
        sizeSpinner.setSelection(selectedSize);

        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"分先", "让先", "让2子", "让3子", "让4子", "让5子", "让6子", "让7子", "让8子", "让9子"});
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conditionSpinner.setAdapter(conditionAdapter);
        conditionSpinner.setSelection(gameCondition);

        boolean gpuBundled = new File(getApplicationInfo().nativeLibraryDir, "libkatago_gpu.so").isFile();
        boolean npuBundled = new File(getApplicationInfo().nativeLibraryDir, "libkatago_npu.so").isFile()
                && new File(getApplicationInfo().nativeLibraryDir, "librknnrt.so").isFile();
        List<KataGoEngine.BackendPreference> backendChoices = new ArrayList<>();
        List<String> backendNames = new ArrayList<>();
        backendChoices.add(KataGoEngine.BackendPreference.CPU);
        backendNames.add("CPU / Eigen");
        if (gpuBundled) {
            backendChoices.add(KataGoEngine.BackendPreference.GPU);
            backendNames.add("GPU / OpenCL");
        }
        if (npuBundled) {
            backendChoices.add(KataGoEngine.BackendPreference.NPU);
            backendNames.add("NPU / RKNN（9/13/19路，需对应模型；Human SL仍用CPU）");
        }
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, backendNames);
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backendSpinner.setAdapter(backendAdapter);
        int backendIndex = backendChoices.indexOf(selectedBackend);
        backendSpinner.setSelection(Math.max(0, backendIndex));
        backendSpinner.setEnabled(backendChoices.size() > 1 && !forceLegacyBackend);

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
            final int requestedBoardSize = boardSizes[sizeSpinner.getSelectedItemPosition()];

            int colorId = colorGroup.getCheckedRadioButtonId();
            if (colorId == R.id.whiteRadio) playerColor = StoneColor.WHITE;
            else if (colorId == R.id.randomRadio) playerColor = (System.nanoTime() & 1L) == 0L ? StoneColor.BLACK : StoneColor.WHITE;
            else playerColor = StoneColor.BLACK;

            int selectedKyu = 18 - difficultySpinner.getSelectedItemPosition();
            int selectedCondition = conditionSpinner.getSelectedItemPosition();
            KataGoEngine.BackendPreference requested = backendChoices.get(backendSpinner.getSelectedItemPosition());
            if (requested == KataGoEngine.BackendPreference.NPU && !hasBundledNpuModel(requestedBoardSize)) {
                Toast.makeText(this, "当前 APK 没有 " + requestedBoardSize + " 路 RKNN 模型，请安装多尺寸版或选择 CPU/GPU", Toast.LENGTH_LONG).show();
                return;
            }
            Runnable begin = () -> {
                boardSize = requestedBoardSize;
                aiKyu = selectedKyu;
                gameCondition = selectedCondition;
                selectedBackend = requested;
                getSharedPreferences("katago_settings", MODE_PRIVATE).edit()
                        .putInt("board_size", boardSize).putString("backend", requested.name()).apply();
                Log.i(TAG, "Saved new-game preference boardSize=" + boardSize + " backend=" + requested);
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
        lastAiMoveTimeMs = -1;
        nnAreaScoreBoard = null;
        nnAreaScoreMessage = null;
        endgameEvaluation = null;
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
        final KataGoEngine.BackendPreference requestedBackend = selectedBackend;
        boolean npuBoardChanged = requestedBackend == KataGoEngine.BackendPreference.NPU
                && engine.getActiveNpuBoardSize() != size;
        boolean reusingHumanEngine = engine.isHumanSLRunning() && !npuBoardChanged
                && engine.getBackendName().startsWith(requestedBackend.name());
        render(reusingHumanEngine ? "正在初始化棋局..." : requestedBackend == KataGoEngine.BackendPreference.NPU
                ? "NPU/RKNN 初始化中..." : requestedBackend == KataGoEngine.BackendPreference.GPU
                ? "GPU 初始化中，首次调优可能需要数分钟..." : "正在准备人类棋力模型...");
        if (!reusingHumanEngine) showGpuStartupScreen(size, requestedBackend);

        engineExecutor.execute(() -> {
            String error = null;
            try {
                engine.setStartupBoardSize(size);
                engine.setBackendPreference(requestedBackend);
                advanceGpuStartup(8, "正在准备对弈模型...");
                boolean restartNpuForBoard = requestedBackend == KataGoEngine.BackendPreference.NPU
                        && engine.getActiveNpuBoardSize() != size;
                if (!engine.isHumanSLRunning() || restartNpuForBoard) {
                    if (restartNpuForBoard)
                        Log.i(TAG, "NPU_MODEL_SWITCH board=" + engine.getActiveNpuBoardSize() + " -> " + size
                                + "; restarting native GTP for matching RKNN model");
                    engine.prepareHumanModel((done, total) -> {
                        int percent = (int) (done * 100 / total);
                        mainHandler.post(() -> {
                            if (engineStarting) {
                                render("正在准备棋力模型 " + percent + "%");
                                if (gpuStartupScreen != null)
                                    gpuStartupScreen.setProgress(8 + percent / 20, "正在准备 Human SL 模型...");
                            }
                        });
                    });
                    engine.stop();
                    if (!engine.start(KataGoEngine.Model.HUMAN, true)) throw new IllegalStateException("Human SL 引擎启动失败");
                    advanceGpuStartup(96, "正在初始化棋盘规则...");
                } else {
                    Log.i(TAG, "Reusing running Human SL KataGo process for new game");
                }
                long initStartedNs = System.nanoTime(), phaseNs = initStartedNs;
                boolean ok = engine.setBoardSize(size);
                Log.i(TAG, "New game setup boardsize elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                phaseNs = System.nanoTime();
                ok = engine.clearBoard() && ok;
                Log.i(TAG, "New game setup clear_board elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                phaseNs = System.nanoTime();
                ok = engine.setChineseRules() && ok;
                Log.i(TAG, "New game setup chinese_rules elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                phaseNs = System.nanoTime();
                ok = engine.setKomi(komiFor(size)) && ok;
                Log.i(TAG, "New game setup komi elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L + " ok=" + ok);
                if (ok && handicapCount > 0) ok = engine.setHandicapStones(handicapPoints, size);
                if (!ok) throw new IllegalStateException("棋盘或让子初始化失败");
                phaseNs = System.nanoTime();
                if (!engine.setSearchLimits(visits, seconds)) throw new IllegalStateException("AI 搜索限制设置失败");
                Log.i(TAG, "New game setup search_limits elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L);
                phaseNs = System.nanoTime();
                if (!engine.setHumanRank(kyu)) throw new IllegalStateException("AI 棋力等级设置失败");
                Log.i(TAG, "New game setup human_rank elapsedMs=" + (System.nanoTime() - phaseNs) / 1000000L
                        + " totalMs=" + (System.nanoTime() - initStartedNs) / 1000000L);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.e(TAG, "Human SL game initialization failed", e);
                if (!engine.isReady()) engine.start(KataGoEngine.Model.HUMAN);
            }
            boolean ready = error == null && engine.isReady();
            boolean running = engine.isReady();
            String runningBackend = engine.getBackendName();
            boolean gpuFallback = ready && requestedBackend == KataGoEngine.BackendPreference.GPU
                    && !runningBackend.startsWith("GPU");
            boolean npuFallback = ready && requestedBackend == KataGoEngine.BackendPreference.NPU
                    && !runningBackend.startsWith("NPU");
            Log.i(TAG, "Game backend requested=" + requestedBackend + " actual=" + runningBackend + " ready=" + ready);
            String failure = error;
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = running;
                gameReady = ready;
                dismissGpuTuning(ready);
                if (!ready) {
                    render("棋力模型准备失败：" + failure);
                    Toast.makeText(this, "未进入对局，请重试下载或检查日志", Toast.LENGTH_LONG).show();
                    return;
                }
                initializeWinRateAnalysis(board, size, komiFor(size), handicapPoints, currentPlayer);
                if (gpuFallback || npuFallback) Toast.makeText(this,
                        (npuFallback ? "NPU" : "GPU") + " 不可用，已回退 " + runningBackend, Toast.LENGTH_LONG).show();
                if (playerFirst) render(gpuFallback || npuFallback ? "轮到你了（已回退 " + runningBackend + "）" : "轮到你了");
                else requestAiMove();
            });
        });
    }

    /** Full-screen ink-wash loading screen, reused for cold GPU preloads and new games. */
    private void showGpuStartupScreen(int size, KataGoEngine.BackendPreference backend) {
        if (backend != KataGoEngine.BackendPreference.GPU || forceLegacyBackend) return;
        if (!new File(getApplicationInfo().nativeLibraryDir, "libkatago_gpu.so").isFile()) return;
        if (gpuStartupScreen != null) return;

        int tuneMask = GpuTuneCache.cachedModelMask(this, size);
        boolean needsTuning = tuneMask != GpuTuneCache.BOTH_MODELS;
        gpuStartupStartedMs = SystemClock.elapsedRealtime();
        final int sessionId = ++gpuStartupGeneration;
        Log.i(TAG, "GPU tuning preflight boardSize=" + size + " cachedMask=" + tuneMask
                + " requiredMask=" + GpuTuneCache.BOTH_MODELS + " showStartupScreen=true");

        InkLoadingView loading = new InkLoadingView(this);
        loading.setClickable(true);
        loading.setFocusable(true);
        loading.setTuning(needsTuning);
        loading.setProgress(3, needsTuning ? "正在检查 GPU 调优参数..." : "正在读取 GPU 缓存...");
        FrameLayout root = findViewById(android.R.id.content);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loading.bringToFront();
        gpuStartupScreen = loading;
        gpuProgressTracker = new GpuStartupLogProgress(getApplicationContext(), (percent, stage) ->
                mainHandler.post(() -> {
                    if (sessionId == gpuStartupGeneration && gpuStartupScreen == loading)
                        gpuStartupScreen.setProgress(percent, stage);
                }));
        Log.i(TAG, "GPU startup screen shown boardSize=" + size + " needsTuning=" + needsTuning);
    }

    private void advanceGpuStartup(int percent, String stage) {
        mainHandler.post(() -> {
            if (gpuStartupScreen != null) gpuStartupScreen.setProgress(percent, stage);
        });
    }

    private void dismissGpuTuning(boolean ready) {
        if (gpuProgressTracker != null) {
            gpuProgressTracker.close();
            gpuProgressTracker = null;
        }
        gpuStartupGeneration++;
        InkLoadingView loading = gpuStartupScreen;
        gpuStartupScreen = null;
        if (loading == null) return;
        if (ready) loading.setProgress(100, "AI 准备完成");
        Log.i(TAG, "GPU startup screen dismissed elapsedMs=" +
                (SystemClock.elapsedRealtime() - gpuStartupStartedMs) + " ready=" + ready);
        // Display 100% briefly on success; failures remove the overlay immediately.
        Runnable remove = () -> {
            if (loading.getParent() instanceof ViewGroup)
                ((ViewGroup) loading.getParent()).removeView(loading);
        };
        if (ready) mainHandler.postDelayed(remove, 140);
        else remove.run();
    }

    private float komiFor(int size) {
        DebugLog.enter(TAG, "komiFor in, size=" + size + ", condition=" + gameCondition);
        // The regression SGF explicitly records KM[7], unlike the app's ordinary 9x9 5.5.
        if (isRefereeSmokeIntent() && size == 9) return 7f;
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
        if (nnAreaScoreBoard != board || nnAreaScoreMoves != board.getMoveCount()) {
            nnAreaScoreBoard = null;
            nnAreaScoreMessage = null;
        }
        nnAreaScoreText.setVisibility(nnAreaScoreMessage == null ? View.GONE : View.VISIBLE);
        if (nnAreaScoreMessage != null) nnAreaScoreText.setText(nnAreaScoreMessage);
        aiMoveTimeText.setText(thinking ? "AI 思考中…" : lastAiMoveTimeMs < 0
                ? "本手用时 --" : String.format(Locale.CHINA,"本手用时 %.2f 秒",lastAiMoveTimeMs/1000.0));
        updateWinRateLabels();
        aiDifficultyText.setText(getDifficultyName() + " · Human SL · "
                + (engineReady ? engine.getBackendName() : selectedBackend == KataGoEngine.BackendPreference.NPU ? "NPU 准备中" : selectedBackend == KataGoEngine.BackendPreference.GPU ? "GPU 准备中" : "CPU 准备中"));
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && gameReady && !engineStarting && !thinking
                && !evaluating && currentPlayer == playerColor && !board.isGameOver());
        boolean detailedResult = boardView.hasRecommendations();
        statusText.setMaxLines(detailedResult ? 3 : 2);
        statusText.setTextSize(detailedResult ? 10f : 13f);
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
        countTerritoryButton.setText("智能裁判");
        countTerritoryButton.setEnabled(engineReady && gameReady
                && !thinking && !engineStarting && !evaluating && variationBoard == null
                && !(board.getLastMove() instanceof Move.Resign));
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
        dismissGpuTuning(false);
        super.onDestroy();
        engineExecutor.execute(() -> engine.stop());
        winRateExecutor.execute(() -> winRateEngine.stop());
        engineExecutor.shutdown();
        winRateExecutor.shutdown();
    }
}
