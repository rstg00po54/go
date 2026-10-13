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
import com.badukai.game.GoBoard;
import com.badukai.game.FastEndgameScorer;
import com.badukai.game.SmartReferee;
import com.badukai.game.EndgameAdjudicator;
import com.badukai.game.MidgameTacticalReview;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int DEFAULT_BOARD_SIZE = 9;
    // Reversible experimental build option: false restores the original
    // default final_score -> final_status_list dead -> kata-raw-nn 0 pipeline.
    private static final boolean EXPERIMENTAL_FAST_ENDGAME = true;
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
    private boolean evaluating;
    private boolean gameReady;
    private String gameResultText;
    private String finalScoreText;
    private String finalDeadStones;
    private boolean preciseScoreReady;
    private double fastEndgameLead = Double.NaN;
    private KataGoEngine.PositionEvaluation endgameEvaluation;
    private EndgameAdjudicator.Result endgameDecision;
    // Experimental referee is a local, explicitly confirmed dead-chain overlay.
    private boolean refereeMode;
    // Native referee overlays survive dialog dismissal until cleared or the position changes.
    private GoBoard nativeRefereeMarkedBoard;
    private int nativeRefereeMarkedMoves = -1;
    private boolean refereeAwaitingTap;
    private boolean refereeConfirmed;
    private final Set<Point> refereeAccepted = new HashSet<>();
    private final Set<Point> refereeDead = new HashSet<>();
    private final Set<Point> refereeSuggested = new HashSet<>();
    private final Set<Point> refereeReview = new HashSet<>();
    private final Set<Point> refereeInitialReview = new HashSet<>();
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
    private InkLoadingView gpuStartupScreen;
    private GpuStartupLogProgress gpuProgressTracker;
    private int gpuStartupGeneration;
    private long gpuStartupStartedMs;
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
    private View refereePanel, gameActionPanel, refereeTapOverlay;
    private TextView refereePanelTitle, refereePanelScore, refereePanelHint;
    private Button refereeResetButton, refereeConfirmButton, refereeExitButton, refereeContinueButton;

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
        selectedBackend = "GPU".equals(settings.getString("backend", "CPU"))
                ? KataGoEngine.BackendPreference.GPU : KataGoEngine.BackendPreference.CPU;
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
                KataGoEngine.PositionEvaluation nn = engine.evaluatePosition(9);
                if (nn == null || nn.whiteOwnership == null || nn.whiteOwnership.length != 81)
                    throw new IllegalStateException("10b raw NN ownership unavailable");
                SmartReferee.Analysis analysis = SmartReferee.analyze(replay,nn.whiteOwnership);
                Log.i("RefereeSmoke",String.format(Locale.US,
                        "REPLAY id=%s game=%s moves=%d terminal=%s backend=%s lead=%+.2f dead=%d review=%d vulnerable=%d",
                        id,game,replay.getMoveCount(),replay.isGameOver(),engine.getBackendName(),nn.whiteLead,
                        analysis.suggestedGroups,analysis.reviewGroups,analysis.vulnerableGroups));
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
                    endgameEvaluation = midgame ? null : nn;
                    preciseScoreReady = false;
                    refereeMode = false;
                    finalDeadStones = null;
                    showGamePage();
                    render("自动测试：真实9路SGF终局已加载，正在打开智能裁判...");
                    startSmartReferee();
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
        refereePanel = findViewById(R.id.refereePanel);
        gameActionPanel = findViewById(R.id.gameActionPanel);
        refereeTapOverlay = findViewById(R.id.refereeTapOverlay);
        refereeTapOverlay.setOnClickListener(v -> {
            if (refereeMode && refereeRequiresContinuation() && refereeAwaitingTap) resumeFromReferee();
        });
        refereePanelTitle = findViewById(R.id.refereePanelTitle);
        refereePanelScore = findViewById(R.id.refereePanelScore);
        refereePanelHint = findViewById(R.id.refereePanelHint);
        refereeResetButton = findViewById(R.id.refereeResetButton);
        refereeConfirmButton = findViewById(R.id.refereeConfirmButton);
        refereeExitButton = findViewById(R.id.refereeExitButton);
        refereeContinueButton = findViewById(R.id.refereeContinueButton);
        refereeResetButton.setOnClickListener(v -> resetRefereeSuggestions());
        refereeConfirmButton.setOnClickListener(v -> showRefereeConfirmation());
        refereeExitButton.setOnClickListener(v -> leaveRefereeReview());
        refereeContinueButton.setOnClickListener(v -> resumeFromReferee());
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
                if (ready && selectedBackend == KataGoEngine.BackendPreference.GPU
                        && !engine.getBackendName().startsWith("GPU"))
                    Toast.makeText(this, "GPU 初始化失败，已使用 " + engine.getBackendName(), Toast.LENGTH_LONG).show();
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

    /** Midgame inspections never count as completed games, even when NN looks stable. */
    private boolean refereeRequiresContinuation() {
        return !board.isGameOver() || (endgameDecision != null
                && endgameDecision.verdict != EndgameAdjudicator.Verdict.READY_TO_COUNT);
    }

    private void onBoardTap(int x, int y) {
        DebugLog.enter(TAG, "onBoardTap in, x=" + x + ", y=" + y + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (refereeMode) {
            // Fallback if a board touch bypasses the transparent full-screen overlay:
            // consume the entire gesture to continue, NEVER also place a stone.
            if (refereeRequiresContinuation()) {
                if (refereeAwaitingTap) resumeFromReferee();
                return;
            }
            if (board.isInside(x, y) && board.get(x, y) != Intersection.EMPTY) {
                Point tapped = new Point(x, y);
                if (refereeReview.contains(tapped)) {
                    new AlertDialog.Builder(this).setTitle("复核这块棋")
                            .setMessage("AI 对整块棋的死活判断不确定。请选择最终处理方式。")
                            .setPositiveButton("判活（保留）", (dialog, which) -> {
                                SmartReferee.decideGroup(board, refereeDead, refereeReview, tapped, false);
                                refreshRefereePreview();
                            })
                            .setNeutralButton("判死（移除）", (dialog, which) -> {
                                SmartReferee.decideGroup(board, refereeDead, refereeReview, tapped, true);
                                refreshRefereePreview();
                            })
                            .setNegativeButton("暂不处理", null).show();
                } else {
                    SmartReferee.toggleGroup(board, refereeDead, tapped);
                    refreshRefereePreview();
                }
            }
            return;
        }
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
            String move = engine.generateMove(aiColor.toGtp());
            mainHandler.post(() -> handleAiMove(move, aiColor));
        });
    }

    private void handleAiMove(String move, StoneColor aiColor) {
        DebugLog.enter(TAG, "handleAiMove in, move=" + move + ", aiColor=" + aiColor);
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

    /**
     * Experimental fast path: one raw NN pass (no MCTS), conservative
     * chain-level dead suggestions and immediate display of raw whiteLead.
     * Original KataGo final_score remains available via the Deep Compare button.
     * If raw NN cannot be evaluated, fall back to the original slow pipeline.
     */
    private void finishByScore(String pendingPassColor) {
        DebugLog.enter(TAG, "finishByScore in, pendingPassColor=" + pendingPassColor);
        if (evaluating) return;
        evaluating = true;
        endgameEvaluation = null;
        endgameDecision = null;
        winRateEpoch++;
        winRateSession++;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        final long epoch = winRateEpoch, startedNs = System.nanoTime();
        render(EXPERIMENTAL_FAST_ENDGAME ? "正在快速AI估算..." : "正在搜索计算最终目差...");
        engineExecutor.execute(() -> {
            boolean synced = pendingPassColor == null || engine.playMove(pendingPassColor, "pass");
            if (EXPERIMENTAL_FAST_ENDGAME && synced) {
                long evalStartedNs = System.nanoTime();
                KataGoEngine.PositionEvaluation quick = engine.evaluatePosition(size);
                long evalMs = (System.nanoTime() - evalStartedNs) / 1000000L;
                if (quick != null && Double.isFinite(quick.whiteLead)) {
                    try {
                        FastEndgameScorer.Result suggested = FastEndgameScorer.suggest(snapshot, quick.whiteOwnership);
                        GoBoard.FinalScore local = snapshot.countChineseScore(suggested.deadStones,
                                komiFor(size), gameCondition >= 2 ? gameCondition : 0);
                        String estimatedDead = suggested.deadVertices(size);
                        double lead = quick.whiteLead;
                        long elapsedMs = (System.nanoTime() - startedNs) / 1000000L;
                        Log.i(TAG, String.format(Locale.US,
                                "Chinese area score B stones=%d territory=%d, W stones=%d territory=%d, "
                                + "dead B=%d W=%d, total B=%.1f W=%.1f, lead local=%.1f mode=fast_nn",
                                local.blackStones, local.blackTerritory, local.whiteStones, local.whiteTerritory,
                                local.deadBlack, local.deadWhite, local.blackPoints, local.whitePoints,
                                local.whitePoints - local.blackPoints));
                        Log.i(TAG, String.format(Locale.US,
                                "ENDGAME_TIMING phase=fast_nn_done elapsedMs=%d evalMs=%d "
                                + "nnWhiteLead=%.3f localWhiteLead=%.3f suggestedDead=%d groups=%d",
                                elapsedMs, evalMs, lead, local.whitePoints - local.blackPoints,
                                suggested.deadStones.size(), suggested.scannedGroups));
                        mainHandler.post(() -> {
                            if (board != snapshot || board.getMoveCount() != moves || winRateEpoch != epoch) return;
                            evaluating = false;
                            preciseScoreReady = false;
                            endgameEvaluation = quick;
                            finalDeadStones = estimatedDead;
                            fastEndgameLead = lead;
                            gameResultText = formatFastEndgameResult(lead);
                            finalScoreText = String.format(Locale.CHINA, "快速AI预估\n%s",
                                    Math.abs(lead) < 0.05 ? "接近平局" : String.format(Locale.CHINA, "%s领先约%.1f目",
                                    lead > 0 ? "白" : "黑", Math.abs(lead)));
                            territorySummary(quick);
                            recordWinRate(snapshot, moves, epoch,
                                    new KataGoEngine.WinRate((float) quick.blackWin, (float) quick.whiteWin));
                            render(finalScoreText);
                        });
                        return;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Fast NN endgame failed; using unchanged KataGo final_score", e);
                    }
                } else {
                    Log.w(TAG, "Fast NN endgame unavailable; using unchanged KataGo final_score");
                }
            }

            long scoreStartedNs = System.nanoTime();
            String score = synced ? engine.getFinalScore() : null;
            long scoreMs = (System.nanoTime() - scoreStartedNs) / 1000000L;
            Log.i(TAG, "ENDGAME_TIMING phase=score_done elapsedMs="
                    + (System.nanoTime() - startedNs) / 1000000L + " scoreMs=" + scoreMs
                    + " valid=" + (score != null) + " score=" + (score == null ? "null" : score.trim()));
            if (score != null) {
                mainHandler.post(() -> {
                    if (board != snapshot || board.getMoveCount() != moves || winRateEpoch != epoch) return;
                    gameResultText = formatFinalResult(score);
                    render("正在确认死子和地盘...");
                });
            }
            long deadStartedNs = System.nanoTime();
            String deadStones = synced ? engine.getFinalDeadStones() : null;
            long deadMs = (System.nanoTime() - deadStartedNs) / 1000000L;
            KataGoEngine.PositionEvaluation result = synced ? engine.evaluatePosition(size) : null;
            Log.i(TAG, "ENDGAME_TIMING phase=full_done elapsedMs="
                    + (System.nanoTime() - startedNs) / 1000000L + " scoreMs=" + scoreMs
                    + " deadMs=" + deadMs + " synced=" + synced);
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || winRateEpoch != epoch) return;
                evaluating = false;
                finalDeadStones = deadStones;
                preciseScoreReady = score != null;
                fastEndgameLead = Double.NaN;
                gameResultText = synced ? formatFinalResult(score) : "对局结束 · 同步失败";
                if (result != null) territorySummary(result);
                recordFinalWinRate(snapshot, moves, epoch, score);
                finalScoreText = synced ? finalPointsSummary(score, deadStones) : "终局数子\n同步失败";
                render(finalScoreText);
            });
        });
    }

    private String formatFastEndgameResult(double lead) {
        if (Math.abs(lead) < 0.05) return "对局结束 · 快速AI预估接近平局";
        return String.format(Locale.CHINA, "对局结束 · 快速AI预估%s领先约%.1f目",
                lead > 0 ? "白" : "黑", Math.abs(lead));
    }

    /** Explicit, reversible referee review. Keeps original NN outcome untouched until user confirmation. */
    private void showEndgameScoringOptions() {
        if (!engineReady || !gameReady || evaluating || thinking
                || board.getLastMove() instanceof Move.Resign) return;
        if (refereeMode) {
            if (refereeRequiresContinuation()) {
                if (refereeAwaitingTap) resumeFromReferee();
            } else showRefereeConfirmation();
            return;
        }
        // Fast raw NN estimate first, just like the automatic pass scoring.
        // Explicit dead-group search remains available as a separate action.
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
        final Point immediateKo = board.getSimpleKoPoint();
        final int atariGroups = board.countAtariGroups();
        final KataGoEngine.PositionEvaluation cached = gameEnded ? endgameEvaluation : null;
        render("智能裁判：正在快速 NN 估分...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation nn = cached;
            try {
                if (nn == null || nn.size != size) nn = engine.evaluatePosition(size);
            } catch (RuntimeException e) {
                Log.w(TAG,"Quick referee NN evaluation failed",e);
                nn = null;
            }
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
                    return;
                }
                String leader = Math.abs(result.whiteLead) < 0.05 ? "双方接近持平"
                        : String.format(Locale.CHINA,"%s领先约 %.1f 目",
                                result.whiteLead > 0 ? "白棋" : "黑棋",Math.abs(result.whiteLead));
                String summary = String.format(Locale.CHINA,
                        "%s\n黑棋胜率 %.1f%% · 白棋胜率 %.1f%%",
                        leader,result.blackWin * 100.0,result.whiteWin * 100.0);
                render("快速 NN 预估：" + leader);
                String positionWarning = immediateKo != null
                        ? "\n\n当前存在劫禁（" + immediateKo.toGtp(size) + "），不能直接确认终局。"
                        : atariGroups > 0 ? String.format(Locale.CHINA,
                                "\n\n当前有 %d 块单气棋，建议复核死活。",atariGroups) : "";
                Log.i(TAG,String.format(Locale.US,
                        "REFEREE_FAST_NN moves=%d terminal=%s whiteLead=%.3f blackWin=%.4f whiteWin=%.4f ko=%s atariGroups=%d",
                        moves,gameEnded,result.whiteLead,result.blackWin,result.whiteWin,
                        immediateKo == null ? "none" : immediateKo.toGtp(size),atariGroups));
                new AlertDialog.Builder(this).setTitle("智能裁判 · 快速 NN 估分")
                        .setMessage(summary + positionWarning + (gameEnded
                                ? "\n\n双方已停棋，以上仍是预估，不是确认数子结果。"
                                : "\n\n当前棋形估算，仅供参考；不代表已经判定死活。"))
                        .setPositiveButton(gameEnded ? "关闭" : "继续下棋",null)
                        .setNeutralButton("KataGo 原生裁判", (dialog,which) -> startNativeReferee())
                        .setNegativeButton("局面检查", (dialog,which) -> inspectUnfinishedPosition())
                        .show();
            });
        });
    }

    /**
     * Opt-in KataGo GTP final-score and dead-stone estimation.
     * Does not pass, undo, or alter the board. Midgame results are predictions.
     */
    private void startNativeReferee() {
        if (!engineReady || !gameReady || evaluating || thinking || variationBoard != null) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final boolean endedAtStart = board.isGameOver();
        final long startedNs = System.nanoTime();
        nativeRefereeMarkedBoard = null;
        boardView.setRefereeMarks(null,null);
        render("智能裁判：KataGo 正在检查形势与死子...");
        engineExecutor.execute(() -> {
            String nativeScore = null, nativeDead = null;
            final long scoreStartNs = System.nanoTime();
            try {
                nativeScore = engine.getFinalScore();
            } catch (RuntimeException ex) {
                Log.w(TAG,"Native KataGo final_score failed",ex);
            }
            final long scoreMs = (System.nanoTime()-scoreStartNs)/1000000L;
            final String preliminaryScore = nativeScore;
            mainHandler.post(() -> {
                if (board == snapshot && board.getMoveCount() == moves && boardSize == size && evaluating)
                    render(preliminaryScore == null ? "KataGo 正在判断死子..."
                            : "KataGo 已估分 " + preliminaryScore.trim() + "，正在判断死子...");
            });
            final long deadStartNs = System.nanoTime();
            try {
                nativeDead = engine.getFinalDeadStones();
            } catch (RuntimeException ex) {
                Log.w(TAG,"Native KataGo final_status_list dead failed",ex);
            }
            final long deadMs = (System.nanoTime()-deadStartNs)/1000000L;
            final String score = nativeScore, deadVertices = nativeDead;
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size) {
                    evaluating = false;
                    return;
                }
                evaluating = false;
                if (score == null && deadVertices == null) {
                    render("KataGo 原生裁判暂不可用");
                    new AlertDialog.Builder(this).setTitle("智能裁判")
                            .setMessage("KataGo 原生计分和死子判断都未返回结果，请稍后重试。")
                            .setPositiveButton("关闭",null).show();
                    return;
                }
                Set<Point> predictedDead = new HashSet<>();
                if (deadVertices != null && !deadVertices.trim().isEmpty()) {
                    for (String vertex : deadVertices.trim().split("\\s+")) {
                        Point point = Point.fromGtp(vertex,size);
                        if (point != null && board.get(point) != Intersection.EMPTY) predictedDead.add(point);
                    }
                }
                String estimate = score == null || score.trim().isEmpty()
                        ? "计分预测暂不可用" : "预测结果：" + score.trim();
                String deadInfo = deadVertices == null ? "死子预测暂不可用"
                        : "KataGo 预测死子 " + predictedDead.size() + " 颗";
                String caution = endedAtStart
                        ? "双方已停棋；结果仅供复核，正式数子仍需确认。"
                        : "棋局尚未结束；这是当前局面的预测，不代表对局自动结束。";
                Log.i(TAG,String.format(Locale.US,
                        "REFEREE_NATIVE_GTP moves=%d terminal=%s score=%s dead=%d scoreMs=%d deadMs=%d elapsedMs=%d",
                        moves,endedAtStart,score,predictedDead.size(),scoreMs,deadMs,
                        (System.nanoTime()-startedNs)/1000000L));
                nativeRefereeMarkedBoard = snapshot;
                nativeRefereeMarkedMoves = moves;
                boardView.setRefereeMarks(predictedDead,null);
                render("KataGo 原生裁判：" + estimate);
                new AlertDialog.Builder(this).setTitle("智能裁判 · KataGo 原生预测")
                        .setMessage(estimate + "\n" + deadInfo
                                + (predictedDead.isEmpty() ? "" : "\n棋盘红叉为 KataGo 预测死子。")
                                + "\n\n" + caution)
                        .setPositiveButton("查看棋盘",null)
                        .setNeutralButton("清除标记",(dialog,which) -> {
                            nativeRefereeMarkedBoard = null;
                            boardView.setRefereeMarks(null,null);
                        })
                        .setOnDismissListener(dialog -> {
                            if (board == snapshot && board.getMoveCount() == moves && !refereeMode)
                                render(endedAtStart
                                        ? (finalScoreText == null ? "终局预估" : finalScoreText)
                                        : (currentPlayer == playerColor ? "轮到你了" : "轮到 AI"));
                        }).show();
            });
        });
    }

    private static String refereeSearchSummary(String side, List<KataGoEngine.SearchRecommendation> choices) {
        if (choices.isEmpty()) return side + "先：暂无搜索候选";
        KataGoEngine.SearchRecommendation best = choices.get(0);
        String opponent = "黑".equals(side) ? "白" : "黑";
        String leader = best.scoreLead >= 0 ? side : opponent;
        StringBuilder result = new StringBuilder(String.format(Locale.CHINA,
                "%s先：%s（%s方搜索胜率 %.1f%%，预计%s领先 %.1f 目，候选访问 %d）",
                side,best.move,side,best.winrate*100.0,leader,Math.abs(best.scoreLead),best.visits));
        if (!best.pvMoves.isEmpty()) {
            result.append("；变化：");
            for (int i=0; i<best.pvMoves.size() && i<6; i++) {
                if (i>0) result.append(' ');
                result.append(best.pvMoves.get(i));
            }
        }
        return result.toString();
    }

    /**
     * Four user-facing results from strict simple-ko state plus native KataGo PASS comparisons.
     * Not a self-written Go endgame/life-and-death solver. Does not play or pass for either side.
     */
    private void inspectUnfinishedPosition() {
        if (!engineReady || !gameReady || evaluating || thinking || variationBoard != null) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final boolean ended = board.isGameOver();
        final StoneColor side = currentPlayer;
        final Point simpleKo = snapshot.getSimpleKoPoint();
        final int atariGroups = snapshot.countAtariGroups();
        final long startedNs = System.nanoTime();
        render("KataGo：正在比较双方继续落子与 PASS...");
        engineExecutor.execute(() -> {
            KataGoEngine.NativePositionInspection inspection = null;
            if (simpleKo == null) {
                try {
                    inspection = engine.inspectPositionNative(
                            side == StoneColor.BLACK ? "black" : "white",size,24,0.5);
                } catch (RuntimeException ex) {
                    Log.w(TAG,"KataGo endgame comparison unavailable",ex);
                }
            }
            String candidateScore = null, candidateDead = null;
            if (inspection != null && simpleKo == null && atariGroups == 0
                    && inspection.highVariationPoints == 0
                    && inspection.black.estimatedMoveBenefit <= 0.5
                    && inspection.white.estimatedMoveBenefit <= 0.5) {
                // Deeper KataGo queries only run when both PASS comparisons suggest an end.
                try {
                    candidateScore = engine.getFinalScore();
                    candidateDead = engine.getFinalDeadStones();
                } catch (RuntimeException ex) {
                    Log.w(TAG,"KataGo native final score/dead status unavailable",ex);
                }
            }
            final String nativeScore = candidateScore, nativeDead = candidateDead;
            final KataGoEngine.NativePositionInspection nativeInfo = inspection;
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size) {
                    evaluating = false;
                    return;
                }
                evaluating = false;
                String status;
                String explanation;
                if (simpleKo != null) {
                    status = "有打劫";
                    explanation = "当前劫禁点：" + simpleKo.toGtp(size) + "。劫争尚未解决，请继续下棋。";
                } else if (nativeInfo == null) {
                    status = "不能确认";
                    explanation = "KataGo 暂时无法完成双方 PASS 对照分析，请继续下棋或稍后重试。";
                } else {
                    double blackGain = nativeInfo.black.estimatedMoveBenefit;
                    double whiteGain = nativeInfo.white.estimatedMoveBenefit;
                    // These are conservative DISPLAY thresholds for two KataGo estimates,
                    // not a locally implemented algorithm for calculating territory or life/death.
                    if (blackGain >= 1.5 || whiteGain >= 1.5) {
                        status = "官子未收完";
                        explanation = "KataGo 认为继续落子仍有明显收益，可能是官子或局部攻防。建议继续下棋。";
                    } else if (atariGroups == 0 && nativeInfo.highVariationPoints == 0
                            && blackGain <= 0.5 && whiteGain <= 0.5
                            && nativeScore != null && !nativeScore.trim().isEmpty() && nativeDead != null) {
                        status = "裁判完成";
                        explanation = "KataGo 认为官子基本收完。预测结果：" + nativeScore.trim() + "。死子预测已完成，需核对。";
                    } else {
                        status = "不能确认";
                        explanation = atariGroups > 0
                                ? "存在单气棋块，尚不能确认死活，请继续下棋。"
                                : nativeInfo.highVariationPoints > 0
                                ? "KataGo 预测的归属存在波动，可能有死活或争议，请继续下棋。"
                                : nativeScore == null || nativeDead == null
                                ? "PASS 对照接近，但原生计分或死子预测未完成，请继续下棋或稍后复核。"
                                : "搜索证据不足，暂不能确认终局，请继续下棋。";
                    }
                }
                String detail = nativeInfo == null ? "KataGo 原生分析没有返回足够数据。"
                        : String.format(Locale.CHINA,
                        "黑方 %s：继续落子相对 PASS 预计收益 %.2f 目。\n"
                        + "白方 %s：继续落子相对 PASS 预计收益 %.2f 目。\n"
                        + "归属波动最大 %.3f，明显波动点 %d 个。\n"
                        + "这些数据不能证明双活、死活或复杂劫争已解决。",
                        nativeInfo.black.bestMove,nativeInfo.black.estimatedMoveBenefit,
                        nativeInfo.white.bestMove,nativeInfo.white.estimatedMoveBenefit,
                        nativeInfo.maximumOwnershipStdev,nativeInfo.highVariationPoints);
                Set<Point> predictedDead = new HashSet<>();
                if ("裁判完成".equals(status) && nativeDead != null && !nativeDead.trim().isEmpty()) {
                    for (String vertex : nativeDead.trim().split("\\s+")) {
                        Point point = Point.fromGtp(vertex,size);
                        if (point != null && board.get(point) != Intersection.EMPTY) predictedDead.add(point);
                    }
                }
                if ("裁判完成".equals(status)) {
                    nativeRefereeMarkedBoard = snapshot;
                    nativeRefereeMarkedMoves = moves;
                    boardView.setRefereeMarks(predictedDead,null);
                    explanation += " KataGo 预测死子 " + predictedDead.size() + " 颗，棋盘红叉仅供复核。";
                }
                String message = explanation + "\n\n以上为 KataGo 估计，不会自动结束或改动棋谱。";
                Log.i(TAG,String.format(Locale.US,
                        "REFEREE_ENDGAME_RESULT status=%s moves=%d terminal=%s ko=%s atari=%d blackGain=%.3f whiteGain=%.3f highVariation=%d elapsedMs=%d",
                        status,moves,ended,simpleKo == null ? "none" : simpleKo.toGtp(size),
                        atariGroups,nativeInfo == null ? Double.NaN : nativeInfo.black.estimatedMoveBenefit,
                        nativeInfo == null ? Double.NaN : nativeInfo.white.estimatedMoveBenefit,
                        nativeInfo == null ? -1 : nativeInfo.highVariationPoints,
                        (System.nanoTime()-startedNs)/1000000L));
                render("局面检查：" + status);
                AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                        .setTitle("智能裁判 · " + status).setMessage(message)
                        .setPositiveButton("裁判完成".equals(status) ? "查看棋盘" : "继续下棋", (d,w) -> {
                            if (!"裁判完成".equals(status) && ended
                                    && board == snapshot && board.canResumeAfterPasses()) resumeFromReferee();
                        })
                        .setNeutralButton("查看详情", (d,w) -> new AlertDialog.Builder(this)
                                .setTitle("KataGo 分析依据").setMessage(detail)
                                .setPositiveButton("关闭",null).show());
                dialog.show();
            });
        });
    }

    private void startSmartReferee() {
        if (!engineReady || !gameReady || evaluating || thinking || variationBoard != null) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final boolean endedAtStart = board.isGameOver();
        // Endgame ownership caches do not describe an ongoing, changing game.
        final KataGoEngine.PositionEvaluation cached = endedAtStart ? endgameEvaluation : null;
        render(endedAtStart ? "智能裁判：正在检查终局争议..." : "智能裁判：正在检查当前棋形...");
        engineExecutor.execute(() -> {
            SmartReferee.Analysis analysis = null;
            KataGoEngine.PositionEvaluation nn = cached;
            try {
                if (nn == null || nn.size != size) nn = engine.evaluatePosition(size);
                if (nn != null) analysis = SmartReferee.analyze(snapshot, nn.whiteOwnership);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Smart referee NN dead-chain suggestion unavailable", ex);
            }
            final SmartReferee.Analysis suggestion = analysis;
            final KataGoEngine.PositionEvaluation evaluated = nn;
            SmartReferee.ScoreWhatIf range = null;
            if (analysis != null && analysis.reviewGroups > 0) {
                try {
                    range = SmartReferee.scoreWhatIf(snapshot,analysis.suggestedDead,analysis.needsReview,
                            komiFor(size),gameCondition >= 2 ? gameCondition : 0);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "Endgame hypothetical score range unavailable", ex);
                }
            }
            final SmartReferee.ScoreWhatIf sensitivity = range;
            EndgameAdjudicator.Result verdict;
            try {
                verdict = EndgameAdjudicator.analyze(snapshot, nn == null ? null : nn.whiteOwnership);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Endgame triage failed; request manual review", ex);
                verdict = EndgameAdjudicator.analyze(snapshot, null);
            }
            final EndgameAdjudicator.Result decision = verdict;
            String searchEvidence = "";
            List<List<String>> blackVariations = new ArrayList<>(), whiteVariations = new ArrayList<>();
            if ((!endedAtStart || decision.verdict != EndgameAdjudicator.Verdict.READY_TO_COUNT) && nn != null) {
                try {
                    // Search as both sides, but NEVER treat the suggested moves as a solved life/death proof.
                    List<KataGoEngine.SearchRecommendation> black = engine.searchRecommendations("black", 48, 1.0);
                    List<KataGoEngine.SearchRecommendation> white = engine.searchRecommendations("white", 48, 1.0);
                    for (KataGoEngine.SearchRecommendation move : black) blackVariations.add(move.pvMoves);
                    for (KataGoEngine.SearchRecommendation move : white) whiteVariations.add(move.pvMoves);
                    searchEvidence = "\n" + refereeSearchSummary("黑",black)
                            + "\n" + refereeSearchSummary("白",white)
                            + "\n以上仅是48次访问上限、每方1秒的短搜索估计；"
                            + "候选变化可揭示攻防风险，但不是死活证明，仍需逐块复核。";
                    Log.i(TAG, "ENDGAME_TWO_SIDE_SEARCH black="
                            + (black.isEmpty() ? "none" : black.get(0).move)
                            + " white=" + (white.isEmpty() ? "none" : white.get(0).move));
                } catch (RuntimeException ex) {
                    Log.w(TAG, "Endgame two-sided search unavailable; keep manual review", ex);
                    searchEvidence = "\n双方先手搜索不可用，请人工确认；不能凭此认定已经结束。";
                }
            }
            final MidgameTacticalReview.Result tactical = endedAtStart ? null
                    : MidgameTacticalReview.analyze(snapshot,decision,blackVariations,whiteVariations);
            if (tactical != null) Log.i(TAG,"MIDGAME_LOCAL_SEARCH candidates=" + tactical.candidates
                    + " corroborated=" + tactical.searchSupported + " points=" + tactical.tacticalMoves.size());
            final String mctsInfo = searchEvidence;
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size) return;
                if (evaluated != null && evaluated.size == size && endedAtStart) endgameEvaluation = evaluated;
                endgameDecision = decision;
                refereeMode = true;
                refereeAwaitingTap = false;
                refereeDead.clear();
                refereeSuggested.clear();
                refereeReview.clear();
                refereeInitialReview.clear();
                if (endedAtStart && suggestion != null) {
                    // NN ownership is acceptable as a tentative ENDGAME scoring
                    // suggestion, but never declare a midgame string dead.
                    refereeDead.addAll(suggestion.suggestedDead);
                    refereeSuggested.addAll(suggestion.suggestedDead);
                    refereeReview.addAll(suggestion.needsReview);
                    refereeInitialReview.addAll(suggestion.needsReview);
                } else if (!endedAtStart && tactical != null) {
                    refereeReview.addAll(tactical.threatenedStones);
                    refereeInitialReview.addAll(tactical.threatenedStones);
                }
                boardView.setOwnership(null);
                boardView.setRefereeMarks(refereeDead, refereeReview);
                Set<Point> alerts = new HashSet<>();
                if (!endedAtStart) {
                    // Orange: tactical liberty corroborated by a local two-order
                    // simulation and a short KataGo PV. Yellow: threatened stones.
                    // No midgame red crosses, generic ownership or seki guesses.
                    if (tactical != null) alerts.addAll(tactical.tacticalMoves);
                } else {
                    for (EndgameAdjudicator.Finding finding : decision.findings) {
                        if (finding.reason == EndgameAdjudicator.Reason.FIRST_MOVE_TACTIC
                                || finding.reason == EndgameAdjudicator.Reason.POSSIBLE_KO
                                || finding.reason == EndgameAdjudicator.Reason.POSSIBLE_SEKI) {
                            if (finding.suggestedMove != null) alerts.add(finding.suggestedMove);
                            else alerts.addAll(finding.points);
                        }
                    }
                    if (decision.verdict != EndgameAdjudicator.Verdict.READY_TO_COUNT
                            && alerts.isEmpty() && refereeDead.isEmpty() && refereeReview.isEmpty())
                        alerts.addAll(decision.highlights);
                }
                boardView.setEndgameAlerts(alerts);
                // No marked dispute in an ongoing game is not an endgame
                // controversy. Show a single, short informational dialog and
                // immediately return to normal play, without a second tap.
                if (!endedAtStart && refereeDead.isEmpty() && refereeReview.isEmpty() && alerts.isEmpty()) {
                    refereeMode = false;
                    endgameDecision = null;
                    boardView.setRefereeMarks(null,null);
                    boardView.setEndgameAlerts(null);
                    render("棋局还没下完");
                    new AlertDialog.Builder(this).setTitle("智能裁判")
                            .setMessage("棋局还没下完")
                            .setPositiveButton("继续下棋",null).show();
                    if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","NO_DISPUTE id=" + refereeSmokeId()
                            + " moves=" + board.getMoveCount() + " terminal=" + board.isGameOver());
                    return;
                }
                refreshRefereePreview();
                String info = suggestion == null
                        ? "AI 建议不可用。可手动点击整块棋标记死子。"
                        : String.format(Locale.CHINA, "AI 建议死子 %d 块，需关注 %d 块；仅供参考。",
                                suggestion.suggestedGroups, suggestion.reviewGroups);
                StringBuilder reasons = new StringBuilder(info).append("\n\n终局判断：")
                        .append(decision.summary()).append("\n");
                int shown = 0;
                for (EndgameAdjudicator.Finding finding : decision.findings) {
                    if (shown++ == 6) { reasons.append("还有其他提示，请查看日志。\n"); break; }
                    reasons.append("· ").append(finding.message);
                    if (finding.suggestedMove != null)
                        reasons.append("（建议关注 ").append(finding.suggestedMove.toGtp(size)).append("）");
                    reasons.append("\n");
                    Log.i(TAG, "ENDGAME_REASON reason=" + finding.reason + " message=" + finding.message
                            + " point=" + finding.suggestedMove);
                }
                reasons.append(mctsInfo);
                if (sensitivity != null && sensitivity.winnerCanChange()) {
                    reasons.append(String.format(Locale.CHINA,
                            "\n不同死子组合可能改变胜负：白方净胜目试算范围 %+.1f 至 %+.1f（负数为黑方领先）。",
                            sensitivity.minWhiteLead,sensitivity.maxWhiteLead));
                    if (!sensitivity.exhaustive()) reasons.append("仅分析前8块待复核棋，范围不完整。");
                    reasons.append("\n这是极端假设，不表示任何棋块已死，必须人工复核。\n");
                }
                reasons.append("\n红叉：AI 建议判死；黄圈：需明确复核；橙圈：需要关注的争议位置。\n"
                        + "以上均为辅助判断，不能代替双方确认。");
                // Ongoing games can NEVER enter formal scoring, regardless of
                // how confident a future adjudicator version may become.
                if (endedAtStart && decision.verdict == EndgameAdjudicator.Verdict.READY_TO_COUNT) {
                    new AlertDialog.Builder(this).setTitle("智能裁判 · " + decision.summary())
                            .setMessage(reasons.toString())
                            .setPositiveButton("确认数子", null)
                            .setNegativeButton("稍后确认", null).show();
                } else {
                    // Dialog first, with marked stones already drawn on the board.
                    // Continue only dismisses the modal; the following screen
                    // tap clears the annotations and resumes the game.
                    StringBuilder prompt = new StringBuilder(String.format(Locale.CHINA,
                            endedAtStart ? "棋盘上已标出争议：红叉 %d 处、黄圈 %d 处、橙圈 %d 处。\n"
                                    : "正在对局：黄圈 %d 颗棋子、橙圈 %d 个攻防点；中盘不判死（红叉 0 处）。\n",
                            endedAtStart ? refereeDead.size() : refereeReview.size(),
                            endedAtStart ? refereeReview.size() : alerts.size(),
                            endedAtStart ? alerts.size() : 0));
                    if (endedAtStart) {
                        int described = 0;
                        for (EndgameAdjudicator.Finding finding : decision.findings) {
                            if (described++ >= 2) break;
                            prompt.append("· ").append(finding.message).append("\n");
                        }
                    } else {
                        prompt.append("已经模拟双方在关键点先落一手，并参考短搜索变化；只是攻防提醒，不是死活结论。\n");
                    }
                    prompt.append("\n点「继续下棋」关闭弹窗，争议标记仍保留。");
                    prompt.append(endedAtStart
                            ? "\n然后再点屏幕任意位置，清除标记并恢复对局。"
                            : "\n然后再点屏幕任意位置，清除标记并继续当前对局；不会撤销落子。");
                    new AlertDialog.Builder(this).setTitle(endedAtStart
                            ? "智能裁判 · 存在争议" : "智能裁判 · 当前棋形分析")
                            .setMessage(prompt.toString()).setCancelable(false)
                            .setPositiveButton("继续下棋", (dialog, which) -> armRefereeTapToResume())
                            .show();
                    Log.i(TAG,"REFEREE_BOARD_DISPUTE dead=" + refereeDead.size()
                            + " review=" + refereeReview.size() + " alerts=" + alerts.size());
                    if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","OVERLAY id=" + refereeSmokeId()
                            + " dead=" + refereeDead.size() + " review=" + refereeReview.size()
                            + " alerts=" + alerts.size());
                }
                if (isRefereeSmokeIntent()) {
                    SmartReferee.ScoreWhatIf actualRange = sensitivity;
                    String game = getIntent().getStringExtra("referee_smoke_game");
                    if (game == null) game = "cgos9_expand_006_12";
                    boolean knownCase = endedAtStart && "cgos9_expand_006_12".equals(game);
                    boolean hasSearch = decision.verdict == EndgameAdjudicator.Verdict.READY_TO_COUNT
                            || (mctsInfo.contains("黑先：") && mctsInfo.contains("白先："));
                    boolean genericGood = suggestion != null
                            && (endedAtStart == board.isGameOver()) && (!endedAtStart || hasSearch)
                            && reasons.toString().contains("黄圈");
                    boolean strictGood = !knownCase || (suggestion.suggestedGroups == 1
                            && suggestion.reviewGroups == 2 && suggestion.vulnerableGroups == 1
                            && actualRange != null && actualRange.exhaustive()
                            && Math.abs(actualRange.minWhiteLead + 20.0) < 0.01
                            && Math.abs(actualRange.maxWhiteLead - 8.0) < 0.01
                            && decision.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED
                            && mctsInfo.contains("搜索胜率") && mctsInfo.contains("变化：")
                            && reasons.toString().contains("不同死子组合可能改变胜负"));
                    boolean good = genericGood && strictGood;
                    Log.i("RefereeSmoke",String.format(Locale.US,
                            "%s id=%s game=%s stage=%s verdict=%s moves=%d dead=%d review=%d vulnerable=%d "
                            + "minLead=%.1f maxLead=%.1f hasBlack=%s hasWhite=%s hasPV=%s",
                            good ? "PASS" : "FAIL",refereeSmokeId(),game,
                            !endedAtStart ? "midgame_dialog"
                                    : decision.verdict == EndgameAdjudicator.Verdict.READY_TO_COUNT ? "dialog" : "dispute_dialog",
                            decision.verdict,moves,
                            suggestion == null ? -1 : suggestion.suggestedGroups,
                            suggestion == null ? -1 : suggestion.reviewGroups,
                            suggestion == null ? -1 : suggestion.vulnerableGroups,
                            actualRange == null ? Double.NaN : actualRange.minWhiteLead,
                            actualRange == null ? Double.NaN : actualRange.maxWhiteLead,
                            mctsInfo.contains("黑先："),mctsInfo.contains("白先："),mctsInfo.contains("变化：")));
                }
            });
        });
    }

    /** The modal's Continue action only arms the next screen tap; it never plays a move. */
    private void armRefereeTapToResume() {
        if (!refereeMode || !refereeRequiresContinuation()
                || (board.isGameOver() && !board.canResumeAfterPasses())) {
            Toast.makeText(this,"当前不能继续对局",Toast.LENGTH_LONG).show();
            return;
        }
        refereeAwaitingTap = true;
        render("争议已标记，点屏幕清除标记并继续下棋");
        if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","ARMED id=" + refereeSmokeId()
                + " moves=" + board.getMoveCount() + " terminal=" + board.isGameOver()
                + " marked=" + (refereeDead.size()+refereeReview.size()));
    }

    /** Review toolbar always operates on temporary stone marks, never on the live board. */
    private void resetRefereeSuggestions() {
        if (!refereeMode) return;
        refereeDead.clear();
        refereeDead.addAll(refereeSuggested);
        refereeReview.clear();
        refereeReview.addAll(refereeInitialReview);
        refreshRefereePreview();
    }

    private void leaveRefereeReview() {
        if (!refereeMode) return;
        refereeMode = false;
        refereeDead.clear();
        refereeReview.clear();
        if (refereeConfirmed) {
            refereeDead.addAll(refereeAccepted);
            boardView.setRefereeMarks(refereeAccepted,null);
        } else boardView.setRefereeMarks(null,null);
        boardView.setEndgameAlerts(null);
        render(finalScoreText == null ? "快速 AI 预估" : finalScoreText);
    }

    private void refreshRefereePreview() {
        double nnLead = endgameEvaluation == null ? fastEndgameLead : endgameEvaluation.whiteLead;
        SmartReferee.ScoreAudit audit = SmartReferee.audit(board, refereeDead, nnLead, komiFor(boardSize),
                gameCondition >= 2 ? gameCondition : 0);
        double lead = audit.whiteLead;
        int pending = SmartReferee.countGroups(board, refereeReview);
        boardView.setRefereeMarks(refereeDead, refereeReview);
        boolean mustContinue = refereeRequiresContinuation();
        String shortStatus = mustContinue ? "智能裁判\n建议继续下" : "智能裁判\n确认数子";
        refereePanelTitle.setText(!board.isGameOver() ? "智能裁判 · 当前棋形"
                : mustContinue ? "智能裁判 · 存在争议" : "智能裁判 · 可以确认数子");
        refereePanelScore.setText(!board.isGameOver()
                ? "黄圈：局部有危险的棋块  ·  橙圈：关键攻防点"
                : mustContinue ? "红叉：疑似死棋  ·  黄圈：待确认  ·  橙圈：争议点"
                : String.format(Locale.CHINA,"黑 %.1f · 白 %.1f · %s",
                        audit.score.blackPoints,audit.score.whitePoints,
                        Math.abs(lead) < 0.001 ? "平局" :
                                String.format(Locale.CHINA,"%s领先 %.1f 目",lead > 0 ? "白" : "黑",Math.abs(lead))));
        refereePanelHint.setText(mustContinue
                ? "轻触屏幕任意位置：清除标记，继续下棋"
                : "双方已停棋且无明显争议；确认死子后即可数子。");
        render(shortStatus);
    }

    private void showRefereeConfirmation() { showRefereeConfirmation(false); }

    private void showRefereeConfirmation(boolean ignoreContinueAdvice) {
        // Unresolved disputes always return to play, never manual scoring.
        if (refereeRequiresContinuation()) {
            resumeFromReferee();
            return;
        }
        if (!refereeReview.isEmpty()) {
            int pending = SmartReferee.countGroups(board, refereeReview);
            AlertDialog.Builder reviewPrompt = new AlertDialog.Builder(this)
                    .setTitle("还有 " + pending + " 块死子需要确认")
                    .setMessage("双方已经停棋。请点棋盘上的黄圈逐块确认判活或判死；也可以明确将所有黄圈判活。")
                    .setPositiveButton("返回棋盘确认", null)
                    .setNeutralButton("黄圈全部判活", (dialog, which) -> {
                        refereeDead.removeAll(refereeReview);
                        refereeReview.clear();
                        refreshRefereePreview();
                        showRefereeConfirmation();
                    });
            reviewPrompt.show();
            return;
        }
        if (!ignoreContinueAdvice && endgameDecision != null
                && endgameDecision.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED) {
            boolean sekiOrKo = false;
            for (EndgameAdjudicator.Finding item : endgameDecision.findings)
                if (item.reason == EndgameAdjudicator.Reason.POSSIBLE_SEKI
                        || item.reason == EndgameAdjudicator.Reason.POSSIBLE_KO) sekiOrKo = true;
            if (sekiOrKo) {
                AlertDialog.Builder warning = new AlertDialog.Builder(this).setTitle("疑似双活或劫争尚未证明")
                        .setMessage("AI 只发现了类似双活或劫形的结构，无法保证争议已经解决。"
                                + "双方既已停棋，请核对棋形后再确认数子。")
                        .setPositiveButton("人工确认并数子", (dialog, which) -> showRefereeConfirmation(true))
                        .setNegativeButton("返回棋盘检查", null);
                warning.show();
                return;
            }
        }
        double nnLead = endgameEvaluation == null ? fastEndgameLead : endgameEvaluation.whiteLead;
        SmartReferee.ScoreAudit audit = SmartReferee.audit(board, refereeDead, nnLead, komiFor(boardSize),
                gameCondition >= 2 ? gameCondition : 0);
        GoBoard.FinalScore totals = audit.score;
        String verdict = SmartReferee.resultText(totals);
        String warning = audit.needsVerification ? String.format(Locale.CHINA,
                "\n注意：AI 估分与数子相差 %.1f 目，可能有死活争议，请再次核对。\n", audit.difference) : "";
        new AlertDialog.Builder(this).setTitle("确认中国规则数子")
                .setMessage(String.format(Locale.CHINA,
                        "黑棋 %.1f，白棋 %.1f\n结果：%s\n"
                        + "红叉为准备移除的死子。AI 判断不是裁判证明，请逐块确认。"
                        + "%s\n这是用户复核的中国规则数子；若为双人对局应双方确认。",
                        totals.blackPoints, totals.whitePoints, verdict, warning))
                .setPositiveButton("已复核并确认", (dialog, which) -> {
                    finalDeadStones = SmartReferee.toGtpVertices(refereeDead, boardSize);
                    gameResultText = "确认数子 · " + ("0".equals(verdict) ? "平局" :
                            (verdict.startsWith("W") ? "白胜 " : "黑胜 ") + verdict.substring(2) + " 目");
                    finalScoreText = String.format(Locale.CHINA,
                            "中国规则（确认死子后）\n黑 %.1f · 白 %.1f\n%s",
                            totals.blackPoints, totals.whitePoints, gameResultText);
                    preciseScoreReady = true;
                    refereeConfirmed = true;
                    refereeAccepted.clear();
                    refereeAccepted.addAll(refereeDead);
                    fastEndgameLead = Double.NaN;
                    refereeMode = false;
                    refereeReview.clear();
                    boardView.setRefereeMarks(refereeDead, null);
                    boardView.setEndgameAlerts(null);
                    Log.i(TAG, "REFEREE_CONFIRMED score=" + verdict + " dead=" + finalDeadStones
                            + " black=" + totals.blackPoints + " white=" + totals.whitePoints);
                    render(finalScoreText);
                })
                .setNeutralButton("恢复 AI 建议", (dialog, which) -> resetRefereeSuggestions())
                .setNegativeButton("退出裁判", (dialog, which) -> leaveRefereeReview()).show();
    }

    /** Midgame inspection only clears marks; terminal inspection also undoes both passes. */
    private void resumeFromReferee() {
        if (evaluating || thinking || !engineReady || !gameReady) {
            Toast.makeText(this, "当前不能恢复对局", Toast.LENGTH_LONG).show();
            return;
        }
        if (!board.isGameOver()) {
            final int moves = board.getMoveCount();
            refereeAwaitingTap = false;
            refereeMode = false;
            refereeConfirmed = false;
            refereeAccepted.clear();
            refereeDead.clear();
            refereeReview.clear();
            refereeSuggested.clear();
            refereeInitialReview.clear();
            boardView.setRefereeMarks(null,null);
            boardView.setEndgameAlerts(null);
            endgameDecision = null;
            // Preserve the engine move history and side to move exactly as they were.
            render(currentPlayer == playerColor ? "棋形检查完成，轮到你了" : "棋形检查完成，轮到 AI");
            if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","INSPECT_RESUME id=" + refereeSmokeId()
                    + " moves=" + moves + " after=" + board.getMoveCount() + " terminal="
                    + board.isGameOver() + " refereeMode=" + refereeMode + " gameReady=" + gameReady);
            return;
        }
        if (!board.canResumeAfterPasses()) {
            Toast.makeText(this,"当前不能恢复对局",Toast.LENGTH_LONG).show();
            return;
        }
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount();
        final Move lastPass = board.getLastMove();
        final StoneColor next = lastPass.color.opposite();
        evaluating = true;
        refereeAwaitingTap = false;
        // Hide dispute markings instantly on the dismissing tap. The touch
        // overlay consumes the gesture so it cannot also place a new stone.
        boardView.setRefereeMarks(null,null);
        boardView.setEndgameAlerts(null);
        render("正在恢复对局并同步引擎...");
        engineExecutor.execute(() -> {
            boolean first = engine.undo();
            boolean restored = first && engine.undo();
            mainHandler.post(() -> {
                if (snapshot != board || moves != board.getMoveCount()) return;
                evaluating = false;
                if (!restored || !board.resumeAfterPasses()) {
                    gameReady = false;
                    refereeMode = false;
                    endgameDecision = null;
                    render("恢复对局失败：棋盘与 AI 可能不同步，请重新开局");
                    if (isRefereeSmokeIntent()) Log.e("RefereeSmoke","RESUME_FAIL id=" + refereeSmokeId()
                            + " moves=" + moves + " undo=" + restored);
                    return;
                }
                refereeMode = false;
                refereeConfirmed = false;
                refereeAccepted.clear();
                refereeDead.clear();
                refereeReview.clear();
                refereeSuggested.clear();
                refereeInitialReview.clear();
                boardView.setRefereeMarks(null, null);
                boardView.setEndgameAlerts(null);
                boardView.setOwnership(null);
                clearRecommendations();
                finalDeadStones = null;
                finalScoreText = null;
                gameResultText = null;
                preciseScoreReady = false;
                fastEndgameLead = Double.NaN;
                endgameEvaluation = null;
                endgameDecision = null;
                lastMove = null;
                currentPlayer = next;
                winRateSession++;
                winRateEpoch++;
                while (blackWinHistory.size() > board.getMoveCount()+1) {
                    blackWinHistory.remove(blackWinHistory.size()-1);
                    whiteWinHistory.remove(whiteWinHistory.size()-1);
                }
                resyncWinRateAfterReferee();
                render(currentPlayer == playerColor ? "继续对局：轮到你了" : "继续对局：轮到 AI");
                if (isRefereeSmokeIntent()) Log.i("RefereeSmoke","RESUME id=" + refereeSmokeId()
                        + " moves=" + board.getMoveCount() + " terminal=" + board.isGameOver()
                        + " refereeMode=" + refereeMode + " gameReady=" + gameReady);
                if (currentPlayer != playerColor) requestAiMove();
            });
        });
    }

    private void resyncWinRateAfterReferee() {
        final long session = winRateSession, epoch = winRateEpoch;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        final StoneColor next = currentPlayer;
        final float komi = komiFor(size);
        final List<Move> history = board.getMoveHistorySnapshot();
        final List<Point> handicap = gameCondition >= 2
                ? GoBoard.standardHandicapPoints(size, gameCondition) : new ArrayList<>();
        winRateExecutor.execute(() -> {
            if (session != winRateSession) return;
            boolean ok = winRateEngine.isReady() || winRateEngine.start(KataGoEngine.Model.HUMAN);
            if (ok) ok = winRateEngine.setBoardSize(size);
            if (ok) ok = winRateEngine.clearBoard();
            if (ok) ok = winRateEngine.setChineseRules();
            if (ok) ok = winRateEngine.setKomi(komi);
            if (ok && !handicap.isEmpty()) ok = winRateEngine.setHandicapStones(handicap, size);
            for (Move move : history) {
                if (!ok) break;
                String vertex = move instanceof Move.Stone
                        ? ((Move.Stone) move).point.toGtp(size) : "pass";
                ok = winRateEngine.playMove(move.color.toGtp(), vertex);
            }
            if (session != winRateSession) return;
            winRateSynchronized = ok;
            if (!ok) {
                Log.w(TAG, "Could not resync independent winrate engine after resumed endgame");
                return;
            }
            KataGoEngine.WinRate rate = winRateEngine.evaluateWinRate(next.toGtp());
            mainHandler.post(() -> recordWinRate(snapshot, moves, epoch, rate));
        });
    }

    /** Optional same-game comparison using original KataGo final_score and dead-status search. */
    private void requestPreciseEndgame() {
        DebugLog.enter(TAG, "requestPreciseEndgame in, evaluating=" + evaluating);
        if (!engineReady || evaluating || !board.isGameOver() || finalDeadStones == null || preciseScoreReady) {
            Toast.makeText(this, "请先完成快速结算", Toast.LENGTH_SHORT).show();
            return;
        }
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount();
        final long epoch = winRateEpoch;
        final String preliminaryDead = finalDeadStones;
        final double preliminaryLead = fastEndgameLead;
        final long startedNs = System.nanoTime();
        render("正在深度搜索对照目差...");
        engineExecutor.execute(() -> {
            String score = engine.getFinalScore();
            String actualDead = score == null ? null : engine.getFinalDeadStones();
            final String deadStones = actualDead == null ? preliminaryDead : actualDead;
            long elapsedMs = (System.nanoTime() - startedNs) / 1000000L;
            double deepLead = parseScoreWhiteLead(score);
            Log.i(TAG, String.format(Locale.US,
                    "ENDGAME_TIMING phase=precise_done elapsedMs=%d valid=%s score=%s",
                    elapsedMs, score != null, score == null ? "null" : score.trim()));
            if (Double.isFinite(preliminaryLead) && Double.isFinite(deepLead)) {
                Log.i(TAG, String.format(Locale.US,
                        "ENDGAME_COMPARE fastLead=%.3f deepLead=%.3f delta=%.3f fastDead=%s deepDead=%s",
                        preliminaryLead, deepLead, deepLead - preliminaryLead,
                        preliminaryDead, actualDead == null ? "unavailable" : actualDead));
            }
            mainHandler.post(() -> {
                if (board != snapshot || board.getMoveCount() != moves || winRateEpoch != epoch) return;
                evaluating = false;
                if (score != null) {
                    preciseScoreReady = true;
                    finalDeadStones = deadStones;
                    gameResultText = formatFinalResult(score);
                    finalScoreText = finalPointsSummary(score, deadStones);
                    recordFinalWinRate(snapshot, moves, epoch, score);
                } else {
                    Toast.makeText(this, "深度估算失败，保留快速结果", Toast.LENGTH_LONG).show();
                }
                render(finalScoreText);
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

    private double parseScoreWhiteLead(String score) {
        if (score == null) return Double.NaN;
        try {
            String text = score.trim().toUpperCase(Locale.US);
            if ("0".equals(text)) return 0.0;
            if (text.startsWith("W+")) return Double.parseDouble(text.substring(2));
            if (text.startsWith("B+")) return -Double.parseDouble(text.substring(2));
        } catch (NumberFormatException ignored) {
            // Resign/no-result formats have no numeric lead.
        }
        return Double.NaN;
    }

    private String formatFinalResult(String score) {
        if (score == null || score.trim().isEmpty()) return "对局结束 · 未得到数目结果";
        String text = score.trim();
        if ("0".equals(text)) return "对局结束 · 搜索预测平局";
        if (text.startsWith("B+")) return "对局结束 · 搜索预测黑胜 " + text.substring(2);
        if (text.startsWith("W+")) return "对局结束 · 搜索预测白胜 " + text.substring(2);
        return "对局结束 · 搜索预测 " + text;
    }

    /** Local Chinese area points after removing GTP-predicted dead stones. */
    private GoBoard.FinalScore calculateChineseFinalTotals(String deadVertices) {
        Set<Point> deadStones = new HashSet<>();
        for (String vertex : deadVertices.trim().split("\\s+")) {
            Point point = Point.fromGtp(vertex, boardSize);
            if (point != null && board.get(point) != Intersection.EMPTY) deadStones.add(point);
        }
        return board.countChineseScore(deadStones, komiFor(boardSize), gameCondition >= 2 ? gameCondition : 0);
    }

    /** Always label the fast estimate as approximate; a delayed precise query
     * may disagree because final_score predicts how play could continue. */
    private String finalPointsSummary(String finalScore, String deadVertices) {
        if (deadVertices == null) return "终局数子\n结果不可用";
        GoBoard.FinalScore totals = calculateChineseFinalTotals(deadVertices);
        double expectedLead = Double.NaN;
        if (finalScore != null) {
            try {
                String text = finalScore.trim().toUpperCase(Locale.US);
                if ("0".equals(text)) expectedLead = 0.0;
                else if (text.startsWith("W+")) expectedLead = Double.parseDouble(text.substring(2));
                else if (text.startsWith("B+")) expectedLead = -Double.parseDouble(text.substring(2));
            } catch (NumberFormatException e) {
                Log.w(TAG, "KataGo final score does not have a numeric margin: " + finalScore, e);
            }
        }
        double calculatedLead = totals.whitePoints - totals.blackPoints;
        boolean agrees = Double.isFinite(expectedLead) && Math.abs(expectedLead - calculatedLead) < 0.01;
        Log.i(TAG, String.format(Locale.US,
                "Chinese area score B stones=%d territory=%d, W stones=%d territory=%d, "
                        + "dead B=%d W=%d, komi=%.1f, total B=%.1f W=%.1f, lead local=%.1f engine=%.1f agrees=%s mode=%s",
                totals.blackStones, totals.blackTerritory, totals.whiteStones, totals.whiteTerritory,
                totals.deadBlack, totals.deadWhite, komiFor(boardSize),
                totals.blackPoints, totals.whitePoints, calculatedLead, expectedLead, agrees,
                finalScore == null ? "fast" : "precise"));
        if (finalScore != null && !agrees)
            Log.w(TAG, "Chinese area score differs from KataGo final_score; these totals are approximate");
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
        String[] backendNames = gpuBundled ? new String[]{"CPU / Eigen", "GPU / OpenCL"} : new String[]{"CPU / Eigen（GPU 未打包）"};
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, backendNames);
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backendSpinner.setAdapter(backendAdapter);
        backendSpinner.setSelection(gpuBundled && selectedBackend == KataGoEngine.BackendPreference.GPU ? 1 : 0);
        backendSpinner.setEnabled(gpuBundled && !forceLegacyBackend);

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
            KataGoEngine.BackendPreference requested = gpuBundled && backendSpinner.getSelectedItemPosition() == 1
                    ? KataGoEngine.BackendPreference.GPU : KataGoEngine.BackendPreference.CPU;
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
        finalDeadStones = null;
        preciseScoreReady = false;
        fastEndgameLead = Double.NaN;
        endgameEvaluation = null;
        endgameDecision = null;
        refereeMode = false;
        refereeAwaitingTap = false;
        refereeConfirmed = false;
        refereeAccepted.clear();
        refereeDead.clear();
        refereeSuggested.clear();
        refereeReview.clear();
        refereeInitialReview.clear();
        boardView.setRefereeMarks(null, null);
        boardView.setEndgameAlerts(null);
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
        boolean reusingHumanEngine = engine.isHumanSLRunning()
                && (selectedBackend == KataGoEngine.BackendPreference.GPU) == engine.getBackendName().startsWith("GPU");
        render(reusingHumanEngine ? "正在初始化棋局..." : requestedBackend == KataGoEngine.BackendPreference.GPU
                ? "GPU 初始化中，首次调优可能需要数分钟..." : "正在准备人类棋力模型...");
        if (!reusingHumanEngine) showGpuStartupScreen(size, requestedBackend);

        engineExecutor.execute(() -> {
            String error = null;
            try {
                engine.setStartupBoardSize(size);
                engine.setBackendPreference(requestedBackend);
                advanceGpuStartup(8, "正在准备对弈模型...");
                if (!engine.isHumanSLRunning()) {
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
                if (gpuFallback) Toast.makeText(this, "GPU 不可用，已回退 " + runningBackend, Toast.LENGTH_LONG).show();
                if (playerFirst) render(gpuFallback ? "轮到你了（已回退 CPU）" : "轮到你了");
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
        if (nativeRefereeMarkedBoard != null && (board != nativeRefereeMarkedBoard
                || board.getMoveCount() != nativeRefereeMarkedMoves)) {
            nativeRefereeMarkedBoard = null;
            boardView.setRefereeMarks(null,null);
        }
        statusText.setText(message);
        updateWinRateLabels();
        aiDifficultyText.setText(getDifficultyName() + " · Human SL · "
                + (engineReady ? engine.getBackendName() : selectedBackend == KataGoEngine.BackendPreference.GPU ? "GPU 准备中" : "CPU 准备中"));
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled((refereeMode && !evaluating && !thinking)
                || (engineReady && gameReady && !engineStarting && !thinking
                && !evaluating && currentPlayer == playerColor && !board.isGameOver()));
        boolean detailedResult = refereeMode || refereeConfirmed || boardView.hasRecommendations();
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
        boolean mustContinue = refereeMode && refereeRequiresContinuation();
        refereePanel.setVisibility(refereeMode && !(mustContinue && evaluating) ? View.VISIBLE : View.GONE);
        gameActionPanel.setVisibility(refereeMode ? View.GONE : View.VISIBLE);
        refereeTapOverlay.setVisibility(mustContinue && refereeAwaitingTap && !evaluating
                ? View.VISIBLE : View.GONE);
        // Disputed positions are handled by a tap anywhere on the screen,
        // not a fourth large button at the bottom.
        refereeContinueButton.setVisibility(View.GONE);
        refereeContinueButton.setEnabled(false);
        // One action for any disagreement; scoring actions only if READY_TO_COUNT.
        refereeResetButton.setVisibility(refereeMode && !mustContinue ? View.VISIBLE : View.GONE);
        refereeConfirmButton.setVisibility(refereeMode && !mustContinue ? View.VISIBLE : View.GONE);
        refereeExitButton.setVisibility(refereeMode && !mustContinue ? View.VISIBLE : View.GONE);
        refereeConfirmButton.setText("确认数子");
        refereeConfirmButton.setEnabled(refereeMode && !mustContinue && !evaluating);
        refereeResetButton.setEnabled(refereeMode && !mustContinue && !evaluating);
        refereeExitButton.setEnabled(refereeMode && !mustContinue && !evaluating);
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
        if (refereeMode && refereeRequiresContinuation()) {
            if (refereeAwaitingTap) resumeFromReferee();
            return;
        }
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
