package com.badukai.engine;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Explicit debug-only timing protocol, launched by MainActivity's benchmark intent.
 * A fresh app process is used per backend by the host test script. Every measured
 * move starts from the same empty board; the first moves are warm-ups, excluded
 * from reported latency. This measures complete GTP genmove, not pure NN speed.
 */
public final class KataGoBackendBenchmark {
    private static final String TAG = "KataGoBench";
    private KataGoBackendBenchmark() {}

    private static long millis(long startNs) {
        return (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L;
    }

    private static boolean legalVertex(String raw, int size) {
        if (raw == null) return false;
        String value = raw.trim().toUpperCase(Locale.US);
        if ("PASS".equals(value) || "RESIGN".equals(value)) return true;
        if (!value.matches("[A-HJ-T]([1-9]|1[0-9])")) return false;
        char col = value.charAt(0);
        int x = col - 'A' - (col > 'I' ? 1 : 0);
        int y = Integer.parseInt(value.substring(1));
        return x >= 0 && x < size && y >= 1 && y <= size;
    }

    /**
     * Create independent deterministic opening positions. The earlier benchmark
     * repeatedly evaluated an empty board and measured cache hits (sometimes
     * 0ms). Each index produces an identical position across CPU/GPU/NPU but
     * differs across timed trials. Position setup is NOT part of move timing.
     */
    private static String setUniquePosition(KataGoEngine engine, int board, int iteration) {
        int center = board / 2;
        int[][] locations = {
                {2, 2}, {board-3, board-3}, {board-3, 2}, {2, board-3},
                {center, center}, {center, 1}, {1, center}, {board-2, center},
                {center, board-2}, {1, 1}, {board-2, 1}, {1, board-2}
        };
        List<int[]> order = new ArrayList<>();
        Collections.addAll(order, locations);
        Collections.shuffle(order, new Random(70379L + board * 1009L + iteration * 7919L));
        final int count = 4 + 2 * (iteration % 3);
        if (!engine.clearBoard()) throw new IllegalStateException("clear_board failed");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int x = order.get(i)[0];
            int y = order.get(i)[1] + 1;
            String coordinate = "" + (char) ('A' + x + (x >= 8 ? 1 : 0)) + y;
            String color = i % 2 == 0 ? "black" : "white";
            if (!engine.playMove(color, coordinate))
                throw new IllegalStateException("Could not set position: " + color + " " + coordinate);
            if (result.length() > 0) result.append(',');
            result.append(color.charAt(0)).append(':').append(coordinate);
        }
        return result.toString();
    }

    public static void run(Context context, String id, String backendName, int board,
                           int visits, double maxSeconds, int warmups, int repeats,
                           boolean human) {
        final KataGoEngine engine = new KataGoEngine(context);
        String step = "init";
        Log.i(TAG, "START id=" + id + " backend=" + backendName + " board=" + board
                + " visits=" + visits + " maxTime=" + maxSeconds
                + " warmups=" + warmups + " repeats=" + repeats + " human=" + human);
        try {
            if (board != 9 && board != 13 && board != 19)
                throw new IllegalArgumentException("Board must be 9, 13 or 19");
            if (visits < 1 || visits > 512 || maxSeconds < 0.1 || maxSeconds > 50
                    || warmups < 1 || warmups > 10 || repeats < 2 || repeats > 30)
                throw new IllegalArgumentException("Invalid performance test limits");
            KataGoEngine.BackendPreference preference =
                    KataGoEngine.BackendPreference.valueOf(backendName);
            if (preference == KataGoEngine.BackendPreference.NPU
                    && !KataGoEngine.supportsMediaTekGame(context, board))
                throw new IllegalStateException("MediaTek LiteRT NPU graph/JNI not bundled");
            engine.setStartupBoardSize(board);
            engine.setBackendPreference(preference);
            if (human) {
                step = "human_prepare";
                Log.i(TAG, "PHASE id=" + id + " stage=" + step);
                engine.prepareHumanModel(null);
            }
            step = "engine_start";
            Log.i(TAG, "PHASE id=" + id + " stage=" + step);
            long startNs = SystemClock.elapsedRealtimeNanos();
            if (!engine.start(KataGoEngine.Model.HUMAN, human))
                throw new IllegalStateException("Engine failed to start");
            long startupMs = millis(startNs);
            String actual = engine.getBackendName();
            if (!actual.startsWith(backendName + "/")) {
                // Do not report GPU/NPU numbers when KataGo auto-falls back to CPU.
                throw new IllegalStateException("Backend fallback: requested=" + backendName
                        + " actual=" + actual);
            }
            if (preference == KataGoEngine.BackendPreference.NPU
                    && engine.getActiveNpuBoardSize() != board)
                throw new IllegalStateException("Wrong NPU model board size");
            Log.i(TAG, "READY id=" + id + " backend=" + backendName + " board=" + board
                    + " actual=" + actual.replace(' ', '_') + " startupMs=" + startupMs);
            step = "gtp_setup";
            Log.i(TAG, "PHASE id=" + id + " stage=" + step);
            if (!engine.setBoardSize(board) || !engine.clearBoard()
                    || !engine.setChineseRules() || !engine.setKomi(board == 9 ? 5.5f : 7.5f)
                    || !engine.setSearchLimits(visits, maxSeconds))
                throw new IllegalStateException("GTP board/rules/search setup failed");
            if (human && !engine.setHumanRank(12))
                throw new IllegalStateException("Human SL 12k setup failed");

            for (int i = 0; i < warmups + repeats; i++) {
                final boolean warmup = i < warmups;
                step = warmup ? "warmup" : "measure";
                Log.i(TAG, "PHASE id=" + id + " stage=" + step + " iter=" + i
                        + " backend=" + backendName);
                // Distinct replayed board positions avoid benchmarking NN/cache
                // hits from the same empty board. Setup is excluded from timing.
                final long setupNs = SystemClock.elapsedRealtimeNanos();
                final String position = setUniquePosition(engine, board, i);
                final long setupMs = millis(setupNs);
                Log.i(TAG, "POSITION id=" + id + " backend=" + backendName
                        + " board=" + board + " iter=" + i + " replayMs=" + setupMs
                        + " stones=" + position);
                long genNs = SystemClock.elapsedRealtimeNanos();
                String move = engine.generateMove("black");
                long elapsedMs = millis(genNs);
                if (!legalVertex(move, board))
                    throw new IllegalStateException("Invalid GTP genmove: " + move);
                Log.i(TAG, "MOVE id=" + id + " backend=" + backendName + " board=" + board
                        + " iter=" + i + " warmup=" + warmup
                        + " ms=" + elapsedMs + " positionIter=" + i
                        + " vertex=" + move.toUpperCase(Locale.US));
            }
            step = "stop";
            Log.i(TAG, "PHASE id=" + id + " stage=" + step);
            engine.stop();
            Log.i(TAG, "PASS id=" + id + " backend=" + backendName + " board=" + board
                    + " measured=" + repeats + " warmups=" + warmups);
        } catch (Exception | LinkageError error) {
            Log.e(TAG, "FAIL id=" + id + " backend=" + backendName + " stage=" + step
                    + " reason=" + error, error);
        } finally {
            engine.stop();
        }
    }
}
