package com.badukai.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Original offline 9x9 exercises: eight single-step captures, followed
 * by four two-step atari-and-chase variations with legal defender responses.
 * Scripted variations are checked against GoBoard on construction. No network,
 * KataGo search, or third-party copyrighted question bank is involved.
 */
public final class TsumegoLessons {
    public static final int BOARD_SIZE = 9;
    private TsumegoLessons() {}

    public static final class Puzzle {
        public final String title, instruction, hint, explanation;
        public final StoneColor toPlay;
        /** First correct move for compatibility with the original one-move lessons. */
        public final Point answer, target;
        private final List<Point> black, white;
        private final List<Point> answers, replies;

        private Puzzle(String title, String instruction, String hint, String explanation,
                       StoneColor toPlay, String black, String white,
                       String target, String answer) {
            this(title, instruction, hint, explanation, toPlay, black, white,
                    target, answer, "");
        }

        /** Multiple learner moves separated by one scripted defender response. */
        private Puzzle(String title, String instruction, String hint, String explanation,
                       StoneColor toPlay, String black, String white,
                       String target, String moves, String defenses) {
            this.title = title;
            this.instruction = instruction;
            this.hint = hint;
            this.explanation = explanation;
            this.toPlay = toPlay;
            this.black = Collections.unmodifiableList(points(black));
            this.white = Collections.unmodifiableList(points(white));
            this.target = point(target);
            this.answers = Collections.unmodifiableList(points(moves));
            this.replies = Collections.unmodifiableList(points(defenses));
            if (answers.isEmpty() || answers.size() != replies.size() + 1)
                throw new IllegalArgumentException("Invalid life-and-death variation: " + title);
            this.answer = answers.get(0);
            checkSolution();
        }

        public GoBoard newBoard() {
            return GoBoard.forTrainingPosition(BOARD_SIZE, black, white);
        }

        /** Number of moves made by the student (automatic answers excluded). */
        public int steps() { return answers.size(); }

        public Point answerAt(int step) { return answers.get(step); }

        /** Defender's forced response following a non-final student move. */
        public Point replyAfter(int step) { return replies.get(step); }

        /** Captures achieved at the end of the verified variation. */
        public int captureCount() {
            GoBoard trial = newBoard();
            int captured = 0;
            for (int i = 0; i < answers.size(); i++) {
                captured += trial.playMove(new Move.Stone(answers.get(i), toPlay)).size();
                if (i < replies.size())
                    trial.playMove(new Move.Stone(replies.get(i), toPlay.opposite()));
            }
            return captured;
        }

        private void checkSolution() {
            GoBoard trial = newBoard();
            Intersection opponent = toPlay == StoneColor.BLACK
                    ? Intersection.WHITE : Intersection.BLACK;
            if (trial.get(target) != opponent)
                throw new IllegalArgumentException("Puzzle target has wrong color: " + title);
            for (int i = 0; i < answers.size(); i++) {
                Point move = answers.get(i);
                if (!trial.isLegalMove(move, toPlay))
                    throw new IllegalArgumentException("Illegal student move at " + i + ": " + title);
                int removed = trial.playMove(new Move.Stone(move, toPlay)).size();
                if (i == answers.size() - 1) {
                    if (removed == 0 || trial.get(target) != Intersection.EMPTY)
                        throw new IllegalArgumentException("Answer does not capture target: " + title);
                } else {
                    if (trial.get(target) != opponent)
                        throw new IllegalArgumentException("Target captured too early: " + title);
                    Point reply = replies.get(i);
                    if (!trial.isLegalMove(reply, toPlay.opposite()))
                        throw new IllegalArgumentException("Illegal defender response: " + title);
                    trial.playMove(new Move.Stone(reply, toPlay.opposite()));
                    if (trial.get(target) != opponent)
                        throw new IllegalArgumentException("Target lost before completion: " + title);
                }
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
                StoneColor.WHITE, "cg dg eg", "cf ch bg dh ef eh fg", "dg", "df"),
        new Puzzle("第9题 · 打吃再追", "黑先，两手吃掉中央白子。对方会应一手。",
                "先从上方打吃，对方往下逃时，再堵最后一气。",
                "先打吃逼白棋下长，再封住延伸后最后的一气。这是两步计算。",
                StoneColor.BLACK, "cd ed ce ee", "dd", "dd", "dc df", "de"),
        new Puzzle("第10题 · 白棋追击", "白先，两手吃掉中央黑子。",
                "先从上面打吃；黑棋向下跑，你再追住。",
                "第一手打吃并非已经吃子；看清对方长出后整块的新气。",
                StoneColor.WHITE, "ff", "ef gf eg gg", "ff", "fe fh", "fg"),
        new Puzzle("第11题 · 左上角追杀", "黑先，追住角上的白棋，需要下两手。",
                "角上的逃跑路线很少，先封右边。",
                "从角上逼迫对方沿边长，再将最后的逃路封住。",
                StoneColor.BLACK, "bb", "aa", "aa", "ba ac", "ab"),
        new Puzzle("第12题 · 右下角收气", "白先，两手提掉右下角黑棋。",
                "先占右下角左侧一气，黑棋只能沿边延伸。",
                "角上只有很少的气。先打吃，待对方延伸后再追一手。",
                StoneColor.WHITE, "ii", "hh", "ii", "hi ig", "ih")
    ));

    public static List<Puzzle> all() { return LESSONS; }
}
