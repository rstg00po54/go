package com.badukai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.badukai.game.GoBoard;
import com.badukai.game.GoSgf;
import com.badukai.game.Move;
import com.badukai.game.StoneColor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * A private, independent resume checkpoint. It is NOT the SGF history archive.
 * Completed games and explicitly started new games invalidate this checkpoint.
 */
final class UnfinishedGameStore {
    private static final String TAG = "UnfinishedGame";
    private static final String PREF = "unfinished_game_checkpoint";
    private static final String FILE = "unfinished_game.sgf";
    private static final String LEGACY_FILE = "last_game.sgf";

    static final class Pending {
        final GoSgf.Game sgf;
        final GoBoard position;
        final StoneColor humanSide;
        final int rank;
        final int condition;
        final String backend;

        Pending(GoSgf.Game sgf, GoBoard position, StoneColor humanSide,
                int rank, int condition, String backend) {
            this.sgf = sgf;
            this.position = position;
            this.humanSide = humanSide;
            this.rank = rank;
            this.condition = condition;
            this.backend = backend;
        }
    }

    private UnfinishedGameStore() {}

    static void save(Context context, String sgf, int size, int moveCount,
            StoneColor humanSide, int rank, int condition, String backend) {
        try {
            File destination = new File(context.getFilesDir(), FILE);
            File temporary = new File(context.getFilesDir(), FILE + ".tmp");
            Files.write(temporary.toPath(), sgf.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putBoolean("tracking_started", true)
                    .putBoolean("available", true)
                    .putInt("size", size)
                    .putInt("moves", moveCount)
                    .putString("color", humanSide.name())
                    .putInt("rank", rank)
                    .putInt("condition", condition)
                    .putString("backend", backend)
                    .apply();
        } catch (Exception exception) {
            Log.e(TAG, "Cannot save unfinished game", exception);
        }
    }

    static void clear(Context context) {
        // Mark unavailable first: even a failed file deletion must not resurrect a game.
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("tracking_started", true)
                .putBoolean("available", false)
                .apply();
        File checkpoint = new File(context.getFilesDir(), FILE);
        if (checkpoint.exists() && !checkpoint.delete())
            Log.w(TAG, "Could not delete outdated checkpoint");
    }

    /** Only the game's own active checkpoint is eligible after this feature is installed. */
    static Pending load(Context context) {
        SharedPreferences state = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean trackingStarted = state.getBoolean("tracking_started", false);
        if (trackingStarted && !state.getBoolean("available", false)) return null;
        File candidate = new File(context.getFilesDir(), trackingStarted ? FILE : LEGACY_FILE);
        if (!candidate.isFile() || candidate.length() < 12 || candidate.length() > 2_000_000)
            return null;
        try {
            GoSgf.Game sgf = GoSgf.decode(new String(Files.readAllBytes(candidate.toPath()),
                    StandardCharsets.UTF_8));
            if (sgf.moves.isEmpty() || (sgf.result != null && !sgf.result.isEmpty()))
                return null;
            GoBoard position = GoSgf.position(sgf, sgf.moves.size());
            if (position.isGameOver()) return null;
            SharedPreferences settings = context.getSharedPreferences(
                    "katago_settings", Context.MODE_PRIVATE);
            int count = sgf.moves.size();
            boolean metadataMatches = trackingStarted
                    && state.getInt("size", -1) == sgf.size
                    && state.getInt("moves", -1) == count;
            StoneColor color = sgf.humanColor;
            if (color == null) {
                String savedColor = metadataMatches ? state.getString("color", "BLACK")
                        : settings.getString("player_color", "BLACK");
                color = "WHITE".equals(savedColor) ? StoneColor.WHITE : StoneColor.BLACK;
            }
            int rank = metadataMatches ? state.getInt("rank", 12)
                    : settings.getInt("ai_kyu", 12);
            rank = Math.max(1, Math.min(18, rank));
            int condition = metadataMatches ? state.getInt("condition", 0)
                    : settings.getInt("game_condition", 0);
            condition = sgf.handicap.size() >= 2 ? sgf.handicap.size()
                    : (sgf.komi == 0f && condition == 1 ? 1 : 0);
            String backend = metadataMatches ? state.getString("backend", "CPU")
                    : settings.getString("backend", "CPU");
            if (!"GPU".equals(backend) && !"NPU".equals(backend)) backend = "CPU";
            Log.i(TAG, "Resume candidate: board=" + sgf.size + " moves=" + count
                    + " backend=" + backend + " historical=" + !trackingStarted);
            return new Pending(sgf, position, color, rank, condition, backend);
        } catch (Exception invalid) {
            Log.w(TAG, "Saved game cannot be resumed", invalid);
            return null;
        }
    }
}
