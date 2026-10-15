package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Experimental real KataGo GTP transport for the MediaTek LiteRT NN backend.
 * Opt-in native library only; not exposed as a user-selectable backend until
 * a real game and CPU/NPU neural-output parity have passed on the vivo device.
 *
 * The g170 and independently converted b18 Human SL models may each use a
 * separate LiteRT instance. Incomplete Human graphs must fall back to Eigen.
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
        return createSession(context, model, config, humanModel, graph, manifest,
                boardSize, compareCpu, null, null);
    }

    public static GtpSession createSession(Context context, File model, File config,
                                           File humanModel, File graph, File manifest,
                                           int boardSize, boolean compareCpu,
                                           File humanGraph, File humanManifest) throws Exception {
        if (!NpuHardware.canProbeMediaTekLiteRt())
            throw new IllegalStateException("MediaTek SoC required");
        load();
        KataGoMediaTekLiteRt runner = new KataGoMediaTekLiteRt(
                context.getApplicationContext(), graph, manifest, boardSize);
        KataGoMediaTekLiteRt humanRunner = null;
        long handle = 0;
        try {
            if (humanModel != null && humanGraph != null && humanManifest != null
                    && humanGraph.isFile() && humanManifest.isFile()) {
                try {
                    humanRunner = new KataGoMediaTekLiteRt(
                            context.getApplicationContext(), humanGraph, humanManifest, boardSize, true);
                    Log.i(TAG, "MTK_HUMAN_LITERT_READY board=" + boardSize + " graph=" + humanGraph.getName());
                } catch (Exception | LinkageError unsupported) {
                    Log.w(TAG, "MTK_HUMAN_CPU_FALLBACK reason=Human_LiteRT_unavailable board=" + boardSize,
                            unsupported);
                }
            } else if (humanModel != null) {
                Log.i(TAG, "MTK_HUMAN_CPU_FALLBACK reason=no_verified_human_graph board=" + boardSize);
            }
            handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                    humanModel == null ? null : humanModel.getAbsolutePath(),
                    runner, humanRunner, compareCpu);
            if (handle == 0)
                throw new IllegalStateException("MediaTek JNI GTP session could not start");
            Log.i(TAG, "MTK_GTP_NATIVE_READY board=" + boardSize + " handle=" + handle
                    + " humanLiteRt=" + (humanRunner != null));
            return new GtpSession(handle, runner, humanRunner);
        } finally {
            if (handle == 0) {
                if (humanRunner != null) humanRunner.close();
                runner.close();
            }
        }
    }

    public static final class GtpSession implements AutoCloseable {
        private volatile long handle;
        private final KataGoMediaTekLiteRt runner;
        private final KataGoMediaTekLiteRt humanRunner;

        private GtpSession(long handle, KataGoMediaTekLiteRt runner,
                           KataGoMediaTekLiteRt humanRunner) {
            this.handle = handle;
            this.runner = runner;
            this.humanRunner = humanRunner;
        }

        public boolean usesHumanLiteRt() { return humanRunner != null; }

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
                try {
                    if (humanRunner != null) humanRunner.close();
                } finally {
                    runner.close();
                }
            }
            Log.i(TAG, "MTK_GTP_NATIVE_CLOSED");
        }
    }

    private static native String nativeBuildStatus();
    private static native long nativeCreateSession(String modelPath, String configPath,
                                                    String humanModelPath,
                                                    KataGoMediaTekLiteRt runner,
                                                    KataGoMediaTekLiteRt humanRunner,
                                                    boolean compareCpu);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeIsSessionAlive(long handle);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
