package com.badukai.java;

public final class CoordinateUtils {
    private static final char[] LETTERS = "ABCDEFGHJKLMNOPQRST".toCharArray();
    private CoordinateUtils() {}
    public static String toGtp(int point, int size) {
        if (point < 0) return "pass";
        int col = point % size, row = point / size;
        return "" + LETTERS[col] + (size - row);
    }
    public static int fromGtp(String text, int size) {
        if (text == null) return -1;
        String t = text.trim();
        if (t.equalsIgnoreCase("pass") || t.equalsIgnoreCase("resign")) return -1;
        if (t.length() < 2) return -1;
        int col = -1;
        char c = Character.toUpperCase(t.charAt(0));
        for (int i = 0; i < LETTERS.length; i++) if (LETTERS[i] == c) { col = i; break; }
        if (col < 0 || col >= size) return -1;
        try {
            int rowNum = Integer.parseInt(t.substring(1));
            int row = size - rowNum;
            return row >= 0 && row < size ? row * size + col : -1;
        } catch (NumberFormatException e) { return -1; }
    }
}
