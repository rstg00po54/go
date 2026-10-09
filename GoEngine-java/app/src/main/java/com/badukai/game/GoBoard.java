package com.badukai.game;

import com.badukai.util.DebugLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GoBoard {
    private static final String TAG = "GoBoard";
    private final int size;
    private final Intersection[][] board;
    private final List<Move> moveHistory = new ArrayList<>();
    private Point koPoint;
    private int capturedBlack;
    private int capturedWhite;
    private int consecutivePasses;

    public GoBoard() {
        this(19);
        DebugLog.enter(TAG, "GoBoard in");
    }

    public GoBoard(int size) {
        DebugLog.enter(TAG, "GoBoard in, size=" + size);
        this.size = size;
        this.board = new Intersection[size][size];
        clearBoardOnly();
    }

    /** Fixed handicap placement shared by Android's board and KataGo's GTP setup. */
    public static List<Point> standardHandicapPoints(int size, int count) {
        if (count < 2 || count > 9 || (size != 9 && size != 11 && size != 13 && size != 15 && size != 19))
            throw new IllegalArgumentException("Unsupported handicap: board=" + size + ", stones=" + count);
        int low = size <= 11 ? 2 : 3;
        int high = size - 1 - low, mid = size / 2;
        List<Point> points = new ArrayList<>(count);
        points.add(new Point(high, low));
        points.add(new Point(low, high));
        if (count >= 3) points.add(new Point(high, high));
        if (count >= 4) points.add(new Point(low, low));
        if (count == 5) points.add(new Point(mid, mid));
        if (count >= 6) {
            points.add(new Point(low, mid));
            points.add(new Point(high, mid));
        }
        if (count == 7) points.add(new Point(mid, mid));
        if (count >= 8) {
            points.add(new Point(mid, low));
            points.add(new Point(mid, high));
        }
        if (count == 9) points.add(new Point(mid, mid));
        return points;
    }

    /** Place initial black handicap stones without treating them as played moves. */
    public boolean placeHandicapStones(List<Point> points) {
        if (points == null || points.size() < 2 || points.size() > 9 || !moveHistory.isEmpty()) return false;
        Set<Point> unique = new HashSet<>();
        for (Point point : points) {
            if (point == null || !isInside(point.x, point.y) || get(point) != Intersection.EMPTY || !unique.add(point))
                return false;
        }
        for (Point point : points) placeStone(point, StoneColor.BLACK);
        return true;
    }

    public int getSize() {
        DebugLog.v(TAG, "getSize in, size=" + size);
        return size;
    }

    public boolean isGameOver() {
        DebugLog.enter(TAG, "isGameOver in, consecutivePasses=" + consecutivePasses + ", moveCount=" + moveHistory.size());
        return consecutivePasses >= 2 || (!moveHistory.isEmpty() && moveHistory.get(moveHistory.size() - 1) instanceof Move.Resign);
    }

    public boolean isInside(int x, int y) {
        DebugLog.v(TAG, "isInside in, x=" + x + ", y=" + y + ", size=" + size);
        return x >= 0 && x < size && y >= 0 && y < size;
    }

    public Intersection get(int x, int y) {
        DebugLog.v(TAG, "get in, x=" + x + ", y=" + y);
        if (!isInside(x, y)) return Intersection.EMPTY;
        return board[y][x];
    }

    public Intersection get(Point point) {
        DebugLog.v(TAG, "get in, point=" + point);
        if (point == null || !isInside(point.x, point.y)) return Intersection.EMPTY;
        return board[point.y][point.x];
    }

    public boolean isLegalMove(Point point, StoneColor color) {
        DebugLog.enter(TAG, "isLegalMove in, point=" + point + ", color=" + color + ", koPoint=" + koPoint);
        if (point == null || !isInside(point.x, point.y)) return false;
        if (get(point) != Intersection.EMPTY) return false;
        if (point.equals(koPoint)) return false;

        GoBoard testBoard = copyPosition();
        testBoard.placeStone(point, color);
        List<Point> captures = testBoard.removeDeadStones(color.opposite());
        Set<Point> group = testBoard.getGroup(point);
        return testBoard.hasLiberty(group) || !captures.isEmpty();
    }

    public List<Point> playMove(Move move) {
        DebugLog.enter(TAG, "playMove in, move=" + move);
        if (move instanceof Move.Stone) {
            Move.Stone stone = (Move.Stone) move;
            return playStone(stone.point, stone.color);
        }
        if (move instanceof Move.Pass) {
            consecutivePasses++;
            moveHistory.add(move);
            koPoint = null;
            return new ArrayList<>();
        }
        if (move instanceof Move.Resign) moveHistory.add(move);
        return new ArrayList<>();
    }

    private List<Point> playStone(Point point, StoneColor color) {
        DebugLog.enter(TAG, "playStone in, point=" + point + ", color=" + color);
        if (!isInside(point.x, point.y)) return new ArrayList<>();
        consecutivePasses = 0;
        placeStone(point, color);
        moveHistory.add(new Move.Stone(point, color));
        List<Point> captured = removeDeadStones(color.opposite());
        if (color == StoneColor.BLACK) capturedWhite += captured.size();
        else capturedBlack += captured.size();

        koPoint = null;
        if (captured.size() == 1) {
            Point capturedPoint = captured.get(0);
            Set<Point> group = getGroup(point);
            if (group.size() == 1 && countLiberties(group) == 1) koPoint = capturedPoint;
        }
        return captured;
    }

    private void placeStone(Point point, StoneColor color) {
        DebugLog.v(TAG, "placeStone in, point=" + point + ", color=" + color);
        board[point.y][point.x] = color == StoneColor.BLACK ? Intersection.BLACK : Intersection.WHITE;
    }

    private void removeStone(Point point) {
        DebugLog.v(TAG, "removeStone in, point=" + point);
        board[point.y][point.x] = Intersection.EMPTY;
    }

    private List<Point> removeDeadStones(StoneColor color) {
        DebugLog.v(TAG, "removeDeadStones in, color=" + color);
        List<Point> removed = new ArrayList<>();
        Intersection target = color == StoneColor.BLACK ? Intersection.BLACK : Intersection.WHITE;
        Set<Point> visited = new HashSet<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Point point = new Point(x, y);
                if (get(point) != target || visited.contains(point)) continue;
                Set<Point> group = getGroup(point);
                visited.addAll(group);
                if (!hasLiberty(group)) {
                    for (Point p : group) {
                        removeStone(p);
                        removed.add(p);
                    }
                }
            }
        }
        return removed;
    }

    private Set<Point> getGroup(Point start) {
        DebugLog.v(TAG, "getGroup in, start=" + start);
        Set<Point> group = new HashSet<>();
        if (start == null || !isInside(start.x, start.y)) return group;
        Intersection color = get(start);
        if (color == Intersection.EMPTY) return group;
        ArrayDeque<Point> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Point point = queue.removeFirst();
            if (group.contains(point) || get(point) != color) continue;
            group.add(point);
            queue.addAll(getNeighbors(point));
        }
        return group;
    }

    private boolean hasLiberty(Set<Point> group) {
        DebugLog.v(TAG, "hasLiberty in, groupSize=" + (group == null ? -1 : group.size()));
        for (Point point : group) {
            for (Point neighbor : getNeighbors(point)) {
                if (get(neighbor) == Intersection.EMPTY) return true;
            }
        }
        return false;
    }

    private int countLiberties(Set<Point> group) {
        DebugLog.v(TAG, "countLiberties in, groupSize=" + (group == null ? -1 : group.size()));
        Set<Point> liberties = new HashSet<>();
        for (Point point : group) {
            for (Point neighbor : getNeighbors(point)) {
                if (get(neighbor) == Intersection.EMPTY) liberties.add(neighbor);
            }
        }
        return liberties.size();
    }

    private List<Point> getNeighbors(Point point) {
        DebugLog.v(TAG, "getNeighbors in, point=" + point);
        List<Point> result = new ArrayList<>(4);
        addIfInside(result, point.x - 1, point.y);
        addIfInside(result, point.x + 1, point.y);
        addIfInside(result, point.x, point.y - 1);
        addIfInside(result, point.x, point.y + 1);
        return result;
    }

    private void addIfInside(List<Point> list, int x, int y) {
        DebugLog.v(TAG, "addIfInside in, x=" + x + ", y=" + y);
        if (isInside(x, y)) list.add(new Point(x, y));
    }

    public boolean undo() {
        DebugLog.enter(TAG, "undo in, moveCount=" + moveHistory.size());
        if (moveHistory.isEmpty()) return false;
        List<Move> history = new ArrayList<>(moveHistory.subList(0, moveHistory.size() - 1));
        clear();
        for (Move move : history) playMove(move);
        return true;
    }

    public void clear() {
        DebugLog.enter(TAG, "clear in, moveCount=" + moveHistory.size());
        clearBoardOnly();
        moveHistory.clear();
        koPoint = null;
        capturedBlack = 0;
        capturedWhite = 0;
        consecutivePasses = 0;
    }

    private void clearBoardOnly() {
        DebugLog.v(TAG, "clearBoardOnly in, size=" + size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) board[y][x] = Intersection.EMPTY;
        }
    }

    /** Chinese area scoring on the position after removing KataGo-adjudicated dead stones. */
    public static final class FinalScore {
        public final int blackTerritory, whiteTerritory, blackStones, whiteStones, deadBlack, deadWhite;
        public final double blackPoints, whitePoints;

        private FinalScore(int blackTerritory, int whiteTerritory, int blackStones, int whiteStones,
                           int deadBlack, int deadWhite, double blackPoints, double whitePoints) {
            this.blackTerritory = blackTerritory;
            this.whiteTerritory = whiteTerritory;
            this.blackStones = blackStones;
            this.whiteStones = whiteStones;
            this.deadBlack = deadBlack;
            this.deadWhite = deadWhite;
            this.blackPoints = blackPoints;
            this.whitePoints = whitePoints;
        }
    }

    public FinalScore countChineseScore(Set<Point> deadStones, double komi) {
        boolean[][] removed = new boolean[size][size];
        int deadBlack = 0, deadWhite = 0;
        if (deadStones != null) {
            for (Point point : deadStones) {
                if (point == null || !isInside(point.x, point.y) || removed[point.y][point.x]) continue;
                Intersection stone = get(point);
                if (stone == Intersection.BLACK) deadBlack++;
                else if (stone == Intersection.WHITE) deadWhite++;
                else continue;
                removed[point.y][point.x] = true;
            }
        }

        int blackTerritory = 0, whiteTerritory = 0, blackStones = 0, whiteStones = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (removed[y][x]) continue;
                if (board[y][x] == Intersection.BLACK) blackStones++;
                else if (board[y][x] == Intersection.WHITE) whiteStones++;
            }
        }
        boolean[][] visited = new boolean[size][size];
        int[] dx = {-1, 1, 0, 0}, dy = {0, 0, -1, 1};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (visited[y][x] || (board[y][x] != Intersection.EMPTY && !removed[y][x])) continue;
                ArrayDeque<Point> queue = new ArrayDeque<>();
                queue.add(new Point(x, y));
                visited[y][x] = true;
                int points = 0;
                boolean bordersBlack = false, bordersWhite = false;
                while (!queue.isEmpty()) {
                    Point p = queue.removeFirst();
                    points++;
                    for (int k = 0; k < 4; k++) {
                        int nx = p.x + dx[k], ny = p.y + dy[k];
                        if (!isInside(nx, ny)) continue;
                        Intersection stone = board[ny][nx];
                        if (stone != Intersection.EMPTY && !removed[ny][nx]) {
                            if (stone == Intersection.BLACK) bordersBlack = true;
                            else bordersWhite = true;
                        } else if (!visited[ny][nx]) {
                            visited[ny][nx] = true;
                            queue.add(new Point(nx, ny));
                        }
                    }
                }
                if (bordersBlack && !bordersWhite) blackTerritory += points;
                else if (bordersWhite && !bordersBlack) whiteTerritory += points;
            }
        }

        // Chinese area scoring: living stones + controlled empty points, not prisoners.
        // White gets the configured komi. Dead stones have already been removed above.
        double blackPoints = blackStones + blackTerritory;
        double whitePoints = whiteStones + whiteTerritory + komi;
        return new FinalScore(blackTerritory, whiteTerritory, blackStones, whiteStones,
                deadBlack, deadWhite, blackPoints, whitePoints);
    }

    /** Copy the current game for a read-only preview; subsequent moves affect only the copy. */
    public GoBoard copyForPreview() {
        GoBoard result = copyPosition();
        result.moveHistory.addAll(moveHistory);
        result.capturedBlack = capturedBlack;
        result.capturedWhite = capturedWhite;
        result.consecutivePasses = consecutivePasses;
        return result;
    }

    private GoBoard copyPosition() {
        DebugLog.v(TAG, "copyPosition in, size=" + size + ", koPoint=" + koPoint);
        GoBoard copy = new GoBoard(size);
        for (int y = 0; y < size; y++) System.arraycopy(board[y], 0, copy.board[y], 0, size);
        copy.koPoint = koPoint;
        return copy;
    }

    public int getCapturedBlack() { DebugLog.v(TAG, "getCapturedBlack in, capturedBlack=" + capturedBlack); return capturedBlack; }
    public int getCapturedWhite() { DebugLog.v(TAG, "getCapturedWhite in, capturedWhite=" + capturedWhite); return capturedWhite; }
    public int getMoveCount() { DebugLog.v(TAG, "getMoveCount in, moveCount=" + moveHistory.size()); return moveHistory.size(); }
    public Move getLastMove() { DebugLog.v(TAG, "getLastMove in, moveCount=" + moveHistory.size()); return moveHistory.isEmpty() ? null : moveHistory.get(moveHistory.size() - 1); }
}
