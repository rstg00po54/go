package com.badukai.game;

import java.util.List;

/**
 * Preliminary, per-move win-rate loss from consecutive KataGo 10b samples.
 * Rates always represent BLACK win probability, independent of the display side.
 * These are raw NN screening labels, not verified MCTS judgements.
 */
public final class GoMoveQuality {
    public static final int NONE = 0;
    public static final int QUESTIONABLE = 1;
    public static final int MISTAKE = 2;
    public static final int BLUNDER = 3;

    private GoMoveQuality() {}

    public static final class Result {
        /** Percentage-point loss (0..100), NaN for moves without two samples. */
        public final float[] losses;
        public final int[] severity;
        public final int questionable, mistakes, blunders;

        private Result(float[] values, int[] types, int questions, int errors, int bad) {
            losses = values;
            severity = types;
            questionable = questions;
            mistakes = errors;
            blunders = bad;
        }

        public int total() { return questionable + mistakes + blunders; }
    }

    public static Result analyze(float[] blackWins, List<Move> moves) {
        int count = moves == null ? 0 : moves.size();
        float[] losses = new float[count + 1];
        int[] severity = new int[count + 1];
        java.util.Arrays.fill(losses, Float.NaN);
        int questions = 0, errors = 0, bad = 0;
        if (blackWins == null) return new Result(losses, severity, 0, 0, 0);
        for (int i = 1; i <= count && i < blackWins.length; i++) {
            Move move = moves.get(i - 1);
            // Passing and resigning require additional rule/terminal context.
            if (!(move instanceof Move.Stone)) continue;
            float before = blackWins[i - 1];
            float after = blackWins[i];
            if (!Float.isFinite(before) || !Float.isFinite(after)
                    || before < 0f || before > 1f || after < 0f || after > 1f) continue;
            float raw = (move.color == StoneColor.BLACK ? before - after : after - before) * 100f;
            losses[i] = Math.max(0f, Math.min(100f, raw));
            int level = losses[i] >= 20f ? BLUNDER
                    : losses[i] >= 10f ? MISTAKE
                    : losses[i] >= 5f ? QUESTIONABLE : NONE;
            severity[i] = level;
            if (level == BLUNDER) bad++;
            else if (level == MISTAKE) errors++;
            else if (level == QUESTIONABLE) questions++;
        }
        return new Result(losses, severity, questions, errors, bad);
    }

    public static String label(int severity) {
        switch (severity) {
            case BLUNDER: return "疑似恶手";
            case MISTAKE: return "疑似失误";
            case QUESTIONABLE: return "疑问手";
            default: return "正常";
        }
    }
}
