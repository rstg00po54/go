package com.badukai.engine;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import com.badukai.util.DebugLog;
import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class KataGoEngine {
    private static final String TAG = "KataGoEngine";

    private static void startupTiming(String phase, long startNs) {
        Log.i(TAG, "STARTUP_TIMING phase=" + phase + " ms=" + (System.nanoTime() - startNs) / 1000000.0);
    }
    private static final String NATIVE_BINARY = "libkatago_exec.so";
    private static final String CONFIG_ASSET = "engine/default_gtp.cfg";
    private static final String DEFAULT_MODEL_ASSET = "engine/10b.bin";
    private static final String HUMAN_CONFIG_ASSET = "engine/human_gtp.cfg";
    private static final String HUMAN_MODEL_NAME = "b18c384nbt-humanv0.bin.gz";
    private static final String HUMAN_MODEL_ASSET = "engine/" + HUMAN_MODEL_NAME;
    private static final String HUMAN_MODEL_URL = "https://github.com/lightvector/KataGo/releases/download/v1.15.0/" + HUMAN_MODEL_NAME;
    private static final long HUMAN_MODEL_BYTES = 99066230L;
    private static final String HUMAN_MODEL_SHA256 = "637746e44f0efe00ad1245a50aa9bbf0716efe364c43965ead97bd6835d84ab5";
    private static final String HUMAN_MODEL_RAW_NAME = "b18c384nbt-humanv0.bin";
    private static final String HUMAN_MODEL_RAW_ASSET = "engine/" + HUMAN_MODEL_RAW_NAME;
    private static final long HUMAN_MODEL_RAW_BYTES = 107185997L;
    private static final String HUMAN_MODEL_RAW_SHA256 = "c0b100bab7afd20afdf6d73f483464e0e9188be5479ee1c2ffc6b1c0a528509c";
    // Already validated on RK3588: original Human SL b18 9x9 FP16 RKNN.
    // APK staging is pinned to this exact release digest.
    private static final String HUMAN_RKNN_9_ASSET = "engine/katago_human_9x9.rknn";
    private static final long HUMAN_RKNN_9_BYTES = 59069801L;
    private static final String HUMAN_RKNN_9_SHA256 = "78bee9b2561c4ba14024c1428d750e4a4cec3206011290b6b6b1d7b1e95947c7";

    public interface ModelProgress {
        void onProgress(long downloaded, long total);
    }

    public enum Model {
        HUMAN("Human", "10b.bin", "10-block"),
        SUPERHUMAN("Superhuman", "18b.bin", "18-block"),
        GODLIKE("Godlike", "28b.bin", "28-block");

        public final String displayName;
        public final String fileName;
        public final String description;

        Model(String displayName, String fileName, String description) {
            DebugLog.enter(TAG, "Model in, displayName=" + displayName + ", fileName=" + fileName + ", description=" + description);
            this.displayName = displayName;
            this.fileName = fileName;
            this.description = description;
        }
    }

    private final Context context;
    private final String engineDirectoryName;
    // Each engine start uses a new queue so old reader threads cannot feed a new session.
    private volatile LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process process;
    private volatile KataGoNative.GtpSession jniSession;
    private volatile KataGoGpuNative.GtpSession gpuSession;
    private volatile KataGoNpuNative.GtpSession npuSession;
    private volatile KataGoMediaTekNative.GtpSession mediaTekSession;
    private volatile boolean mediaTekParityEnabled;
    // False in normal games. The debug-only RK3588 smoke runner explicitly enables
    // native Eigen-vs-RKNN head comparison without changing production game speed.
    private volatile boolean humanRknnParityEnabled;
    private volatile int activeNpuBoardSize = -1;
    public enum BackendPreference { CPU, GPU, NPU }
    private volatile BackendPreference backendPreference = BackendPreference.CPU;
    private volatile boolean gpuDisabledForProcess;
    private volatile boolean npuDisabledForProcess;
    private final boolean preferJni;
    private volatile boolean jniDisabledForProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Thread readerThread;
    private Thread errorReaderThread;
    // Invalidate readers of an old JNI/PIE session during fast backend switches.
    private volatile long sessionGeneration;
    private volatile SearchStats currentSearchStats;
    private volatile boolean humanSLRunning;
    // GTP boardsize changes the board dimensions; clear_board alone resets a same-size game.
    private volatile int configuredBoardSize = -1;
    private volatile int startupBoardSize = 9;
    private volatile File verifiedHumanModel;
    private volatile long verifiedHumanModelLength;
    private volatile long verifiedHumanModelModified;

    private static final class SearchStats {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile long rootVisits = -1;
        volatile long newPlayouts = -1;
    }

    public KataGoEngine(Context context) { this(context, "engine", true); }
    public KataGoEngine(Context context, String engineDirectoryName) { this(context, engineDirectoryName, false); }

    /**
     * Prefer in-process JNI for gameplay or the isolated tsumego process.
     * Win-rate analysis still uses PIE. SgfTsumegoActivity MUST remain in
     * its own Android process so a second native GTP session never shares
     * the game's process-wide JNI state.
     */
    public KataGoEngine(Context context, String engineDirectoryName, boolean preferJni) {
        this.context = context.getApplicationContext();
        this.engineDirectoryName = engineDirectoryName;
        this.preferJni = preferJni && ("engine".equals(engineDirectoryName)
                || "engine_tsumego_ai".equals(engineDirectoryName));
        // Independent PIE-based CPU workers: keep analysis and tsumego isolated
        // from the live game's GTP session and from each other.
        if (!"engine".equals(engineDirectoryName)
                && !"engine_winrate".equals(engineDirectoryName)
                && !"engine_tsumego_ai".equals(engineDirectoryName))
            throw new IllegalArgumentException("Unsupported engine directory: " + engineDirectoryName);
    }

    /** RKNN graphs bundled here target RK3588/RK3588S, not other Android NPUs. */
    public static boolean supportsRknnHardware() { return NpuHardware.canRunRknn(); }

    /** The actual backend, not merely the requested preference; false after CPU fallback. */
    public boolean usesNpuBackend() { return npuSession != null || mediaTekSession != null; }

    private boolean hasRawAsset(String path) {
        try (InputStream stream = context.getAssets().open(path)) { return stream.read() >= 0; }
        catch (IOException missing) { return false; }
    }

    // Compact builds replace size-specific models with .kgdelta, while all
    // callers retain their existing logical .tflite/.rknn asset names.
    private boolean hasAsset(String path) {
        return hasRawAsset(path) || (isCompactableModel(path) && hasRawAsset(path + ".kgdelta"));
    }

    private static boolean isCompactableModel(String path) {
        return path.matches("engine/katago_(human|10b)_(9x9|11x11|13x13|15x15)\\.(tflite|rknn)");
    }

    /** A MediaTek GTP session also needs a working, size-matched LiteRT model. */
    public static boolean supportsMediaTekGame(Context context, int size) {
        if (!NpuHardware.canProbeMediaTekLiteRt() || (size != 9 && size != 11 && size != 13 && size != 15 && size != 19)) return false;
        if (!new File(context.getApplicationInfo().nativeLibraryDir, "libkatago_mtk.so").isFile()) return false;
        String stem = "engine/katago_10b_" + size + "x" + size;
        try (InputStream graph = context.getAssets().open(stem + ".tflite");
             InputStream manifest = context.getAssets().open(stem + ".json")) {
            return graph.read() >= 0 && manifest.read() >= 0;
        } catch (IOException missing) { return false; }
    }

    /** Board size of the RKNN model loaded by the active native GTP session. */
    /** Debug only: compare native Eigen and LiteRT pre-postprocessing output heads. */
    public void setMediaTekParityEnabled(boolean enabled) { mediaTekParityEnabled = enabled; }

    /** Debug smoke only: opt in to native Human b18 CPU/NPU five-head parity. */
    public void setHumanRknnParityEnabled(boolean enabled) { humanRknnParityEnabled = enabled; }

    public int getActiveNpuBoardSize() { return npuSession != null || mediaTekSession != null ? activeNpuBoardSize : -1; }

    public String getBackendName() {
        return mediaTekSession != null ? (humanSLRunning
                ? (mediaTekSession.usesHumanLiteRt() ? "NPU/MediaTek LiteRT + Human SL NPU"
                        : "NPU/MediaTek LiteRT + CPU Human SL")
                : "NPU/MediaTek LiteRT JNI") : npuSession != null ? (humanSLRunning
                ? (npuSession.usesHumanRknn() ? "NPU 10b + Human b18 NPU" : "NPU 10b + CPU Human SL")
                : "NPU/RKNN JNI") : gpuSession != null ? "GPU/OpenCL JNI" : jniSession != null ? "CPU/Eigen JNI" : "PIE/ProcessBuilder";
    }

    /**
     * Conservative, read-only strict endgame proof using KataGo's Benson/pass-alive
     * area checker. True certifies a fully resolved area position. False includes
     * unsettled points, current simple ko, invalid position, or missing JNI support.
     * Never equate two passes or NN confidence with a proof.
     */
    public boolean isStrictPassAliveComplete(GoBoard position) {
        if (position == null || !isReady() || position.getSimpleKoPoint() != null) return false;
        int size = position.getSize();
        if (size < 2 || size > 19) return false;
        byte[] colors = new byte[size * size];
        for (int y=0; y<size; y++) {
            for (int x=0; x<size; x++) {
                Intersection stone = position.get(x,y);
                colors[y*size+x] = (byte)(stone == Intersection.BLACK ? 1 : stone == Intersection.WHITE ? 2 : 0);
            }
        }
        boolean certified = npuSession != null ? KataGoNpuNative.isAllPassAlive(size, colors)
                : gpuSession != null ? KataGoGpuNative.isAllPassAlive(size,colors)
                : jniSession != null && KataGoNative.isAllPassAlive(size,colors);
        Log.i(TAG,"PASS_ALIVE_CHECK board=" + size + " certified=" + certified + " backend=" + getBackendName());
        return certified;
    }

    /** Native KataGo reads defaultBoardSize before loading any OpenCL model. */
    public void setStartupBoardSize(int size) {
        if (size < 2 || size > 19) throw new IllegalArgumentException("Unsupported startup board size: " + size);
        startupBoardSize = size;
    }

    /** Change only between games; restarting a selected GPU recreates the native session. */
    public synchronized void setBackendPreference(BackendPreference preference) {
        if (!preferJni) return;
        if (preference == null) preference = BackendPreference.CPU;
        if (backendPreference != preference || (preference == BackendPreference.GPU && running.get() && gpuSession == null)
                || (preference == BackendPreference.NPU && running.get() && npuSession == null && mediaTekSession == null))
            stop();
        backendPreference = preference;
        if (preference == BackendPreference.GPU) gpuDisabledForProcess = false;
        if (preference == BackendPreference.NPU) npuDisabledForProcess = false;
    }

    public synchronized boolean start(Model model) { return start(model, false); }

    public synchronized boolean start(Model model, boolean useHumanSL) {
        long totalStartNs = System.nanoTime();
        DebugLog.enter(TAG, "start in, model=" + model + ", running=" + running.get());
        if (running.get()) {
            // RKNN graphs are fixed-shape; a different board needs a fresh session.
            if (isReady() && humanSLRunning == useHumanSL
                    && ((npuSession == null && mediaTekSession == null) || activeNpuBoardSize == startupBoardSize)) {
                Log.i(TAG, "KATAGO_SESSION_REUSE startCalled=1 board=" + startupBoardSize
                        + " backend=" + getBackendName() + " rknnInit=0");
                return true;
            }
            stop(); // Different engine mode or an engine process/session died.
        }
        configuredBoardSize = -1;
        responseQueue = new LinkedBlockingQueue<>();
        Log.i(TAG, "=== JAVA KATAGO ENGINE / ANDROID ARM64 ===");
        try {
            long phaseNs = System.nanoTime();
            File engineDir = new File(context.getFilesDir(), engineDirectoryName);
            if (!engineDir.exists() && !engineDir.mkdirs()) throw new IOException("Cannot create engine dir: " + engineDir);

            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            File binaryFile = new File(nativeLibDir, NATIVE_BINARY);
            File configFile = new File(engineDir, useHumanSL ? "human_gtp.cfg" : "default_gtp.cfg");
            File modelFile = new File(engineDir, model.fileName);

            String rknnModelName = "katago_10b_" + startupBoardSize + "x" + startupBoardSize + ".rknn";
            String rknnAssetPath = "engine/" + rknnModelName;
            boolean npuSizeSupported = startupBoardSize == 9 || startupBoardSize == 11 || startupBoardSize == 13 || startupBoardSize == 15 || startupBoardSize == 19;
            boolean mtkSizeSupported = npuSizeSupported || startupBoardSize == 11 || startupBoardSize == 15;
            boolean rknnModelAvailable = false;
            if (npuSizeSupported) {
                try (InputStream ignored = context.getAssets().open(rknnAssetPath)) {
                    rknnModelAvailable = true;
                } catch (IOException ignored) {
                    // Only enable RKNN for models actually included in this APK.
                }
            }
            boolean rknnAvailable = new File(nativeLibDir, "libkatago_npu.so").isFile()
                    && new File(nativeLibDir, "librknnrt.so").isFile() && rknnModelAvailable && supportsRknnHardware();
            String liteRtStem = "katago_10b_" + startupBoardSize + "x" + startupBoardSize;
            boolean mtkAvailable = NpuHardware.canProbeMediaTekLiteRt() && mtkSizeSupported
                    && new File(nativeLibDir, "libkatago_mtk.so").isFile()
                    && hasAsset("engine/" + liteRtStem + ".tflite")
                    && hasAsset("engine/" + liteRtStem + ".json");
            boolean useRknn = preferJni && backendPreference == BackendPreference.NPU
                    && !npuDisabledForProcess && rknnAvailable;
            boolean useMediaTek = preferJni && backendPreference == BackendPreference.NPU
                    && !npuDisabledForProcess && mtkAvailable;
            boolean useNpu = useRknn || useMediaTek;
            if (preferJni && backendPreference == BackendPreference.NPU && !useNpu)
                Log.w(TAG, "NPU model/runtime unavailable for board=" + startupBoardSize + "; falling back to CPU");
            boolean gpuAvailable = new File(nativeLibDir, "libkatago_gpu.so").isFile();
            boolean useGpu = preferJni && backendPreference == BackendPreference.GPU && !gpuDisabledForProcess && gpuAvailable;
            if (preferJni && backendPreference == BackendPreference.GPU && !gpuAvailable)
                Log.w(TAG, "GPU JNI library not bundled; using CPU instead");
            boolean useJni = preferJni && !useNpu && !useGpu && !jniDisabledForProcess
                    && new File(nativeLibDir, "libkatago.so").isFile();
            if (!useJni && !useGpu && !useNpu && !binaryFile.isFile())
                throw new IOException("KataGo binary not installed in nativeLibraryDir: " + binaryFile);
            if (!useJni && !useGpu && !useNpu && !binaryFile.canExecute())
                throw new IOException("KataGo binary is not executable: " + binaryFile);
            startupTiming("engine_backend_and_paths", phaseNs);
            phaseNs = System.nanoTime();
            copyAssetToFile(useHumanSL ? HUMAN_CONFIG_ASSET : CONFIG_ASSET, configFile);
            if (useJni || useGpu || useNpu) {
                // Reuse the RK3588 OpenCL tuning cache created by the validated GPU JNI smoke test.
                File smokeGpuHome = new File(context.getFilesDir(), "jni_gpu_smoke/home");
                File homeDir = useGpu && smokeGpuHome.isDirectory() ? smokeGpuHome
                        : new File(engineDir, useGpu ? "gpu_home" : "jni_home");
                File logsDir = new File(engineDir, "gtp_logs");
                if (!homeDir.exists() && !homeDir.mkdirs()) throw new IOException("Cannot create JNI home dir: " + homeDir);
                if (!logsDir.exists() && !logsDir.mkdirs()) throw new IOException("Cannot create GTP log dir: " + logsDir);
                String cfg = new String(java.nio.file.Files.readAllBytes(configFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                cfg = cfg.replace("logDir = gtp_logs", "logDir = " + logsDir.getAbsolutePath());
                cfg += "\n" + "homeDataDir = " + homeDir.getAbsolutePath() + "\n";
                if (useNpu) cfg = KataGoGtpConfig.set(cfg, "nnMaxBatchSize", "1");
                java.nio.file.Files.write(configFile.toPath(), cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if ("engine_winrate".equals(engineDirectoryName)
                    || "engine_tsumego_ai".equals(engineDirectoryName)) {
                // Avoid 6 search threads in independent CPU PIE processes
                // competing with the main game engine.
                String config = new String(java.nio.file.Files.readAllBytes(configFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                config = config.replace("numSearchThreads = 6", "numSearchThreads = 2");
                java.nio.file.Files.write(configFile.toPath(), config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            // Without this native GTP initially builds 19x19 networks, then boardsize 9
            // tears them down and builds 9x9 networks a second time.
            String startupConfig = "\ndefaultBoardSize = " + startupBoardSize + "\n";
            java.nio.file.Files.write(configFile.toPath(), startupConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            Log.i(TAG, "Native GTP defaultBoardSize=" + startupBoardSize);
            startupTiming("engine_prepare_config", phaseNs);
            phaseNs = System.nanoTime();

            String modelAsset = "engine/" + model.fileName;
            try {
                copyAssetToFile(modelAsset, modelFile);
            } catch (IOException e) {
                if (model == Model.HUMAN) throw e;
                Log.w(TAG, modelAsset + " not bundled, falling back to 10b.bin");
                modelFile = new File(engineDir, "10b.bin");
                copyAssetToFile(DEFAULT_MODEL_ASSET, modelFile);
            }
            if (useRknn) copyAssetToFile(rknnAssetPath, new File(engineDir, rknnModelName));
            File liteRtGraph = new File(engineDir, liteRtStem + ".tflite");
            File liteRtManifest = new File(engineDir, liteRtStem + ".json");
            // Optional experimental b18 Human NPU graphs. Never use a partially
            // populated asset pair; KataGoMediaTekLiteRt validates the graph
            // SHA-256 and exact 192 SGF feature tensor contract at start-up.
            String humanStem = "katago_human_" + startupBoardSize + "x" + startupBoardSize;
            File humanGraph = null, humanManifest = null;
            if (useMediaTek) {
                copyAssetToFile("engine/" + liteRtStem + ".tflite", liteRtGraph);
                copyAssetToFile("engine/" + liteRtStem + ".json", liteRtManifest);
                if (useHumanSL && hasAsset("engine/" + humanStem + ".tflite")
                        && hasAsset("engine/" + humanStem + ".json")) {
                    humanGraph = new File(engineDir, humanStem + ".tflite");
                    humanManifest = new File(engineDir, humanStem + ".json");
                    copyAssetToFile("engine/" + humanStem + ".tflite", humanGraph);
                    copyAssetToFile("engine/" + humanStem + ".json", humanManifest);
                    Log.i(TAG, "MTK_HUMAN_GRAPH_STAGED board=" + startupBoardSize);
                }
            }
            startupTiming("engine_prepare_model_asset", phaseNs);
            phaseNs = System.nanoTime();

            File humanFile = null;
            if (useHumanSL) {
                humanFile = isCachedHumanModelValid() ? verifiedHumanModel : findInstalledHumanModel(engineDir);
                if (humanFile == null) throw new IOException("Human SL model unavailable; prepare it first");
                Log.i(TAG, "Human SL model: " + humanFile.getAbsolutePath() + " size=" + humanFile.length());
            }
            startupTiming("engine_find_human_model", phaseNs);

            Log.i(TAG, "Model: " + modelFile.getAbsolutePath() + " size=" + modelFile.length());
            Log.i(TAG, "Config: " + configFile.getAbsolutePath());
            Log.i(TAG, "Backend requested: " + (useMediaTek ? "NPU/MediaTek LiteRT in-process JNI" : useRknn ? "NPU/RKNN in-process JNI" :
                    useGpu ? "GPU/OpenCL in-process JNI" : useJni ? "CPU/JNI Eigen" : "PIE/ProcessBuilder"));

            if (useMediaTek) {
                try {
                    phaseNs = System.nanoTime();
                    mediaTekSession = KataGoMediaTekNative.createSession(context, modelFile, configFile,
                            humanFile, liteRtGraph, liteRtManifest, startupBoardSize,
                            mediaTekParityEnabled, humanGraph, humanManifest);
                    startupTiming("engine_mtk_native_create", phaseNs);
                    if (mediaTekSession == null) throw new IOException("MediaTek GTP returned null");
                    activeNpuBoardSize = startupBoardSize;
                    running.set(true);
                    humanSLRunning = useHumanSL;
                    startReaderThread();
                    responseQueue.clear();
                    if (!sendCommandSync("name") || !waitForStartupResponse(120000))
                        throw new IOException("MediaTek LiteRT GTP did not become ready");
                    Log.i(TAG, "MTK_GAME_BACKEND_READY board=" + activeNpuBoardSize
                            + " humanSL=" + useHumanSL + " backend=" + getBackendName());
                    startupTiming("engine_mtk_ready", totalStartNs);
                    return true;
                } catch (Exception | LinkageError e) {
                    Log.e(TAG, "MediaTek LiteRT GTP startup failed; reverting to CPU", e);
                    npuDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
            }

            if (useRknn) {
                try {
                    phaseNs = System.nanoTime();
                    // Five size-specific Human SL RKNN graphs are independently
                    // validated. Normal games use NPU only; debug smoke can opt in
                    // to expensive native Eigen-vs-RKNN comparison for all five heads.
                    // The existing CPU fallback remains available on an NPU failure.
                    File experimentalHumanRknn = null;
                    boolean verifyHumanNpuAgainstCpu = false;
                    if (useHumanSL && npuSizeSupported) {
                        final int humanBoardSize = startupBoardSize;
                        final String stem = "katago_human_" + humanBoardSize
                                + "x" + humanBoardSize;
                        final String asset = "engine/" + stem + ".rknn";
                        File candidate = new File(engineDir, stem + ".rknn");
                        // .json is reserved for MediaTek LiteRT; RKNN manifests
                        // have their own .rknn.json name to avoid asset conflicts.
                        File manifest = new File(engineDir, stem + ".rknn.json");
                        if (hasAsset(asset)) {
                            try {
                                copyAssetToFile(asset, candidate);
                                if (humanBoardSize != 9)
                                    copyAssetToFile("engine/" + stem + ".rknn.json", manifest);
                            } catch (IOException copyError) {
                                Log.e(TAG, "HUMAN_RKNN_ASSET_COPY_FAILED board="
                                        + humanBoardSize + ": using CPU Human", copyError);
                                candidate = null;
                            }
                        }
                        if (candidate != null &&
                                isTrustedHumanRknnModel(candidate, manifest, humanBoardSize)) {
                            experimentalHumanRknn = candidate;
                            verifyHumanNpuAgainstCpu = humanRknnParityEnabled;
                            Log.i(TAG, "HUMAN_RKNN_EXPERIMENT_REQUESTED board=" + humanBoardSize
                                    + " compareCpu=" + verifyHumanNpuAgainstCpu
                                    + " model=" + candidate);
                            Log.i(TAG, "HUMAN_RKNN_AUTO_ENABLED board=" + humanBoardSize
                                    + " safetyNativeCpuParity=" + verifyHumanNpuAgainstCpu);
                        } else {
                            Log.w(TAG, "HUMAN_RKNN_NOT_STAGED board=" + humanBoardSize
                                    + ": keeping CPU Human; 10b NPU remains enabled");
                        }
                    }
                    npuSession = KataGoNpuNative.createSession(modelFile, configFile, humanFile,
                            new File(engineDir, rknnModelName),
                            experimentalHumanRknn, verifyHumanNpuAgainstCpu);
                    startupTiming("engine_npu_native_create", phaseNs);
                    if (npuSession == null) throw new IOException("NPU JNI createSession returned null");
                    activeNpuBoardSize = startupBoardSize;
                    Log.i(TAG, "NPU_MODEL_SELECTED board=" + activeNpuBoardSize + " path=" + new File(engineDir, rknnModelName));
                    running.set(true);
                    humanSLRunning = useHumanSL;
                    startReaderThread();
                    responseQueue.clear();
                    boolean nameReady = sendCommandSync("name") && waitForStartupResponse(120000);
                    if (!nameReady) throw new IOException("NPU/RKNN GTP did not become ready");
                    startupTiming("engine_npu_ready", totalStartNs);
                    return true;
                } catch (Exception | LinkageError e) {
                    Log.e(TAG, "NPU GTP startup failed; reverting to CPU", e);
                    npuDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
            }

            if (useGpu) {
                try {
                    phaseNs = System.nanoTime();
                    gpuSession = KataGoGpuNative.createSession(modelFile, configFile, humanFile);
                    startupTiming("engine_gpu_native_create", phaseNs);
                    if (gpuSession == null) throw new IOException("GPU JNI createSession returned null");
                    phaseNs = System.nanoTime();
                    running.set(true);
                    humanSLRunning = useHumanSL;
                    startReaderThread();
                    responseQueue.clear();
                    startupTiming("engine_gpu_reader_setup", phaseNs);
                    phaseNs = System.nanoTime();
                    boolean nameSent = sendCommandSync("name");
                    startupTiming("engine_gpu_gtp_name_send", phaseNs);
                    phaseNs = System.nanoTime();
                    boolean nameReady = nameSent && waitForStartupResponse(360000);
                    startupTiming("engine_gpu_gtp_name_wait", phaseNs);
                    if (!nameReady) throw new IOException("GPU OpenCL JNI GTP did not become ready");
                    startupTiming("engine_java_start_total", totalStartNs);
                    Log.i(TAG, "=== GPU/OPENCL JNI ENGINE STARTED SUCCESSFULLY ===");
                    return true;
                } catch (Exception | LinkageError e) {
                    startupTiming("engine_gpu_start_failed", totalStartNs);
                    Log.e(TAG, "GPU in-process JNI startup failed; falling back to CPU", e);
                    gpuDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
            }

            if (useJni) {
                try {
                    phaseNs = System.nanoTime();
                    jniSession = KataGoNative.createSession(modelFile, configFile, humanFile);
                    startupTiming("engine_cpu_jni_create", phaseNs);
                    if (jniSession == null) throw new IOException("KataGo JNI createSession returned null");
                } catch (LinkageError | RuntimeException e) {
                    Log.e(TAG, "JNI startup failed, falling back to ProcessBuilder", e);
                    jniDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                } catch (IOException e) {
                    Log.e(TAG, "JNI session creation failed; fallback", e);
                    jniDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
                running.set(true);
                humanSLRunning = useHumanSL;
                startReaderThread();
                responseQueue.clear();
                phaseNs = System.nanoTime();
                boolean nameSent = sendCommandSync("name");
                startupTiming("engine_cpu_gtp_name_send", phaseNs);
                phaseNs = System.nanoTime();
                boolean nameReady = nameSent && waitForStartupResponse(120000);
                startupTiming("engine_cpu_gtp_name_wait", phaseNs);
                if (!nameReady) {
                    Log.e(TAG, "JNI GTP failed to become ready; retrying with PIE");
                    jniDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
                startupTiming("engine_java_start_total", totalStartNs);
                Log.i(TAG, "=== JNI/EIGEN ENGINE STARTED SUCCESSFULLY ===");
                return true;
            }

            List<String> command = new ArrayList<>();
            command.add(binaryFile.getAbsolutePath());
            command.add("gtp");
            command.add("-model");
            command.add(modelFile.getAbsolutePath());
            command.add("-config");
            command.add(configFile.getAbsolutePath());
            if (humanFile != null) {
                command.add("-human-model");
                command.add(humanFile.getAbsolutePath());
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(engineDir);
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", nativeLibDir);
            env.remove("ADSP_LIBRARY_PATH");
            env.put("HOME", context.getFilesDir().getAbsolutePath());

            Log.i(TAG, "Command: " + String.join(" ", command));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            running.set(true);
            humanSLRunning = useHumanSL;
            startReaderThread();
            startErrorReaderThread();

            // Wait for the actual native GTP response instead of a fixed 2-second sleep.
            responseQueue.clear();
            if (!sendCommandSync("name") || !waitForStartupResponse(30000)) {
                Log.e(TAG, "KataGo GTP did not become ready");
                stop();
                return false;
            }
            Log.i(TAG, "=== ENGINE STARTED SUCCESSFULLY ===");
            return true;
        } catch (Exception | LinkageError e) {
            startupTiming("engine_java_start_failed", totalStartNs);
            Log.e(TAG, "Failed to start engine", e);
            if (jniSession != null) {
                jniDisabledForProcess = true;
                stop();
                Log.w(TAG, "Retrying startup with PIE after JNI exception");
                return start(model, useHumanSL);
            }
            stop();
            return false;
        }
    }

    /** GTP is ready only after its models have loaded and it replies to name. */
    private boolean waitForStartupResponse(int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (jniSession != null && !jniSession.isAlive()) return false;
            if (gpuSession != null && !gpuSession.isAlive()) return false;
            if (npuSession != null && !npuSession.isAlive()) return false;
            if (mediaTekSession != null && !mediaTekSession.isAlive()) return false;
            if (jniSession == null && gpuSession == null && npuSession == null && mediaTekSession == null && (process == null || !process.isAlive())) return false;
            try {
                String response = responseQueue.poll(200, TimeUnit.MILLISECONDS);
                if (response != null) {
                    String reply = response.trim();
                    if (reply.startsWith("=") && reply.substring(1).trim().equals("KataGo")) {
                        Log.i(TAG, "KataGo GTP ready response: " + reply);
                        return true;
                    }
                    Log.w(TAG, "Ignoring stale/non-name GTP startup response: " + reply);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void startReaderThread() {
        DebugLog.enter(TAG, "startReaderThread in");
        if (mediaTekSession != null) { startMediaTekReaderThread(); return; }
        if (npuSession != null) { startNpuReaderThread(); return; }
        if (gpuSession != null) { startGpuReaderThread(); return; }
        if (jniSession != null) { startJniReaderThread(); return; }
        final BufferedReader sessionReader = reader;
        final Process expectedProcess = process;
        final long generation = sessionGeneration;
        final LinkedBlockingQueue<String> sessionResponses = responseQueue;
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                String line;
                while (running.get() && generation == sessionGeneration && expectedProcess == process
                        && (line = sessionReader.readLine()) != null) {
                    if (!running.get() || generation != sessionGeneration || expectedProcess != process) break;
                    Log.d(TAG, "KataGo stdout: " + line);
                    buffer.append(line).append('\n');
                    if (line.isEmpty() && buffer.length() > 0) {
                        sessionResponses.offer(buffer.toString());
                        buffer.setLength(0);
                    }
                }
            } catch (IOException e) {
                if (running.get()) Log.e(TAG, "Stdout reader failed", e);
            }
        }, "KataGo-stdout");
        readerThread.start();
    }

    private void startJniReaderThread() {
        final KataGoNative.GtpSession session = jniSession;
        final long generation = sessionGeneration;
        final LinkedBlockingQueue<String> sessionResponses = responseQueue;
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                // Bind this reader to its original session: an old reader must
                // never consume a new session's output during a quick restart.
                while (running.get() && generation == sessionGeneration && jniSession == session) {
                    String chunk = session.read(1000);
                    if (chunk == null || !running.get() || generation != sessionGeneration || jniSession != session) break;
                    buffer.append(chunk.replace("\r\n", "\n"));
                    int end;
                    while ((end = buffer.indexOf("\n\n")) >= 0) {
                        String response = buffer.substring(0, end + 2);
                        buffer.delete(0, end + 2);
                        Log.d(TAG, "KataGo JNI GTP response: " + response.trim());
                        sessionResponses.offer(response);
                    }
                    if (buffer.length() > 8 * 1024 * 1024) throw new IOException("JNI GTP reply too large");
                }
                if (running.get()) Log.w(TAG, "JNI GTP reader reached EOF");
            } catch (Exception | LinkageError e) {
                if (running.get()) Log.e(TAG, "JNI GTP output reader failed", e);
            }
        }, "KataGo-JNI-stdout");
        readerThread.start();
    }

    private void startGpuReaderThread() {
        final KataGoGpuNative.GtpSession session = gpuSession;
        final long generation = sessionGeneration;
        final LinkedBlockingQueue<String> sessionResponses = responseQueue;
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                while (running.get() && generation == sessionGeneration && gpuSession == session) {
                    String chunk = session.read(1000);
                    if (chunk == null || !running.get() || generation != sessionGeneration || gpuSession != session) break;
                    buffer.append(chunk.replace("\r\n", "\n"));
                    int end;
                    while ((end = buffer.indexOf("\n\n")) >= 0) {
                        String response = buffer.substring(0, end + 2);
                        buffer.delete(0, end + 2);
                        Log.d(TAG, "GPU JNI GTP response: " + response.trim());
                        sessionResponses.offer(response);
                    }
                    if (buffer.length() > 8 * 1024 * 1024) throw new IOException("GPU GTP reply too large");
                }
                if (running.get()) Log.w(TAG, "GPU JNI GTP reader reached EOF");
            } catch (Exception e) {
                if (running.get()) Log.e(TAG, "GPU GTP output reader failed", e);
            }
        }, "KataGo-GPU-JNI-stdout");
        readerThread.start();
    }

    private void startMediaTekReaderThread() {
        final KataGoMediaTekNative.GtpSession session = mediaTekSession;
        final long generation = sessionGeneration;
        final LinkedBlockingQueue<String> sessionResponses = responseQueue;
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                while (running.get() && generation == sessionGeneration && mediaTekSession == session) {
                    String chunk = session.read(1000);
                    if (chunk == null || !running.get() || generation != sessionGeneration || mediaTekSession != session) break;
                    buffer.append(chunk.replace("\r\n", "\n"));
                    int end;
                    while ((end = buffer.indexOf("\n\n")) >= 0) {
                        String response = buffer.substring(0, end + 2);
                        buffer.delete(0, end + 2);
                        Log.d(TAG, "MediaTek JNI GTP response: " + response.trim());
                        sessionResponses.offer(response);
                    }
                    if (buffer.length() > 8 * 1024 * 1024) throw new IOException("MediaTek GTP reply too large");
                }
            } catch (Exception | LinkageError e) {
                if (running.get()) Log.e(TAG, "MediaTek GTP output reader failed", e);
            }
        }, "KataGo-MediaTek-JNI-stdout");
        readerThread.start();
    }

    private void startNpuReaderThread() {
        final KataGoNpuNative.GtpSession session = npuSession;
        final long generation = sessionGeneration;
        final LinkedBlockingQueue<String> sessionResponses = responseQueue;
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                while (running.get() && generation == sessionGeneration && npuSession == session) {
                    String chunk = session.read(1000);
                    if (chunk == null || !running.get() || generation != sessionGeneration || npuSession != session) break;
                    buffer.append(chunk.replace("\r\n", "\n"));
                    int end;
                    while ((end = buffer.indexOf("\n\n")) >= 0) {
                        String response = buffer.substring(0, end + 2);
                        buffer.delete(0, end + 2);
                        Log.d(TAG, "NPU JNI GTP response: " + response.trim());
                        sessionResponses.offer(response);
                    }
                    if (buffer.length() > 8 * 1024 * 1024) throw new IOException("NPU GTP reply too large");
                }
                if (running.get()) Log.w(TAG, "NPU JNI GTP reader reached EOF");
            } catch (Exception e) {
                if (running.get()) Log.e(TAG, "NPU GTP output reader failed", e);
            }
        }, "KataGo-NPU-JNI-stdout");
        readerThread.start();
    }

    private void startErrorReaderThread() {
        DebugLog.enter(TAG, "startErrorReaderThread in");
        final BufferedReader sessionErrorReader = errorReader;
        final Process expectedProcess = process;
        final long generation = sessionGeneration;
        errorReaderThread = new Thread(() -> {
            try {
                String line;
                while (running.get() && generation == sessionGeneration && expectedProcess == process
                        && (line = sessionErrorReader.readLine()) != null) {
                    if (!running.get() || generation != sessionGeneration || expectedProcess != process) break;
                    recordSearchStats(line);
                    Log.d(TAG, "KataGo stderr: " + line);
                }
            } catch (IOException e) {
                if (running.get() && generation == sessionGeneration) Log.e(TAG, "Stderr reader failed", e);
            }
        }, "KataGo-stderr");
        errorReaderThread.start();
    }

    private void recordSearchStats(String line) {
        SearchStats stats = currentSearchStats;
        if (stats == null) return;
        try {
            if (line.startsWith("Root visits: ")) {
                stats.rootVisits = Long.parseLong(line.substring("Root visits: ".length()).trim());
            } else if (line.startsWith("New playouts: ")) {
                stats.newPlayouts = Long.parseLong(line.substring("New playouts: ".length()).trim());
                stats.ready.countDown();
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Cannot parse KataGo search statistics: " + line, e);
            stats.ready.countDown();
        }
    }

    public synchronized void stop() {
        DebugLog.enter(TAG, "stop in, running=" + running.get() + ", process=" + process);
        if (!running.get() && process == null && jniSession == null && gpuSession == null && npuSession == null && mediaTekSession == null) return;
        sessionGeneration++;
        try { sendCommandSync("quit"); } catch (Exception ignored) {}
        running.set(false);
        humanSLRunning = false;
        configuredBoardSize = -1;
        activeNpuBoardSize = -1;
        if (mediaTekSession != null) {
            KataGoMediaTekNative.GtpSession old = mediaTekSession;
            mediaTekSession = null;
            try { old.close(); } catch (Exception | LinkageError e) { Log.w(TAG, "MediaTek session cleanup failed", e); }
            responseQueue.clear();
            Log.i(TAG, "NPU/MediaTek LiteRT JNI session stopped");
        }
        if (npuSession != null) {
            KataGoNpuNative.GtpSession old = npuSession;
            npuSession = null;
            try { old.close(); } catch (Exception | LinkageError e) { Log.w(TAG, "NPU session cleanup failed", e); }
            responseQueue.clear();
            Log.i(TAG, "NPU/RKNN JNI session stopped");
        }
        if (gpuSession != null) {
            KataGoGpuNative.GtpSession old = gpuSession;
            gpuSession = null;
            old.close();
            responseQueue.clear();
            Log.i(TAG, "GPU/OpenCL JNI session stopped");
        }
        if (jniSession != null) {
            KataGoNative.GtpSession old = jniSession;
            jniSession = null;
            try { old.close(); } catch (Exception | LinkageError e) { Log.w(TAG, "JNI GTP cleanup failed", e); }
            responseQueue.clear();
            Log.i(TAG, "KataGo JNI session stopped");
        }
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (errorReader != null) errorReader.close(); } catch (Exception ignored) {}
        if (process != null) {
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
        responseQueue.clear();
        process = null;
        writer = null;
        reader = null;
        errorReader = null;
        Log.i(TAG, "KataGo stopped");
    }

    /**
     * A new game can reuse the existing GTP/RKNN model context. The UI must
     * not infer this from the text of getBackendName(), which reflects the
     * actual fallback backend rather than the requested backend.
     *
     * The 9/13/19 RKNN graphs have fixed shapes: on a size change we must
     * create a new native session instead of sending a mismatched boardsize.
     * Human rank, komi, handicap and search limits are GTP parameters, not
     * reasons to call rknn_init() again.
     */
    public boolean canReuseHumanSession(BackendPreference requested, int size) {
        if (requested == null || requested != backendPreference
                || !humanSLRunning || !isReady()) return false;
        if (requested == BackendPreference.NPU) {
            return (npuSession != null || mediaTekSession != null)
                    && activeNpuBoardSize == size;
        }
        if (requested == BackendPreference.GPU) return gpuSession != null;
        // The CPU fallback is reusable if the user explicitly chose CPU.
        return npuSession == null && mediaTekSession == null && gpuSession == null;
    }

    public boolean isHumanSLRunning() { return isReady() && humanSLRunning; }

    public boolean hasHumanModel() {
        File dir = new File(context.getFilesDir(), "engine");
        if (isValidHumanModel(new File(dir, HUMAN_MODEL_NAME))
                || isValidHumanModel(new File(dir, HUMAN_MODEL_RAW_NAME))) return true;
        for (String asset : new String[]{HUMAN_MODEL_ASSET, HUMAN_MODEL_RAW_ASSET}) {
            try (InputStream ignored = context.getAssets().open(asset)) {
                return true;
            } catch (java.io.FileNotFoundException missing) {
                // Try the other supported model encoding.
            } catch (IOException e) {
                Log.w(TAG, "Cannot check Human SL asset " + asset, e);
            }
        }
        return false;
    }

    private boolean isRawHumanModel(File file) {
        String name = file.getName();
        return HUMAN_MODEL_RAW_NAME.equals(name) || (HUMAN_MODEL_RAW_NAME + ".part").equals(name);
    }

    private boolean isValidHumanModel(File file) {
        boolean raw = isRawHumanModel(file);
        if (!file.isFile() || file.length() != (raw ? HUMAN_MODEL_RAW_BYTES : HUMAN_MODEL_BYTES)) return false;
        if (raw) return true;
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isTrustedHumanModel(File file) throws IOException {
        if (!isValidHumanModel(file)) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] data = new byte[65536];
                int n;
                while ((n = in.read(data)) != -1) if (n > 0) digest.update(data, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            String expected = isRawHumanModel(file) ? HUMAN_MODEL_RAW_SHA256 : HUMAN_MODEL_SHA256;
            return expected.equals(hex.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** Only activate the exact RK3588-validated Human RKNN graph, never a
     * partial download, renamed 10b graph, or unverified test model.
     */
    private boolean isTrustedHumanRknnModel(File file, File manifest, int size) {
        if (size == 9) return isTrustedHumanRknnModel(file);
        if ((size != 11 && size != 13 && size != 15 && size != 19) || file == null || !file.isFile()
                || file.length() < 1000000L || !manifest.isFile()) return false;
        try {
            JSONObject doc = new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(manifest.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!"b18c384nbt-humanv0".equals(doc.optString("model"))
                    || doc.optInt("board") != size
                    || !"rk3588".equals(doc.optString("target"))
                    || doc.optBoolean("quantized", true)) return false;
            JSONObject inputs = doc.getJSONObject("inputs");
            if (inputs.getJSONArray("InputSpatial").getInt(1) != 22
                    || inputs.getJSONArray("InputSpatial").getInt(2) != size
                    || inputs.getJSONArray("InputSpatial").getInt(3) != size
                    || inputs.getJSONArray("InputMeta").getInt(1) != 192
                    || inputs.getJSONArray("InputMask").getInt(2) != size
                    || inputs.getJSONArray("InputMask").getInt(3) != size) return false;
            String expectedSha = doc.optString("sha256");
            if (!expectedSha.matches("[0-9a-f]{64}")) return false;
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0)
                    if (count > 0) sha.update(buffer, 0, count);
            }
            StringBuilder digest = new StringBuilder(64);
            for (byte b : sha.digest())
                digest.append(String.format(Locale.US, "%02x", b & 0xff));
            return expectedSha.equals(digest.toString());
        } catch (Exception error) {
            Log.w(TAG, "Human RKNN " + size + " manifest/hash verification failed", error);
            return false;
        }
    }

    private boolean isTrustedHumanRknnModel(File file) {
        if (!file.isFile() || file.length() != HUMAN_RKNN_9_BYTES) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int bytes;
            while ((bytes = input.read(buffer)) != -1) if (bytes > 0) hash.update(buffer, 0, bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : hash.digest()) text.append(String.format(Locale.US, "%02x", value & 0xff));
            return HUMAN_RKNN_9_SHA256.equals(text.toString());
        } catch (IOException | NoSuchAlgorithmException error) {
            Log.w(TAG, "Cannot verify Human b18 RKNN SHA-256", error);
            return false;
        }
    }

    /** Trust a SHA-256 result only while the private file remains unchanged. */
    private boolean isCachedHumanModelValid() {
        File file = verifiedHumanModel;
        return file != null && isValidHumanModel(file)
                && file.length() == verifiedHumanModelLength
                && file.lastModified() == verifiedHumanModelModified;
    }

    private File findInstalledHumanModel(File dir) throws IOException {
        // Installed files are app-private and were checked when imported/downloaded.
        // Avoid hashing the full Human SL model again on every app startup.
        File compressed = new File(dir, HUMAN_MODEL_NAME);
        if (isValidHumanModel(compressed)) return compressed;
        File raw = new File(dir, HUMAN_MODEL_RAW_NAME);
        return isValidHumanModel(raw) ? raw : null;
    }

    /** Load from either official gzip asset or its verified decompressed .bin equivalent. */
    private File copyBundledHumanModel(File dir, String asset, String name, long expected, ModelProgress progress) throws IOException {
        InputStream source;
        try {
            source = context.getAssets().open(asset);
        } catch (java.io.FileNotFoundException missing) {
            return null;
        }

        File part = new File(dir, name + ".part");
        try (InputStream input = source) {
            Log.i(TAG, "Preparing bundled Human SL model: " + asset);
            copyStream(input, part, expected, progress);
            if (!isTrustedHumanModel(part)) throw new IOException("Human SL asset SHA-256 mismatch: " + asset);
            File dest = new File(dir, name);
            if (dest.exists() && !dest.delete()) throw new IOException("Cannot replace existing Human SL model");
            if (!part.renameTo(dest)) throw new IOException("Cannot install bundled Human SL model");
            return dest;
        } finally {
            if (part.exists()) part.delete();
        }
    }

    /** Run on the engine executor, not the Android UI thread. */
    public File prepareHumanModel(ModelProgress progress) throws IOException {
        File dir = new File(context.getFilesDir(), "engine");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create model directory");

        if (isCachedHumanModelValid()) {
            Log.i(TAG, "Human SL model reused from verified cache: " + verifiedHumanModel.getAbsolutePath());
            return verifiedHumanModel;
        }
        verifiedHumanModel = null;
        File model = findInstalledHumanModel(dir);
        if (model == null) model = copyBundledHumanModel(dir, HUMAN_MODEL_ASSET, HUMAN_MODEL_NAME, HUMAN_MODEL_BYTES, progress);
        if (model == null) model = copyBundledHumanModel(dir, HUMAN_MODEL_RAW_ASSET, HUMAN_MODEL_RAW_NAME, HUMAN_MODEL_RAW_BYTES, progress);

        if (model == null) {
            model = new File(dir, HUMAN_MODEL_NAME);
            File part = new File(dir, HUMAN_MODEL_NAME + ".part");
            try {
                downloadHumanModel(part, progress);
                if (!isTrustedHumanModel(part)) throw new IOException("Downloaded Human SL model SHA-256 mismatch");
                if (model.exists() && !model.delete()) throw new IOException("Cannot replace Human SL model");
                if (!part.renameTo(model)) throw new IOException("Cannot install downloaded Human SL model");
            } finally {
                if (part.exists()) part.delete();
            }
        }

        verifiedHumanModelLength = model.length();
        verifiedHumanModelModified = model.lastModified();
        verifiedHumanModel = model;
        Log.i(TAG, "Human SL model ready: " + model.getAbsolutePath());
        return model;
    }

    private void downloadHumanModel(File part, ModelProgress progress) throws IOException {
        Log.i(TAG, "Downloading Human SL model from official KataGo release");
        HttpURLConnection conn = (HttpURLConnection) new URL(HUMAN_MODEL_URL).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "BadukaiJ-Android");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IOException("Human SL model HTTP " + conn.getResponseCode());
            try (InputStream input = conn.getInputStream()) {
                copyStream(input, part, HUMAN_MODEL_BYTES, progress);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void copyStream(InputStream input, File dest, long expected, ModelProgress progress) throws IOException {
        long written = 0;
        long nextProgress = 0;
        try (FileOutputStream out = new FileOutputStream(dest, false)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                if (n == 0) continue;
                written += n;
                if (written > expected) throw new IOException("Human SL model is larger than expected");
                out.write(buffer, 0, n);
                if (progress != null && written >= nextProgress) {
                    progress.onProgress(written, expected);
                    nextProgress = written + 1024 * 1024;
                }
            }
        }
        if (written != expected) throw new IOException("Human SL model truncated: " + written + "/" + expected);
        if (progress != null) progress.onProgress(written, expected);
    }

    public boolean setHumanRank(int kyu) {
        if (!isHumanSLRunning() || kyu < 1 || kyu > 18) return false;
        String profile = "rank_" + kyu + "k";
        boolean ok = simpleCommand("kata-set-param humanSLProfile " + profile, 10000);
        String actual = ok ? getSearchParam("humanSLProfile") : null;
        boolean verified = profile.equals(actual);
        Log.i(TAG, "Human SL rank verified=" + verified + " requested=" + profile + " readback=" + actual);
        return verified;
    }

    public boolean isReady() {
        DebugLog.enter(TAG, "isReady in, running=" + running.get());
        KataGoNative.GtpSession session = jniSession;
        KataGoGpuNative.GtpSession remote = gpuSession;
        KataGoNpuNative.GtpSession npu = npuSession;
        KataGoMediaTekNative.GtpSession mtk = mediaTekSession;
        return running.get() && (mtk != null ? mtk.isAlive() : npu != null ? npu.isAlive() : remote != null ? remote.isAlive()
                : session != null ? session.isAlive() : (process != null && process.isAlive()));
    }

    /**
     * Applies the new-game search limits to the running GTP engine.
     * Both limits are ceilings; whichever is reached first can stop a search.
     * A successful set response is followed by a get-param readback.
     */
    public synchronized boolean setSearchLimits(int visits, double seconds) {
        DebugLog.enter(TAG, "setSearchLimits in, visits=" + visits + ", seconds=" + seconds);
        if (!running.get() || visits < 1 || !Double.isFinite(seconds) || seconds <= 0.0) {
            Log.e(TAG, "Search limits invalid or engine not ready");
            return false;
        }

        String timeText = String.format(Locale.US, "%.3f", seconds);
        boolean visitsSet = simpleCommand("kata-set-param maxVisits " + visits, 10000);
        boolean timeSet = visitsSet && simpleCommand("kata-set-param maxTime " + timeText, 10000);
        if (!visitsSet || !timeSet) {
            Log.e(TAG, "Search limit setting FAILED: maxVisits=" + visits + ", maxTime=" + timeText);
            return false;
        }

        String returnedVisits = getSearchParam("maxVisits");
        String returnedTime = getSearchParam("maxTime");
        boolean verified = false;
        try {
            int readVisits = Integer.parseInt(returnedVisits.trim());
            double readTime = Double.parseDouble(returnedTime.trim());
            verified = readVisits == visits && Math.abs(readTime - seconds) < 0.001;
        } catch (Exception e) {
            Log.e(TAG, "Cannot parse search limit readback", e);
        }
        Log.i(TAG, "Search limits verified=" + verified + " requested: maxVisits=" + visits
                + " maxTime=" + timeText + " readback: maxVisits=" + returnedVisits
                + " maxTime=" + returnedTime);
        return verified;
    }

    private String getSearchParam(String name) {
        responseQueue.clear();
        if (!sendCommandSync("kata-get-param " + name)) return null;
        String response = waitForResponse(10000);
        if (!response.startsWith("=")) {
            Log.e(TAG, "kata-get-param " + name + " failed: " + response.trim());
            return null;
        }
        return parseGtpResponse(response);
    }

    public String generateMove(String color) {
        DebugLog.enter(TAG, "generateMove in, color=" + color);
        responseQueue.clear();
        SearchStats stats = new SearchStats();
        currentSearchStats = stats;
        long startedNs = System.nanoTime();
        try {
            // KataGo v1.14.1: same move as genmove, with exact root visit stats on stderr.
            boolean isJni = jniSession != null || gpuSession != null || npuSession != null || mediaTekSession != null;
            if (!sendCommandSync((isJni ? "genmove " : "genmove_debug ") + color)) return null;
            String response = waitForResponse(60000);
            long elapsedMs = (System.nanoTime() - startedNs) / 1000000L;
            String move = parseGtpResponse(response);
            try {
                if (!isJni && !stats.ready.await(500, TimeUnit.MILLISECONDS)) Log.w(TAG, "Search statistics not received from KataGo");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "genmove color=" + color + " move=" + move
                    + " rootVisits=" + (stats.rootVisits >= 0 ? stats.rootVisits : "unavailable")
                    + " newPlayouts=" + (stats.newPlayouts >= 0 ? stats.newPlayouts : "unavailable")
                    + " elapsedMs=" + elapsedMs + " (wall time includes GTP and inference overhead)");
            return move;
        } finally {
            currentSearchStats = null;
        }
    }

    public boolean playMove(String color, String move) {
        DebugLog.enter(TAG, "playMove in, color=" + color + ", move=" + move);
        return simpleCommand("play " + color + " " + move, 5000);
    }

    /** Apply real Chinese area rules to GTP and verify the active ruleset. */
    public boolean setChineseRules() {
        if (!simpleCommand("kata-set-rules chinese", 10000)) return false;
        responseQueue.clear();
        if (!sendCommandSync("kata-get-rules")) return false;
        String json = parseGtpResponse(waitForResponse(10000));
        if (json == null) return false;
        try {
            JSONObject rules = new JSONObject(json);
            boolean correct = "AREA".equals(rules.optString("scoring"))
                    && "NONE".equals(rules.optString("tax"))
                    && "SIMPLE".equals(rules.optString("ko"))
                    && !rules.optBoolean("suicide", true);
            Log.i(TAG, "Chinese rules verified=" + correct + " rules=" + json);
            return correct;
        } catch (Exception e) {
            Log.e(TAG, "Cannot verify KataGo Chinese rules", e);
            return false;
        }
    }

    public boolean setBoardSize(int size) {
        DebugLog.enter(TAG, "setBoardSize in, size=" + size);
        // Never resize an active RKNN model to a different graph shape:
        // MainActivity must restart GTP and choose that board's RKNN asset.
        if ((npuSession != null || mediaTekSession != null) && activeNpuBoardSize != size) {
            Log.e(TAG, "NPU_BOARD_SIZE_MISMATCH activeModel=" + activeNpuBoardSize
                    + " requested=" + size + "; restart NPU engine first");
            return false;
        }
        if (isReady() && configuredBoardSize == size) {
            Log.i(TAG, "Reuse existing GTP boardsize=" + size + "; clear_board will reset game state");
            return true;
        }
        long startedNs = System.nanoTime();
        boolean ok = simpleCommand("boardsize " + size, npuSession != null || mediaTekSession != null ? 120000 : gpuSession != null ? 360000 : jniSession != null ? 90000 : 5000);
        if (ok) configuredBoardSize = size;
        Log.i(TAG, "GTP boardsize=" + size + " ok=" + ok + " elapsedMs=" + (System.nanoTime() - startedNs) / 1000000L);
        return ok;
    }

    /** Initialize KataGo with the same fixed handicap positions as the Java board. */
    public boolean setHandicapStones(List<com.badukai.game.Point> points, int boardSize) {
        if (points == null || points.size() < 2 || points.size() > 9) return false;
        StringBuilder command = new StringBuilder("set_free_handicap");
        for (com.badukai.game.Point point : points) {
            String vertex = point == null ? null : point.toGtp(boardSize);
            if (vertex == null) return false;
            command.append(' ').append(vertex);
        }
        boolean ok = simpleCommand(command.toString(), 10000);
        Log.i(TAG, "KataGo fixed handicap stones=" + points.size() + " ready=" + ok);
        return ok;
    }

    public boolean clearBoard() {
        DebugLog.enter(TAG, "clearBoard in");
        return simpleCommand("clear_board", 5000);
    }

    /**
     * KataGo's official GTP extension for whole-board SGF setup positions.
     * Unlike a succession of play commands, this does not invent game history
     * or accidentally capture stones while placing the initial black/white setup.
     */
    public boolean setTrainingPosition(GoBoard board) {
        if (board == null || board.getSize() != configuredBoardSize || !isReady())
            return false;
        StringBuilder cmd = new StringBuilder("set_position");
        int size = board.getSize();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Intersection stone = board.get(x, y);
                if (stone == Intersection.EMPTY) continue;
                String vertex = new com.badukai.game.Point(x, y).toGtp(size);
                if (vertex == null) return false;
                cmd.append(stone == Intersection.BLACK ? " B " : " W ").append(vertex);
            }
        }
        return simpleCommand(cmd.toString(), 10000);
    }



    public boolean setKomi(float komi) {
        DebugLog.enter(TAG, "setKomi in, komi=" + komi);
        return simpleCommand("komi " + komi, 5000);
    }

    public boolean undo() {
        DebugLog.enter(TAG, "undo in");
        return simpleCommand("undo", 5000);
    }

    /**
     * A single evaluation from the normal 10b model, NOT the human-style model.
     * whiteLead already accounts for komi, in points. KataGo raw NN arrays
     * use row-major board order: x left-to-right, y top-to-bottom.
     */
    /** A ranked MCTS candidate from the normal KataGo 10b search, not Human SL. */
    public static final class SearchRecommendation {
        public final String move;
        public final double winrate, scoreLead, prior;
        public final int visits, order;
        // KataGo principal variation in GTP notation, beginning with this candidate move.
        public final List<String> pvMoves;

        private SearchRecommendation(String move, double winrate, double scoreLead, double prior, int visits,
                                     int order, List<String> pvMoves) {
            this.move = move;
            this.winrate = winrate;
            this.scoreLead = scoreLead;
            this.prior = prior;
            this.visits = visits;
            this.order = order;
            this.pvMoves = Collections.unmodifiableList(new ArrayList<>(pvMoves));
        }
    }

    /**
     * Run a short non-playing MCTS search. All commands run on engineExecutor.
     * Save and restore the Human SL game limits even if analysis fails.
     * Results use the requested player's perspective (scoreLead includes komi).
     */
    public List<SearchRecommendation> searchRecommendations(String player, int visits, double seconds) {
        return searchRecommendationsRestricted(player, visits, seconds, "");
    }

    /**
     * Search only the rectangular fight area derived from the actual SGF
     * solution, NOT the entire displayed 9x9 training viewport.
     * [left,right) x [top,bottom) are full-board 19x19 SGF coordinates.
     * KataGo still evaluates the complete position under full-board rules.
     */
    public List<SearchRecommendation> searchLocalRecommendations(String player, int visits,
            double seconds, GoBoard board, int left, int top, int right, int bottom) {
        if (board == null || left < 0 || top < 0 || right <= left || bottom <= top
                || right > board.getSize() || bottom > board.getSize())
            return Collections.emptyList();
        boolean black = "black".equals(player), white = "white".equals(player);
        if (!black && !white) return Collections.emptyList();
        StringBuilder vertices = new StringBuilder();
        com.badukai.game.StoneColor side = black
                ? com.badukai.game.StoneColor.BLACK : com.badukai.game.StoneColor.WHITE;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                com.badukai.game.Point at = new com.badukai.game.Point(x, y);
                if (!board.isLegalMove(at, side)) continue;
                if (vertices.length() > 0) vertices.append(',');
                vertices.append(at.toGtp(board.getSize()));
            }
        }
        if (vertices.length() == 0) return Collections.emptyList();
        // KataGo GTP's documented allow PLAYER VERTICES DEPTH restriction.
        String options = " allow " + (black ? "B" : "W") + " " + vertices + " 4";
        List<SearchRecommendation> raw = searchRecommendationsRestricted(
                player, visits, seconds, options);
        List<SearchRecommendation> allowed = new ArrayList<>();
        for (SearchRecommendation candidate : raw) {
            com.badukai.game.Point p = com.badukai.game.Point.fromGtp(
                    candidate.move, board.getSize());
            if (p == null || candidate.visits <= 0
                    || p.x < left || p.x >= right || p.y < top || p.y >= bottom
                    || !board.isLegalMove(p, side)) {
                Log.w(TAG, "TSUMEGO_REJECT_SEARCH_MOVE move=" + candidate.move
                        + " visits=" + candidate.visits
                        + " bounds=" + left + "," + top + ".." + right + "," + bottom);
                continue;
            }
            allowed.add(candidate);
        }
        return allowed;
    }

    private List<SearchRecommendation> searchRecommendationsRestricted(
            String player, int visits, double seconds, String options) {
        DebugLog.enter(TAG, "searchRecommendations in, player=" + player + ", visits=" + visits + ", seconds=" + seconds);
        if (!isReady() || (!"black".equals(player) && !"white".equals(player))
                || visits < 1 || !Double.isFinite(seconds) || seconds <= 0.0) return Collections.emptyList();
        String previousVisits = getSearchParam("maxVisits");
        String previousTime = getSearchParam("maxTime");
        if (previousVisits == null || previousTime == null) {
            Log.e(TAG, "Cannot read original game search limits; recommendation cancelled");
            return Collections.emptyList();
        }

        try {
            String timeText = String.format(Locale.US, "%.3f", seconds);
            if (!simpleCommand("kata-set-param maxVisits " + visits, 10000)) return Collections.emptyList();
            if (!simpleCommand("kata-set-param maxTime " + timeText, 10000)) return Collections.emptyList();

            // Unlike genmove_analyze, kata-search_analyze does not play the chosen move.
            // It terminates automatically at the configured search limit.
            responseQueue.clear();
            // The restricted problem search must not pad the results with
            // 0-visit phantom candidates outside the allowed area. Ask for
            // extra real alternatives in case one move is invalid in Java.
            String moveOptions = options.isEmpty()
                    ? " minmoves 3 maxmoves 3" : " minmoves 1 maxmoves 8";
            if (!sendCommandSync("kata-search_analyze " + player
                    + " 50" + moveOptions + options)) return Collections.emptyList();
            String response = waitForResponse(45000);
            if (!response.startsWith("=")) {
                Log.e(TAG, "kata-search_analyze failed: " + response.trim());
                return Collections.emptyList();
            }
            List<SearchRecommendation> results = parseSearchRecommendations(response);
            Log.i(TAG, "KataGo MCTS recommendation candidates=" + results.size() + " maxVisits=" + visits + " maxTime=" + timeText);
            return results;
        } finally {
            boolean visitsRestored = simpleCommand("kata-set-param maxVisits " + previousVisits, 10000);
            boolean timeRestored = simpleCommand("kata-set-param maxTime " + previousTime, 10000);
            if (!visitsRestored || !timeRestored) {
                Log.e(TAG, "Cannot restore Human SL game search limits; original maxVisits="
                        + previousVisits + ", maxTime=" + previousTime);
            }
        }
    }

    private List<SearchRecommendation> parseSearchRecommendations(String response) {
        // Intermediate analyses can repeat the same move. Keep the latest snapshot.
        Map<String, SearchRecommendation> latest = new LinkedHashMap<>();
        String[] segments = response.split("\\binfo\\s+");
        for (int i = 1; i < segments.length; i++) {
            String[] tokens = segments[i].trim().split("\\s+");
            String move = null;
            int visits = -1, order = -1;
            double winrate = Double.NaN, scoreLead = Double.NaN, prior = Double.NaN;
            List<String> variation = new ArrayList<>();
            try {
                for (int j = 0; j + 1 < tokens.length; j++) {
                    String key = tokens[j];
                    if ("pv".equals(key)) {
                        for (int p = j + 1; p < tokens.length && variation.size() < 12; p++) {
                            String vertex = tokens[p];
                            if ("rootInfo".equals(vertex) || "ownership".equals(vertex)
                                    || "ownershipStdev".equals(vertex) || "play".equals(vertex)) break;
                            variation.add(vertex);
                        }
                        break;
                    }
                    if ("rootInfo".equals(key) || "ownership".equals(key)
                            || "play".equals(key) || "ownershipStdev".equals(key)) break;
                    if ("move".equals(key)) move = tokens[++j];
                    else if ("visits".equals(key)) visits = Integer.parseInt(tokens[++j]);
                    else if ("winrate".equals(key)) winrate = Double.parseDouble(tokens[++j]);
                    else if ("scoreLead".equals(key)) scoreLead = Double.parseDouble(tokens[++j]);
                    else if ("prior".equals(key)) prior = Double.parseDouble(tokens[++j]);
                    else if ("order".equals(key)) order = Integer.parseInt(tokens[++j]);
                }
                if (move != null && order >= 0 && visits >= 0 && Double.isFinite(winrate)
                        && winrate >= 0 && winrate <= 1 && Double.isFinite(scoreLead)) {
                    // Some KataGo responses omit PV for low-visit candidates.
                    if (variation.isEmpty() || !move.equalsIgnoreCase(variation.get(0))) {
                        variation.clear();
                        variation.add(move);
                    }
                    latest.put(move, new SearchRecommendation(move, winrate, scoreLead, prior, visits, order, variation));
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid KataGo candidate analysis data", e);
            }
        }
        List<SearchRecommendation> candidates = new ArrayList<>(latest.values());
        // Rank displayed suggestions by the current player's win rate (high to low).
        // Use KataGo's original search order to break ties.
        candidates.sort((a, b) -> {
            int result = Double.compare(b.winrate, a.winrate);
            return result != 0 ? result : Integer.compare(a.order, b.order);
        });
        return candidates;
    }

    public static final class WinRate {
        public final float black, white;

        public WinRate(float black, float white) {
            this.black = black;
            this.white = white;
        }
    }

    /**
     * Evaluate the actual position by bounded MCTS instead of one noisy raw NN inference.
     * rootInfo winrate is in SIDETOMOVE perspective (configured in human_gtp.cfg).
     * This is analysis-only: neither the GTP position nor the Human SL rank is changed.
     * Run only on engineExecutor.
     */
    public WinRate evaluateWinRate(String nextPlayer) {
        if (!isReady() || (!"black".equals(nextPlayer) && !"white".equals(nextPlayer))) return null;
        String previousVisits = getSearchParam("maxVisits");
        String previousTime = getSearchParam("maxTime");
        if (previousVisits == null || previousTime == null) return null;

        try {
            if (!simpleCommand("kata-set-param maxVisits 48", 10000)) return null;
            if (!simpleCommand("kata-set-param maxTime 1.200", 10000)) return null;
            responseQueue.clear();
            if (!sendCommandSync("kata-search_analyze " + nextPlayer
                    + " 20 minmoves 1 maxmoves 1 rootInfo true")) return null;
            String response = waitForResponse(20000);
            if (response == null || !response.startsWith("=")) {
                Log.w(TAG, "Win rate MCTS search failed: " + (response == null ? "null" : response.trim()));
                return null;
            }
            // Intermediate analyses can repeat rootInfo. Use the final report.
            int root = response.lastIndexOf("rootInfo");
            if (root < 0) {
                Log.w(TAG, "Win rate MCTS search did not report rootInfo");
                return null;
            }
            String[] parts = response.substring(root + "rootInfo".length()).trim().split("\\s+");
            float playerWin = Float.NaN;
            int rootVisits = 0;
            for (int i = 0; i + 1 < parts.length; i++) {
                String key = parts[i];
                if ("info".equals(key) || "ownership".equals(key) || "rootInfo".equals(key)) break;
                if ("winrate".equals(key)) playerWin = Float.parseFloat(parts[++i]);
                else if ("visits".equals(key)) rootVisits = Integer.parseInt(parts[++i]);
            }
            if (rootVisits < 1 || !Float.isFinite(playerWin) || playerWin < 0f || playerWin > 1f) return null;
            float black = "black".equals(nextPlayer) ? playerWin : 1f - playerWin;
            Log.d(TAG, String.format(Locale.US,
                    "MCTS winrate: toPlay=%s rootVisits=%d black=%.3f white=%.3f",
                    nextPlayer, rootVisits, black, 1f - black));
            return new WinRate(black, 1f - black);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot parse KataGo MCTS winrate", e);
            return null;
        } finally {
            boolean visitsRestored = simpleCommand("kata-set-param maxVisits " + previousVisits, 10000);
            boolean timeRestored = simpleCommand("kata-set-param maxTime " + previousTime, 10000);
            if (!visitsRestored || !timeRestored)
                Log.e(TAG, "Could not restore Human SL move search limits after winrate evaluation");
        }
    }

    public static final class PositionEvaluation {
        public final double blackWin, whiteWin, whiteLead;
        public final int size;
        public final float[] whiteOwnership;
        // Raw 10b neural policy for the side to move, NOT per-move win rates.
        // Both arrays use KataGo's top-row-first board order.
        public final float[] movePolicy;
        public final float passPolicy;

        private PositionEvaluation(double blackWin, double whiteWin, double whiteLead,
                                   int size, float[] ownership, float[] policy, float passPolicy) {
            this.blackWin = blackWin;
            this.whiteWin = whiteWin;
            this.whiteLead = whiteLead;
            this.size = size;
            this.whiteOwnership = ownership.clone();
            this.movePolicy = policy == null ? null : policy.clone();
            this.passPolicy = passPolicy;
        }
    }

    /** Does not make a move or change the GTP board; call on engineExecutor. */
    public PositionEvaluation evaluatePosition(int size) {
        DebugLog.enter(TAG, "evaluatePosition in, size=" + size);
        if (!running.get() || size < 2 || size > 19) return null;
        responseQueue.clear();
        long startNs = System.nanoTime();
        if (!sendCommandSync("kata-raw-nn 0")) return null;
        String response = waitForResponse(30000);
        if (!response.startsWith("=")) {
            Log.e(TAG, "kata-raw-nn failed: " + response.trim());
            return null;
        }
        try {
            String[] tokens = response.substring(1).trim().split("\\s+");
            double whiteWin = Double.NaN, blackWin = Double.NaN, whiteLead = Double.NaN;
            float[] ownership = null, policy = null;
            float policyPass = Float.NaN;
            for (int i = 0; i < tokens.length; i++) {
                String key = tokens[i];
                if ("whiteWin".equals(key) && i + 1 < tokens.length) whiteWin = Double.parseDouble(tokens[++i]);
                else if ("whiteLoss".equals(key) && i + 1 < tokens.length) blackWin = Double.parseDouble(tokens[++i]);
                else if ("whiteLead".equals(key) && i + 1 < tokens.length) whiteLead = Double.parseDouble(tokens[++i]);
                else if ("policyPass".equals(key) && i + 1 < tokens.length) {
                    String value = tokens[++i];
                    policyPass = "NAN".equalsIgnoreCase(value) ? Float.NaN : Float.parseFloat(value);
                } else if ("policy".equals(key)) {
                    int count = size * size;
                    if (i + count >= tokens.length) throw new IllegalArgumentException("Incomplete policy array");
                    policy = new float[count];
                    for (int p = 0; p < count; p++) {
                        String value = tokens[++i];
                        float prob = "NAN".equalsIgnoreCase(value) ? -1f : Float.parseFloat(value);
                        policy[p] = Float.isFinite(prob) && prob >= 0f && prob <= 1f ? prob : -1f;
                    }
                } else if ("whiteOwnership".equals(key)) {
                    int count = size * size;
                    if (i + count >= tokens.length) throw new IllegalArgumentException("Incomplete ownership array");
                    ownership = new float[count];
                    for (int p = 0; p < count; p++) {
                        float value = Float.parseFloat(tokens[++i]);
                        if (!Float.isFinite(value) || Math.abs(value) > 1.01f)
                            throw new IllegalArgumentException("Invalid ownership value: " + value);
                        ownership[p] = Math.max(-1f, Math.min(1f, value));
                    }
                }
            }
            if (!Double.isFinite(whiteWin) || !Double.isFinite(blackWin) || !Double.isFinite(whiteLead)
                    || whiteWin < 0 || whiteWin > 1 || blackWin < 0 || blackWin > 1 || ownership == null)
                throw new IllegalArgumentException("Incomplete KataGo raw NN evaluation");

            PositionEvaluation eval = new PositionEvaluation(blackWin, whiteWin, whiteLead, size, ownership, policy, policyPass);
            long elapsedMs = (System.nanoTime() - startNs) / 1000000L;
            Log.i(TAG, String.format(Locale.US, "Position evaluation blackWin=%.3f whiteWin=%.3f whiteLead=%.2f size=%d elapsedMs=%d",
                    blackWin, whiteWin, whiteLead, size, elapsedMs));
            return eval;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot parse kata-raw-nn response", e);
            return null;
        }
    }

    public String getFinalScore() {
        DebugLog.enter(TAG, "getFinalScore in");
        responseQueue.clear();
        if (!sendCommandSync("final_score")) return null;
        // Deeper on-demand search can be slower than 10s on larger boards.
        // Do not time out and leave an unfinished GTP response in the queue.
        return parseGtpResponse(waitForResponse(60000));
    }

    /** KataGo GTP final_status_list dead returns the coordinates of predicted dead stones. */
    public String getFinalDeadStones() {
        responseQueue.clear();
        if (!sendCommandSync("final_status_list dead")) return null;
        return parseGtpMultilineResponse(waitForResponse(30000));
    }

    private boolean simpleCommand(String command, int timeoutMs) {
        DebugLog.enter(TAG, "simpleCommand in, command=" + command + ", timeoutMs=" + timeoutMs);
        responseQueue.clear();
        if (!sendCommandSync(command)) return false;
        String response = waitForResponse(timeoutMs);
        return response.startsWith("=");
    }

    private synchronized boolean sendCommandSync(String command) {
        DebugLog.enter(TAG, "sendCommandSync in, command=" + command + ", running=" + running.get());
        if (!running.get() && !"quit".equals(command)) return false;
        if (mediaTekSession != null) {
            boolean queued = mediaTekSession.send(command);
            Log.d(TAG, "MediaTek GTP send=" + queued + " command=" + command);
            return queued;
        }
        if (npuSession != null) {
            boolean queued = npuSession.send(command);
            Log.d(TAG, "NPU JNI GTP command=" + command + " queued=" + queued);
            return queued;
        }
        if (gpuSession != null) {
            boolean queued = gpuSession.send(command);
            Log.d(TAG, "GPU in-process JNI GTP command=" + command + " queued=" + queued);
            return queued;
        }
        if (jniSession != null) {
            long startedNs = System.nanoTime();
            try {
                boolean sent = jniSession.send(command);
                long enqueueMs = (System.nanoTime() - startedNs) / 1000000L;
                Log.d(TAG, "JNI GTP command=" + command + " queued=" + sent + " enqueueMs=" + enqueueMs);
                if (enqueueMs > 200) Log.w(TAG, "JNI GTP command send unexpectedly slow: " + command + " (" + enqueueMs + "ms)");
                return sent;
            } catch (Exception | LinkageError e) {
                Log.e(TAG, "JNI GTP send failed: " + command, e);
                return false;
            }
        }
        if (writer == null) return false;
        try {
            Log.d(TAG, "Sending: " + command);
            writer.write(command);
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + command, e);
            return false;
        }
    }

    private String waitForResponse(int timeoutMs) {
        DebugLog.enter(TAG, "waitForResponse in, timeoutMs=" + timeoutMs);
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                String response = responseQueue.poll(Math.min(200, remainingMs), TimeUnit.MILLISECONDS);
                if (response != null) return response;
                // Do not send GTP "stop" while awaiting an ordinary reply.
                // Each stop produces its own blank "=\\n\\n" response; the
                // queued reply can be mistaken for the next genmove result.
                // Explicit cancel/close paths alone may request stopSearch().
                KataGoNpuNative.GtpSession npu = npuSession;
                if (npu != null && !npu.isAlive()) {
                    Log.e(TAG, "NPU JNI GTP worker stopped while waiting for a reply");
                    return "";
                }
                KataGoGpuNative.GtpSession remote = gpuSession;
                if (remote != null && !remote.isAlive()) {
                    Log.e(TAG, "GPU JNI GTP worker stopped while waiting for a reply");
                    return "";
                }
            }
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Preserve every line of the GTP dead-stone coordinate list. */
    private String parseGtpMultilineResponse(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        return trimmed.startsWith("=") ? trimmed.substring(1).trim() : null;
    }

    private String parseGtpResponse(String response) {
        DebugLog.enter(TAG, "parseGtpResponse in, response=" + response);
        if (response == null) return null;
        String trimmed = response.trim();
        if (!trimmed.startsWith("=")) return null;
        String body = trimmed.substring(1).trim();
        int newline = body.indexOf('\n');
        return newline >= 0 ? body.substring(0, newline).trim() : body;
    }

    private void copyAssetToFile(String assetPath, File dest) throws IOException {
        DebugLog.enter(TAG, "copyAssetToFile in, assetPath=" + assetPath + ", dest=" + dest);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Reconstruct the exact original graph the first time a board is used;
        // later game sessions reuse the SHA-verified private cache. The same
        // native backends, manifests, and hashes are used after restoration.
        if (isCompactableModel(assetPath) && !hasRawAsset(assetPath)
                && hasRawAsset(assetPath + ".kgdelta")) {
            String baseAsset = assetPath.replaceFirst("_(?:9x9|11x11|13x13|15x15)\\.", "_19x19.");
            if (baseAsset.equals(assetPath))
                throw new IOException("Invalid compact model asset: " + assetPath);
            File baseFile = new File(parent, baseAsset.substring(baseAsset.lastIndexOf('/') + 1));
            copyAssetToFile(baseAsset, baseFile);
            KataGoModelDelta.restore(context, baseFile, assetPath + ".kgdelta", dest);
            return;
        }

        // Preserve cached full models between launches. Configs are still
        // rewritten. Keeping .tflite bases is important for compact builds.
        boolean modelAsset = assetPath.endsWith(".bin") || assetPath.endsWith(".rknn")
                || assetPath.endsWith(".tflite");
        String cacheKey = "asset_install_" + assetPath + "_" + dest.getName();
        long apkUpdateTime = -1;
        android.content.SharedPreferences prefs = null;
        if (modelAsset) {
            try {
                apkUpdateTime = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                prefs = context.getSharedPreferences("katago_asset_cache", Context.MODE_PRIVATE);
                if (dest.isFile() && apkUpdateTime == prefs.getLong(cacheKey, -2)) {
                    try (InputStream input = context.getAssets().open(assetPath)) {
                        int assetSize = input.available();
                        if (assetSize > 0 && dest.length() == assetSize) {
                            Log.i(TAG, "Reusing installed model asset: " + dest.getAbsolutePath());
                            return;
                        }
                    }
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Fall back to copying the asset.
            }
        }

        try (InputStream input = context.getAssets().open(assetPath); FileOutputStream output = new FileOutputStream(dest, false)) {
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        }
        if (modelAsset && prefs != null && apkUpdateTime >= 0)
            prefs.edit().putLong(cacheKey, apkUpdateTime).apply();
    }
}
