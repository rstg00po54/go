package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class KataGoEngine {
    private static final String TAG = "KataGoEngine";
    private static final String BINARY_NAME = "libkatago.so";
    private static final String CONFIG_NAME = "gtp_static.cfg";

    public enum Model {
        HUMAN("Human", "10b.bin", "10-block"),
        SUPERHUMAN("Superhuman", "18b.bin", "18-block"),
        GODLIKE("Godlike", "28b.bin", "28-block");

        public final String displayName;
        public final String fileName;
        public final String description;

        Model(String displayName, String fileName, String description) {
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

    public KataGoEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean start(Model model) {
        if (running.get()) return true;
        Log.i(TAG, "=== JAVA KATAGO ENGINE ===");
        try {
            String dataDataPath = "/data/data/" + context.getPackageName();
            File filesDir = new File(dataDataPath, "files");
            File hexagonDir = new File(filesDir, "hexagon");
            File appDir = new File(filesDir, "app");
            if (!hexagonDir.exists()) hexagonDir.mkdirs();
            if (!appDir.exists()) appDir.mkdirs();

            File binaryFile = new File(filesDir, BINARY_NAME);
            // Refresh the executable on every launch so APK updates never reuse stale native code.
            copyAssetToFile(BINARY_NAME, binaryFile);
            binaryFile.setExecutable(true);
            Log.i(TAG, "KataGo binary refreshed from APK assets, bytes=" + binaryFile.length());

            // Always overwrite config so editing app/src/main/assets/gtp_static.cfg takes effect after reinstall/update.
            File configFile = new File(filesDir, CONFIG_NAME);
            copyAssetToFile(CONFIG_NAME, configFile);

            File modelFile = new File(appDir, model.fileName);
            if (!modelFile.exists()) copyAssetToFile("models/" + model.fileName, modelFile);

            Log.i(TAG, "Model: " + modelFile.getAbsolutePath() + " exists=" + modelFile.exists() + " size=" + modelFile.length());
            Log.i(TAG, "Config: " + configFile.getAbsolutePath());
            Log.i(TAG, "Binary: " + binaryFile.getAbsolutePath());

            List<String> command = new ArrayList<>();
            command.add("/system/bin/linker64");
            command.add(binaryFile.getAbsolutePath());
            command.add("gtp");
            command.add("-model");
            command.add(modelFile.getAbsolutePath());
            command.add("-config");
            command.add(configFile.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(hexagonDir);
            Map<String, String> env = builder.environment();
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            env.put("LD_LIBRARY_PATH", nativeLibDir + ":" + hexagonDir.getAbsolutePath() + ":/vendor/lib64:/system/vendor/lib64");
            env.put("ADSP_LIBRARY_PATH", nativeLibDir + ";" + hexagonDir.getAbsolutePath() + ";/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp");
            env.put("HOME", filesDir.getAbsolutePath());

            Log.i(TAG, "Command: " + String.join(" ", command));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            // Drain stdout/stderr immediately. Startup failures often happen within the first 2 seconds.
            running.set(true);
            startReaderThread();
            startErrorReaderThread();
            Thread.sleep(2000);
            if (!process.isAlive()) {
                Log.e(TAG, "KataGo process exited during startup, exitCode=" + process.exitValue());
                stop();
                return false;
            }

            Log.i(TAG, "=== ENGINE STARTED SUCCESSFULLY ===");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start engine", e);
            stop();
            return false;
        }
    }

    private void startReaderThread() {
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
        errorReaderThread = new Thread(() -> {
            try {
                String line;
                while (running.get() && (line = errorReader.readLine()) != null) Log.w(TAG, "KataGo stderr: " + line);
            } catch (IOException e) {
                if (running.get()) Log.e(TAG, "Stderr reader failed", e);
            }
        }, "KataGo-stderr");
        errorReaderThread.start();
    }

    public synchronized void stop() {
        if (!running.get() && process == null) return;
        try { sendCommandSync("quit"); } catch (Exception ignored) {}
        running.set(false);
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

    public boolean isReady() {
        return running.get();
    }

    public String generateMove(String color) {
        responseQueue.clear();
        if (!sendCommandSync("genmove " + color)) return null;
        String response = waitForResponse(60000);
        String move = parseGtpResponse(response);
        Log.i(TAG, "Generated move for " + color + ": " + move);
        return move;
    }

    public boolean playMove(String color, String move) {
        return simpleCommand("play " + color + " " + move, 5000);
    }

    public boolean setBoardSize(int size) {
        return simpleCommand("boardsize " + size, 5000);
    }

    public boolean clearBoard() {
        return simpleCommand("clear_board", 5000);
    }

    public boolean setKomi(float komi) {
        return simpleCommand("komi " + komi, 5000);
    }

    public boolean undo() {
        return simpleCommand("undo", 5000);
    }

    public String getFinalScore() {
        responseQueue.clear();
        if (!sendCommandSync("final_score")) return null;
        return parseGtpResponse(waitForResponse(10000));
    }

    private boolean simpleCommand(String command, int timeoutMs) {
        responseQueue.clear();
        if (!sendCommandSync(command)) return false;
        String response = waitForResponse(timeoutMs);
        return response.startsWith("=");
    }

    private synchronized boolean sendCommandSync(String command) {
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
        try {
            String response = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return response == null ? "" : response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private String parseGtpResponse(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        if (!trimmed.startsWith("=")) return null;
        String body = trimmed.substring(1).trim();
        int newline = body.indexOf('\n');
        return newline >= 0 ? body.substring(0, newline).trim() : body;
    }

    private void copyAssetToFile(String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream input = context.getAssets().open(assetPath); FileOutputStream output = new FileOutputStream(dest, false)) {
            byte[] buffer = new byte[1024 * 64];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        }
    }
}
