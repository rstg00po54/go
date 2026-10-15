package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** ADB-only real KataGo GTP test; does not affect saved games or UI backend. */
public final class KataGoMediaTekGtpSmoke {
    private static final String TAG = "KataGoMTK";
    private KataGoMediaTekGtpSmoke() {}

    private static File asset(Context app, File dir, String name, String assetPath) throws Exception {
        File output = new File(dir, name);
        try (InputStream in = app.getAssets().open(assetPath)) {
            Files.copy(in, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    /** Wait for the GTP blank-line terminator, not just the first stdout chunk. */
    private static String command(KataGoMediaTekNative.GtpSession session, String command,
                                  int timeoutMs) throws Exception {
        if (!session.send(command)) throw new IllegalStateException("GTP send failed: " + command);
        long endNs = System.nanoTime() + timeoutMs * 1000000L;
        StringBuilder response = new StringBuilder();
        while (System.nanoTime() < endNs) {
            String text = session.read(1000);
            if (text == null) throw new IllegalStateException("GTP exited: " + command);
            response.append(text);
            int finish = response.indexOf("\n\n");
            if (finish >= 0) {
                String reply = response.substring(0, finish).trim();
                if (!reply.startsWith("="))
                    throw new IllegalStateException("GTP failure: " + command + " => " + reply);
                Log.i(TAG, "MTK_GTP_REPLY command=" + command + " reply=" + reply);
                return reply.substring(1).trim();
            }
            if (response.length() > 65536) throw new IllegalStateException("Oversized GTP response");
        }
        throw new IllegalStateException("GTP timed out: " + command);
    }

    private static boolean legalVertex(String vertex, int size) {
        String value = vertex.trim().toUpperCase(java.util.Locale.US);
        if ("PASS".equals(value)) return true;
        if (!value.matches("[A-HJ-T]([1-9]|1[0-9])")) return false;
        char col = value.charAt(0);
        int x = col - 'A' - (col > 'I' ? 1 : 0);
        int y = Integer.parseInt(value.substring(1));
        return x >= 0 && x < size && y <= size;
    }

    public static void run(Context app, String id, int boardSize) {
        Log.i(TAG, "MTK_GTP_SMOKE_BEGIN id=" + id + " board=" + boardSize);
        try {
            if (boardSize != 9 && boardSize != 13 && boardSize != 19)
                throw new IllegalArgumentException("Unsupported GTP board");
            if (!KataGoMediaTekLiteRt.isMediaTek())
                throw new IllegalStateException("Not a MediaTek device");
            File lib = new File(app.getApplicationInfo().nativeLibraryDir, "libkatago_mtk.so");
            if (!lib.isFile()) throw new IllegalStateException("Missing libkatago_mtk.so in APK");
            File dir = new File(app.getFilesDir(), "mtk_gtp_smoke");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create GTP folder");
            File logs = new File(dir, "gtp_logs");
            if (!logs.isDirectory() && !logs.mkdirs()) throw new IllegalStateException("Cannot create GTP logs");

            String prefix = "katago_10b_" + boardSize + "x" + boardSize;
            File graph = asset(app, dir, prefix + ".tflite", "engine/" + prefix + ".tflite");
            File manifest = asset(app, dir, prefix + ".json", "engine/" + prefix + ".json");
            File model = asset(app, dir, "10b.bin", "engine/10b.bin");
            File config = asset(app, dir, "mtk_gtp.cfg", "engine/default_gtp.cfg");
            String text = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            text = KataGoGtpConfig.set(text, "logDir", logs.getAbsolutePath());
            text = KataGoGtpConfig.set(text, "homeDataDir", dir.getAbsolutePath());
            text = KataGoGtpConfig.set(text, "defaultBoardSize", Integer.toString(boardSize));
            text = KataGoGtpConfig.set(text, "numSearchThreads", "2");
            text = KataGoGtpConfig.set(text, "maxVisits", "8");
            text = KataGoGtpConfig.set(text, "nnMaxBatchSize", "1");
            Files.write(config.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "MTK_GTP_ASSETS_READY id=" + id + " board=" + boardSize);
            try (KataGoMediaTekNative.GtpSession session =
                         KataGoMediaTekNative.createSession(app, model, config, null,
                                 graph, manifest, boardSize)) {
                command(session, "name", 120000);
                command(session, "boardsize " + boardSize, 60000);
                command(session, "clear_board", 60000);
                command(session, "komi 7.5", 60000);
                long start = System.nanoTime();
                String black = command(session, "genmove black", 180000);
                long blackMs = (System.nanoTime() - start) / 1000000L;
                start = System.nanoTime();
                String white = command(session, "genmove white", 180000);
                long whiteMs = (System.nanoTime() - start) / 1000000L;
                if (!legalVertex(black, boardSize) || !legalVertex(white, boardSize))
                    throw new IllegalStateException("Invalid GTP vertices: " + black + " " + white);
                Log.i(TAG, "MTK_GTP_SMOKE_PASS id=" + id + " board=" + boardSize
                        + " black=" + black + " blackMs=" + blackMs
                        + " white=" + white + " whiteMs=" + whiteMs);
            }
        } catch (Throwable error) {
            Log.e(TAG, "MTK_GTP_SMOKE_FAIL id=" + id + " board=" + boardSize
                    + " reason=" + error, error);
        }
    }
}
