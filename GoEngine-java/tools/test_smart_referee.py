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
import java.util.List;
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
    private static boolean hasReason(EndgameAdjudicator.Result r, EndgameAdjudicator.Reason reason) {
        for (EndgameAdjudicator.Finding item : r.findings) if (item.reason == reason) return true;
        return false;
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
        Set<Point> pending = new HashSet<>(uncertain.needsReview);
        ok(SmartReferee.countGroups(board, pending) == 1, "pending review counted by groups");
        SmartReferee.decideGroup(board, marked, pending, new Point(0,0), false);
        ok(marked.isEmpty() && pending.isEmpty(), "live decision clears dead and review marks");
        pending.addAll(uncertain.needsReview);
        SmartReferee.decideGroup(board, marked, pending, new Point(1,0), true);
        ok(marked.size() == 2 && pending.isEmpty(), "dead decision marks full group and resolves review");
        SmartReferee.ScoreAudit agrees = SmartReferee.audit(board, marked, 5.5, 5.5, 0);
        ok(!agrees.needsVerification && agrees.difference < 0.001, "same NN and confirmed score needs no warning");
        SmartReferee.ScoreAudit conflicts = SmartReferee.audit(board, new HashSet<Point>(), 5.5, 5.5, 0);
        ok(conflicts.needsVerification && conflicts.difference > 5.0, "opposite NN and final score must warn");
        SmartReferee.ScoreAudit noNn = SmartReferee.audit(board, marked, Double.NaN, 5.5, 0);
        ok(!noNn.needsVerification, "manual fallback must work without NN estimates");
        ok(board.get(0,0) == Intersection.BLACK, "review decisions never mutate real board");
        for (int size : new int[]{13,19}) {
            GoBoard empty = new GoBoard(size);
            SmartReferee.Analysis result = SmartReferee.analyze(empty, new float[size*size]);
            ok(result.groups == 0, "empty board should have zero groups");
        }
        GoBoard stopped = twoBlackChain();
        EndgameAdjudicator.Result unfinished = EndgameAdjudicator.analyze(stopped, new float[81]);
        ok(unfinished.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED, "ongoing game cannot be finished");
        stopped.playMove(new Move.Pass(StoneColor.BLACK));
        stopped.playMove(new Move.Pass(StoneColor.WHITE));
        ok(stopped.canResumeAfterPasses(), "two consecutive passes can be resumed");
        EndgameAdjudicator.Result calm = EndgameAdjudicator.analyze(stopped, new float[81]);
        ok(calm.verdict == EndgameAdjudicator.Verdict.READY_TO_COUNT,
                "quiet double-pass game may enter manual scoring");
        ok(calm.summary().contains("确认数子"), "settled game must not display unfinished warning");
        ok(stopped.resumeAfterPasses() && !stopped.isGameOver(), "resume restores playing state");
        ok(stopped.getMoveCount() == 2, "resume removes exactly the two passes");
        ok(!stopped.canResumeAfterPasses(), "normal game cannot resume after passes");
        GoBoard urgent = new GoBoard(9);
        urgent.playMove(new Move.Stone(new Point(1,1), StoneColor.BLACK));
        urgent.playMove(new Move.Stone(new Point(0,1), StoneColor.WHITE));
        urgent.playMove(new Move.Stone(new Point(1,0), StoneColor.WHITE));
        urgent.playMove(new Move.Stone(new Point(2,1), StoneColor.WHITE));
        urgent.playMove(new Move.Pass(StoneColor.BLACK));
        urgent.playMove(new Move.Pass(StoneColor.WHITE));
        EndgameAdjudicator.Result tactical = EndgameAdjudicator.analyze(urgent, new float[81]);
        ok(tactical.verdict == EndgameAdjudicator.Verdict.CONTINUE_RECOMMENDED,
                "first-move tactical capture should recommend continuation");
        ok(tactical.summary().contains("继续对局"), "forced tactical continuation keeps its stronger warning");
        ok(hasReason(tactical, EndgameAdjudicator.Reason.FIRST_MOVE_TACTIC),
                "must explain atari and supply a concrete location");
        ok(urgent.get(1,1) == Intersection.BLACK, "tactical simulation must not mutate actual board");
        float[] doomedOwnership = new float[81];
        doomedOwnership[1*9+1] = 0.98f;
        EndgameAdjudicator.Result doomedAtari = EndgameAdjudicator.analyze(urgent,doomedOwnership);
        ok(doomedAtari.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED,
                "a strongly NN-dead atari group needs human confirmation, not forced continuation");
        ok(doomedAtari.summary().contains("建议继续下"),
                "an unresolved terminal dispute must suggest continuing play");
        ok(!hasReason(doomedAtari,EndgameAdjudicator.Reason.FIRST_MOVE_TACTIC),
                "extending a proposed dead chain is not proof the game must resume");
        GoBoard shared = new GoBoard(3);
        for (int y=0;y<3;y++) {
            shared.playMove(new Move.Stone(new Point(0,y),StoneColor.BLACK));
            shared.playMove(new Move.Stone(new Point(2,y),StoneColor.WHITE));
        }
        shared.playMove(new Move.Stone(new Point(1,2),StoneColor.BLACK));
        shared.playMove(new Move.Pass(StoneColor.BLACK));
        shared.playMove(new Move.Pass(StoneColor.WHITE));
        float[] sekiOwner = new float[9];
        for (int y=0;y<3;y++) { sekiOwner[y*3] = -1; sekiOwner[y*3+2] = 1; }
        sekiOwner[7] = -1;
        EndgameAdjudicator.Result seki = EndgameAdjudicator.analyze(shared,sekiOwner);
        ok(hasReason(seki, EndgameAdjudicator.Reason.POSSIBLE_SEKI), "shared two-liberty seki-like shape flagged");
        ok(seki.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED, "suspected seki requires confirmation");
        ok(seki.summary().contains("建议继续下"),
                "seki-like unresolved dispute must suggest continuing play");
        GoBoard ko = new GoBoard(9);
        ko.playMove(new Move.Stone(new Point(1,1), StoneColor.BLACK));
        for (Point p : new Point[]{new Point(0,2),new Point(2,2),new Point(1,3)})
            ko.playMove(new Move.Stone(p,StoneColor.BLACK));
        for (Point p : new Point[]{new Point(0,1),new Point(2,1),new Point(1,0)})
            ko.playMove(new Move.Stone(p,StoneColor.WHITE));
        ko.playMove(new Move.Pass(StoneColor.BLACK));
        ko.playMove(new Move.Pass(StoneColor.WHITE));
        EndgameAdjudicator.Result koResult = EndgameAdjudicator.analyze(ko,new float[81]);
        ok(hasReason(koResult, EndgameAdjudicator.Reason.POSSIBLE_KO),
                "single-stone recapture ko pattern should be detected");
        ok(koResult.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED,
                "ko with an available recapture is a review-or-resume choice, not a mandatory continuation");
        ok(!hasReason(koResult, EndgameAdjudicator.Reason.FIRST_MOVE_TACTIC),
                "the same ko exchange must not be counted twice as non-ko life/death urgency");
        GoBoard dame = new GoBoard(9);
        dame.playMove(new Move.Stone(new Point(0,0), StoneColor.BLACK));
        dame.playMove(new Move.Stone(new Point(2,0), StoneColor.WHITE));
        dame.playMove(new Move.Pass(StoneColor.BLACK));
        dame.playMove(new Move.Pass(StoneColor.WHITE));
        EndgameAdjudicator.Result neutral = EndgameAdjudicator.analyze(dame,new float[81]);
        ok(hasReason(neutral, EndgameAdjudicator.Reason.POSSIBLE_DAME),
                "unfilled neutral point must be identified");
        ok(neutral.verdict == EndgameAdjudicator.Verdict.READY_TO_COUNT,
                "unfilled dame alone should not force continuation");
        ok(EndgameAdjudicator.analyze(dame,null).verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED,
                "missing NN should ask for review rather than invent life/death");
        // Regression: real 9x9 SGF previously missed a large upper-left white chain.
        // Synthetic NN confidently labels every existing group as friendly-owned,
        // so this verifies the independent tactical risk check, NOT model accuracy.
        String sgfMoves = "ee eg cf fc gd dc gc cd gg cg ce bg fb gh ae eb fd bd ec dd ef fg cb db bb ad be ab hh hg gf hi ca ea ac bc aa ba dg dh aa df de ba ih ig aa ac fa ba ed ii fh fi hf eh if ff fe ag aa hh dg ba df bf aa ch af ba -- hb aa hc hd gb ic ib id ba -- --";
        GoBoard real = new GoBoard(9);
        StoneColor turn = StoneColor.BLACK;
        for (String token : sgfMoves.split(" ")) {
            if ("--".equals(token)) real.playMove(new Move.Pass(turn));
            else {
                Point p = new Point(token.charAt(0)-'a', token.charAt(1)-'a');
                ok(real.isLegalMove(p, turn), "fixture SGF must be legal at " + token);
                real.playMove(new Move.Stone(p, turn));
            }
            turn = turn.opposite();
        }
        ok(real.isGameOver(), "real fixture must end with both sides passing");
        Set<Point> upperLeftWhite = SmartReferee.group(real, new Point(0,3));
        ok(upperLeftWhite.size() == 11, "fixture has expected connected white chain of 11");
        float[] friendlyOwner = new float[81];
        for (int y=0;y<9;y++) for (int x=0;x<9;x++) {
            Intersection stone = real.get(x,y);
            friendlyOwner[y*9+x] = stone == Intersection.BLACK ? -0.99f
                    : stone == Intersection.WHITE ? 0.99f : 0.0f;
        }
        SmartReferee.Analysis risk = SmartReferee.analyze(real, friendlyOwner);
        ok(risk.suggestedGroups == 0, "friendly NN cannot silently declare any group dead");
        ok(risk.vulnerableGroups >= 1, "tight, large chain must be flagged for independent review");
        ok(risk.vulnerableReview.containsAll(upperLeftWhite), "risk scanner must flag all 11 white stones");
        ok(risk.needsReview.containsAll(upperLeftWhite), "Android referee should highlight entire chain");
        // F2/G1/J2 are enclosed by several *white* chains. Black cannot
        // legally invade them, so the nearby six-stone white chain must not
        // be called risky solely because its three liberties are shared eyes.
        Set<Point> lowerRightWhite = SmartReferee.group(real, new Point(6,7));
        ok(lowerRightWhite.size() == 6, "fixture contains right-side white six-stone group");
        ok(!risk.vulnerableReview.contains(new Point(6,7)),
                "do not flag shared friendly eyes as a low-liberty group");
        ok(risk.vulnerableGroups == 1,
                "with friendly NN only the upper-left white 11 should be low-liberty review");
        for (Point eye : new Point[]{new Point(5,7),new Point(6,8),new Point(8,7)})
            ok(!real.isLegalMove(eye,StoneColor.BLACK),
                    "the lower right contains non-invadable white eye candidates");

        EndgameAdjudicator.Result triage = EndgameAdjudicator.analyze(real, friendlyOwner);
        ok(triage.verdict == EndgameAdjudicator.Verdict.REVIEW_REQUIRED,
                "large risky chain must require review, not assume ready to count");
        ok(hasReason(triage, EndgameAdjudicator.Reason.LOW_LIBERTY_LARGE_GROUP),
                "referee must provide a human-readable risk reason");
        // CGOS 006_12 deterministic liberty/capture evidence. It is the ORDER
        // of the moves that changes the result, not NN ownership thresholds.
        GoBoard whiteFirst = real.copyForPreview();
        Point c7 = new Point(2,2), a9 = new Point(0,0),
              d9 = new Point(3,0), h5 = new Point(7,4);
        ok(whiteFirst.isLegalMove(c7,StoneColor.WHITE), "white can first fill C7");
        whiteFirst.playMove(new Move.Stone(c7,StoneColor.WHITE));
        ok(whiteFirst.isLegalMove(a9,StoneColor.BLACK), "black can capture B9 at A9");
        List<Point> whiteLossAtA9 = whiteFirst.playMove(new Move.Stone(a9,StoneColor.BLACK));
        ok(whiteLossAtA9.size() == 1 && whiteLossAtA9.contains(new Point(1,0)),
                "black A9 captures the single B9 stone, creating a new black liberty");
        ok(!whiteFirst.isLegalMove(d9,StoneColor.WHITE),
                "white D9 becomes suicide after black captures B9");
        ok(whiteFirst.isLegalMove(h5,StoneColor.WHITE), "white H5 is legal but cannot rescue upper left");
        whiteFirst.playMove(new Move.Stone(h5,StoneColor.WHITE));
        ok(whiteFirst.isLegalMove(d9,StoneColor.BLACK), "black can finish capture at D9");
        List<Point> capturedTwelve = whiteFirst.playMove(new Move.Stone(d9,StoneColor.BLACK));
        ok(capturedTwelve.size() == 12 && capturedTwelve.contains(new Point(4,0))
                && capturedTwelve.contains(c7),
                "black D9 must capture all 12 upper-left white stones after white C7");

        GoBoard blackFirst = real.copyForPreview();
        ok(blackFirst.isLegalMove(c7,StoneColor.BLACK), "black C7 is legal on the recorded final board");
        blackFirst.playMove(new Move.Stone(c7,StoneColor.BLACK));
        ok(blackFirst.isLegalMove(d9,StoneColor.WHITE), "white D9 is the saving capture after black C7");
        List<Point> capturedFour = blackFirst.playMove(new Move.Stone(d9,StoneColor.WHITE));
        ok(capturedFour.size() == 4 && capturedFour.contains(c7)
                && capturedFour.contains(new Point(2,1)),
                "white D9 must capture the four black stones after black C7");

        // The 006_12 score discrepancy is caused by interacting dead-group
        // choices: removing B9 alone does little, but removing it together with
        // the upper-left 11 can convert a large neutral region into black area.
        Set<Point> knownDead = SmartReferee.group(real, new Point(6,1));
        Set<Point> koStone = SmartReferee.group(real, new Point(1,0));
        Set<Point> bottomRight = SmartReferee.group(real, new Point(7,6));
        Set<Point> unresolved = new HashSet<>(upperLeftWhite);
        unresolved.addAll(koStone);
        unresolved.addAll(bottomRight);
        GoBoard.FinalScore baseCount = real.countChineseScore(knownDead,7.0,0);
        ok(Math.abs(baseCount.whitePoints - baseCount.blackPoints - 8.0) < 0.001,
                "fixture starts at tentative W+8 after NN-suggested dead");
        SmartReferee.ScoreWhatIf whatIf = SmartReferee.scoreWhatIf(real,knownDead,unresolved,7.0,0);
        ok(whatIf.reviewGroups == 3 && whatIf.exhaustive(), "enumerate all three undecided chains");
        ok(whatIf.winnerCanChange(), "uncertain groups can reverse the winner");
        ok(Math.abs(whatIf.maxWhiteLead - 8.0) < 0.001, "base scenario W+8");
        Set<Point> whiteKoAndUpper = new HashSet<>(knownDead);
        whiteKoAndUpper.addAll(koStone);
        whiteKoAndUpper.addAll(upperLeftWhite);
        GoBoard.FinalScore joint = real.countChineseScore(whiteKoAndUpper,7.0,0);
        ok(Math.abs(joint.whitePoints - joint.blackPoints + 20.0) < 0.001,
                "reviewing B9 with upper-left white 11 yields hypothetical B+20");


        // Two distinct enclosed eye points do NOT trigger the crude low-liberty
        // warning, even for a large chain. This is a heuristic safety regression.
        GoBoard withEyes = new GoBoard(9);
        for (int y=0;y<9;y++) for (int x=0;x<9;x++) {
            if ((x == 2 && y == 2) || (x == 6 && y == 6)) continue;
            withEyes.playMove(new Move.Stone(new Point(x,y), StoneColor.BLACK));
        }
        SmartReferee.Analysis twoEyes = SmartReferee.analyze(withEyes, new float[81]);
        ok(twoEyes.vulnerableGroups == 0, "two-enclosed-eye group should not be called low-liberty risk");
        System.out.println("SmartReferee + EndgameAdjudicator smoke PASS");
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
            "StoneColor.java", "SmartReferee.java", "EndgameAdjudicator.java",
        )]
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(dest),
                        str(source), str(stub)] + [str(x) for x in paths], check=True)
        subprocess.run(["java", "-cp", str(dest), "com.badukai.game.SmartRefereeSmoke"], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
