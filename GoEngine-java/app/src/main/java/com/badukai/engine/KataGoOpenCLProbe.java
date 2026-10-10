package com.badukai.engine;

/** Debug-only JNI smoke test for vendor OpenCL visibility inside the APP process. */
public final class KataGoOpenCLProbe {
    private KataGoOpenCLProbe() {}

    public static String check() {
        try {
            System.loadLibrary("katago_probe");
            return nativeProbe();
        } catch (LinkageError | SecurityException e) {
            return "JNI/OpenCL probe unavailable: " + e;
        }
    }

    private static native String nativeProbe();
}
