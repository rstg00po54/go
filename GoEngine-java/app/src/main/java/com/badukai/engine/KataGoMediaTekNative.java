package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Experimental real KataGo GTP transport for the MediaTek LiteRT NN backend.
 * Opt-in native library only; not exposed as a user-selectable backend until
 * a real game and CPU/NPU neural-output parity have passed on the vivo device.
 *
 * The g170 network runs through Java LiteRT, while Human SL uses native Eigen.
 */
public final class KataGoMediaTekNative {
    private static final String TAG = "KataGoMTK";
    private static boolean loaded;

    private KataGoMediaTekNative() {}

    private static synchronized void load() {
        if (loaded) return;
        System.loadLibrary("katago_mtk");
        loaded = true;
    }

    public static String checkCoreLinkage() {
        try {
            load();
            return nativeBuildStatus();
        } catch (LinkageError | SecurityException e) {
            return "MediaTek GTP JNI unavailable: " + e;
        }
    }

    /** Graph and manifest must match boardSize and the native 10b.bin weights. */
    public static GtpSession createSession(Context context, File model, File config,
                                           File humanModel, File graph, File manifest,
                                           int boardSize) throws Exception {
        return createSession(context, model, config, humanModel, graph, manifest, boardSize, false);
    }

    public static GtpSession createSession(Context context, File model, File config,
                                           File humanModel, File graph, File manifest,
                                           int boardSize, boolean compareCpu) throws Exception {
        if (!NpuHardware.canProbeMediaTekLiteRt())
            throw new IllegalStateException("MediaTek SoC required");
        load();
        KataGoMediaTekLiteRt runner = new KataGoMediaTekLiteRt(
                context.getApplicationContext(), graph, manifest, boardSize);
        long handle = 0;
        try {
            handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                    humanModel == null ? null : humanModel.getAbsolutePath(), runner, compareCpu);
            if (handle == 0)
                throw new IllegalStateException("MediaTek JNI GTP session could not start");
            Log.i(TAG, "MTK_GTP_NATIVE_READY board=" + boardSize + " handle=" + handle);
            return new GtpSession(handle, runner);
        } finally {
            if (handle == 0) runner.close();
        }
    }

    public static final class GtpSession implements AutoCloseable {
        private volatile long handle;
        private final KataGoMediaTekLiteRt runner;

        private GtpSession(long handle, KataGoMediaTekLiteRt runner) {
            this.handle = handle;
            this.runner = runner;
        }

        public boolean send(String command) {
            long id = handle;
            return id != 0 && nativeSendCommand(id, command);
        }

        public String read(int timeoutMs) {
            long id = handle;
            return id == 0 ? null : nativeReadOutput(id, timeoutMs);
        }

        public boolean isAlive() {
            long id = handle;
            return id != 0 && nativeIsSessionAlive(id);
        }

        public boolean stopSearch() {
            long id = handle;
            return id != 0 && nativeStopSearch(id);
        }

        @Override public synchronized void close() {
            long old = handle;
            if (old == 0) return;
            handle = 0;
            try {
                // JNI first: waits for all NN threads, then releases the global
                // Java callback ref. Never close LiteRT while a search is active.
                nativeDestroySession(old);
            } finally {
                runner.close();
            }
            Log.i(TAG, "MTK_GTP_NATIVE_CLOSED");
        }
    }

    private static native String nativeBuildStatus();
    private static native long nativeCreateSession(String modelPath, String configPath,
                                                    String humanModelPath,
                                                    KataGoMediaTekLiteRt runner, boolean compareCpu);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeIsSessionAlive(long handle);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
