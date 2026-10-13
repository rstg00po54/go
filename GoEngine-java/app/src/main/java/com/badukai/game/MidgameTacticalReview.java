package com.badukai.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative midgame referee: an NN ownership prediction is NOT proof that a
 * stone is dead. We only draw a tactical warning when local capture/escape
 * trials disagree AND a short KataGo candidate variation visits that liberty.
 * This is a warning about a move, not a final life/death judgment.
 */
public final class MidgameTacticalReview {
    public static final class Result {
        public final Set<Point> tacticalMoves, threatenedStones;
        public final int candidates, searchSupported;
        private Result(Set<Point> moves, Set<Point> stones, int candidates, int supported) {
            tacticalMoves = Collections.unmodifiableSet(new LinkedHashSet<>(moves));
            threatenedStones = Collections.unmodifiableSet(new LinkedHashSet<>(stones));
            this.candidates = candidates;
            searchSupported = supported;
        }
    }

    private MidgameTacticalReview() {}

    /** A match in a root recommendation or its first three moves is weak
     * corroboration; it is NEVER a proof that a group can or cannot live. */
    private static boolean mentionedBySearch(Point target, int size, List<List<String>> lines) {
        if (lines == null) return false;
        String coordinate = target.toGtp(size);
        if (coordinate == null) return false;
        for (List<String> pv : lines) {
            if (pv == null) continue;
            for (int i = 0; i < Math.min(3, pv.size()); i++) {
                if (coordinate.equalsIgnoreCase(pv.get(i))) return true;
            }
        }
        return false;
    }

    private static int liberties(GoBoard board, Set<Point> stones) {
        Set<Point> free = new HashSet<>();
        for (Point stone : stones) {
            if (board.get(stone) == Intersection.EMPTY) continue;
            for (int i = 0; i < 4; i++) {
                int x = stone.x + (i == 0 ? -1 : i == 1 ? 1 : 0);
                int y = stone.y + (i == 2 ? -1 : i == 3 ? 1 : 0);
                if (board.isInside(x, y) && board.get(x, y) == Intersection.EMPTY)
                    free.add(new Point(x, y));
            }
        }
        return free.size();
    }

    /**
     * Search finds candidate lines on the actual, unchanged position. Local
     * simulation then tests both immediate orders at the suggested liberty:
     * attacker captures versus defender extends to at least two liberties.
     * A further move could still change the outcome, so the returned group
     * is highlighted yellow (risk), never red (dead).
     */
    public static Result analyze(GoBoard board, EndgameAdjudicator.Result rules,
                                 List<List<String>> blackPv, List<List<String>> whitePv) {
        if (board == null || rules == null || board.isGameOver())
            return new Result(Collections.emptySet(),Collections.emptySet(),0,0);
        Set<Point> moves = new LinkedHashSet<>(), groups = new LinkedHashSet<>();
        int candidates = 0, supported = 0;
        for (EndgameAdjudicator.Finding finding : rules.findings) {
            if (finding.reason != EndgameAdjudicator.Reason.FIRST_MOVE_TACTIC
                    || finding.suggestedMove == null) continue;
            Point liberty = finding.suggestedMove;
            Point anchor = null;
            for (Point point : finding.points) {
                if (board.get(point) != Intersection.EMPTY) { anchor = point; break; }
            }
            if (anchor == null) continue;
            Set<Point> chain = SmartReferee.group(board,anchor);
            if (chain.isEmpty() || liberties(board,chain) != 1
                    || !board.isLegalMove(liberty,StoneColor.BLACK)
                    || !board.isLegalMove(liberty,StoneColor.WHITE)) continue;
            candidates++;
            StoneColor defender = board.get(anchor) == Intersection.BLACK ? StoneColor.BLACK : StoneColor.WHITE;
            StoneColor attacker = defender.opposite();
            GoBoard capture = board.copyForPreview(), escape = board.copyForPreview();
            capture.playMove(new Move.Stone(liberty,attacker));
            escape.playMove(new Move.Stone(liberty,defender));
            boolean removed = true, survived = true;
            for (Point p : chain) {
                removed &= capture.get(p) == Intersection.EMPTY;
                survived &= escape.get(p) == board.get(p);
            }
            if (!removed || !survived || liberties(escape,SmartReferee.group(escape,anchor)) < 2)
                continue;
            if (!mentionedBySearch(liberty,board.getSize(),blackPv)
                    && !mentionedBySearch(liberty,board.getSize(),whitePv)) continue;
            supported++;
            moves.add(liberty);
            groups.addAll(chain);
        }
        return new Result(moves,groups,candidates,supported);
    }
}
