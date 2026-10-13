package com.badukai.game;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Experimental Chinese-area scoring assistant. A RAW NN ownership map suggests
 * entire dead chains, but NEVER constitutes an automatic rules decision.
 * Human confirmation of every marked group is required before publishing
 * a counted result. The original GoBoard is never mutated.
 */
public final class SmartReferee {
    private static final int[] DX = {-1, 1, 0, 0}, DY = {0, 0, -1, 1};

    private SmartReferee() {}

    public static final class Analysis {
        public final Set<Point> suggestedDead, needsReview, vulnerableReview;
        public final int groups, suggestedGroups, reviewGroups, vulnerableGroups;

        Analysis(Set<Point> dead, Set<Point> review, Set<Point> vulnerable,
                 int groups, int suggested, int uncertain, int vulnerableCount) {
            suggestedDead = Collections.unmodifiableSet(new HashSet<>(dead));
            needsReview = Collections.unmodifiableSet(new HashSet<>(review));
            vulnerableReview = Collections.unmodifiableSet(new HashSet<>(vulnerable));
            this.groups = groups;
            suggestedGroups = suggested;
            reviewGroups = uncertain;
            vulnerableGroups = vulnerableCount;
        }
    }

    public static Analysis analyze(GoBoard board, float[] whiteOwnership) {
        if (board == null) throw new IllegalArgumentException("Missing board");
        int n = board.getSize();
        if (whiteOwnership == null || whiteOwnership.length != n * n)
            throw new IllegalArgumentException("Ownership map size mismatch");
        boolean[][] seen = new boolean[n][n];
        Set<Point> dead = new HashSet<>(), review = new HashSet<>(), vulnerable = new HashSet<>();
        int groups = 0, marked = 0, uncertain = 0, vulnerableCount = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                Intersection color = board.get(x, y);
                if (color == Intersection.EMPTY || seen[y][x]) continue;
                groups++;
                Set<Point> chain = group(board, new Point(x, y));
                double total = 0.0, minimum = 1.0;
                for (Point p : chain) {
                    seen[p.y][p.x] = true;
                    float raw = whiteOwnership[p.y * n + p.x];
                    if (!Float.isFinite(raw)) throw new IllegalArgumentException("Invalid NN ownership");
                    double enemy = color == Intersection.BLACK ? raw : -raw;
                    minimum = Math.min(minimum, enemy);
                    total += enemy;
                }
                double mean = total / chain.size();
                // Deliberately stricter than the previous fast-dead heuristic:
                // false positives cannot be called an official rules verdict.
                if (minimum >= 0.80 && mean >= 0.90) {
                    dead.addAll(chain);
                    marked++;
                } else {
                    boolean nnUncertain = mean >= 0.25 || (mean >= -0.10 && minimum <= -0.65);
                    // Tactical warning: a large chain with only a few liberties can
                    // be dead despite confident *friendly* NN ownership. Two enclosed
                    // eye candidates suppress this heuristic, but are not a proof
                    // of life (false eyes/seki still need deeper verification).
                    boolean tightLargeGroup = chain.size() >= 6 && isLowLibertyWithoutTwoEyes(board, chain);
                    if (nnUncertain || tightLargeGroup) {
                        review.addAll(chain);
                        uncertain++;
                    }
                    if (tightLargeGroup) {
                        vulnerable.addAll(chain);
                        vulnerableCount++;
                    }
                }
            }
        }
        return new Analysis(dead, review, vulnerable, groups, marked, uncertain, vulnerableCount);
    }

    /** Heuristic ONLY: do not adjudicate death from group size or liberty count. */
    private static boolean isLowLibertyWithoutTwoEyes(GoBoard board, Set<Point> chain) {
        Set<Point> liberties = new HashSet<>();
        for (Point p : chain) for (int i = 0; i < 4; i++) {
            int x = p.x + DX[i], y = p.y + DY[i];
            if (board.isInside(x, y) && board.get(x, y) == Intersection.EMPTY)
                liberties.add(new Point(x, y));
        }
        if (liberties.size() > 3) return false;
        int eyeCandidates = 0;
        for (Point p : liberties) {
            boolean surrounded = true;
            for (int i = 0; i < 4; i++) {
                int x = p.x + DX[i], y = p.y + DY[i];
                if (board.isInside(x, y) && !chain.contains(new Point(x, y))) {
                    surrounded = false;
                    break;
                }
            }
            if (surrounded && ++eyeCandidates >= 2) return false;
        }
        return true;
    }

    /** Full-chain toggle. Never removes a stone from the actual game board. */
    public static void toggleGroup(GoBoard board, Set<Point> dead, Point point) {
        if (board == null || dead == null || point == null || board.get(point) == Intersection.EMPTY) return;
        Set<Point> chain = group(board, point);
        if (dead.containsAll(chain)) dead.removeAll(chain);
        else dead.addAll(chain);
    }

    /** Resolve an ENTIRE chain after explicit review. Neither action edits the game board. */
    public static void decideGroup(GoBoard board, Set<Point> dead, Set<Point> review, Point point, boolean markDead) {
        if (board == null || dead == null || review == null || point == null || board.get(point) == Intersection.EMPTY) return;
        Set<Point> chain = group(board, point);
        review.removeAll(chain);
        dead.removeAll(chain);
        if (markDead) dead.addAll(chain);
    }

    /** A review count is per connected chain, never per stone. */
    public static int countGroups(GoBoard board, Set<Point> stones) {
        if (board == null || stones == null || stones.isEmpty()) return 0;
        Set<Point> visited = new HashSet<>();
        int count = 0;
        for (Point point : stones) {
            if (point == null || visited.contains(point) || board.get(point) == Intersection.EMPTY) continue;
            visited.addAll(group(board, point));
            count++;
        }
        return count;
    }

    /** Compare the confirmed-stone count against the NN estimate to detect disputed endings. */
    public static final class ScoreAudit {
        public final GoBoard.FinalScore score;
        public final double whiteLead, nnWhiteLead, difference;
        public final boolean needsVerification;

        private ScoreAudit(GoBoard.FinalScore score, double nnWhiteLead, boolean needsVerification) {
            this.score = score;
            whiteLead = score.whitePoints - score.blackPoints;
            this.nnWhiteLead = nnWhiteLead;
            difference = Double.isFinite(nnWhiteLead) ? Math.abs(whiteLead - nnWhiteLead) : Double.NaN;
            this.needsVerification = needsVerification;
        }
    }

    public static ScoreAudit audit(GoBoard board, Set<Point> dead, double nnWhiteLead, double komi, int handicap) {
        if (board == null) throw new IllegalArgumentException("Missing board");
        GoBoard.FinalScore score = board.countChineseScore(dead, komi, handicap);
        double localLead = score.whitePoints - score.blackPoints;
        double delta = Math.abs(localLead - nnWhiteLead);
        boolean oppositeWinner = (localLead >= 0.5 && nnWhiteLead <= -0.5)
                || (localLead <= -0.5 && nnWhiteLead >= 0.5);
        double threshold = Math.max(5.0, board.getSize() * board.getSize() * 0.03);
        boolean verify = Double.isFinite(nnWhiteLead) && (oppositeWinner || delta >= threshold);
        return new ScoreAudit(score, nnWhiteLead, verify);
    }

    public static Set<Point> group(GoBoard board, Point start) {
        Set<Point> result = new HashSet<>();
        if (board == null || start == null || board.get(start) == Intersection.EMPTY)
            return result;
        int n = board.getSize();
        Intersection color = board.get(start);
        ArrayDeque<Point> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Point p = pending.removeFirst();
            if (!result.add(p)) continue;
            for (int i = 0; i < 4; i++) {
                int x = p.x + DX[i], y = p.y + DY[i];
                if (x >= 0 && y >= 0 && x < n && y < n && board.get(x, y) == color) {
                    Point adjacent = new Point(x, y);
                    if (!result.contains(adjacent)) pending.addLast(adjacent);
                }
            }
        }
        return result;
    }

    public static String toGtpVertices(Set<Point> dead, int size) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            Point p = new Point(x, y);
            if (!dead.contains(p)) continue;
            if (out.length() != 0) out.append(' ');
            out.append(p.toGtp(size));
        }
        return out.toString();
    }

    public static String resultText(GoBoard.FinalScore score) {
        double lead = score.whitePoints - score.blackPoints;
        if (Math.abs(lead) < 0.001) return "0";
        return String.format(java.util.Locale.US, "%s+%.1f", lead > 0 ? "W" : "B", Math.abs(lead));
    }
}
