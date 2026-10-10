package com.badukai.engine;

import java.io.File;
import android.util.Log;

/** In-process KataGo CPU/Eigen GTP session with CPU-only JNI bindings. */
public final class KataGoNative {
    private static final String TAG = "KataGoNative";
    private KataGoNative() {}

    // CPU JNI and GPU JNI have independent Java classes and ELF SONAMEs.
    private static boolean loaded;

    private static synchronized void load() {
        if (!loaded) {
            long startNs = System.nanoTime();
            System.loadLibrary("katago");
            loaded = true;
            Log.i(TAG, "STARTUP_TIMING phase=jni_load_library ms=" +
                    (System.nanoTime() - startNs) / 1000000.0 + " library=katago");
        }
    }

    public static String checkCoreLinkage() {
        try {
            load();
            return nativeBuildStatus();
        } catch (LinkageError | SecurityException e) {
            return "KataGo JNI core unavailable: " + e;
        }
    }

    /** Only one JNI session per process for now; returns null when creation fails. */
    public static GtpSession createSession(File model, File config) { return createSession(model, config, null); }

    /** An optional Human SL model enables rank-style GTP play in the same JNI session. */
    public static GtpSession createSession(File model, File config, File humanModel) {
        long totalNs = System.nanoTime();
        load();
        long nativeNs = System.nanoTime();
        long handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                humanModel == null ? null : humanModel.getAbsolutePath());
        Log.i(TAG, "STARTUP_TIMING phase=jni_native_create_session ms=" +
                (System.nanoTime() - nativeNs) / 1000000.0 + " humanSL=" + (humanModel != null) + " ok=" + (handle != 0));
        Log.i(TAG, "STARTUP_TIMING phase=jni_create_session_total ms=" +
                (System.nanoTime() - totalNs) / 1000000.0);
        return handle == 0 ? null : new GtpSession(handle);
    }

    public static final class GtpSession implements AutoCloseable {
        // Reads can block while waiting for native output. Never hold the Java monitor
        // during a read: it starves send() and can stall the UI/gameplay for minutes.
        // The C++ session registry/queues protect concurrent reads, sends and close.
        private volatile long handle;

        private GtpSession(long handle) { this.handle = handle; }

        /** Sends one GTP command without a newline. */
        public boolean send(String command) {
            long id = handle;
            return id != 0 && nativeSendCommand(id, command);
        }

        /** Returns a chunk of GTP stdout; empty string on timeout, null at native EOF. */
        public String read(int timeoutMs) {
            long id = handle;
            return id == 0 ? null : nativeReadOutput(id, timeoutMs);
        }

        /** True while the native GTP worker has not exited. */
        public boolean isAlive() {
            long id = handle;
            return id != 0 && nativeIsSessionAlive(id);
        }

        /** Enqueues GTP stop; synchronous genmove cannot be interrupted until it returns. */
        public boolean stopSearch() {
            long id = handle;
            return id != 0 && nativeStopSearch(id);
        }

        @Override public synchronized void close() {
            if (handle != 0) {
                long oldHandle = handle;
                handle = 0;
                nativeDestroySession(oldHandle);
            }
        }
    }

    private static native String nativeBuildStatus();
    private static native long nativeCreateSession(String modelPath, String configPath, String humanModelPath);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeIsSessionAlive(long handle);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
