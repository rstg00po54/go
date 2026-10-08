package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import com.badukai.util.DebugLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
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
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private BufferedReader errorReader;
    private Thread readerThread;
    private Thread errorReaderThread;
    private volatile SearchStats currentSearchStats;
    private volatile boolean humanSLRunning;

    private static final class SearchStats {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile long rootVisits = -1;
        volatile long newPlayouts = -1;
    }

    public KataGoEngine(Context context) {
        DebugLog.enter(TAG, "KataGoEngine in, context=" + context);
        this.context = context.getApplicationContext();
    }

    public synchronized boolean start(Model model) { return start(model, false); }

    public synchronized boolean start(Model model, boolean useHumanSL) {
        DebugLog.enter(TAG, "start in, model=" + model + ", running=" + running.get());
        if (running.get()) return true;
        Log.i(TAG, "=== JAVA KATAGO ENGINE / ANDROID ARM64 ===");
        try {
            File engineDir = new File(context.getFilesDir(), "engine");
            if (!engineDir.exists() && !engineDir.mkdirs()) throw new IOException("Cannot create engine dir: " + engineDir);

            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            File binaryFile = new File(nativeLibDir, NATIVE_BINARY);
            File configFile = new File(engineDir, useHumanSL ? "human_gtp.cfg" : "default_gtp.cfg");
            File modelFile = new File(engineDir, model.fileName);

            if (!binaryFile.isFile()) throw new IOException("KataGo binary not installed in nativeLibraryDir: " + binaryFile);
            if (!binaryFile.canExecute()) throw new IOException("KataGo binary is not executable: " + binaryFile);
            copyAssetToFile(useHumanSL ? HUMAN_CONFIG_ASSET : CONFIG_ASSET, configFile);

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
                humanFile = new File(engineDir, HUMAN_MODEL_NAME);
                if (!isValidHumanModel(humanFile)) throw new IOException("Human SL model unavailable; download it first");
                Log.i(TAG, "Human SL model: " + humanFile.getAbsolutePath() + " size=" + humanFile.length());
            }

            Log.i(TAG, "Model: " + modelFile.getAbsolutePath() + " size=" + modelFile.length());
            Log.i(TAG, "Config: " + configFile.getAbsolutePath());
            Log.i(TAG, "Binary: " + binaryFile.getAbsolutePath());

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

            Thread.sleep(2000);
            if (!process.isAlive()) {
                Log.e(TAG, "KataGo process exited during startup");
                stop();
                return false;
            }

            running.set(true);
            humanSLRunning = useHumanSL;
            startReaderThread();
            startErrorReaderThread();
            Log.i(TAG, "=== ENGINE STARTED SUCCESSFULLY ===");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start engine", e);
            stop();
            return false;
        }
    }

    private void startReaderThread() {
        DebugLog.enter(TAG, "startReaderThread in");
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
        if (!running.get() && process == null) return;
        try { sendCommandSync("quit"); } catch (Exception ignored) {}
        running.set(false);
        humanSLRunning = false;
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

    public boolean isHumanSLRunning() { return running.get() && humanSLRunning; }

    public boolean hasHumanModel() {
        File stored = new File(new File(context.getFilesDir(), "engine"), HUMAN_MODEL_NAME);
        if (isValidHumanModel(stored)) return true;
        try (InputStream ignored = context.getAssets().open(HUMAN_MODEL_ASSET)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isValidHumanModel(File file) {
        if (!file.isFile() || file.length() != HUMAN_MODEL_BYTES) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Run on a background executor. Use the bundled model when available,
     * otherwise fetch the official KataGo v1.15.0 release asset via HTTPS.
     * Partial downloads never replace the usable model.
     */
    public File prepareHumanModel(ModelProgress progress) throws IOException {
        File dir = new File(context.getFilesDir(), "engine");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create model directory");
        File model = new File(dir, HUMAN_MODEL_NAME);
        if (isValidHumanModel(model)) return model;
        File part = new File(dir, HUMAN_MODEL_NAME + ".part");
        if (part.exists() && !part.delete()) throw new IOException("Cannot remove partial model");

        try {
            try (InputStream asset = context.getAssets().open(HUMAN_MODEL_ASSET)) {
                Log.i(TAG, "Preparing bundled Human SL model");
                copyStream(asset, part, HUMAN_MODEL_BYTES, progress);
            } catch (java.io.FileNotFoundException missing) {
                downloadHumanModel(part, progress);
            }
            if (!isValidHumanModel(part)) throw new IOException("Human SL model file size or gzip header invalid");
            if (model.exists() && !model.delete()) throw new IOException("Cannot replace old Human SL model");
            if (!part.renameTo(model)) throw new IOException("Cannot install Human SL model");
            Log.i(TAG, "Human SL model ready: " + model.getAbsolutePath());
            return model;
        } finally {
            if (part.exists()) part.delete();
        }
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
        return running.get();
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
            if (!sendCommandSync("genmove_debug " + color)) return null;
            String response = waitForResponse(60000);
            long elapsedMs = (System.nanoTime() - startedNs) / 1000000L;
            String move = parseGtpResponse(response);
            try {
                if (!stats.ready.await(500, TimeUnit.MILLISECONDS)) Log.w(TAG, "Search statistics not received from KataGo");
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

    public boolean setBoardSize(int size) {
        DebugLog.enter(TAG, "setBoardSize in, size=" + size);
        return simpleCommand("boardsize " + size, 5000);
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

    public String getFinalScore() {
        DebugLog.enter(TAG, "getFinalScore in");
        responseQueue.clear();
        if (!sendCommandSync("final_score")) return null;
        return parseGtpResponse(waitForResponse(10000));
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
