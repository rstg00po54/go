package com.badukai.game;

import com.badukai.util.DebugLog;

import java.util.Locale;
import java.util.Objects;

public final class Point {
    private static final String TAG = "Point";
    public final int x;
    public final int y;

    public Point(int x, int y) {
        DebugLog.enter(TAG, "Point in, x=" + x + ", y=" + y);
        this.x = x;
        this.y = y;
    }

    public String toGtp(int boardSize) {
        DebugLog.enter(TAG, "toGtp in, x=" + x + ", y=" + y + ", boardSize=" + boardSize);
        final String letters = "ABCDEFGHJKLMNOPQRST";
        if (x < 0 || x >= boardSize || x >= letters.length() || y < 0 || y >= boardSize) return null;
        return String.valueOf(letters.charAt(x)) + (boardSize - y);
    }

    public static Point fromGtp(String gtp, int boardSize) {
        DebugLog.enter(TAG, "fromGtp in, gtp=" + gtp + ", boardSize=" + boardSize);
        if (gtp == null) return null;
        gtp = gtp.trim().toUpperCase(Locale.US);
        if (gtp.length() < 2) return null;
        final String letters = "ABCDEFGHJKLMNOPQRST";
        int x = letters.indexOf(gtp.charAt(0));
        if (x < 0 || x >= boardSize) return null;
        try {
            int row = Integer.parseInt(gtp.substring(1));
            int y = boardSize - row;
            if (y < 0 || y >= boardSize) return null;
            return new Point(x, y);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object obj) {
        DebugLog.enter(TAG, "equals in, obj=" + obj);
        if (this == obj) return true;
        if (!(obj instanceof Point)) return false;
        Point other = (Point) obj;
        return x == other.x && y == other.y;
    }

    @Override
    public int hashCode() {
        DebugLog.enter(TAG, "hashCode in, x=" + x + ", y=" + y);
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "Point(" + x + "," + y + ")";
    }
}
