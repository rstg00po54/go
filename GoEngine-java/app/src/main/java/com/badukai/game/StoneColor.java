package com.badukai.game;

import com.badukai.util.DebugLog;

public enum StoneColor {
    BLACK, WHITE;

    private static final String TAG = "StoneColor";

    public StoneColor opposite() {
        DebugLog.enter(TAG, "opposite in, color=" + this);
        return this == BLACK ? WHITE : BLACK;
    }

    public String toGtp() {
        DebugLog.enter(TAG, "toGtp in, color=" + this);
        return this == BLACK ? "black" : "white";
    }
}
