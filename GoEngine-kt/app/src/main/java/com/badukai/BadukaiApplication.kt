package com.badukai

import android.app.Application
import android.util.Log

class BadukaiApplication : Application() {
    companion object {
        private const val TAG = "BadukaiApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Badukai application created")
    }
}
