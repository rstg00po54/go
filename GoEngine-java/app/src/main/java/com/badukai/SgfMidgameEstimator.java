package com.badukai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.badukai.engine.KataGoEngine;
import com.badukai.game.GoSgf;
import com.badukai.game.Move;
import com.badukai.game.Point;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estimates legacy SGF snapshots on a single background worker. Never invents
 * outcomes or converts an NN score into SGF's formal RE property.
 */
final class SgfMidgameEstimator {
    private static final String TAG = "SgfMidgameEstimate";
    private static final String PREFS = "sgf_midgame_scores_v1";

    static final class Item {
        final File file;
        final GoSgf.Game game;
        Item(File file, GoSgf.Game game) {
            this.file = file;
            this.game = game;
        }
    }

    interface Listener {
        void onEstimate(File file, String estimate);
    }

    private final Context app;
    private final SharedPreferences cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();

    SgfMidgameEstimator(Context context) {
        app = context.getApplicationContext();
        cache = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String cached(File file) {
        // SgfArchive names files using the content hash: cache keys never
        // become stale if an archived game receives another snapshot.
        return cache.getString(file.getName(), null);
    }

    void request(List<Item> games, Listener listener) {
        final int ticket = generation.incrementAndGet();
        if (games.isEmpty()) return;
        worker.execute(() -> evaluateBatch(ticket, games, listener));
    }

    void cancel() {
        generation.incrementAndGet();
    }

    void close() {
        cancel();
        worker.shutdown();
    }

    private void evaluateBatch(int ticket, List<Item> items, Listener listener) {
        KataGoEngine engine = null;
        int boardSize = -1;
        try {
            for (Item item : items) {
                if (generation.get() != ticket || Thread.currentThread().isInterrupted())
                    return;
                String estimate = null;
                try {
                    GoSgf.Game game = item.game;
                    if (game.moves.isEmpty()) continue;
                    // Invalid SGFs are never passed into the native engine.
                    GoSgf.position(game, game.moves.size());
                    if (engine == null || boardSize != game.size) {
                        if (engine != null) engine.stop();
                        engine = new KataGoEngine(app, "engine_sgf_preview");
                        engine.setStartupBoardSize(game.size);
                        if (!engine.start(KataGoEngine.Model.HUMAN)
                                || !engine.setBoardSize(game.size)
                                || !engine.setChineseRules())
                            throw new IllegalStateException("KataGo 10b unavailable");
                        boardSize = game.size;
                    }
                    if (!engine.clearBoard() || !engine.setKomi(game.komi))
                        throw new IllegalStateException("Cannot reset analysis board");
                    if (!game.handicap.isEmpty()
                            && !engine.setHandicapStones(game.handicap, game.size))
                        throw new IllegalStateException("Cannot set handicap");
                    for (Move move : game.moves) {
                        String vertex = move instanceof Move.Stone
                                ? ((Move.Stone) move).point.toGtp(game.size) : "pass";
                        if (!engine.playMove(move.color.toGtp(), vertex))
                            throw new IllegalStateException("Cannot replay move");
                    }
                    KataGoEngine.PositionEvaluation result = engine.evaluatePosition(game.size);
                    if (result != null && Double.isFinite(result.whiteLead)) {
                        double whiteLead = result.whiteLead;
                        estimate = Math.abs(whiteLead) < 0.05 ? "0"
                                : String.format(Locale.US, "%s+%.1f",
                                        whiteLead > 0 ? "W" : "B", Math.abs(whiteLead));
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Failed estimate " + item.file.getName(), ex);
                    if (engine != null) engine.stop();
                    engine = null;
                    boardSize = -1;
                }
                if (generation.get() != ticket) return;
                if (estimate != null) {
                    cache.edit().putString(item.file.getName(), estimate).apply();
                }
                final String value = estimate;
                main.post(() -> {
                    if (generation.get() == ticket)
                        listener.onEstimate(item.file, value);
                });
            }
        } finally {
            if (engine != null) engine.stop();
        }
    }
}
