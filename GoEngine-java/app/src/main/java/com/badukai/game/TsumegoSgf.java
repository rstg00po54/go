package com.badukai.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Strict, offline SGF variation reader for attributed Go Game Guru problems.
 * It does NOT infer a solution from a guessed capture point. Only branches
 * explicitly marked C[Correct...] in the original SGF are accepted.
 */
public final class TsumegoSgf {
    private TsumegoSgf() {}

    public static final class Node {
        public final List<Node> children = new ArrayList<>();
        public StoneColor color;
        public Point point;
        public String comment = "";
        public boolean correct;
        public boolean leadsToCorrect;
    }

    public static final class Puzzle {
        public final String assetName, title;
        public final int size;
        public final StoneColor toPlay;
        public final Node root;
        private final List<Point> black, white;

        private Puzzle(String name, int size, StoneColor player, Node root,
                       List<Point> black, List<Point> white) {
            assetName = name;
            title = "Go Game Guru · 初级 " + name.replaceAll("[^0-9]", "");
            this.size = size;
            toPlay = player;
            this.root = root;
            this.black = Collections.unmodifiableList(black);
            this.white = Collections.unmodifiableList(white);
        }

        public GoBoard newBoard() {
            return GoBoard.forTrainingPosition(size, black, white);
        }

        /**
         * Match any author-recorded SGF branch, including wrong answers.
         * Not being present in the SGF is NOT proof a move is bad.
         */
        public Node recordedMove(Node position, Point at, StoneColor player) {
            if (position == null || at == null) return null;
            for (Node child : position.children)
                if (child.color == player && at.equals(child.point)) return child;
            return null;
        }

        /** First legal author-recorded continuation for an attempted move. */
        public Node recordedReply(Node position, StoneColor player, GoBoard board) {
            if (position == null) return null;
            for (Node child : position.children)
                if (child.color == player && child.point != null
                        && board.isLegalMove(child.point, player)) return child;
            return null;
        }

        public Node correctMove(Node position, Point at, StoneColor player) {
            for (Node child : position.children)
                if (child.leadsToCorrect && child.color == player
                        && at.equals(child.point)) return child;
            return null;
        }

        /** Original SGF lists defender replies in author-defined order. */
        public Node defenderReply(Node position, GoBoard board) {
            for (Node child : position.children)
                if (child.leadsToCorrect && child.color == toPlay.opposite()
                        && child.point != null && board.isLegalMove(child.point, child.color))
                    return child;
            return null;
        }

        public Node nextCorrect(Node node) {
            for (Node child : node.children)
                if (child.leadsToCorrect) return child;
            return null;
        }

        public List<Node> principalSolution() {
            List<Node> result = new ArrayList<>();
            Node cursor = root;
            for (int i = 0; i < 80 && !cursor.correct; i++) {
                cursor = nextCorrect(cursor);
                if (cursor == null) break;
                result.add(cursor);
            }
            return result;
        }
    }

    public static Puzzle parse(String filename, String sgf) {
        if (sgf == null || sgf.length() > 100_000)
            throw new IllegalArgumentException("SGF 题目过大或为空");
        Reader reader = new Reader(sgf);
        Node root = reader.tree();
        reader.space();
        if (reader.index != sgf.length() || root.color != null)
            throw new IllegalArgumentException("无效的 SGF 题目根节点");
        if (!reader.board19 || root.children.isEmpty())
            throw new IllegalArgumentException("仅支持原始19路 SGF 死活题");
        if (!reader.foundSuccess || !markCorrectPaths(root))
            throw new IllegalArgumentException("原题没有标记正确变化");
        StoneColor human = null;
        for (Node child : root.children) {
            if (!child.leadsToCorrect) continue;
            if (human == null) human = child.color;
            if (human != child.color)
                throw new IllegalArgumentException("题目首手执子不一致");
        }
        if (human == null) throw new IllegalArgumentException("没有可用解答分支");
        Puzzle result = new Puzzle(filename, 19, human, root,
                reader.black, reader.white);
        // Do not ship corrupt principal variations or fictitious solutions.
        GoBoard trial = result.newBoard();
        List<Node> solution = result.principalSolution();
        if (solution.isEmpty() || !solution.get(solution.size() - 1).correct)
            throw new IllegalArgumentException("缺少完整正解");
        for (Node node : solution) {
            if (node.point == null || node.color == null
                    || !trial.isLegalMove(node.point, node.color))
                throw new IllegalArgumentException("正解变化出现非法落点");
            trial.playMove(new Move.Stone(node.point, node.color));
        }
        return result;
    }

    private static boolean markCorrectPaths(Node node) {
        boolean successful = node.correct;
        for (Node child : node.children) successful |= markCorrectPaths(child);
        node.leadsToCorrect = successful;
        return successful;
    }

    private static Point point(String value) {
        if (value.length() != 2) throw new IllegalArgumentException("非法SGF坐标");
        int x = value.charAt(0) - 'a';
        int y = value.charAt(1) - 'a';
        if (x < 0 || x >= 19 || y < 0 || y >= 19)
            throw new IllegalArgumentException("SGF落点超出19路棋盘");
        return new Point(x, y);
    }

    private static final class Reader {
        private final String sgf;
        int index;
        boolean board19, foundSuccess;
        final List<Point> black = new ArrayList<>(), white = new ArrayList<>();

        Reader(String input) { sgf = input; }
        void space() {
            while (index < sgf.length() && Character.isWhitespace(sgf.charAt(index)))
                index++;
        }
        boolean take(char ch) {
            space();
            if (index < sgf.length() && sgf.charAt(index) == ch) {
                index++;
                return true;
            }
            return false;
        }
        void expect(char ch) {
            if (!take(ch)) throw new IllegalArgumentException("SGF语法错误: " + ch);
        }
        String propertyValue() {
            expect('[');
            StringBuilder out = new StringBuilder();
            boolean closed = false;
            while (index < sgf.length()) {
                char c = sgf.charAt(index++);
                if (c == ']') { closed = true; break; }
                if (c == '\\' && index < sgf.length()) {
                    c = sgf.charAt(index++);
                    if (c == '\r' || c == '\n') {
                        if (c == '\r' && index < sgf.length() && sgf.charAt(index) == '\n')
                            index++;
                        continue;
                    }
                }
                out.append(c);
            }
            if (!closed) throw new IllegalArgumentException("SGF字段未关闭");
            return out.toString();
        }
        Node tree() {
            expect('(');
            Node first = null, tail = null;
            while (take(';')) {
                Node node = new Node();
                if (first == null) first = node;
                if (tail != null) tail.children.add(node);
                tail = node;
                while (true) {
                    space();
                    if (index >= sgf.length() || !Character.isUpperCase(sgf.charAt(index)))
                        break;
                    int start = index;
                    while (index < sgf.length() && Character.isUpperCase(sgf.charAt(index)))
                        index++;
                    String key = sgf.substring(start, index);
                    space();
                    if (index >= sgf.length() || sgf.charAt(index) != '[')
                        throw new IllegalArgumentException("SGF 属性没有值: " + key);
                    do {
                        String value = propertyValue();
                        if ("SZ".equals(key)) board19 = "19".equals(value);
                        else if ("AB".equals(key)) black.add(point(value));
                        else if ("AW".equals(key)) white.add(point(value));
                        else if ("B".equals(key) || "W".equals(key)) {
                            if (node.color != null)
                                throw new IllegalArgumentException("SGF一手包含两个落子");
                            node.color = "B".equals(key) ? StoneColor.BLACK : StoneColor.WHITE;
                            node.point = point(value);
                        } else if ("C".equals(key)) {
                            node.comment = value;
                            if (value.trim().toLowerCase(Locale.ROOT).startsWith("correct")) {
                                node.correct = true;
                                foundSuccess = true;
                            }
                        }
                        space();
                    } while (index < sgf.length() && sgf.charAt(index) == '[');
                }
            }
            if (first == null) throw new IllegalArgumentException("SGF题目没有节点");
            while (true) {
                space();
                if (index >= sgf.length() || sgf.charAt(index) != '(') break;
                tail.children.add(tree());
            }
            expect(')');
            return first;
        }
    }
}
