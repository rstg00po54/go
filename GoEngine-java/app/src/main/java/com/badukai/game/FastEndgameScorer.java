package com.badukai.game;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight experimental dead-stone suggestion from a single raw NN evaluation.
 * KataGo's whiteOwnership is top-row-first: negative=black, positive=white.
 * Treat a WHOLE connected chain as dead only if ownership consistently favors
 * the opposing player. This is heuristic, not a proof of life or death.
 */
public final class FastEndgameScorer {
    private static final float MIN_ENEMY_OWNERSHIP = 0.55f;
    private static final double MEAN_ENEMY_OWNERSHIP = 0.70;
    private static final int[] DX = {-1, 1, 0, 0};
    private static final int[] DY = {0, 0, -1, 1};

    private FastEndgameScorer() {}

    public static final class Result {
        public final Set<Point> deadStones;
        public final int scannedGroups;
        public final int deadGroups;

        private Result(Set<Point> deadStones, int scannedGroups, int deadGroups) {
            this.deadStones = Collections.unmodifiableSet(deadStones);
            this.scannedGroups = scannedGroups;
            this.deadGroups = deadGroups;
        }

        public String deadVertices(int size) {
            StringBuilder out = new StringBuilder();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (!deadStones.contains(new Point(x, y))) continue;
                    if (out.length() > 0) out.append(' ');
                    out.append(new Point(x, y).toGtp(size));
                }
            }
            return out.toString();
        }
    }

    public static Result suggest(GoBoard board, float[] whiteOwnership) {
        if (board == null) throw new IllegalArgumentException("Missing Go board");
        int size = board.getSize();
        if (whiteOwnership == null || whiteOwnership.length != size * size)
            throw new IllegalArgumentException("Ownership map size does not match board");

        boolean[][] seen = new boolean[size][size];
        Set<Point> dead = new HashSet<>();
        int scannedGroups = 0, deadGroups = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Intersection color = board.get(x, y);
                if (color == Intersection.EMPTY || seen[y][x]) continue;
                scannedGroups++;
                ArrayDeque<Point> pending = new ArrayDeque<>();
                ArrayDeque<Point> chain = new ArrayDeque<>();
                pending.add(new Point(x, y));
                seen[y][x] = true;
                double sumEnemy = 0;
                boolean allEnemy = true;
                while (!pending.isEmpty()) {
                    Point point = pending.removeFirst();
                    chain.add(point);
                    float white = whiteOwnership[point.y * size + point.x];
                    double enemy = color == Intersection.BLACK ? white : -white;
                    if (!Float.isFinite(white) || enemy < MIN_ENEMY_OWNERSHIP) allEnemy = false;
                    else sumEnemy += enemy;
                    for (int i = 0; i < 4; i++) {
                        int nx = point.x + DX[i], ny = point.y + DY[i];
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size || seen[ny][nx]
                                || board.get(nx, ny) != color) continue;
                        seen[ny][nx] = true;
                        pending.add(new Point(nx, ny));
                    }
                }
                if (allEnemy && sumEnemy / chain.size() >= MEAN_ENEMY_OWNERSHIP) {
                    dead.addAll(chain);
                    deadGroups++;
                }
            }
        }
        return new Result(dead, scannedGroups, deadGroups);
    }
}
