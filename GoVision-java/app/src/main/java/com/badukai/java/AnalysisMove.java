package com.badukai.java;

public class AnalysisMove {
    public final int point;
    public final int visits;
    public final float winrate;
    public final float scoreLead;
    public AnalysisMove(int point, int visits, float winrate, float scoreLead) {
        this.point = point; this.visits = visits; this.winrate = winrate; this.scoreLead = scoreLead;
    }
}
