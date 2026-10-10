package com.badukai.engine;

import android.util.Log;
import java.io.File;

/** Direct in-process OpenCL GTP transport. JNI symbols and library are GPU-specific. */
public final class KataGoGpuNative {
    private static final String TAG = "KataGoGpuNative";
    private static boolean loaded;

    private KataGoGpuNative() {}

    private static synchronized void load() {
        if (loaded) return;
        long startedNs = System.nanoTime();
        System.loadLibrary("katago_gpu");
        loaded = true;
        Log.i(TAG, "STARTUP_TIMING phase=gpu_jni_load_library ms=" + (System.nanoTime() - startedNs) / 1000000.0);
    }

    public static GtpSession createSession(File model, File config, File humanModel) {
        long totalNs = System.nanoTime();
        load();
        long startNs = System.nanoTime();
        long handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                humanModel == null ? null : humanModel.getAbsolutePath());
        Log.i(TAG, "STARTUP_TIMING phase=gpu_jni_create_session ms=" +
                (System.nanoTime() - startNs) / 1000000.0 + " ok=" + (handle != 0));
        Log.i(TAG, "STARTUP_TIMING phase=gpu_jni_create_total ms=" +
                (System.nanoTime() - totalNs) / 1000000.0);
        return handle == 0 ? null : new GtpSession(handle);
    }

    public static final class GtpSession implements AutoCloseable {
        private volatile long handle;

        private GtpSession(long handle) { this.handle = handle; }

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
            if (handle != 0) {
                long oldHandle = handle;
                handle = 0;
                nativeDestroySession(oldHandle);
            }
        }
    }

    private static native long nativeCreateSession(String modelPath, String configPath, String humanModelPath);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeIsSessionAlive(long handle);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
