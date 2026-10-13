#!/usr/bin/env python3
"""Offline regression checks: larger corpus sampler, provenance and safe cleanup."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fetch_real_sgf_corpus import list_source_paths, populate, validate_download, SOURCES
from benchmark_real_sgf_nn_android import collect_games


class CorpusDownloaderTests(unittest.TestCase):
    def test_tree_filtering_and_deterministic_order(self):
        raw = [
            {"type": "blob", "path": "DataSet/2015-Go9-small/a.sgf"},
            {"type": "blob", "path": "DataSet/2015-Go9-small/b.sgf"},
            {"type": "tree", "path": "DataSet/2015-Go9-small/not_a_file.sgf"},
            {"type": "blob", "path": "DataSet/2019-1255games/c.sgf"},
            {"type": "blob", "path": "DataSet/2019-1255games/ignore.txt"},
        ]
        tree = {"truncated": False, "tree": raw}
        one = list_source_paths(tree)
        two = list_source_paths(tree)
        self.assertEqual(one, two)
        self.assertEqual(len(one["archived9"]), 2)
        self.assertEqual(len(one["archived19"]), 1)
        with self.assertRaisesRegex(ValueError, "truncated"):
            list_source_paths(dict(tree, truncated=True))

    def test_actual_scored_game_acceptance_and_mismatch(self):
        games, _ = collect_games()
        c = games[0]
        metadata = validate_download(c["name"], 9, "DataSet/2015-Go9-small/c.sgf", c["sgf"])
        self.assertEqual(metadata["official_re"], "B+2")
        self.assertEqual(metadata["size"], 9)
        self.assertIn("/LatinScribe/BetaGoZero/", metadata["source"])
        with self.assertRaisesRegex(ValueError, "Wrong board size"):
            validate_download(c["name"], 13, "DataSet/2015-Go9-small/c.sgf", c["sgf"])
        with self.assertRaisesRegex(ValueError, "Only numeric scored"):
            validate_download("resign", 9, "x.sgf", c["sgf"].replace("RE[B+2]", "RE[B+R]"))

    def test_populate_preserves_only_accepted_and_cleans_only_managed_stale(self):
        # The expanded bundled corpus contains more than 100 entries, mostly
        # 9x9. Never assume games[-1] is 19x19: the default limit is a slice.
        games, _ = collect_games(limit=1000)
        g9 = next(g for g in games if g["size"] == 9)
        g19 = next(g for g in games if g["size"] == 19)
        self.assertNotEqual(g9["sha256"], g19["sha256"])
        self.assertEqual(validate_download("b.sgf", 19, "DataSet/2019-1255games/b.sgf",
                                           g19["sgf"])["size"], 19)
        contents = {"a.sgf": g9["sgf"], "bad.sgf": g9["sgf"].replace(
            "RE[B+2]", "RE[W+Resign]"), "b.sgf": g19["sgf"]}
        fake_paths = {
            "archived9": ["DataSet/2015-Go9-small/bad.sgf", "DataSet/2015-Go9-small/a.sgf"],
            "archived19": ["DataSet/2019-1255games/b.sgf"],
        }
        def fake_fetch(url, **kwargs):
            return contents[Path(url).name]
        with tempfile.TemporaryDirectory() as temp, patch(
                "fetch_real_sgf_corpus.fetch", side_effect=fake_fetch):
            folder = Path(temp)
            (folder / "archived9_999_old.sgf").write_text("stale")
            (folder / "personal.sgf").write_text("never touch")
            manifest = populate(folder, fake_paths, 1, 1, workers=1)
            self.assertEqual(manifest["unique_games"], 2, msg=json.dumps(
                manifest["statistics"], ensure_ascii=False, indent=2))
            self.assertEqual(len(list(folder.glob("archived9_*.sgf"))), 1)
            self.assertEqual(len(list(folder.glob("archived19_*.sgf"))), 1)
            self.assertFalse((folder / "archived9_999_old.sgf").exists())
            self.assertTrue((folder / "personal.sgf").exists())
            self.assertEqual(len(json.loads((folder / "manifest.json").read_text())["games"]), 2)
            cases, skipped = collect_games(folder, limit=1000)
            self.assertEqual(len(cases), 2)
            self.assertEqual(len(skipped), 1)  # Unrelated user-owned personal.sgf is retained but rejected.
            self.assertTrue(all(c["source"].startswith("https://raw.githubusercontent.com/")
                                for c in cases))
            self.assertTrue(all(c["sha256"] for c in cases))


if __name__ == "__main__":
    unittest.main()
