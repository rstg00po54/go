package com.badukai.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Eight original, offline 9x9 beginner life-and-death exercises.
 * All questions have one objectively verifiable answer: capture the marked
 * atari group in one move. No network, KataGo search, or copyrighted puzzle
 * sources are required. Future lessons can add deeper variations separately.
 */
public final class TsumegoLessons {
    public static final int BOARD_SIZE = 9;
    private TsumegoLessons() {}

    public static final class Puzzle {
        public final String title, instruction, hint, explanation;
        public final StoneColor toPlay;
        public final Point answer, target;
        private final List<Point> black, white;

        private Puzzle(String title, String instruction, String hint, String explanation,
                       StoneColor toPlay, String black, String white,
                       String target, String answer) {
            this.title = title;
            this.instruction = instruction;
            this.hint = hint;
            this.explanation = explanation;
            this.toPlay = toPlay;
            this.black = Collections.unmodifiableList(points(black));
            this.white = Collections.unmodifiableList(points(white));
            this.target = point(target);
            this.answer = point(answer);
            checkSolution();
        }

        public GoBoard newBoard() {
            return GoBoard.forTrainingPosition(BOARD_SIZE, black, white);
        }

        /** Return how many opponent stones this one-move solution captures. */
        public int captureCount() {
            GoBoard trial = newBoard();
            return trial.playMove(new Move.Stone(answer, toPlay)).size();
        }

        private void checkSolution() {
            GoBoard trial = newBoard();
            Intersection opposite = toPlay == StoneColor.BLACK
                    ? Intersection.WHITE : Intersection.BLACK;
            if (trial.get(target) != opposite)
                throw new IllegalArgumentException("Puzzle target has wrong color: " + title);
            if (!trial.isLegalMove(answer, toPlay))
                throw new IllegalArgumentException("Puzzle has illegal answer: " + title);
            int removed = trial.playMove(new Move.Stone(answer, toPlay)).size();
            if (removed == 0 || trial.get(target) != Intersection.EMPTY)
                throw new IllegalArgumentException("Puzzle answer does not capture target: " + title);
            // A correctly constructed one-liberty capture question must have
            // a unique winning coordinate, not multiple arbitrary answers.
            GoBoard start = newBoard();
            for (int y = 0; y < BOARD_SIZE; y++) for (int x = 0; x < BOARD_SIZE; x++) {
                Point candidate = new Point(x, y);
                if (candidate.equals(answer) || !start.isLegalMove(candidate, toPlay)) continue;
                GoBoard copy = newBoard();
                copy.playMove(new Move.Stone(candidate, toPlay));
                if (copy.get(target) == Intersection.EMPTY)
                    throw new IllegalArgumentException("Puzzle has multiple one-move captures: " + title);
            }
        }
    }

    private static Point point(String name) {
        if (name == null || name.length() != 2)
            throw new IllegalArgumentException("Invalid lesson coordinate");
        int x = name.charAt(0) - 'a', y = name.charAt(1) - 'a';
        if (x < 0 || y < 0 || x >= BOARD_SIZE || y >= BOARD_SIZE)
            throw new IllegalArgumentException("Lesson point outside 9x9 board");
        return new Point(x, y);
    }

    private static List<Point> points(String names) {
        List<Point> result = new ArrayList<>();
        if (names == null || names.trim().isEmpty()) return result;
        Set<Point> unique = new HashSet<>();
        for (String name : names.trim().split("\\s+")) {
            Point p = point(name);
            if (!unique.add(p)) throw new IllegalArgumentException("Duplicate lesson stone");
            result.add(p);
        }
        return result;
    }

    private static final List<Puzzle> LESSONS = Collections.unmodifiableList(Arrays.asList(
        new Puzzle("第1题 · 中央一气", "黑先，提掉中央白子。",
                "白子只剩下面一口气。", "围住最后一口气，就能提掉白子。",
                StoneColor.BLACK, "cd ed dc", "dd", "dd", "de"),
        new Puzzle("第2题 · 换白棋", "白先，提掉中央黑子。",
                "找到黑子下面的空点。", "黑棋也一样：最后一口气被占，就会被提走。",
                StoneColor.WHITE, "ff", "ef fe gf", "ff", "fg"),
        new Puzzle("第3题 · 两子同气", "黑先，一手提掉相连的两颗白子。",
                "白棋两子连在一起，只剩左下方一口气。", "相连的两颗棋共享气，同时被提走。",
                StoneColor.BLACK, "bd cc dc ed de", "cd dd", "cd", "ce"),
        new Puzzle("第4题 · 白棋破网", "白先，吃掉竖排两颗黑子。",
                "黑棋右边还有一口气。", "找准整块黑棋最后的气，一手提两子。",
                StoneColor.WHITE, "fc fd", "ec gc fb ed fe", "fd", "gd"),
        new Puzzle("第5题 · 边上提子", "黑先，吃掉左边线上的白子。",
                "棋盘边缘本来就少一边的气。", "边上的棋只需封住余下的气便能提掉。",
                StoneColor.BLACK, "ad be", "ae", "ae", "af"),
        new Puzzle("第6题 · 角上提子", "白先，吃掉右下角的黑子。",
                "角上的棋只有两侧可喘气。", "角上棋子的气比中央少，白棋封住最后一气。",
                StoneColor.WHITE, "ii", "hi", "ii", "ih"),
        new Puzzle("第7题 · 弯三子", "黑先，找出三颗白子的共同最后一气。",
                "注意凹进去的交叉点。", "虽然棋形弯曲，只要整块只有一气，也能一手提掉。",
                StoneColor.BLACK, "de ed ge fd gf fg", "ee fe ff", "ee", "ef"),
        new Puzzle("第8题 · 三子一线", "白先，吃掉横向相连的三颗黑子。",
                "三子中间的上方仍有一口气。", "吃子要数整块棋的气，而不是一颗一颗数。",
                StoneColor.WHITE, "cg dg eg", "cf ch bg dh ef eh fg", "dg", "df")
    ));

    public static List<Puzzle> all() { return LESSONS; }
}
