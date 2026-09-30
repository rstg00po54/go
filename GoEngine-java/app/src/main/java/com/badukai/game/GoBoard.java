package com.badukai.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GoBoard {
    private final int size;
    private final Intersection[][] board;
    private final List<Move> moveHistory = new ArrayList<>();
    private Point koPoint;
    private int capturedBlack;
    private int capturedWhite;
    private int consecutivePasses;

    public GoBoard() {
        this(19);
    }

    public GoBoard(int size) {
        this.size = size;
        this.board = new Intersection[size][size];
        clearBoardOnly();
    }

    public int getSize() {
        return size;
    }

    public boolean isGameOver() {
        return consecutivePasses >= 2 || (!moveHistory.isEmpty() && moveHistory.get(moveHistory.size() - 1) instanceof Move.Resign);
    }

    public boolean isInside(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }

    public Intersection get(int x, int y) {
        if (!isInside(x, y)) return Intersection.EMPTY;
        return board[y][x];
    }

    public Intersection get(Point point) {
        if (point == null || !isInside(point.x, point.y)) return Intersection.EMPTY;
        return board[point.y][point.x];
    }

    public boolean isLegalMove(Point point, StoneColor color) {
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
        if (move instanceof Move.Resign) {
            moveHistory.add(move);
        }
        return new ArrayList<>();
    }

    private List<Point> playStone(Point point, StoneColor color) {
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
        board[point.y][point.x] = color == StoneColor.BLACK ? Intersection.BLACK : Intersection.WHITE;
    }

    private void removeStone(Point point) {
        board[point.y][point.x] = Intersection.EMPTY;
    }

    private List<Point> removeDeadStones(StoneColor color) {
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
        for (Point point : group) {
            for (Point neighbor : getNeighbors(point)) {
                if (get(neighbor) == Intersection.EMPTY) return true;
            }
        }
        return false;
    }

    private int countLiberties(Set<Point> group) {
        Set<Point> liberties = new HashSet<>();
        for (Point point : group) {
            for (Point neighbor : getNeighbors(point)) {
                if (get(neighbor) == Intersection.EMPTY) liberties.add(neighbor);
            }
        }
        return liberties.size();
    }

    private List<Point> getNeighbors(Point point) {
        List<Point> result = new ArrayList<>(4);
        addIfInside(result, point.x - 1, point.y);
        addIfInside(result, point.x + 1, point.y);
        addIfInside(result, point.x, point.y - 1);
        addIfInside(result, point.x, point.y + 1);
        return result;
    }

    private void addIfInside(List<Point> list, int x, int y) {
        if (isInside(x, y)) list.add(new Point(x, y));
    }

    public boolean undo() {
        if (moveHistory.isEmpty()) return false;
        List<Move> history = new ArrayList<>(moveHistory.subList(0, moveHistory.size() - 1));
        clear();
        for (Move move : history) playMove(move);
        return true;
    }

    public void clear() {
        clearBoardOnly();
        moveHistory.clear();
        koPoint = null;
        capturedBlack = 0;
        capturedWhite = 0;
        consecutivePasses = 0;
    }

    private void clearBoardOnly() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) board[y][x] = Intersection.EMPTY;
        }
    }

    private GoBoard copyPosition() {
        GoBoard copy = new GoBoard(size);
        for (int y = 0; y < size; y++) {
            System.arraycopy(board[y], 0, copy.board[y], 0, size);
        }
        copy.koPoint = koPoint;
        return copy;
    }

    public int getCapturedBlack() { return capturedBlack; }
    public int getCapturedWhite() { return capturedWhite; }
    public int getMoveCount() { return moveHistory.size(); }
    public Move getLastMove() { return moveHistory.isEmpty() ? null : moveHistory.get(moveHistory.size() - 1); }
}
