package com.badukai.game;

/** NN probability-weighted Chinese-area estimate; never a final official score. */
public final class NnAreaScore {
    public final double blackPoints, whitePoints;
    private NnAreaScore(double blackPoints, double whitePoints) {
        this.blackPoints = blackPoints;
        this.whitePoints = whitePoints;
    }
    public static NnAreaScore estimate(float[] whiteOwnership, int size, double komi, int handicapStones) {
        if (size < 2 || size > 19 || whiteOwnership == null || whiteOwnership.length != size*size
                || !Double.isFinite(komi) || handicapStones < 0)
            throw new IllegalArgumentException("Invalid NN ownership estimate");
        double black = 0.0, white = komi + handicapStones;
        for (float value : whiteOwnership) {
            if (!Float.isFinite(value) || Math.abs(value) > 1.01f)
                throw new IllegalArgumentException("Invalid NN ownership: " + value);
            double owner = Math.max(-1.0, Math.min(1.0, value));
            black += (1.0-owner)*0.5;
            white += (1.0+owner)*0.5;
        }
        return new NnAreaScore(black,white);
    }
}
