package com.badukai.game;

public enum StoneColor {
    BLACK, WHITE;

    public StoneColor opposite() {
        return this == BLACK ? WHITE : BLACK;
    }

    public String toGtp() {
        return this == BLACK ? "black" : "white";
    }
}
