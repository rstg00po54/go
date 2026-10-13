#!/usr/bin/env python3
"""Compile and smoke-test Android-independent referee rules code with the JDK.

No Gradle, Android SDK, JNI, OpenCL, devices, or network calls required.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "app/src/main/java/com/badukai/game"
JAVA = """package com.badukai.game;
import java.util.HashSet;
import java.util.Set;

public final class SmartRefereeSmoke {
    private static void ok(boolean x, String reason) {
        if (!x) throw new AssertionError(reason);
    }
    private static GoBoard twoBlackChain() {
        GoBoard board = new GoBoard(9);
        board.playMove(new Move.Stone(new Point(0,0), StoneColor.BLACK));
        board.playMove(new Move.Stone(new Point(1,0), StoneColor.BLACK));
        return board;
    }
    public static void main(String[] args) {
        GoBoard board = twoBlackChain();
        float[] owner = new float[81];
        owner[0] = 0.95f; owner[1] = 0.94f;
        SmartReferee.Analysis strong = SmartReferee.analyze(board, owner);
        ok(strong.groups == 1, "connected two stones must be one group");
        ok(strong.suggestedGroups == 1, "both black stones predicted strongly dead");
        ok(strong.suggestedDead.size() == 2, "entire chain must be marked");
        ok(strong.reviewGroups == 0, "confident dead group should not be uncertain");
        Set<Point> marked = new HashSet<>(strong.suggestedDead);
        SmartReferee.toggleGroup(board, marked, new Point(1,0));
        ok(marked.isEmpty(), "tap must restore entire chain, not one stone");
        SmartReferee.toggleGroup(board, marked, new Point(0,0));
        ok(marked.size() == 2, "second tap must mark entire group again");
        ok(SmartReferee.toGtpVertices(marked,9).equals("A9 B9"), "GTP coordinates must be ordered");
        GoBoard.FinalScore liveScore = board.countChineseScore(new HashSet<Point>(), 5.5, 0);
        GoBoard.FinalScore deadScore = board.countChineseScore(marked, 5.5, 0);
        ok(deadScore.blackPoints <= liveScore.blackPoints, "removing black cannot increase black area");
        ok(SmartReferee.resultText(deadScore).startsWith("W+"), "empty board with komi favors white");
        ok(board.get(0,0) == Intersection.BLACK, "referee must not change actual board");
        owner[0] = 0.10f;
        SmartReferee.Analysis uncertain = SmartReferee.analyze(board,owner);
        ok(uncertain.suggestedGroups == 0, "mixed-confidence ownership cannot auto-mark dead");
        ok(uncertain.reviewGroups == 1, "mixed-confidence chain must be reviewed");
        ok(uncertain.needsReview.size() == 2, "review entire chain");
        try {
            SmartReferee.analyze(board, new float[2]);
            throw new AssertionError("wrong ownership dimensions must fail");
        } catch (IllegalArgumentException expected) {}
        for (int size : new int[]{13,19}) {
            GoBoard empty = new GoBoard(size);
            SmartReferee.Analysis result = SmartReferee.analyze(empty, new float[size*size]);
            ok(result.groups == 0, "empty board should have zero groups");
        }
        System.out.println("SmartReferee smoke PASS");
    }
}
"""
DEBUGLOG = """package com.badukai.util;
public final class DebugLog {
    public static void v(String tag, String msg) {}
    public static void enter(String tag, String msg) {}
}
"""


def main():
    if not shutil.which("javac") or not shutil.which("java"):
        print("ERROR: javac/java not installed (JDK 17 needed)", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory(prefix="smart_referee_") as tmp:
        folder = Path(tmp)
        source = folder / "SmartRefereeSmoke.java"
        source.write_text(JAVA, encoding="utf-8")
        stub = folder / "DebugLog.java"
        stub.write_text(DEBUGLOG, encoding="utf-8")
        dest = folder / "classes"
        dest.mkdir()
        paths = [GAME / name for name in (
            "GoBoard.java", "Intersection.java", "Move.java", "Point.java",
            "StoneColor.java", "SmartReferee.java",
        )]
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(dest),
                        str(source), str(stub)] + [str(x) for x in paths], check=True)
        subprocess.run(["java", "-cp", str(dest), "com.badukai.game.SmartRefereeSmoke"], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
