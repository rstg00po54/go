package com.badukai.game;

public abstract class Move {
    public final StoneColor color;

    protected Move(StoneColor color) {
        this.color = color;
    }

    public static final class Stone extends Move {
        public final Point point;

        public Stone(Point point, StoneColor color) {
            super(color);
            this.point = point;
        }
    }

    public static final class Pass extends Move {
        public Pass(StoneColor color) {
            super(color);
        }
    }

    public static final class Resign extends Move {
        public Resign(StoneColor color) {
            super(color);
        }
    }
}
