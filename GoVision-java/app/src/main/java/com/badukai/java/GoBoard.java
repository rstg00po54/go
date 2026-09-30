package com.badukai.java;

import java.util.*;

public class GoBoard {
    private final int size;
    private final Stone[] cells;
    private int ko = -1;

    public GoBoard(int size) { this.size = size; this.cells = new Stone[size * size]; }
    public int getSize() { return size; }
    public Stone get(int p) { return p >= 0 && p < cells.length ? cells[p] : null; }
    public void clear() { Arrays.fill(cells, null); ko = -1; }

    public boolean play(int p, Stone s) {
        if (p < 0 || p >= cells.length || cells[p] != null || p == ko) return false;
        Stone[] backup = cells.clone(); int oldKo = ko;
        cells[p] = s;
        int captured = 0, singleCapture = -1;
        for (int n : neighbors(p)) {
            if (cells[n] == s.opposite() && liberties(n) == 0) {
                List<Integer> g = group(n);
                captured += g.size();
                if (g.size() == 1) singleCapture = g.get(0);
                for (int q : g) cells[q] = null;
            }
        }
        if (liberties(p) == 0) {
            System.arraycopy(backup, 0, cells, 0, cells.length); ko = oldKo; return false;
        }
        ko = captured == 1 && group(p).size() == 1 && liberties(p) == 1 ? singleCapture : -1;
        return true;
    }

    private List<Integer> group(int start) {
        Stone c = cells[start];
        ArrayList<Integer> out = new ArrayList<>();
        boolean[] seen = new boolean[cells.length];
        ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(start); seen[start] = true;
        while (!q.isEmpty()) {
            int p = q.removeFirst(); out.add(p);
            for (int n : neighbors(p)) if (!seen[n] && cells[n] == c) { seen[n] = true; q.add(n); }
        }
        return out;
    }

    private int liberties(int start) {
        Stone c = cells[start]; if (c == null) return 0;
        boolean[] seen = new boolean[cells.length], libs = new boolean[cells.length];
        ArrayDeque<Integer> q = new ArrayDeque<>(); q.add(start); seen[start] = true;
        int count = 0;
        while (!q.isEmpty()) {
            int p = q.removeFirst();
            for (int n : neighbors(p)) {
                if (cells[n] == null && !libs[n]) { libs[n] = true; count++; }
                else if (cells[n] == c && !seen[n]) { seen[n] = true; q.add(n); }
            }
        }
        return count;
    }

    private int[] neighbors(int p) {
        int r = p / size, c = p % size; int[] tmp = new int[4]; int n = 0;
        if (r > 0) tmp[n++] = p - size; if (r + 1 < size) tmp[n++] = p + size;
        if (c > 0) tmp[n++] = p - 1; if (c + 1 < size) tmp[n++] = p + 1;
        return Arrays.copyOf(tmp, n);
    }
}
