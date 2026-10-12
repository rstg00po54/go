#!/usr/bin/env bash
# Standalone Java 17 smoke test: no Gradle, Android SDK, APK or KataGo needed.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/app/src/main/java"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stub/com/badukai/util" "$TMP/classes"
cat > "$TMP/stub/com/badukai/util/DebugLog.java" <<'JAVA'
package com.badukai.util;
public final class DebugLog {
    public static void enter(String tag, String msg) {}
    public static void v(String tag, String msg) {}
}
JAVA
cat > "$TMP/TestTsumegoCore.java" <<'JAVA'
import com.badukai.game.*;
import com.badukai.training.TsumegoProblems;

public final class TestTsumegoCore {
    public static void main(String[] args) {
        int n = 0;
        for (TsumegoProblems.Problem p : TsumegoProblems.all()) {
            GoBoard board = p.createBoard();
            if (board.getMoveCount() != 0) throw new AssertionError("setup counted as moves: " + p.title);
            if (!board.isLegalMove(p.answer, p.turn)) throw new AssertionError("illegal answer: " + p.title);
            if (!TsumegoProblems.matchesAnswer(p, p.answer)) throw new AssertionError("answer mismatch");
            if (TsumegoProblems.matchesAnswer(p, new Point(0, 0))) throw new AssertionError("wrong answer accepted");
            GoBoard original = p.createBoard();
            int captures = board.playMove(new Move.Stone(p.answer, p.turn)).size();
            if (!TsumegoProblems.goalReached(p, board, captures)) throw new AssertionError("goal missed: " + p.title);
            if (!board.undo()) throw new AssertionError("undo failed: " + p.title);
            if (board.getMoveCount() != 0) throw new AssertionError("undo move count");
            for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) {
                if (board.get(x, y) != original.get(x, y)) throw new AssertionError("setup not restored: " + p.title);
            }
            n++;
        }
        System.out.println("TSUMEGO_CORE_PASS count=" + n);
    }
}
JAVA

javac -encoding UTF-8 -d "$TMP/classes" \
    "$TMP/stub/com/badukai/util/DebugLog.java" \
    "$SOURCE/com/badukai/game/GoBoard.java" \
    "$SOURCE/com/badukai/game/Intersection.java" \
    "$SOURCE/com/badukai/game/Move.java" \
    "$SOURCE/com/badukai/game/Point.java" \
    "$SOURCE/com/badukai/game/StoneColor.java" \
    "$SOURCE/com/badukai/training/TsumegoProblems.java" \
    "$TMP/TestTsumegoCore.java"
java -cp "$TMP/classes" TestTsumegoCore
