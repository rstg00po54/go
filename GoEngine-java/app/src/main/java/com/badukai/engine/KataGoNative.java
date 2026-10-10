package com.badukai.engine;

import java.io.File;

/** Experimental in-process KataGo CPU/Eigen GTP session. */
public final class KataGoNative {
    private KataGoNative() {}

    private static String selectedLibrary = "katago";
    private static String loadedLibrary;

    /**
     * GPU GTP smoke runs in a dedicated Android process. It must select its
     * own library before calling any other KataGoNative method.
     */
    public static synchronized void selectIsolatedGpuLibrary() {
        if (loadedLibrary != null) throw new IllegalStateException("KataGo JNI already loaded: " + loadedLibrary);
        selectedLibrary = "katago_gpu";
    }

    private static synchronized void load() {
        if (loadedLibrary == null) {
            System.loadLibrary(selectedLibrary);
            loadedLibrary = selectedLibrary;
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
        load();
        long handle = nativeCreateSession(model.getAbsolutePath(), config.getAbsolutePath(),
                humanModel == null ? null : humanModel.getAbsolutePath());
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
