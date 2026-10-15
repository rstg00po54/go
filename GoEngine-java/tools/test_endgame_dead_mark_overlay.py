#!/usr/bin/env python3
"""Guards against dead-stone ownership markers being covered or shown midgame."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
VIEW = ROOT / "app/src/main/java/com/badukai/ui/GoBoardView.java"
MAIN = ROOT / "app/src/main/java/com/badukai/MainActivity.java"


class EndgameDeadMarksTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.view = VIEW.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")

    def test_markers_are_rendered_after_stones_not_under_them(self):
        drawing = self.view.split("protected void onDraw(Canvas canvas)", 1)[1].split(
            "private void drawGrid(", 1)[0]
        self.assertLess(drawing.index("drawStones(canvas, n"), drawing.index("drawDeadStoneMarks(canvas, n)"))
        self.assertIn("if (previewBoard == null)", drawing)
        marks = self.view.split("private void drawDeadStoneMarks(", 1)[1].split(
            "private void drawRecommendations(", 1)[0]
        self.assertIn("deadStoneMarks.isEmpty()", marks)
        self.assertIn("ownershipMode == OWNERSHIP_OFF", marks)
        self.assertIn("stone == Intersection.EMPTY", marks)
        self.assertIn("stone == Intersection.BLACK && estimate <= 0f", marks)
        self.assertIn("stone == Intersection.WHITE && estimate >= 0f", marks)
        self.assertIn("canvas.drawRoundRect(", marks)

    def test_no_stale_markers_after_new_ownership_or_move(self):
        setter = self.view.split("public void setOwnership(float[] values)", 1)[1].split(
            "public void setDeadStoneMarks(", 1)[0]
        self.assertGreaterEqual(setter.count("deadStoneMarks = Collections.emptySet();"), 2)
        self.assertIn("new HashSet<>(points)", self.view)
        self.assertIn("boardView.setOwnership(null)", self.main)

    def test_final_nn_auto_renders_but_midgame_does_not_infer_dead(self):
        finish = self.main.split("private void finishByScore(", 1)[1].split(
            "private String formatFastEndgameResult(", 1)[0]
        self.assertIn("displayEndgameOwnership(result)", finish)
        overlay = self.main.split("private int displayEndgameOwnership(", 1)[1].split(
            "private String territorySummary(", 1)[0]
        self.assertIn("if (!board.isGameOver()) return 0;", overlay)
        self.assertIn("FastEndgameScorer.suggest(board, result.whiteOwnership)", overlay)
        self.assertIn("boardView.setDeadStoneMarks(dead.deadStones)", overlay)
        self.assertIn("verified=false", overlay)
        referee = self.main.split("private void showQuickNnRefereeEstimate(", 1)[1].split(
            "private String", 1)[0]
        self.assertIn("if (gameEnded && result.whiteOwnership != null", referee)


if __name__ == "__main__":
    unittest.main()
