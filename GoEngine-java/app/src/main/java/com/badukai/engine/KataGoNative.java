package com.badukai.engine;

/** Experimental KataGo JNI core; not yet a GTP engine or a gameplay backend. */
public final class KataGoNative {
    private KataGoNative() {}

    public static String checkCoreLinkage() {
        try {
            System.loadLibrary("katago");
            return nativeBuildStatus();
        } catch (LinkageError | SecurityException e) {
            return "KataGo JNI core unavailable: " + e;
        }
    }

    private static native String nativeBuildStatus();
}
