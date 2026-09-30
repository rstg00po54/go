package com.badukai;

import android.app.Application;
import android.util.Log;

public class BadukaiApplication extends Application {
    private static final String TAG = "BadukaiApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Badukai application created");
    }
}
