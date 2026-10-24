package com.badukai.training;

import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hand-authored beginner training positions. These are one-move exercises:
 * checking the recorded teaching move is NOT an exhaustive proof that every
 * other legal move loses. No KataGo inference or network connection is used.
 */
public final class TsumegoProblems {
    public enum Goal { CAPTURE, ESCAPE_ATARI, MAKE_TWO_EYES }

    public static final class Problem {
        public final String title, instruction, explanation;
        public final StoneColor turn;
        public final Goal goal;
        public final int minimumCaptures;
        public final Point answer;
        private final String[] rows;

        private Problem(String title, String instruction, String explanation, StoneColor turn,
                        Goal goal, int minimumCaptures, Point answer, String[] rows) {
            this.title = title;
            this.instruction = instruction;
            this.explanation = explanation;
            this.turn = turn;
            this.goal = goal;
            this.minimumCaptures = minimumCaptures;
            this.answer = answer;
            this.rows = rows.clone();
        }

        public GoBoard createBoard() {
            GoBoard board = new GoBoard(9);
            board.setupDiagram(rows);
            return board;
        }
    }

    private static final List<Problem> ALL = Collections.unmodifiableList(createProblems());

    private TsumegoProblems() {}

    public static List<Problem> all() { return ALL; }

    public static boolean matchesAnswer(Problem problem, Point selected) {
        return problem != null && problem.answer.equals(selected);
    }

    public static boolean goalReached(Problem problem, GoBoard board, int captureCount) {
        if (problem.goal == Goal.CAPTURE) return captureCount >= problem.minimumCaptures;
        if (problem.goal == Goal.ESCAPE_ATARI) return countLiberties(board, problem.answer) >= 3;
        return countSinglePointEyes(board, problem.answer) >= 2;
    }

    private static List<Problem> createProblems() {
        List<Problem> result = new ArrayList<>();
        result.add(problem("01 · 黑先提子", "吃子 · 初级", "黑先，找出白棋最后一口气。",
                "补在白棋的唯一气上，白子立即被提走。",
                StoneColor.BLACK, Goal.CAPTURE, 1, 4, 5,
                ".........", ".........", ".........", "....X....", "...XOX...",
                ".........", ".........", ".........", "........."));
        result.add(problem("02 · 白先提子", "吃子 · 初级", "白先，找出黑棋最后一口气。",
                "白棋落在上方的唯一气，提掉中间黑子。",
                StoneColor.WHITE, Goal.CAPTURE, 1, 4, 3,
                ".........", ".........", ".........", ".........", "...OXO...",
                "....O....", ".........", ".........", "........."));
        result.add(problem("03 · 黑先提两子", "吃子 · 初级", "黑先，一手提掉连接的两颗白子。",
                "两颗连在一起的白棋共用一口气，补上便一起被提走。",
                StoneColor.BLACK, Goal.CAPTURE, 2, 5, 5,
                ".........", ".........", ".........", "....XX...", "...XOOX..",
                "....X....", ".........", ".........", "........."));
        result.add(problem("04 · 白先提两子", "吃子 · 初级", "白先，一手提掉连接的两颗黑子。",
                "数气时要把连在一起的棋子当作一整块。",
                StoneColor.WHITE, Goal.CAPTURE, 2, 4, 5,
                ".........", ".........", ".........", "...OO....", "..OXXO...",
                "...O.....", ".........", ".........", "........."));
        result.add(problem("05 · 黑棋逃出打吃", "逃子 · 初级", "黑先，中间黑子只有一口气，先逃出来。",
                "向下长出，黑棋从一口气变为多口气；逃出打吃不等于已经做活。",
                StoneColor.BLACK, Goal.ESCAPE_ATARI, 0, 4, 5,
                ".........", ".........", ".........", "....O....", "...OXO...",
                ".........", ".........", ".........", "........."));
        result.add(problem("06 · 白棋逃出打吃", "逃子 · 初级", "白先，右上方白子仅剩一口气。",
                "向下长出增加气；下一步还要继续判断能否真正做活。",
                StoneColor.WHITE, Goal.ESCAPE_ATARI, 0, 5, 4,
                ".........", ".........", ".....X...", "....XOX..", ".........",
                ".........", ".........", ".........", "........."));
        result.add(problem("07 · 黑先做两眼", "做活 · 初级", "黑先，将内部三个连通空点分隔成两个单点眼。",
                "走在中间分割点，左右各留下一个黑棋围住的单点眼；两眼活。",
                StoneColor.BLACK, Goal.MAKE_TWO_EYES, 0, 4, 3,
                ".........", ".........", "..XXXXX..", "..X...X..", "..XXXXX..",
                ".........", ".........", ".........", "........."));
        result.add(problem("08 · 白先做两眼", "做活 · 初级", "白先，补一手形成左右两只眼。",
                "下在中间，让同一块白棋拥有两处独立的真眼。",
                StoneColor.WHITE, Goal.MAKE_TWO_EYES, 0, 4, 3,
                ".........", ".........", "..OOOOO..", "..O...O..", "..OOOOO..",
                ".........", ".........", ".........", "........."));
        for (Problem problem : result) validate(problem);
        return result;
    }

    private static Problem problem(String title, String type, String task, String explanation,
                                   StoneColor turn, Goal goal, int captures, int x, int y,
                                   String... rows) {
        return new Problem(title + " · " + type, task, explanation, turn, goal, captures,
                new Point(x, y), rows);
    }

    /** Detect obviously mis-entered coordinates/diagrams before showing a broken problem. */
    private static void validate(Problem problem) {
        GoBoard board = problem.createBoard();
        if (!board.isLegalMove(problem.answer, problem.turn))
            throw new IllegalStateException("Illegal teaching answer: " + problem.title);
        int captured = board.playMove(new Move.Stone(problem.answer, problem.turn)).size();
        if (!goalReached(problem, board, captured))
            throw new IllegalStateException("Teaching answer misses goal: " + problem.title);
    }

    private static int countLiberties(GoBoard board, Point start) {
        if (board.get(start) == Intersection.EMPTY) return 0;
        Set<Point> group = connectedGroup(board, start);
        Set<Point> liberties = new HashSet<>();
        for (Point p : group) for (Point n : neighbors(board, p)) {
            if (board.get(n) == Intersection.EMPTY) liberties.add(n);
        }
        return liberties.size();
    }

    private static int countSinglePointEyes(GoBoard board, Point start) {
        if (board.get(start) == Intersection.EMPTY) return 0;
        Set<Point> group = connectedGroup(board, start);
        int eyes = 0;
        for (int y = 0; y < board.getSize(); y++) {
            for (int x = 0; x < board.getSize(); x++) {
                Point p = new Point(x, y);
                if (board.get(p) != Intersection.EMPTY) continue;
                List<Point> around = neighbors(board, p);
                if (!around.isEmpty() && group.containsAll(around)) eyes++;
            }
        }
        return eyes;
    }

    private static Set<Point> connectedGroup(GoBoard board, Point start) {
        Intersection target = board.get(start);
        Set<Point> group = new HashSet<>();
        if (target == Intersection.EMPTY) return group;
        ArrayDeque<Point> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            Point p = pending.removeFirst();
            if (board.get(p) != target || !group.add(p)) continue;
            for (Point n : neighbors(board, p)) if (!group.contains(n)) pending.addLast(n);
        }
        return group;
    }

    private static List<Point> neighbors(GoBoard board, Point point) {
        List<Point> result = new ArrayList<>(4);
        int x = point.x, y = point.y;
        if (board.isInside(x - 1, y)) result.add(new Point(x - 1, y));
        if (board.isInside(x + 1, y)) result.add(new Point(x + 1, y));
        if (board.isInside(x, y - 1)) result.add(new Point(x, y - 1));
        if (board.isInside(x, y + 1)) result.add(new Point(x, y + 1));
        return result;
    }
}
