package com.badukai.util;

import android.util.Log;

public final class DebugLog {
    public static boolean ENABLED = true;

    private DebugLog() {}

    public static void enter(String tag, String message) {
        if (ENABLED) Log.d(tag, message);
    }

    public static void d(String tag, String message) {
        if (ENABLED) Log.d(tag, message);
    }

    public static void i(String tag, String message) {
        if (ENABLED) Log.i(tag, message);
    }

    public static void w(String tag, String message) {
        if (ENABLED) Log.w(tag, message);
    }

    public static void e(String tag, String message) {
        if (ENABLED) Log.e(tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        if (ENABLED) Log.e(tag, message, throwable);
    }
}
