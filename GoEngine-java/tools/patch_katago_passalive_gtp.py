#!/usr/bin/env python3
"""Inject *test-only* GTP command into a temporary upstream KataGo v1.15 checkout.

Uses the ORIGINAL BoardHistory::endGameIfAllPassAlive, copies the board history,
does not play/pass/mutate the engine's live game.
"""
import sys
from pathlib import Path

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/katago-src/cpp/command/gtp.cpp")
source = path.read_text(encoding="utf-8")
def replace_once(old, new):
    global source
    if source.count(old) != 1:
        raise RuntimeError(f"KataGo v1.15 source mismatch: {old[:70]!r} count={source.count(old)}")
    source = source.replace(old, new, 1)

replace_once('  "final_score",', '  "kata-test-all-pass-alive",\n  "final_score",')
replace_once('using namespace std;\n','using namespace std;\n#include <chrono>\n')
native = r'''
    else if(command == "kata-test-all-pass-alive") {
      int repeats = 1000;
      if(pieces.size() > 1 || (pieces.size() == 1 &&
          (!Global::tryStringToInt(pieces[0],repeats) || repeats < 1 || repeats > 5000))) {
        responseIsError = true;
        response = "usage: kata-test-all-pass-alive [repeats 1..5000]";
      }
      else {
        const Board& board = engine->bot->getRootBoard();
        const BoardHistory& rootHist = engine->bot->getRootHist();
        // Board-only sufficiency: start a fresh, nonterminal History from this
        // exact position, avoiding SGFs already terminated by double PASS.
        Color area[Board::MAX_ARR_SIZE];
        board.calculateArea(area,false,false,false,rootHist.rules.multiStoneSuicideLegal);
        int unknown = 0;
        for(int y = 0; y < board.y_size; y++) {
          for(int x = 0; x < board.x_size; x++) {
            Loc loc = Location::getLoc(x,y,board.x_size);
            if(area[loc] != C_BLACK && area[loc] != C_WHITE)
              unknown++;
          }
        }
        int finished = 0, scored = 0;
        float resultScore = 0.0f;
        long long sumNs = 0;
        for(int i = 0; i < repeats; i++) {
          BoardHistory hist(board,P_BLACK,rootHist.rules,0);
          auto start = std::chrono::steady_clock::now();
          hist.endGameIfAllPassAlive(board);
          auto stop = std::chrono::steady_clock::now();
          sumNs += std::chrono::duration_cast<std::chrono::nanoseconds>(stop-start).count();
          if(i == 0) {
            finished = hist.isGameFinished ? 1 : 0;
            scored = hist.isScored ? 1 : 0;
            if(hist.isScored)
              resultScore = hist.finalWhiteMinusBlackScore;
          }
        }
        response = Global::strprintf(
          "pass_alive_finished %d scored %d unknown %d area %d score_white_minus_black %.1f repeats %d average_ns %.2f",
          finished,scored,unknown,board.x_size*board.y_size,
          resultScore,repeats,((double)sumNs)/repeats);
      }
    }

'''
replace_once('    else if(command == "final_score") {',native+'    else if(command == "final_score") {')
path.write_text(source, encoding="utf-8")
print(f"Patched temporary KataGo GTP source: {path}")
