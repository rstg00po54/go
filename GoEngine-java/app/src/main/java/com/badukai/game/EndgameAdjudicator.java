package com.badukai.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative endgame triage, NOT an automatic proof of life, death, ko or seki.
 * Operates on a read-only GoBoard snapshot and one NN ownership map. All tactical
 * trials run on independent copies. A player must confirm marked dead groups.
 */
public final class EndgameAdjudicator {
    public enum Verdict { READY_TO_COUNT, CONTINUE_RECOMMENDED, REVIEW_REQUIRED }
    public enum Reason { STABLE, POSSIBLE_SEKI, POSSIBLE_KO, FIRST_MOVE_TACTIC,
                         POSSIBLE_DAME, UNCERTAIN_DEATH, LOW_LIBERTY_LARGE_GROUP, NO_NN, UNFINISHED_BOARD }
    public static final class Finding {
        public final Reason reason;
        public final String message;
        public final Set<Point> points;
        public final Point suggestedMove;
        Finding(Reason reason, String message, Set<Point> points, Point move) {
            this.reason = reason;
            this.message = message;
            this.points = Collections.unmodifiableSet(new LinkedHashSet<>(points));
            this.suggestedMove = move;
        }
    }
    public static final class Result {
        public final Verdict verdict;
        public final List<Finding> findings;
        public final Set<Point> highlights;
        public final int groups, deadSuggested, reviewSuggested;
        Result(Verdict verdict, List<Finding> findings, Set<Point> highlights,
               int groups, int deadSuggested, int reviewSuggested) {
            this.verdict = verdict;
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
            this.highlights = Collections.unmodifiableSet(new LinkedHashSet<>(highlights));
            this.groups = groups;
            this.deadSuggested = deadSuggested;
            this.reviewSuggested = reviewSuggested;
        }
        public String summary() {
            if (verdict == Verdict.CONTINUE_RECOMMENDED) return "建议继续对局";
            if (verdict == Verdict.REVIEW_REQUIRED) return "存在争议，请复核";
            return "可以进入确认数子";
        }
    }
    private static final class Chain {
        final Intersection color;
        final Set<Point> stones = new LinkedHashSet<>(), liberties = new LinkedHashSet<>();
        Chain(Intersection color) { this.color = color; }
    }
    private EndgameAdjudicator() {}

    private static List<Point> neighbors(GoBoard board, Point p) {
        List<Point> list = new ArrayList<>(4);
        if (p.x > 0) list.add(new Point(p.x-1, p.y));
        if (p.x+1 < board.getSize()) list.add(new Point(p.x+1, p.y));
        if (p.y > 0) list.add(new Point(p.x, p.y-1));
        if (p.y+1 < board.getSize()) list.add(new Point(p.x, p.y+1));
        return list;
    }

    private static List<Chain> chains(GoBoard board) {
        List<Chain> out = new ArrayList<>();
        Set<Point> visited = new HashSet<>();
        for (int y = 0; y < board.getSize(); y++) for (int x = 0; x < board.getSize(); x++) {
            Point first = new Point(x,y);
            Intersection color = board.get(first);
            if (color == Intersection.EMPTY || visited.contains(first)) continue;
            Chain chain = new Chain(color);
            ArrayDeque<Point> todo = new ArrayDeque<>();
            todo.add(first);
            while (!todo.isEmpty()) {
                Point p = todo.removeFirst();
                if (!visited.add(p)) continue;
                chain.stones.add(p);
                for (Point next : neighbors(board,p)) {
                    if (board.get(next) == Intersection.EMPTY) chain.liberties.add(next);
                    else if (board.get(next) == color && !visited.contains(next)) todo.add(next);
                }
            }
            out.add(chain);
        }
        return out;
    }

    private static void add(List<Finding> findings, Set<Point> highlights,
                            Reason reason, String message, Set<Point> points, Point move) {
        findings.add(new Finding(reason, message, points, move));
        highlights.addAll(points);
    }

    private static boolean quickKo(GoBoard board, Point p, StoneColor attack) {
        if (!board.isLegalMove(p, attack)) return false;
        GoBoard trial = board.copyForPreview();
        List<Point> captured = trial.playMove(new Move.Stone(p,attack));
        if (captured.size() != 1) return false;
        Set<Point> placed = SmartReferee.group(trial,p);
        if (placed.size() != 1) return false;
        Set<Point> liberties = new HashSet<>();
        for (Point a : neighbors(trial,p)) if (trial.get(a) == Intersection.EMPTY) liberties.add(a);
        return liberties.size() == 1 && liberties.contains(captured.get(0));
    }

    private static boolean canSaveAtari(GoBoard board, Chain chain, Point liberty) {
        StoneColor color = chain.color == Intersection.BLACK ? StoneColor.BLACK : StoneColor.WHITE;
        if (!board.isLegalMove(liberty,color)) return false;
        GoBoard trial = board.copyForPreview();
        trial.playMove(new Move.Stone(liberty,color));
        Set<Point> saved = SmartReferee.group(trial,liberty);
        if (!saved.containsAll(chain.stones)) return false;
        Set<Point> liberties = new HashSet<>();
        for (Point p : saved) for (Point n : neighbors(trial,p))
            if (trial.get(n) == Intersection.EMPTY) liberties.add(n);
        return liberties.size() >= 2;
    }

    private static boolean canCaptureAtari(GoBoard board, Chain chain, Point liberty) {
        StoneColor attack = chain.color == Intersection.BLACK ? StoneColor.WHITE : StoneColor.BLACK;
        if (!board.isLegalMove(liberty,attack)) return false;
        GoBoard trial = board.copyForPreview();
        trial.playMove(new Move.Stone(liberty,attack));
        for (Point p : chain.stones) if (trial.get(p) != Intersection.EMPTY) return false;
        return true;
    }

    /** Two distinct points repeatedly played by opposite sides indicate ko activity, not proof an unsettled ko. */
    private static boolean recentKoActivity(GoBoard board, Point liberty, Point captured) {
        List<Move> history = board.getMoveHistorySnapshot();
        int atLiberty = 0, atCaptured = 0, limit = Math.max(0, history.size() - 48);
        for (int i = history.size()-1; i >= limit; i--) {
            Move move = history.get(i);
            if (!(move instanceof Move.Stone)) continue;
            Point point = ((Move.Stone) move).point;
            if (liberty.equals(point)) atLiberty++;
            else if (captured.equals(point)) atCaptured++;
        }
        return atLiberty >= 2 && atCaptured >= 2;
    }

    public static Result analyze(GoBoard board, float[] whiteOwnership) {
        if (board == null) throw new IllegalArgumentException("Missing board");
        int n = board.getSize();
        if (whiteOwnership != null && whiteOwnership.length != n*n)
            throw new IllegalArgumentException("Ownership dimensions do not match board");
        List<Chain> groups = chains(board);
        List<Finding> findings = new ArrayList<>();
        Set<Point> highlighted = new LinkedHashSet<>();
        int suggestedDead = 0, suggestedReview = 0;
        Set<Point> confidentlyDead = Collections.emptySet();
        boolean needsContinue = false, needsReview = false;

        if (whiteOwnership == null) {
            add(findings, highlighted, Reason.NO_NN, "AI 归属数据不可用，不能自动确定死活，请人工复核",
                    Collections.emptySet(), null);
            needsReview = true;
        } else {
            SmartReferee.Analysis nn = SmartReferee.analyze(board,whiteOwnership);
            confidentlyDead = nn.suggestedDead;
            suggestedDead = nn.suggestedGroups;
            suggestedReview = nn.reviewGroups;
            if (nn.reviewGroups > 0) {
                add(findings, highlighted, Reason.UNCERTAIN_DEATH,
                        "有 " + nn.reviewGroups + " 块棋需要复核（含气紧棋块），请逐块确认", nn.needsReview, null);
                needsReview = true;
            }
            if (nn.vulnerableGroups > 0) {
                add(findings, highlighted, Reason.LOW_LIBERTY_LARGE_GROUP,
                        "发现 " + nn.vulnerableGroups + " 块大棋只剩少量气、没有明显两眼；"
                                + "NN 即使倾向判活也需复核，不能仅凭气数判死",
                        nn.vulnerableReview, null);
                needsReview = true;
            }
            if (nn.suggestedGroups > 0) {
                add(findings, highlighted, Reason.UNCERTAIN_DEATH,
                        "AI 建议判死 " + nn.suggestedGroups + " 块棋，尚需人工确认", nn.suggestedDead, null);
                needsReview = true;
            }
        }

        Set<Point> possibleKoPoints = new HashSet<>();
        for (Chain chain : groups) {
            if (chain.stones.size() != 1 || chain.liberties.size() != 1) continue;
            Point liberty = chain.liberties.iterator().next();
            StoneColor attack = chain.color == Intersection.BLACK ? StoneColor.WHITE : StoneColor.BLACK;
            if (quickKo(board,liberty,attack)) possibleKoPoints.add(liberty);
        }

        for (Chain chain : groups) {
            if (chain.liberties.isEmpty()) {
                add(findings, highlighted, Reason.UNFINISHED_BOARD, "发现无气棋块，棋面需要检查",
                        chain.stones, null);
                needsReview = true;
                continue;
            }
            if (chain.liberties.size() != 1) continue;
            // An already NN-marked dead chain can often be extended or captured
            // in one move. That does NOT force resumption: confirm its removal.
            // Only tactical threats on chains not strongly marked dead recommend play.
            if (confidentlyDead.containsAll(chain.stones)) continue;
            Point liberty = chain.liberties.iterator().next();
            // The exact same one-move swing is a normal ko exchange. Handle it
            // with the ko verdict below, NOT as proven unresolved life/death.
            if (chain.stones.size() == 1 && possibleKoPoints.contains(liberty)) continue;
            if (canSaveAtari(board,chain,liberty) && canCaptureAtari(board,chain,liberty)) {
                Set<Point> marked = new LinkedHashSet<>(chain.stones);
                marked.add(liberty);
                add(findings, highlighted, Reason.FIRST_MOVE_TACTIC,
                        "这块棋只有一口气：一方可补棋延气，另一方可直接提子。建议继续下",
                        marked, liberty);
                needsContinue = true;
            }
        }

        // A possible ko SHAPE is not a proof of an unsettled ko fight. Passes can
        // erase koPoint; only a local single-stone recapture shape is flagged.
        Set<Point> koChecked = new HashSet<>();
        for (Chain chain : groups) {
            if (chain.liberties.size() != 1 || chain.stones.size() != 1) continue;
            Point liberty = chain.liberties.iterator().next();
            if (!koChecked.add(liberty)) continue;
            if (possibleKoPoints.contains(liberty)) {
                Set<Point> marked = new LinkedHashSet<>(chain.stones);
                marked.add(liberty);
                boolean fought = recentKoActivity(board, liberty, chain.stones.iterator().next());
                add(findings, highlighted, Reason.POSSIBLE_KO,
                        fought ? "发现劫形，棋谱中两处劫点曾反复落子；请双方确认劫争是否已结束，或继续对局"
                                : "发现疑似劫形，但尚不能证明劫争未结束；请人工确认或继续对局",
                        marked, liberty);
                needsReview = true;
            }
        }

        // Candidate seki: opposing strings have precisely two common liberties
        // and no other liberties. This pattern is NOT a proof of seki.
        Set<String> sekiPairs = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) for (int j = i+1; j < groups.size(); j++) {
            Chain a = groups.get(i), b = groups.get(j);
            if (a.color == b.color || a.liberties.size() != 2 || b.liberties.size() != 2
                    || !a.liberties.equals(b.liberties)) continue;
            String key = i+":"+j;
            if (!sekiPairs.add(key)) continue;
            Set<Point> marked = new LinkedHashSet<>(a.stones);
            marked.addAll(b.stones);
            marked.addAll(a.liberties);
            add(findings, highlighted, Reason.POSSIBLE_SEKI,
                    "疑似双活：双方共用两口气。需确认互相无法提子；确认双活后不必继续争夺",
                    marked, null);
            needsReview = true;
        }

        // Narrow neutral points between both colors can be unfilled dame. A
        // neutral point is not by itself a reason to force further moves.
        if (whiteOwnership != null) {
            Set<Point> dame = new LinkedHashSet<>();
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                Point p = new Point(x,y);
                if (board.get(p) != Intersection.EMPTY || !Float.isFinite(whiteOwnership[y*n+x])
                        || Math.abs(whiteOwnership[y*n+x]) > 0.35f) continue;
                boolean black = false, white = false;
                for (Point around : neighbors(board,p)) {
                    black |= board.get(around) == Intersection.BLACK;
                    white |= board.get(around) == Intersection.WHITE;
                }
                if (black && white) dame.add(p);
            }
            if (!dame.isEmpty()) add(findings, highlighted, Reason.POSSIBLE_DAME,
                    "有 " + dame.size() + " 个疑似单官/中立点；只凭空点未填不能要求继续下",
                    dame, null);
        }
        if (!board.isGameOver()) {
            add(findings, highlighted, Reason.UNFINISHED_BOARD,
                    "棋局尚未停棋，不能认定为已经结束", Collections.emptySet(), null);
            needsReview = true;
        }
        if (groups.isEmpty()) {
            add(findings, highlighted, Reason.UNFINISHED_BOARD,
                    "棋盘没有落子，不能判断为已结束", Collections.emptySet(), null);
            needsReview = true;
        }
        Verdict verdict = needsContinue ? Verdict.CONTINUE_RECOMMENDED :
                needsReview ? Verdict.REVIEW_REQUIRED : Verdict.READY_TO_COUNT;
        if (verdict == Verdict.READY_TO_COUNT) add(findings, highlighted, Reason.STABLE,
                "未发现明确的紧急攻防或死活争议；仍需双方确认死子后才可正式数子",
                Collections.emptySet(), null);
        return new Result(verdict,findings,highlighted,groups.size(),suggestedDead,suggestedReview);
    }
}
