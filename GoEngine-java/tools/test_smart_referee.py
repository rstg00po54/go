#!/usr/bin/env python3
"""JDK-only regression for NN Chinese-area point estimation (historical filename).

The former SmartReferee, EndgameAdjudicator and manual-scoring tests are removed.
No Android SDK, device, GTP, JNI, OpenCL or network is required.
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT/"app/src/main/java/com/badukai/game"

JAVA = """package com.badukai.game;
import java.util.Arrays;

public final class NnAreaSmoke {
    private static void ok(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void bad(float[] values, int size, double komi, int handicap) {
        try {
            NnAreaScore.estimate(values,size,komi,handicap);
            throw new AssertionError("Invalid NN inputs should be rejected");
        } catch (IllegalArgumentException expected) {}
    }
    public static void main(String[] args) {
        float[] zero = new float[81];
        NnAreaScore equal = NnAreaScore.estimate(zero,9,5.5,0);
        ok(Math.abs(equal.blackPoints-40.5)<1e-6, "neutral black 40.5");
        ok(Math.abs(equal.whitePoints-46.0)<1e-6, "neutral white 46 with komi");
        float[] white = new float[81];
        Arrays.fill(white,1f);
        NnAreaScore allWhite = NnAreaScore.estimate(white,9,0,2);
        ok(allWhite.blackPoints==0 && allWhite.whitePoints==83, "handicap compensation");
        float[] black = new float[81];
        Arrays.fill(black,-1f);
        NnAreaScore allBlack = NnAreaScore.estimate(black,9,7,0);
        ok(allBlack.blackPoints==81 && allBlack.whitePoints==7, "all black");
        zero[0] = -1;
        NnAreaScore mixed = NnAreaScore.estimate(zero,9,0,0);
        ok(Math.abs(mixed.blackPoints-41.0)<1e-6, "mixed black");
        ok(Math.abs(mixed.whitePoints-40.0)<1e-6, "mixed white");
        for (int size : new int[]{9,11,13,15,19}) {
            float[] data = new float[size*size];
            Arrays.fill(data,0.25f);
            float[] snapshot = data.clone();
            NnAreaScore score = NnAreaScore.estimate(data,size,7.5,0);
            ok(Math.abs(score.blackPoints+score.whitePoints-(size*size+7.5))<1e-5,
                    "area-point conservation");
            ok(Arrays.equals(snapshot,data),"read-only NN estimate");
        }
        bad(null,9,7,0);
        bad(new float[80],9,7,0);
        bad(new float[81],1,7,0);
        bad(new float[81],9,Double.NaN,0);
        bad(new float[81],9,7,-1);
        float[] nan = new float[81]; nan[17] = Float.NaN;
        bad(nan,9,7,0);
        float[] invalid = new float[81]; invalid[3] = 1.2f;
        bad(invalid,9,7,0);
        System.out.println("NN area inference regression PASS");
    }
}
"""


def main():
    if not shutil.which("javac") or not shutil.which("java"):
        print("ERROR: JDK javac/java not installed",file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory(prefix="nn_area_smoke_") as tmp:
        root = Path(tmp)
        main = root/"NnAreaSmoke.java"
        main.write_text(JAVA,encoding="utf-8")
        output = root/"classes"
        output.mkdir()
        subprocess.run(["javac","-encoding","UTF-8","-d",str(output),str(main),
                        str(GAME/"NnAreaScore.java")],check=True)
        subprocess.run(["java","-cp",str(output),"com.badukai.game.NnAreaSmoke"],check=True)
    return 0


if __name__=="__main__":
    sys.exit(main())
