package com.badukai.game;

import com.badukai.util.DebugLog;

public abstract class Move {
    protected static final String TAG = "Move";
    public final StoneColor color;

    protected Move(StoneColor color) {
        DebugLog.enter(TAG, "Move in, color=" + color);
        this.color = color;
    }

    public static final class Stone extends Move {
        public final Point point;

        public Stone(Point point, StoneColor color) {
            super(color);
            DebugLog.enter(TAG, "Stone in, point=" + point + ", color=" + color);
            this.point = point;
        }
    }

    public static final class Pass extends Move {
        public Pass(StoneColor color) {
            super(color);
            DebugLog.enter(TAG, "Pass in, color=" + color);
        }
    }

    public static final class Resign extends Move {
        public Resign(StoneColor color) {
            super(color);
            DebugLog.enter(TAG, "Resign in, color=" + color);
        }
    }
}
