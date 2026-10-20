package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.engine.KataGoMediaTekLiteRt;
import com.badukai.engine.KataGoMediaTekGtpSmoke;
import com.badukai.engine.KataGoBackendBenchmark;
import com.badukai.engine.NpuHardware;
import com.badukai.engine.DeviceHardwareInfo;
import com.badukai.engine.GpuTuneCache;
import com.badukai.engine.GpuStartupLogProgress;
import com.badukai.engine.KataGoOpenCLProbe;
import com.badukai.engine.KataGoNative;
import com.badukai.engine.KataGoGpuNative;
import com.badukai.game.GoBoard;
import com.badukai.game.GoSgf;
import com.badukai.game.SgfArchive;
import com.badukai.game.NnAreaScore;
import com.badukai.game.FastEndgameScorer;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.InkLoadingView;
import com.badukai.ui.WinRateChartView;
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
    private boolean openNewGameOnResume;

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
    private TextView gameMoveCountText;
    private TextView gameModeSummaryText;
    private TextView gameRankSummaryText;
    private TextView gameBackendSummaryText;
    private GoBoardView boardView;
    private TextView statusText;
    private TextView nnAreaScoreText;
    private InkLoadingView gpuStartupScreen;
    private GpuStartupLogProgress gpuProgressTracker;
    private int gpuStartupGeneration;
    private long gpuStartupStartedMs;
    private android.widget.ProgressBar winRateProgress;
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
        if (NpuHardware.current() == NpuHardware.Vendor.MEDIATEK)
            return KataGoEngine.supportsMediaTekGame(this, size);
        if (!KataGoEngine.supportsRknnHardware()) return false;
        if (size != 9 && size != 11 && size != 13 && size != 15 && size != 19) return false;
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
        Log.i(TAG, "NPU_PLATFORM detected=" + NpuHardware.summary()
                + " mediatekGtpReady=" + KataGoEngine.supportsMediaTekGame(this, boardSize));
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
        setHomeImageArt(R.id.gameBackdrop, "home/background.png");
        Log.i(TAG, "JADE_GAME_V1 nativeBoard=true artPack=25");
        // Each home UI element is a separate PNG (not a painted button hotspot).
        try (InputStream in = getAssets().open("home/background.png")) {
            ((ImageView) findViewById(R.id.homeArtwork))
                    .setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ex) {
            Log.e(TAG, "Home background missing", ex);
        }
        setHomeButtonArt(R.id.aiBattleButton, "home/play.png");
        setHomeButtonArt(R.id.homeRecordButton, "home/record.png");
        setHomeButtonArt(R.id.homeTrainingButton, "home/training.png");
        setHomeButtonArt(R.id.homeSettingsButton, "home/settings.png");
        ((com.badukai.ui.GoHomeTouchLayout) mainPageContainer).refreshPositions();
        com.badukai.ui.JadeBottomNavView homeNav = findViewById(R.id.homeNavBarArtwork);
        com.badukai.ui.JadeBottomNavView gameNav = findViewById(R.id.gameSharedNav);
        homeNav.setSelectedTab(com.badukai.ui.JadeBottomNavView.HOME);
        gameNav.setSelectedTab(com.badukai.ui.JadeBottomNavView.PLAY);
        homeNav.setOnTabClickListener(tab -> {
            if (tab == com.badukai.ui.JadeBottomNavView.HOME) showMainPage();
            else if (tab == com.badukai.ui.JadeBottomNavView.PLAY) showNewGameDialog();
            else if (tab == com.badukai.ui.JadeBottomNavView.RECORDS) openReplay();
            else if (tab == com.badukai.ui.JadeBottomNavView.TRAINING) openTsumego();
            else com.badukai.ui.JadeNavigation.open(this, tab);
        });
        gameNav.setOnTabClickListener(tab -> {
            if (tab == com.badukai.ui.JadeBottomNavView.HOME) showMainPage();
            else if (tab == com.badukai.ui.JadeBottomNavView.PLAY) showNewGameDialog();
            else if (tab == com.badukai.ui.JadeBottomNavView.RECORDS) openReplay();
            else if (tab == com.badukai.ui.JadeBottomNavView.TRAINING) openTsumego();
            else com.badukai.ui.JadeNavigation.open(this, tab);
        });
        findViewById(R.id.homeGameTabButton).setOnClickListener(v -> showNewGameDialog());
        findViewById(R.id.homeTabButton).setOnClickListener(v -> showMainPage());
        for (int id : new int[]{R.id.homeSettingsButton, R.id.homeSettingsTabButton}) {
            findViewById(id).setOnClickListener(v -> com.badukai.ui.JadeNavigation.open(
                    this, com.badukai.ui.JadeBottomNavView.SETTINGS));
        }
        // Offline training is isolated from the live KataGo engine and match.
        findViewById(R.id.homeTrainingButton).setOnClickListener(v -> openTsumego());
        findViewById(R.id.homeTrainingTabButton).setOnClickListener(v -> openTsumego());
        findViewById(R.id.homeRecordButton).setOnClickListener(v -> openReplay());
        findViewById(R.id.homeRecordTabButton).setOnClickListener(v -> openReplay());
        // Real buttons: spring back on release; normal OnClick listeners still handle navigation.
        for (int id : new int[]{R.id.aiBattleButton, R.id.homeRecordButton,
                R.id.homeTrainingButton, R.id.homeSettingsButton,
                R.id.homeTabButton, R.id.homeGameTabButton,
                R.id.homeRecordTabButton, R.id.homeTrainingTabButton,
                R.id.homeSettingsTabButton}) {
            View button = findViewById(id);
            button.setOnTouchListener((view, event) -> {
                if (!getSharedPreferences("katago_settings", MODE_PRIVATE)
                        .getBoolean("ui_animations", true)) {
                    view.animate().cancel();
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setAlpha(1f);
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    view.animate().scaleX(0.95f).scaleY(0.95f)
                            .alpha(0.83f).setDuration(95).start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setInterpolator(new OvershootInterpolator(1.35f))
                            .setDuration(260).start();
                }
                return false;
            });
        }
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        aiWinRateText.setOnClickListener(v -> showWinRateChart());
        playerWinRateText.setOnClickListener(v -> showWinRateChart());
        winRateProgress.setOnClickListener(v -> showWinRateChart());
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
        findViewById(R.id.gameAnalysisButton).setOnClickListener(v -> showWinRateChart());
        findViewById(R.id.gameMoreButton).setOnClickListener(this::showGameMoreMenu);
        android.widget.ScrollView gameScroll = findViewById(R.id.gameContentScroll);
        gameScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                view.post(this::fitGameBoardToViewport);
        });
        render("正在启动 AI...");
        showMainPage();
        if (getIntent() != null) {
            if (getIntent().getBooleanExtra(NewGameActivity.EXTRA_START, false)) {
                mainHandler.post(() -> startSelectedGame(getIntent()));
            } else if (getIntent().getBooleanExtra("navigate_new_game", false)
                    || getIntent().getBooleanExtra("navigate_play", false)) {
                openNewGameOnResume = true;
            }
        }
        if (isRefereeSmokeIntent()) {
            startDebugRefereeSmoke();
        } else if (isNpuSmokeIntent()) {
            startDebugNpuSwitchSmoke();
        } else if (isBackendBenchIntent()) {
            startDebugBackendBench();
        } else if (isMtkGtpSmokeIntent()) {
            startDebugMtkGtpSmoke();
        } else if (isMtkSmokeIntent()) {
            startDebugMtkSmoke();
        } else if (!jniGtpSmoke && !jniGtpRepeat) {
            showGpuStartupScreen(boardSize, selectedBackend);
            startEngine();
        }
    }

    /** Real CPU/GPU/NPU timing test; active only for explicit debug ADB launches. */
    private boolean isBackendBenchIntent() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null && getIntent().getBooleanExtra("katago_backend_bench", false);
    }

    private void startDebugBackendBench() {
        final String id = getIntent().getStringExtra("bench_id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,40}")) {
            Log.e("KataGoBench", "FAIL id=invalid stage=args reason=Invalid_run_id");
            return;
        }
        String backend = getIntent().getStringExtra("bench_backend");
        if (!"CPU".equals(backend) && !"GPU".equals(backend) && !"NPU".equals(backend)) {
            Log.e("KataGoBench", "FAIL id=" + id + " stage=args reason=Invalid_backend");
            return;
        }
        final int size = getIntent().getIntExtra("bench_board", 9);
        final int visits = getIntent().getIntExtra("bench_visits", 32);
        final int warmup = getIntent().getIntExtra("bench_warmup", 1);
        final int repeats = getIntent().getIntExtra("bench_repeats", 5);
        final double seconds = getIntent().getDoubleExtra("bench_seconds", 12.0);
        final boolean human = getIntent().getBooleanExtra("bench_human", false);
        final String selected = backend;
        render("性能测试：" + selected + " " + size + "路");
        engineExecutor.execute(() -> {
            KataGoBackendBenchmark.run(getApplicationContext(), id, selected, size,
                    visits, seconds, warmup, repeats, human);
            mainHandler.post(() -> render("性能测试结束：" + selected));
        });
    }

    private boolean isMtkGtpSmokeIntent() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null && getIntent().getBooleanExtra("mtk_gtp_smoke", false);
    }

    private void startDebugMtkGtpSmoke() {
        final String id = getIntent().getStringExtra("mtk_smoke_id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,40}")) {
            Log.e("KataGoMTK", "MTK_GTP_SMOKE_FAIL id=invalid reason=Invalid_run_id");
            return;
        }
        final int size = getIntent().getIntExtra("mtk_board_size", 9);
        render("MediaTek KataGo 真实 GTP 落子测试中...");
        engineExecutor.execute(() -> {
            KataGoMediaTekGtpSmoke.run(getApplicationContext(), id, size);
            mainHandler.post(() -> render("MediaTek GTP 测试结束，请查看自动诊断报告"));
        });
    }

    private boolean isMtkSmokeIntent() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null && getIntent().getBooleanExtra("mtk_litert_smoke", false);
    }

    private void startDebugMtkSmoke() {
        String id = getIntent().getStringExtra("mtk_smoke_id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,40}")) {
            Log.e("KataGoMTK", "MTK_SMOKE_FAIL id=invalid reason=Missing_run_id");
            return;
        }
        final int size = getIntent().getIntExtra("mtk_board_size", 9);
        Log.i("KataGoMTK", "MTK_SMOKE_BEGIN id=" + id + " board=" + size);
        render("MediaTek LiteRT NPU 模型检查中...");
        engineExecutor.execute(() -> {
            try {
                if (!KataGoMediaTekLiteRt.isMediaTek()) throw new IllegalStateException("Not a MediaTek phone");
                if (size != 9 && size != 11 && size != 13 && size != 15 && size != 19) throw new IllegalArgumentException("Unsupported board");
                String filename = "katago_10b_" + size + "x" + size;
                File dir = new File(getCacheDir(), "mtk_litert_smoke");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Failed to create model cache");
                File graph = new File(dir, filename + ".tflite");
                File manifest = new File(dir, filename + ".json");
                try (InputStream input = getAssets().open("engine/" + filename + ".tflite")) {
                    Files.copy(input, graph.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                try (InputStream input = getAssets().open("engine/" + filename + ".json")) {
                    Files.copy(input, manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                try (KataGoMediaTekLiteRt runner = new KataGoMediaTekLiteRt(this, graph, manifest, size)) {
                    for (int i=0;i<3;i++) Log.i("KataGoMTK", runner.smokeOnce());
                }
                Log.i("KataGoMTK", "MTK_SMOKE_PASS id=" + id + " board=" + size
                        + " tfliteInference=true hardwareExecution=UNVERIFIED");
                mainHandler.post(() -> render("LiteRT 模型推理成功；NPU 硬件调用尚待确认"));
            } catch (Throwable error) {
                Log.e("KataGoMTK", "MTK_SMOKE_FAIL id=" + id + " board=" + size
                        + " reason=" + error, error);
                mainHandler.post(() -> render("手机 NPU 检查失败：" + error.getMessage()));
            }
        });
    }

    // ADB-only NPU regression: same Java engine, sequential fixed-shape RKNN graphs.
    // Does not touch the user's saved board/backend preferences or simulate UI taps.
    private boolean isNpuSmokeIntent() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent() != null && getIntent().getBooleanExtra("npu_switch_smoke", false);
    }

    private void startDebugNpuSwitchSmoke() {
        String rawId = getIntent().getStringExtra("npu_smoke_id");
        final String id = (rawId == null ? "" : rawId.replaceAll("[^A-Za-z0-9_-]", ""));
        if (id.isEmpty() || id.length() > 40) {
            Log.e("NpuSmoke", "FAIL id=invalid stage=args reason=Invalid_run_id");
            return;
        }
        String sizesArg = getIntent().getStringExtra("npu_smoke_sizes");
        if (sizesArg == null) sizesArg = "9,13,19,9";
        final boolean humanSL = getIntent().getBooleanExtra("npu_smoke_human", true);
        final boolean parity = getIntent().getBooleanExtra("npu_smoke_parity", false);
        final boolean humanRknnParity = getIntent().getBooleanExtra(
                "npu_smoke_human_rknn_parity", false);
        final int[] sizes;
        try {
            String[] values = sizesArg.split(",", -1);
            if (values.length < 1 || values.length > 12) throw new IllegalArgumentException("Invalid_step_count");
            sizes = new int[values.length];
            for (int i=0; i<values.length; i++) {
                sizes[i] = Integer.parseInt(values[i]);
                if (sizes[i] != 9 && sizes[i] != 11 && sizes[i] != 13 && sizes[i] != 15 && sizes[i] != 19)
                    throw new IllegalArgumentException("Unsupported_board_" + sizes[i]);
                if (!hasBundledNpuModel(sizes[i])) throw new IllegalArgumentException("NPU_asset_missing_" + sizes[i]);
            }
        } catch (RuntimeException ex) {
            Log.e("NpuSmoke", "FAIL id=" + id + " stage=args reason=" + ex, ex);
            return;
        }
        final String requested = sizesArg;
        Log.i("NpuSmoke", "START id=" + id + " sizes=" + requested + " humanSL=" + humanSL);
        render("NPU 自动测试进行中（" + requested + "）");
        engineExecutor.execute(() -> {
            try {
                Log.i("NpuSmoke", "STAGE id=" + id + " phase=human_model_prepare");
                if (humanSL) engine.prepareHumanModel((done, total) -> {});
                Log.i("NpuSmoke", "STAGE id=" + id + " phase=human_model_ready");
                if (NpuHardware.current() == NpuHardware.Vendor.MEDIATEK) {
                    // The normal app typically preloads CPU JNI before a user
                    // switches to NPU. Verify both libraries coexist safely.
                    Log.i("NpuSmoke", "STAGE id=" + id + " phase=cpu_preload");
                    engine.setBackendPreference(KataGoEngine.BackendPreference.CPU);
                    if (!engine.start(KataGoEngine.Model.HUMAN, humanSL)
                            || !engine.getBackendName().startsWith("CPU"))
                        throw new IllegalStateException("cpu_preload_before_mtk_failed");
                    Log.i("NpuSmoke", "MTK_CPU_TO_NPU_PRECHECK_READY id=" + id
                            + " backend=" + engine.getBackendName());
                    engine.stop();
                    Log.i("NpuSmoke", "STAGE id=" + id + " phase=cpu_closed");
                }
                engine.setMediaTekParityEnabled(parity);
                engine.setHumanRknnParityEnabled(humanRknnParity);
                Log.i("NpuSmoke", "HUMAN_RKNN_PARITY_ENABLED id=" + id + " enabled=" + humanRknnParity);
                for (int i=0; i<sizes.length; i++) {
                    int size = sizes[i];
                    Log.i("NpuSmoke", "STEP_BEGIN id=" + id + " index=" + i + " board=" + size);
                    long startupNs = System.nanoTime();
                    engine.setStartupBoardSize(size);
                    engine.setBackendPreference(KataGoEngine.BackendPreference.NPU);
                    Log.i("NpuSmoke", "STAGE id=" + id + " phase=npu_start index=" + i + " board=" + size);
                    if (!engine.start(KataGoEngine.Model.HUMAN, humanSL))
                        throw new IllegalStateException("engine_start_failed");
                    long startupMs = (System.nanoTime() - startupNs) / 1000000L;
                    if (!engine.getBackendName().startsWith("NPU") || engine.getActiveNpuBoardSize() != size)
                        throw new IllegalStateException("wrong_backend_or_model_" + engine.getBackendName()
                                + "_active_" + engine.getActiveNpuBoardSize());
                    long setupNs = System.nanoTime();
                    if (!engine.setBoardSize(size) || !engine.clearBoard() || !engine.setChineseRules()
                            || !engine.setKomi(7.5f) || !engine.setSearchLimits(32, 2.0))
                        throw new IllegalStateException("gtp_setup_failed_board_" + size);
                    if (humanSL && !engine.setHumanRank(12))
                        throw new IllegalStateException("human_rank_setup_failed");
                    long setupMs = (System.nanoTime() - setupNs) / 1000000L;
                    Log.i("NpuSmoke", "PHASE_TIMING id=" + id + " index=" + i + " board=" + size
                            + " phase=gtp_setup ms=" + setupMs);
                    Log.i("NpuSmoke", "STAGE id=" + id + " phase=genmove_black index=" + i + " board=" + size);
                    long firstNs = System.nanoTime();
                    String black = engine.generateMove("black");
                    long blackMs = (System.nanoTime() - firstNs) / 1000000L;
                    Log.i("NpuSmoke", "STAGE id=" + id + " phase=genmove_white index=" + i + " board=" + size);
                    long secondNs = System.nanoTime();
                    String white = engine.generateMove("white");
                    long whiteMs = (System.nanoTime() - secondNs) / 1000000L;
                    Log.i("NpuSmoke", "PHASE_TIMING id=" + id + " index=" + i + " board=" + size
                            + " phase=genmove_black ms=" + blackMs);
                    Log.i("NpuSmoke", "PHASE_TIMING id=" + id + " index=" + i + " board=" + size
                            + " phase=genmove_white ms=" + whiteMs);
                    if (!isValidNpuSmokeMove(black, size) || !isValidNpuSmokeMove(white, size))
                        throw new IllegalStateException("invalid_moves_" + black + "_" + white);
                    Log.i("NpuSmoke", "STEP_PASS id=" + id + " index=" + i + " board=" + size
                            + " active=" + engine.getActiveNpuBoardSize() + " startupMs=" + startupMs
                            + " setupMs=" + setupMs
                            + " black=" + black + " blackMs=" + blackMs
                            + " white=" + white + " whiteMs=" + whiteMs);
                }
                Log.i("NpuSmoke", "PASS id=" + id + " steps=" + sizes.length + " sizes=" + requested);
            } catch (Exception ex) {
                Log.e("NpuSmoke", "FAIL id=" + id + " stage=run reason=" + ex, ex);
            } finally {
                engine.stop();
                engine.setHumanRknnParityEnabled(false);
                engine.setMediaTekParityEnabled(false);
                mainHandler.post(() -> render("NPU 自动测试结束，查看 NpuSmoke 日志"));
            }
        });
    }

    private boolean isValidNpuSmokeMove(String move, int size) {
        if (move == null) return false;
        String vertex = move.trim().toUpperCase(java.util.Locale.US);
        if ("PASS".equals(vertex)) return true;
        if (!vertex.matches("[A-HJ-T]([1-9]|1[0-9])")) return false;
        char col = vertex.charAt(0);
        int x = col - 'A' - (col > 'I' ? 1 : 0);
        int y = Integer.parseInt(vertex.substring(1));
        return x >= 0 && x < size && y <= size;
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
        gameMoveCountText = findViewById(R.id.gameMoveCountText);
        gameModeSummaryText = findViewById(R.id.gameModeSummaryText);
        gameRankSummaryText = findViewById(R.id.gameRankSummaryText);
        gameBackendSummaryText = findViewById(R.id.gameBackendSummaryText);
        boardView = findViewById(R.id.boardView);
        statusText = findViewById(R.id.statusText);
        nnAreaScoreText = findViewById(R.id.nnAreaScoreText);
        aiWinRateText = findViewById(R.id.aiWinRateText);
        winRateProgress = findViewById(R.id.winRateProgress);
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

    private void setHomeImageArt(int viewId, String asset) {
        try (InputStream stream = getAssets().open(asset)) {
            android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) throw new java.io.IOException("Invalid bitmap: " + asset);
            ((ImageView) findViewById(viewId)).setImageBitmap(bitmap);
        } catch (Exception ex) {
            Log.e(TAG, "Cannot load home image: " + asset, ex);
        }
    }

    private void setHomeButtonArt(int viewId, String asset) {
        try (InputStream stream = getAssets().open(asset)) {
            android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) throw new java.io.IOException("Invalid bitmap: " + asset);
            android.graphics.drawable.BitmapDrawable drawable =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
            drawable.setGravity(android.view.Gravity.FILL);
            findViewById(viewId).setBackground(drawable);
        } catch (Exception ex) {
            Log.e(TAG, "Cannot load home button artwork: " + asset, ex);
        }
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

    /**
     * NPU games read the active main-engine 10b RKNN position, avoiding the
     * independent CPU PIE analyser and a potentially unsafe second JNI session.
     * CPU/GPU games retain their original independent MCTS analysis.
     */
    private void initializeWinRateAnalysis(GoBoard snapshot, int size, float komi,
                                           List<Point> handicap, StoneColor nextToPlay) {
        final long session = winRateSession, epoch = winRateEpoch;
        if (engine.usesNpuBackend()) {
            Log.i(TAG, "WINRATE_BACKEND mode=main_npu_raw_nn board=" + size);
            // A previous CPU/GPU match may have left the PIE analyser running.
            winRateExecutor.execute(() -> {
                if (session == winRateSession) {
                    winRateSynchronized = false;
                    winRateEngine.stop();
                }
            });
            queueNpuWinRateEvaluation(snapshot, 0, session, epoch);
            return;
        }
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

    /**
     * Serialize raw NN evaluation with the playing engine's GTP commands, so it
     * reads the exact position after play/genmove/undo and cannot race the RKNN
     * context. This replaces CPU MCTS only when an NPU session really is active.
     */
    private void queueNpuWinRateEvaluation(GoBoard snapshot, int moveCount, long session, long epoch) {
        final int size = snapshot.getSize();
        engineExecutor.execute(() -> {
            if (session != winRateSession || epoch != winRateEpoch || !engine.usesNpuBackend()) return;
            long startedMs = SystemClock.elapsedRealtime();
            KataGoEngine.PositionEvaluation nn = null;
            try {
                nn = engine.evaluatePosition(size);
            } catch (RuntimeException e) {
                Log.w(TAG, "NPU winrate raw NN evaluation failed", e);
            }
            long elapsedMs = SystemClock.elapsedRealtime() - startedMs;
            KataGoEngine.WinRate rate = nn != null && nn.size == size
                    && Double.isFinite(nn.blackWin) && Double.isFinite(nn.whiteWin)
                    && nn.blackWin >= 0.0 && nn.blackWin <= 1.0
                    && nn.whiteWin >= 0.0 && nn.whiteWin <= 1.0
                    ? new KataGoEngine.WinRate((float) nn.blackWin, (float) nn.whiteWin) : null;
            Log.i(TAG, "WINRATE_NPU_RAW_NN board=" + size + " move=" + moveCount
                    + " elapsedMs=" + elapsedMs + " result=" + (rate == null ? "FAIL" : "OK"));
            mainHandler.post(() -> recordWinRate(snapshot, moveCount, epoch, rate));
        });
    }

    /** Only the independent CPU/GPU analysis process sees this command. */
    private void queueWinRateMove(StoneColor played, String vertex, StoneColor next,
                                  GoBoard snapshot, int moveCount) {
        final long session = winRateSession, epoch = winRateEpoch;
        if (engine.usesNpuBackend()) {
            queueNpuWinRateEvaluation(snapshot, moveCount, session, epoch);
            return;
        }
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
        // For NPU, wait for the main GTP engine's two undo commands to finish.
        if (engine.usesNpuBackend()) return;
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
        // Progress is always the black side; keep the empty state neutral at 50%.
        winRateProgress.setProgress(available ? Math.round(black * 1000f) : 500);
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
        new AlertDialog.Builder(this).setTitle("胜率曲线 · " + (engine.usesNpuBackend() ? "NPU单次NN" : "MCTS搜索评估"))
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
                if (second && engine.usesNpuBackend())
                    queueNpuWinRateEvaluation(snapshot, moveCount, winRateSession, winRateEpoch);
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
                final int suspectedDeadStones = displayEndgameOwnership(result);
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
                    nnAreaScoreMessage = String.format(Locale.CHINA,"黑 %.1f · 白 %.1f%s",
                            area.blackPoints,area.whitePoints,
                            suspectedDeadStones == 0 ? "" : " · 疑似死子 " + suspectedDeadStones);
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
                new AlertDialog.Builder(this)
                        .setTitle("终局 · 快速 NN 估分")
                        .setMessage(finalScoreText + "\n快速估分耗时：" + nnMs + " ms"
                                + "\n可选：运行旧版慢速 AI 终局判断，比较实际耗时。")
                        .setPositiveButton("慢速 AI 复核", (dialog, which) -> runSlowEndgameComparison())
                        .setNegativeButton("关闭", null).show();
            });
        });
    }

    /** Optional legacy GTP final_score / final_status_list benchmark. Never alters the board. */
    private void runSlowEndgameComparison() {
        if (evaluating || thinking || !engineReady || !board.isGameOver()
                || board.getLastMove() instanceof Move.Resign) return;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        evaluating = true;
        render("旧版 AI 终局判断中（可能较慢）...");
        engineExecutor.execute(() -> {
            long begin = SystemClock.elapsedRealtime();
            String score = null, dead = null, failure = null;
            try {
                score = engine.getFinalScore();
                dead = engine.getFinalDeadStones();
            } catch (RuntimeException e) {
                failure = e.toString();
                Log.e(TAG, "LEGACY_ENDGAME_BENCH failed", e);
            }
            final long elapsed = SystemClock.elapsedRealtime() - begin;
            final String backend = engine.getBackendName();
            final String finalScore = score, finalDead = dead, finalFailure = failure;
            Log.i(TAG, "LEGACY_ENDGAME_BENCH board=" + size + " moves=" + moves
                    + " backend=" + backend + " elapsedMs=" + elapsed
                    + " finalScore=" + score + " deadStones=" + dead + " error=" + failure);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || boardSize != size || board.getMoveCount() != moves) return;
                String message = "后端：" + backend + "\n总耗时：" + elapsed + " ms"
                        + "\nfinal_score：" + (finalScore == null ? "未返回（可能不支持）" : finalScore)
                        + "\n疑似死子：" + (finalDead == null ? "未返回（可能不支持）"
                                : finalDead.isEmpty() ? "无" : finalDead)
                        + (finalFailure == null ? "" : "\n错误：" + finalFailure)
                        + "\n注意：后端名称不等于每一步都已确认在 NPU 上执行。";
                new AlertDialog.Builder(this).setTitle("旧版 AI 终局判断 · 耗时对照")
                        .setMessage(message).setPositiveButton("确定", null).show();
                render(finalScoreText == null ? "旧版终局判断已完成" : finalScoreText);
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
                if (gameEnded && result.whiteOwnership != null
                        && result.whiteOwnership.length == size * size)
                    displayEndgameOwnership(result);
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

    /**
     * NN ownership preview for both ongoing positions and terminal scoring.
     * Show tentative dead-chain markers only when the whole chain is strongly
     * predicted to belong to the opponent. This never changes game state.
     */
    private int displayEndgameOwnership(KataGoEngine.PositionEvaluation result) {
        boardView.setOwnership(result.whiteOwnership);
        try {
            FastEndgameScorer.Result dead = FastEndgameScorer.suggest(board, result.whiteOwnership);
            boardView.setDeadStoneMarks(dead.deadStones);
            Log.i(TAG, "ENDGAME_DEAD_STONE_MARKS suggested=" + dead.deadStones.size()
                    + " groups=" + dead.deadGroups + " inspected=" + dead.scannedGroups
                    + " verified=false");
            return dead.deadStones.size();
        } catch (IllegalArgumentException ex) {
            boardView.setDeadStoneMarks(null);
            Log.w(TAG, "Cannot display NN-inferred dead-chain markers", ex);
            return 0;
        }
    }

    /** Display NN-estimated territory and tentative dead-chain markers at any stage. */
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
        int suspectedDead = displayEndgameOwnership(result);
        Log.i(TAG,String.format(Locale.US,"NN empty territory B=%d W=%d uncertain=%d suspectedDead=%d",
                blackTerritory,whiteTerritory,uncertainEmpty,suspectedDead));
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

    /** The game setup is a proper Activity. Keep the KataGo engine in this Activity. */
    private void showNewGameDialog() {
        android.content.Intent intent = new android.content.Intent(this, NewGameActivity.class);
        intent.putExtra(NewGameActivity.EXTRA_SIZE, boardSize);
        intent.putExtra(NewGameActivity.EXTRA_RANK, aiKyu);
        intent.putExtra(NewGameActivity.EXTRA_COLOR, playerColor.name());
        intent.putExtra(NewGameActivity.EXTRA_CONDITION, gameCondition);
        intent.putExtra(NewGameActivity.EXTRA_BACKEND, selectedBackend.name());
        intent.putExtra(NewGameActivity.EXTRA_FORCE_LEGACY, forceLegacyBackend);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    /** Apply the result returned by the standalone new-game Activity. */
    private void startSelectedGame(android.content.Intent intent) {
        int requestedSize = intent.getIntExtra(NewGameActivity.EXTRA_SIZE, boardSize);
        int requestedRank = intent.getIntExtra(NewGameActivity.EXTRA_RANK, aiKyu);
        int requestedCondition = intent.getIntExtra(NewGameActivity.EXTRA_CONDITION, gameCondition);
        String requestedColorName = intent.getStringExtra(NewGameActivity.EXTRA_COLOR);
        String requestedBackendName = intent.getStringExtra(NewGameActivity.EXTRA_BACKEND);
        if (!isSupportedBoardSize(requestedSize)
                || requestedRank < 1 || requestedRank > 18
                || requestedCondition < 0 || requestedCondition > 9) {
            Toast.makeText(this, "对局参数无效，请重新选择", Toast.LENGTH_LONG).show();
            showNewGameDialog();
            return;
        }
        final StoneColor requestedColor = "WHITE".equals(requestedColorName)
                ? StoneColor.WHITE : StoneColor.BLACK;
        final KataGoEngine.BackendPreference requestedBackend =
                "NPU".equals(requestedBackendName) ? KataGoEngine.BackendPreference.NPU
                : "GPU".equals(requestedBackendName) ? KataGoEngine.BackendPreference.GPU
                : KataGoEngine.BackendPreference.CPU;
        if (requestedBackend == KataGoEngine.BackendPreference.NPU
                && (forceLegacyBackend || !hasBundledNpuModel(requestedSize))) {
            Toast.makeText(this, requestedSize + "路 NPU 模型不可用，请换路数或后端",
                    Toast.LENGTH_LONG).show();
            showNewGameDialog();
            return;
        }
        Runnable beginGame = () -> {
            boardSize = requestedSize;
            aiKyu = requestedRank;
            gameCondition = requestedCondition;
            playerColor = requestedColor;
            selectedBackend = requestedBackend;
            getSharedPreferences("katago_settings", MODE_PRIVATE).edit()
                    .putInt("board_size", boardSize)
                    .putInt("ai_kyu", aiKyu)
                    .putInt("game_condition", gameCondition)
                    .putString("player_color", playerColor.name())
                    .putString("backend", requestedBackend.name()).apply();
            Log.i(TAG, "JADE_NEW_GAME_START size=" + boardSize + " kyu=" + aiKyu
                    + " color=" + playerColor + " backend=" + selectedBackend
                    + " condition=" + gameCondition);
            showGamePage();
            startNewGame();
        };
        if (engine.hasHumanModel()) beginGame.run();
        else new AlertDialog.Builder(this)
                .setTitle("首次准备棋力模型")
                .setMessage("18级～1级需要 KataGo Human SL 模型，约 99 MB。首次需联网下载，下载后可离线对弈。现在下载吗？")
                .setPositiveButton("下载并开始", (confirm, which) -> beginGame.run())
                .setNegativeButton("取消", (confirm, which) -> showNewGameDialog())
                .show();
    }

    private void saveReplaySnapshot(boolean archive) {
        if (board == null || board.getMoveCount() == 0) return;
        try {
            String sgf = GoSgf.encode(boardSize, komiFor(boardSize),
                    board.getInitialHandicapSnapshot(), board.getMoveHistorySnapshot());
            java.nio.file.Files.write(new File(getFilesDir(), "last_game.sgf").toPath(),
                    sgf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (archive) SgfArchive.save(this, sgf);
            Log.i(TAG, "SGF_SAVED moves=" + board.getMoveCount() + " board=" + boardSize);
        } catch (Exception ex) {
            Log.e(TAG, "SGF save failed", ex);
        }
    }

    /** Navigate from independent Jade pages without recreating the game engine. */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(NewGameActivity.EXTRA_START, false)) {
            openNewGameOnResume = false;
            startSelectedGame(intent);
        } else if (intent != null && intent.getBooleanExtra("navigate_home", false)) {
            openNewGameOnResume = false;
            showMainPage();
        } else if (intent != null
                && (intent.getBooleanExtra("navigate_play", false)
                    || intent.getBooleanExtra("navigate_new_game", false))) {
            openNewGameOnResume = true;
        }
    }

    private void openReplay() {
        // The active board stays visible behind the chooser; do not briefly
        // reveal Home or start a ReplayActivity until a choice is made.
        saveReplaySnapshot(true);
        com.badukai.ui.JadeNavigation.open(this,
                com.badukai.ui.JadeBottomNavView.RECORDS);
    }

    private void openTsumego() {
        saveReplaySnapshot(true);
        com.badukai.ui.JadeNavigation.open(this,
                com.badukai.ui.JadeBottomNavView.TRAINING);
    }

    private void showMainPage() {
        saveReplaySnapshot(true);
        DebugLog.enter(TAG, "showMainPage in");
        mainPageContainer.setVisibility(View.VISIBLE);
        gamePageContainer.setVisibility(View.GONE);
        ((com.badukai.ui.JadeBottomNavView)
                findViewById(R.id.homeNavBarArtwork)).setSelectedTab(
                com.badukai.ui.JadeBottomNavView.HOME);
    }


    /**
     * Size the square board to the free height above the fixed bottom nav.
     * Keep both button rows visible without scrolling on phone-size screens.
     */
    private void fitGameBoardToViewport() {
        android.widget.ScrollView scroll = findViewById(R.id.gameContentScroll);
        View frame = findViewById(R.id.boardFrame);
        if (scroll == null || frame == null || scroll.getHeight() <= 0
                || scroll.getChildCount() == 0) return;
        View content = scroll.getChildAt(0);
        if (content.getWidth() <= 0 || frame.getHeight() <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        int remaining = scroll.getHeight() - (content.getHeight() - frame.getHeight());
        int maxWidth = content.getWidth() - Math.round(18 * density);
        int side = Math.min(maxWidth, remaining - Math.round(4 * density));
        side = Math.min(maxWidth, Math.max(Math.round(180 * density), side));
        if (side <= 0) return;

        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) frame.getLayoutParams();
        if (lp.width != side || lp.height != side
                || lp.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
            lp.width = side;
            lp.height = side;
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            frame.setLayoutParams(lp);
        }
        if (scroll.getScrollY() != 0) scroll.scrollTo(0, 0);
    }

    private void showGamePage() {
        DebugLog.enter(TAG, "showGamePage in, boardSize=" + boardSize);
        mainPageContainer.setVisibility(View.GONE);
        gamePageContainer.setVisibility(View.VISIBLE);
        ((com.badukai.ui.JadeBottomNavView)
                findViewById(R.id.gameSharedNav)).setSelectedTab(
                com.badukai.ui.JadeBottomNavView.PLAY);
        gameTitleText.setText(boardSize + "×" + boardSize);
        gameModeSummaryText.setText("人机对弈");
        gameRankSummaryText.setText(aiKyu + "级");
        gameBackendSummaryText.setText(selectedBackend.name());
        findViewById(R.id.gameContentScroll).post(this::fitGameBoardToViewport);
    }

    private void startNewGame() {
        saveReplaySnapshot(true);
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
        boolean reusingHumanEngine = engine.canReuseHumanSession(requestedBackend, size);
        // GTP clear_board does not reinitialize the RKNN runtime. Do not show
        // the NPU loading screen when the already initialized 10b/b18 session
        // is merely being reused for a new match.
        render(reusingHumanEngine ? "正在重置棋盘..." : requestedBackend == KataGoEngine.BackendPreference.NPU
                ? (NpuHardware.current() == NpuHardware.Vendor.MEDIATEK
                        ? "NPU/MediaTek LiteRT 初始化中..." : "NPU/RKNN 初始化中...")
                : requestedBackend == KataGoEngine.BackendPreference.GPU
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
                boolean reuse = engine.canReuseHumanSession(requestedBackend, size);
                Log.i(TAG, "KATAGO_NEW_GAME_SESSION action=" + (reuse ? "REUSE" : "CREATE")
                        + " board=" + size + " requested=" + requestedBackend
                        + " active=" + engine.getBackendName()
                        + " noModelReload=" + reuse);
                if (!reuse) {
                    if (restartNpuForBoard)
                        Log.i(TAG, "NPU_MODEL_SWITCH board=" + engine.getActiveNpuBoardSize() + " -> " + size
                                + "; restarting native GTP for matching NPU model");
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
                    Log.i(TAG, "KATAGO_NEW_GAME_REUSE board=" + size
                            + " backend=" + engine.getBackendName()
                            + " noModelReload=1; resetting via GTP clear_board");
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
        nnAreaScoreText.setVisibility(nnAreaScoreMessage == null
                || !getSharedPreferences("katago_settings", MODE_PRIVATE)
                    .getBoolean("show_score_estimate", true)
                ? View.GONE : View.VISIBLE);
        if (nnAreaScoreMessage != null) nnAreaScoreText.setText(nnAreaScoreMessage);
        aiMoveTimeText.setText(thinking ? "AI 思考中…" : lastAiMoveTimeMs < 0
                ? "本手用时 --" : String.format(Locale.CHINA,"本手用时 %.2f 秒",lastAiMoveTimeMs/1000.0));
        updateWinRateLabels();
        applyGameDisplaySettings();
        aiDifficultyText.setText(getDifficultyName() + " · Human SL · "
                + (engineReady ? engine.getBackendName() : selectedBackend == KataGoEngine.BackendPreference.NPU ? "NPU 准备中" : selectedBackend == KataGoEngine.BackendPreference.GPU ? "GPU 准备中" : "CPU 准备中"));
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && gameReady && !engineStarting && !thinking
                && !evaluating && currentPlayer == playerColor && !board.isGameOver());
        boolean detailedResult = boardView.hasRecommendations();
        statusText.setMaxLines(1);
        statusText.setTextSize(10f);
        boolean playerBlack = playerColor == StoneColor.BLACK;
        playerStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_black_stone : R.drawable.txwq_white_stone);
        aiStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_white_stone : R.drawable.txwq_black_stone);
        int blackCaptures = board.getCapturedWhite();
        int whiteCaptures = board.getCapturedBlack();
        int playerCaptures = playerBlack ? blackCaptures : whiteCaptures;
        int aiCaptures = playerBlack ? whiteCaptures : blackCaptures;
        playerCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "黑" : "白", playerCaptures));
        aiCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "白" : "黑", aiCaptures));
        gameTitleText.setText(boardSize + "×" + boardSize);
        gameModeSummaryText.setText(variationBoard == null ? "人机对弈" : "变化预览");
        gameRankSummaryText.setText(aiKyu + "级");
        gameBackendSummaryText.setText(selectedBackend.name());
        gameMoveCountText.setText("第 " + board.getMoveCount() + " 手");
        updateButtons();
    }

    private void showGameMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "新开一局")
                .setEnabled(newRoundButton.isEnabled());
        menu.getMenu().add(0, 2, 1, "认输")
                .setEnabled(resignButton.isEnabled());
        menu.getMenu().add(0, 3, 2, "智能裁判")
                .setEnabled(countTerritoryButton.isEnabled());
        menu.getMenu().add(0, 4, 3, "后续变化")
                .setEnabled(variationButton.isEnabled());
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) return newRoundButton.performClick();
            if (item.getItemId() == 2) return resignButton.performClick();
            if (item.getItemId() == 3) return countTerritoryButton.performClick();
            if (item.getItemId() == 4) return variationButton.performClick();
            return false;
        });
        menu.show();
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

    private void applyGameDisplaySettings() {
        boolean showWinrate = getSharedPreferences("katago_settings", MODE_PRIVATE)
                .getBoolean("show_winrate", true);
        int visibility = showWinrate ? View.VISIBLE : View.GONE;
        playerWinRateText.setVisibility(visibility);
        aiWinRateText.setVisibility(visibility);
        winRateProgress.setVisibility(visibility);
        if (!getSharedPreferences("katago_settings", MODE_PRIVATE)
                .getBoolean("show_score_estimate", true))
            nnAreaScoreText.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playerWinRateText != null && nnAreaScoreText != null) {
            applyGameDisplaySettings();
            if (getSharedPreferences("katago_settings", MODE_PRIVATE)
                    .getBoolean("show_score_estimate", true)
                    && nnAreaScoreMessage != null) nnAreaScoreText.setVisibility(View.VISIBLE);
        }
        if (openNewGameOnResume) {
            openNewGameOnResume = false;
            showNewGameDialog();
        }
    }

    @Override
    protected void onPause() {
        saveReplaySnapshot(false);
        super.onPause();
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
