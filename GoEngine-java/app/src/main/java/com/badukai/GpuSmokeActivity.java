package com.badukai;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import com.badukai.engine.KataGoGpuNative;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Debug-only entrypoint enabled by -PenableKataGoGpuJni=true.
 * Runs in an opt-in dedicated process to exercise GPU JNI independently
 * of the app. Normal gameplay now runs GPU JNI in the main app process.
 */
public final class GpuSmokeActivity extends Activity {
    private static final String TAG = "KataGoGpuJni";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("KataGo OpenCL JNI GPU smoke test. Details: adb logcat -s KataGoGpuJni:I");
        status.setPadding(20, 20, 20, 20);
        setContentView(status);
        new Thread(this::runSmoke, "KataGo-OpenCL-GTP").start();
    }

    private void runSmoke() {
        try {
            Log.i(TAG, "Starting isolated OpenCL JNI test pid=" + Process.myPid());
            Log.i(TAG, "GPU JNI linkage: " + KataGoGpuNative.checkCoreLinkage());

            File dir = new File(getFilesDir(), "jni_gpu_smoke");
            File logDir = new File(dir, "gtp_logs");
            File homeDir = new File(dir, "home");
            if (!logDir.isDirectory() && !logDir.mkdirs()) throw new IllegalStateException("Cannot create " + logDir);
            if (!homeDir.isDirectory() && !homeDir.mkdirs()) throw new IllegalStateException("Cannot create " + homeDir);

            File model = new File(dir, "10b.bin");
            if (!model.isFile() || model.length() != 12003218L) {
                try (InputStream in = getAssets().open("engine/10b.bin");
                     FileOutputStream out = new FileOutputStream(model)) {
                    byte[] data = new byte[65536];
                    int n;
                    while ((n = in.read(data)) != -1) out.write(data, 0, n);
                }
            }

            ByteArrayOutputStream cfg = new ByteArrayOutputStream();
            try (InputStream in = getAssets().open("engine/default_gtp.cfg")) {
                byte[] data = new byte[16384];
                int n;
                while ((n = in.read(data)) != -1) cfg.write(data, 0, n);
            }
            String config = new String(cfg.toByteArray(), StandardCharsets.UTF_8)
                    .replace("logDir = gtp_logs", "logDir = " + logDir.getAbsolutePath());
            config += "\nhomeDataDir = " + homeDir.getAbsolutePath() + "\n";
            File configFile = new File(dir, "gpu_gtp.cfg");
            Files.write(configFile.toPath(), config.getBytes(StandardCharsets.UTF_8));

            Log.i(TAG, "Model size=" + model.length() + ", config=" + configFile.getAbsolutePath());
            try (KataGoGpuNative.GtpSession session = KataGoGpuNative.createSession(model, configFile, null)) {
                if (session == null) throw new IllegalStateException("GPU JNI createSession returned null");
                Log.i(TAG, "GPU JNI session created, using compiled OpenCL backend");
                StringBuilder pending = new StringBuilder();
                request(session, pending, "name", 240000);
                request(session, pending, "boardsize 19", 120000);
                request(session, pending, "komi 7.5", 30000);
                request(session, pending, "play B D4", 30000);
                String move = request(session, pending, "genmove W", 180000);
                if (move.substring(1).trim().isEmpty()) throw new IllegalStateException("GPU genmove returned empty result");
                Log.i(TAG, "GPU OpenCL genmove W -> " + move.replace('\n', ' '));
                request(session, pending, "undo", 30000);
                request(session, pending, "clear_board", 30000);
                String raw = request(session, pending, "kata-raw-nn 0", 180000);
                if (!raw.contains("whiteWin") || !raw.contains("whiteOwnership"))
                    throw new IllegalStateException("GPU raw NN response missing expected fields");
                Log.i(TAG, "GPU OpenCL raw neural network inference OK");
            }
            Log.i(TAG, "PASS: GPU JNI OpenCL GTP name/boardsize/play/genmove/undo/clear_board/kata-raw-nn");
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "FAIL: GPU JNI OpenCL smoke", e);
        } finally {
            runOnUiThread(this::finish);
        }
    }

    private String request(KataGoGpuNative.GtpSession session, StringBuilder pending, String command, long timeoutMs) {
        Log.i(TAG, "Sending " + command);
        if (!session.send(command)) throw new IllegalStateException("GPU JNI send failed: " + command);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            int end = pending.indexOf("\n\n");
            if (end >= 0) {
                String response = pending.substring(0, end).trim();
                pending.delete(0, end + 2);
                if (!response.startsWith("=")) throw new IllegalStateException("GTP " + command + " failed: " + response);
                Log.i(TAG, command + " -> " + response.replace('\n', ' ').substring(0, Math.min(140, response.length())));
                return response;
            }
            String chunk = session.read(1000);
            if (chunk == null) throw new IllegalStateException("GPU GTP stopped before " + command);
            pending.append(chunk.replace("\r\n", "\n"));
            if (pending.length() > 4 * 1024 * 1024) throw new IllegalStateException("GPU GTP output too large");
        }
        throw new IllegalStateException("GPU GTP timed out: " + command + " after " + timeoutMs + "ms");
    }
}
