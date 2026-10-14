package com.badukai.engine;

import android.util.Log;
import java.io.File;

/** RK3588 in-process RKNN GTP transport; fixed 19x19 10b. */
public final class KataGoNpuNative {
    private static final String TAG = "KataGoNpuNative";
    private static boolean loaded;

    private KataGoNpuNative() {}

    private static synchronized void load() {
        if (loaded) return;
        long startedNs = System.nanoTime();
        System.loadLibrary("katago_npu");
        loaded = true;
        Log.i(TAG, "STARTUP_TIMING phase=npu_jni_load_library ms=" + (System.nanoTime() - startedNs) / 1000000.0);
    }

    public static String checkCoreLinkage() {
        try {
            load();
            return nativeBuildStatus();
        } catch (LinkageError | SecurityException e) {
            return "KataGo NPU JNI unavailable: " + e;
        }
    }

    public static GtpSession createSession(File model, File config, File humanModel, File rknnModel) {
        long totalNs = System.nanoTime();
        load();
        long startNs = System.nanoTime();
        long handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                humanModel == null ? null : humanModel.getAbsolutePath(), rknnModel.getAbsolutePath());
        Log.i(TAG, "STARTUP_TIMING phase=npu_jni_create_session ms=" +
                (System.nanoTime() - startNs) / 1000000.0 + " ok=" + (handle != 0));
        Log.i(TAG, "STARTUP_TIMING phase=npu_jni_create_total ms=" +
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

    /** Conservative KataGo/Benson proof: false also means not provable. */
    public static boolean isAllPassAlive(int size, byte[] cells) {
        try {
            load();
            return nativeEndgameAllPassAlive(size,cells);
        } catch (LinkageError | SecurityException e) {
            Log.w(TAG,"Strict KataGo pass-alive check unavailable",e);
            return false;
        }
    }

    private static native boolean nativeEndgameAllPassAlive(int size, byte[] cells);
    private static native String nativeBuildStatus();
    private static native long nativeCreateSession(String modelPath, String configPath, String humanModelPath, String rknnPath);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeIsSessionAlive(long handle);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
