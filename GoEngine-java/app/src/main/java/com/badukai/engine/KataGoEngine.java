package com.badukai.engine;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import com.badukai.util.DebugLog;

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
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process process;
    private volatile KataGoNative.GtpSession jniSession;
    private final boolean preferJni;
    private volatile boolean jniDisabledForProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Thread readerThread;
    private Thread errorReaderThread;
    private volatile SearchStats currentSearchStats;
    private volatile boolean humanSLRunning;
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
     * Prefer the in-process JNI engine for the main game, including Human SL.
     * The separate winrate engine remains a PIE process until multi-session JNI is safe.
     */
    public KataGoEngine(Context context, String engineDirectoryName, boolean preferJni) {
        this.context = context.getApplicationContext();
        this.engineDirectoryName = engineDirectoryName;
        this.preferJni = preferJni && "engine".equals(engineDirectoryName);
        if (!"engine".equals(engineDirectoryName) && !"engine_winrate".equals(engineDirectoryName))
            throw new IllegalArgumentException("Unsupported engine directory: " + engineDirectoryName);
    }

    public String getBackendName() { return jniSession != null ? "JNI/Eigen" : "PIE/ProcessBuilder"; }

    public synchronized boolean start(Model model) { return start(model, false); }

    public synchronized boolean start(Model model, boolean useHumanSL) {
        DebugLog.enter(TAG, "start in, model=" + model + ", running=" + running.get());
        if (running.get()) {
            if (isReady() && humanSLRunning == useHumanSL) return true;
            stop(); // Different engine mode or an engine process/session died.
        }
        Log.i(TAG, "=== JAVA KATAGO ENGINE / ANDROID ARM64 ===");
        try {
            File engineDir = new File(context.getFilesDir(), engineDirectoryName);
            if (!engineDir.exists() && !engineDir.mkdirs()) throw new IOException("Cannot create engine dir: " + engineDir);

            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            File binaryFile = new File(nativeLibDir, NATIVE_BINARY);
            File configFile = new File(engineDir, useHumanSL ? "human_gtp.cfg" : "default_gtp.cfg");
            File modelFile = new File(engineDir, model.fileName);

            boolean useJni = preferJni && !jniDisabledForProcess
                    && new File(nativeLibDir, "libkatago.so").isFile();
            if (!useJni && !binaryFile.isFile())
                throw new IOException("KataGo binary not installed in nativeLibraryDir: " + binaryFile);
            if (!useJni && !binaryFile.canExecute())
                throw new IOException("KataGo binary is not executable: " + binaryFile);
            copyAssetToFile(useHumanSL ? HUMAN_CONFIG_ASSET : CONFIG_ASSET, configFile);
            if (useJni) {
                File homeDir = new File(engineDir, "jni_home");
                File logsDir = new File(engineDir, "gtp_logs");
                if (!homeDir.exists() && !homeDir.mkdirs()) throw new IOException("Cannot create JNI home dir: " + homeDir);
                if (!logsDir.exists() && !logsDir.mkdirs()) throw new IOException("Cannot create GTP log dir: " + logsDir);
                String cfg = new String(java.nio.file.Files.readAllBytes(configFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                cfg = cfg.replace("logDir = gtp_logs", "logDir = " + logsDir.getAbsolutePath());
                cfg += "\n" + "homeDataDir = " + homeDir.getAbsolutePath() + "\n";
                java.nio.file.Files.write(configFile.toPath(), cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if ("engine_winrate".equals(engineDirectoryName)) {
                // Avoid 6 search threads in a second process competing with the game engine.
                String config = new String(java.nio.file.Files.readAllBytes(configFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                config = config.replace("numSearchThreads = 6", "numSearchThreads = 2");
                java.nio.file.Files.write(configFile.toPath(), config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            String modelAsset = "engine/" + model.fileName;
            try {
                copyAssetToFile(modelAsset, modelFile);
            } catch (IOException e) {
                if (model == Model.HUMAN) throw e;
                Log.w(TAG, modelAsset + " not bundled, falling back to 10b.bin");
                modelFile = new File(engineDir, "10b.bin");
                copyAssetToFile(DEFAULT_MODEL_ASSET, modelFile);
            }

            File humanFile = null;
            if (useHumanSL) {
                humanFile = isCachedHumanModelValid() ? verifiedHumanModel : findInstalledHumanModel(engineDir);
                if (humanFile == null) throw new IOException("Human SL model unavailable; prepare it first");
                Log.i(TAG, "Human SL model: " + humanFile.getAbsolutePath() + " size=" + humanFile.length());
            }

            Log.i(TAG, "Model: " + modelFile.getAbsolutePath() + " size=" + modelFile.length());
            Log.i(TAG, "Config: " + configFile.getAbsolutePath());
            Log.i(TAG, "Backend requested: " + (useJni ? "JNI/Eigen" : "PIE/ProcessBuilder"));

            if (useJni) {
                try {
                    jniSession = KataGoNative.createSession(modelFile, configFile, humanFile);
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
                if (!sendCommandSync("name") || !waitForStartupResponse(120000)) {
                    Log.e(TAG, "JNI GTP failed to become ready; retrying with PIE");
                    jniDisabledForProcess = true;
                    stop();
                    return start(model, useHumanSL);
                }
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
            if (jniSession == null && (process == null || !process.isAlive())) return false;
            try {
                String response = responseQueue.poll(200, TimeUnit.MILLISECONDS);
                if (response != null) {
                    Log.i(TAG, "KataGo GTP ready response: " + response.trim());
                    return response.startsWith("=");
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
        if (jniSession != null) { startJniReaderThread(); return; }
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    Log.d(TAG, "KataGo stdout: " + line);
                    buffer.append(line).append('\n');
                    if (line.isEmpty() && buffer.length() > 0) {
                        responseQueue.offer(buffer.toString());
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
        readerThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            try {
                // Bind this reader to its original session: an old reader must
                // never consume a new session's output during a quick restart.
                while (running.get() && jniSession == session) {
                    String chunk = session.read(1000);
                    if (chunk == null) break;
                    buffer.append(chunk.replace("\r\n", "\n"));
                    int end;
                    while ((end = buffer.indexOf("\n\n")) >= 0) {
                        String response = buffer.substring(0, end + 2);
                        buffer.delete(0, end + 2);
                        Log.d(TAG, "KataGo JNI GTP response: " + response.trim());
                        responseQueue.offer(response);
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

    private void startErrorReaderThread() {
        DebugLog.enter(TAG, "startErrorReaderThread in");
        errorReaderThread = new Thread(() -> {
            try {
                String line;
                while (running.get() && (line = errorReader.readLine()) != null) {
                    recordSearchStats(line);
                    Log.d(TAG, "KataGo stderr: " + line);
                }
            } catch (IOException e) {
                if (running.get()) Log.e(TAG, "Stderr reader failed", e);
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
        if (!running.get() && process == null && jniSession == null) return;
        try { sendCommandSync("quit"); } catch (Exception ignored) {}
        running.set(false);
        humanSLRunning = false;
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

    /** Trust a SHA-256 result only while the private file remains unchanged. */
    private boolean isCachedHumanModelValid() {
        File file = verifiedHumanModel;
        return file != null && isValidHumanModel(file)
                && file.length() == verifiedHumanModelLength
                && file.lastModified() == verifiedHumanModelModified;
    }

    private File findInstalledHumanModel(File dir) throws IOException {
        File compressed = new File(dir, HUMAN_MODEL_NAME);
        if (isTrustedHumanModel(compressed)) return compressed;
        File raw = new File(dir, HUMAN_MODEL_RAW_NAME);
        return isTrustedHumanModel(raw) ? raw : null;
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
        return running.get() && (session != null ? session.isAlive() : (process != null && process.isAlive()));
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
            boolean isJni = jniSession != null;
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
        return simpleCommand("boardsize " + size, jniSession != null ? 90000 : 5000);
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
            if (!sendCommandSync("kata-search_analyze " + player + " 50 minmoves 3 maxmoves 3")) return Collections.emptyList();
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
        Collections.sort(candidates, (a, b) -> Integer.compare(a.order, b.order));
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
        return parseGtpResponse(waitForResponse(10000));
    }

    /** KataGo GTP final_status_list dead returns the coordinates of predicted dead stones. */
    public String getFinalDeadStones() {
        responseQueue.clear();
        if (!sendCommandSync("final_status_list dead")) return null;
        return parseGtpResponse(waitForResponse(30000));
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
            String response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return response == null ? "" : response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
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
        try (InputStream input = context.getAssets().open(assetPath); FileOutputStream output = new FileOutputStream(dest, false)) {
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        }
    }
}
