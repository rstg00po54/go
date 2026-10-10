package com.badukai.engine;

import java.io.File;

/** Experimental in-process KataGo CPU/Eigen GTP session. */
public final class KataGoNative {
    private KataGoNative() {}

    private static synchronized void load() { System.loadLibrary("katago"); }

    public static String checkCoreLinkage() {
        try {
            load();
            return nativeBuildStatus();
        } catch (LinkageError | SecurityException e) {
            return "KataGo JNI core unavailable: " + e;
        }
    }

    /** Only one JNI session per process for now; returns null when creation fails. */
    public static GtpSession createSession(File model, File config) {
        load();
        long handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath());
        return handle == 0 ? null : new GtpSession(handle);
    }

    public static final class GtpSession implements AutoCloseable {
        private long handle;

        private GtpSession(long handle) { this.handle = handle; }

        /** Sends one GTP command without a newline. */
        public synchronized boolean send(String command) {
            if (handle == 0) return false;
            return nativeSendCommand(handle, command);
        }

        /** Returns a chunk of GTP stdout; empty string on timeout, null at native EOF. */
        public synchronized String read(int timeoutMs) {
            if (handle == 0) return null;
            return nativeReadOutput(handle, timeoutMs);
        }

        /** Enqueues GTP stop; synchronous genmove cannot be interrupted until it returns. */
        public synchronized boolean stopSearch() {
            return handle != 0 && nativeStopSearch(handle);
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
    private static native long nativeCreateSession(String modelPath, String configPath);
    private static native boolean nativeSendCommand(long handle, String command);
    private static native String nativeReadOutput(long handle, int timeoutMs);
    private static native boolean nativeStopSearch(long handle);
    private static native void nativeDestroySession(long handle);
}
