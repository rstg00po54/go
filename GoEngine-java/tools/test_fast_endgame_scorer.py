#!/usr/bin/env python3
"""Compile the real FastEndgameScorer.java with tiny board stubs and run Java assertions.

No Android SDK / ADB / network required; JDK 17 javac and java suffice.
"""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

PROJECT = pathlib.Path(__file__).resolve().parents[1]
PRODUCTION = PROJECT / "app/src/main/java/com/badukai/game/FastEndgameScorer.java"

STUBS = {
    "Intersection.java": """
package com.badukai.game;
public enum Intersection { EMPTY, BLACK, WHITE }
""",
    "Point.java": """
package com.badukai.game;
public final class Point {
    public final int x,y;
    public Point(int x,int y){this.x=x;this.y=y;}
    public boolean equals(Object obj){return obj instanceof Point && x==((Point)obj).x && y==((Point)obj).y;}
    public int hashCode(){return 31*x+y;}
    public String toGtp(int size){return ""+"ABCDEFGHJKLMNOPQRST".charAt(x)+(size-y);}
}
""",
    "GoBoard.java": """
package com.badukai.game;
public final class GoBoard {
    private final int size;
    private final Intersection[][] grid;
    public GoBoard(int s){size=s;grid=new Intersection[s][s];
        for(int y=0;y<s;y++)for(int x=0;x<s;x++)grid[y][x]=Intersection.EMPTY;}
    public int getSize(){return size;}
    public Intersection get(int x,int y){return grid[y][x];}
    public void put(int x,int y,Intersection stone){grid[y][x]=stone;}
}
""",
    "FastEndgameTest.java": """
package com.badukai.game;
public final class FastEndgameTest {
    static void ok(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void main(String[] args) {
        GoBoard b=new GoBoard(9);
        float[] own=new float[81];
        b.put(1,1,Intersection.BLACK); own[1*9+1]=0.95f;
        b.put(3,3,Intersection.BLACK); own[3*9+3]=0.90f;
        b.put(4,3,Intersection.BLACK); own[3*9+4]=0.10f;
        b.put(2,0,Intersection.WHITE); own[2]=-0.85f;
        b.put(7,7,Intersection.WHITE); own[7*9+7]=0.90f;
        FastEndgameScorer.Result r=FastEndgameScorer.suggest(b,own);
        ok(r.deadStones.size()==2,"expected exactly 2 marked stones");
        ok(r.deadStones.contains(new Point(1,1)),"isolated black dead");
        ok(r.deadStones.contains(new Point(2,0)),"white dead and top row correct");
        ok(!r.deadStones.contains(new Point(3,3)),"do not partly mark a chain");
        ok(!r.deadStones.contains(new Point(4,3)),"do not partly mark a chain");
        ok(!r.deadStones.contains(new Point(7,7)),"living white remains");
        ok(r.deadGroups==2 && r.scannedGroups==4,"group counters");
        ok("C9 B8".equals(r.deadVertices(9)),"deterministic GTP ordering: "+r.deadVertices(9));
        own[1*9+1]=Float.NaN;
        ok(!FastEndgameScorer.suggest(b,own).deadStones.contains(new Point(1,1)),"invalid NN point must not auto-die");
        boolean threw=false;
        try {FastEndgameScorer.suggest(b,new float[80]);}
        catch(IllegalArgumentException expected){threw=true;}
        ok(threw,"reject ownership with wrong dimensions");
        System.out.println("FastEndgameScorer: 10 Java checks passed");
    }
}
""",
}


class NativeJavaScorerTests(unittest.TestCase):
    def test_fast_endgame_java(self):
        javac, java = shutil.which("javac"), shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK (javac, java) unavailable")
        self.assertTrue(PRODUCTION.is_file(), "Production scorer missing")
        with tempfile.TemporaryDirectory(prefix="fast_endgame_") as tmp:
            root = pathlib.Path(tmp)
            package = root / "com/badukai/game"
            package.mkdir(parents=True)
            sources = []
            for name, contents in STUBS.items():
                p = package / name
                p.write_text(contents, encoding="utf-8")
                sources.append(str(p))
            sources.append(str(PRODUCTION))
            subprocess.run([javac, "-encoding", "UTF-8", "-d", str(root)] + sources,
                           check=True, text=True, capture_output=True, timeout=45)
            result = subprocess.run([java, "-cp", str(root), "com.badukai.game.FastEndgameTest"],
                                    check=True, text=True, capture_output=True, timeout=20)
            self.assertIn("10 Java checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
