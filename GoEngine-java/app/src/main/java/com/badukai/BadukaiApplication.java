package com.badukai;

import android.app.Application;
import android.util.Log;

import com.badukai.util.DebugLog;

public class BadukaiApplication extends Application {
    private static final String TAG = "BadukaiApplication";

    @Override
    public void onCreate() {
        DebugLog.enter(TAG, "onCreate in");
        super.onCreate();
        Log.i(TAG, "Badukai application created");
    }
}
