#!/usr/bin/env python3
"""Full 9x9 Android Go match and real two-pass settlement timing.

This module is loaded by test_app_ui.py --full-game. No APK changes needed.
Results reflect the actual MainActivity / KataGoEngine logcat timestamps.
"""
import re
import time

from bench_app_ai import log_time
from test_app_ui import TestFailure, BOUNDS

GTP_LETTERS = "ABCDEFGHJKLMNOPQRST"
LOG_EVENT = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+[VDIWEF]/(MainActivity|KataGoEngine)\(\s*\d+\):\s*(.*)$")


def from_gtp(move, size):
    """Convert KataGo GTP coordinates into Android top-left (x,y)."""
    text = move.upper().strip()
    if len(text) < 2 or text[0] not in GTP_LETTERS:
        return None
    try:
        x, row = GTP_LETTERS.index(text[0]), int(text[1:])
    except ValueError:
        return None
    return (x, size - row) if 0 <= x < size and 1 <= row <= size else None


class BoardTracker:
    """Simple capture + suicide + immediate ko safety model for tap selection."""
    def __init__(self, size):
        self.size = size
        self.stones = {}
        self.history = [frozenset()]

    def neighbours(self, point):
        x, y = point
        return [(a, b) for a, b in ((x-1,y),(x+1,y),(x,y-1),(x,y+1))
                if 0 <= a < self.size and 0 <= b < self.size]

    def group(self, board, point):
        color, found, liberties, todo = board[point], set(), set(), [point]
        while todo:
            loc = todo.pop()
            if loc in found:
                continue
            found.add(loc)
            for neighbour in self.neighbours(loc):
                if neighbour not in board:
                    liberties.add(neighbour)
                elif board[neighbour] == color and neighbour not in found:
                    todo.append(neighbour)
        return found, liberties

    def simulate(self, color, point):
        if point in self.stones:
            return None
        if not (0 <= point[0] < self.size and 0 <= point[1] < self.size):
            return None
        next_board = dict(self.stones)
        next_board[point] = color
        opposite = "W" if color == "B" else "B"
        for neighbour in self.neighbours(point):
            if next_board.get(neighbour) == opposite:
                group, liberties = self.group(next_board, neighbour)
                if not liberties:
                    for loc in group:
                        next_board.pop(loc, None)
        if not self.group(next_board, point)[1]:
            return None
        snapshot = frozenset(next_board.items())
        # Prevent an immediate recapture, consistent with the app rules.
        if len(self.history) >= 2 and snapshot == self.history[-2]:
            return None
        return next_board

    def legal(self, color, point):
        return self.simulate(color, point) is not None

    def play(self, color, point):
        if point is None:  # pass
            self.history.append(frozenset(self.stones.items()))
            return
        result = self.simulate(color, point)
        if result is None:
            raise TestFailure(f"日志落子与本地棋盘跟踪不一致: {color} {point}")
        self.stones = result
        self.history.append(frozenset(result.items()))


def score_timings(log_text, year=None):
    """Compute stage wall times; transitions include some Java sequencing overhead."""
    import datetime
    year = datetime.datetime.now().year if year is None else year
    events = []
    for line in log_text.splitlines():
        hit = LOG_EVENT.match(line)
        if hit:
            events.append((log_time(hit.group(1), year), hit.group(2), hit.group(3)))
    starts = [i for i, (_, tag, m) in enumerate(events)
              if tag == "MainActivity" and "finishByScore in" in m]
    if not starts:
        raise ValueError("没有发现 finishByScore，棋局未进入双方停一手数目流程")
    tail = events[starts[-1]:]
    def first(predicate):
        return next((stamp for stamp, tag, msg in tail if predicate(tag, msg)), None)
    begin = first(lambda tag, m: tag == "MainActivity" and "finishByScore in" in m)
    score = first(lambda tag, m: tag == "KataGoEngine" and "sendCommandSync in, command=final_score" in m)
    dead = first(lambda tag, m: tag == "KataGoEngine" and "sendCommandSync in, command=final_status_list dead" in m)
    raw_nn = first(lambda tag, m: tag == "KataGoEngine" and "sendCommandSync in, command=kata-raw-nn 0" in m)
    render = first(lambda tag, m: tag == "MainActivity" and
                   ("render in, message=黑" in m or "render in, message=终局数子" in m))
    chinese = first(lambda tag, m: tag == "MainActivity" and "Chinese area score" in m)
    area_line = next((msg for _, tag, msg in tail
                      if tag == "MainActivity" and "Chinese area score" in msg), None)
    final_gtp = next((m.group(1) for _, tag, msg in tail
                      if tag == "KataGoEngine"
                      for m in [re.search(r"parseGtpResponse in, response==((?:[BW]\+[0-9.]+)|0)", msg)]
                      if m is not None), None)
    position_ms = next((int(m.group(1)) for _, tag, msg in tail
                        if tag == "KataGoEngine" and "Position evaluation" in msg
                        for m in [re.search(r"elapsedMs=(\d+)", msg)] if m is not None), None)
    if not (begin is not None and score is not None and dead is not None
            and raw_nn is not None and render is not None and chinese is not None):
        raise ValueError("终局日志不完整，缺少 final_score / dead / raw-nn / Chinese area score / 最终显示")
    if not (begin <= score <= dead <= raw_nn <= render):
        raise ValueError("终局阶段时间顺序异常")
    def ms(start, end):
        return round((end-start)/1000, 3)
    return {"total_ms": ms(begin, render),
            "before_final_score_ms": ms(begin, score),
            "final_score_ms": ms(score, dead),
            "dead_stones_ms": ms(dead, raw_nn),
            "raw_nn_to_render_ms": ms(raw_nn, render),
            "position_evaluation_ms": position_ms,
            "gtp_final_score": final_gtp, "chinese_area_log": area_line,
            "has_chinese_area_score": True}


def _board_coordinates(runner, size):
    bounds = runner.geometry.get("boardView")
    if bounds is None:
        root = runner.hierarchy()
        item = runner.node(root, rid="boardView")
        if item is None or not BOUNDS.fullmatch(item.get("bounds", "")):
            raise TestFailure("整局测试找不到棋盘 boardView")
        bounds = list(map(int, BOUNDS.fullmatch(item.get("bounds")).groups()))
    x1,y1,x2,y2 = bounds
    side = min(x2-x1, y2-y1)
    outer = 2.0 * runner.density()
    cell = (side - 2*outer) / (size-1+2*0.68)
    pad = outer + 0.68*cell
    def screen(point):
        x,y = point
        return (round(x1+pad+x*cell), round(y1+pad+y*cell))
    return screen


def _await_ai(runner, before, max_wait):
    """Return either a new AI move, or termination entering final_score."""
    def finished(state):
        if state["final_enter"] > before["final_enter"]:
            return ("scoring", None)
        if len(state["ai_replies"]) > len(before["ai_replies"]):
            response = state["ai_replies"][-1]
            if response.lower() == "resign":
                return ("resign", response)
            if response.lower() == "pass":
                if state["ai_pass_ready"] > before["ai_pass_ready"]:
                    return ("pass", response)
            elif state["turns"] > before["turns"]:
                return ("stone", response)
        return None
    return runner.wait_log(finished, max_wait, "等待整局 AI 回棋/停一手/终局")


def run_full_game(runner, size, backend, max_actions=110, settlement_timeout=180):
    """Play real alternating moves until consecutive passes and completed scoring."""
    if not runner.use_log_wait:
        raise TestFailure("整局模式需要 MainActivity DebugLog，当前 APK 无法进行可靠的自动终局检查")
    board = BoardTracker(size)
    screen = _board_coordinates(runner, size)
    # Spread human moves across interior and edges, deterministic for replay.
    coords = [(x,y) for y in range(size) for x in range(size)]
    mid = size // 2
    coords.sort(key=lambda p: (abs(p[0]-mid)+abs(p[1]-mid), p[1], p[0]))
    black_moves, ai_moves, rejected, player_passes, ai_passes = 0, 0, 0, 0, 0
    started = time.monotonic()
    game_baseline = runner.read_log_state()
    rejected_points = set()
    last_ai_pass = False
    end_type = None
    for action in range(max_actions):
        state = runner.read_log_state()
        # The player must pass immediately after an AI pass to end the game.
        candidates = [] if last_ai_pass else [p for p in coords
                                                if p not in rejected_points and board.legal("B", p)]
        # Avoid spending minutes re-tapping a row of UI-rejected points.
        candidates = candidates[:6]
        accepted = False
        before = state
        for point in candidates:
            x,y = screen(point)
            runner.tap(x,y)
            def acknowledged(current):
                return current["thinking"] > before["thinking"] or None
            try:
                runner.wait_log(acknowledged, 1.5, f"整局确认落子 {point}")
            except TestFailure as exc:
                if "日志等待超过" not in str(exc):
                    raise
                rejected += 1
                rejected_points.add(point)
                continue
            board.play("B",point)
            black_moves += 1
            accepted = True
            runner.log(f"整局 #{action+1} 执黑落子 {point}")
            break
        if not accepted:
            # When no legal points remain, or the AI just passed, make an
            # actual Android player pass. Never synthesize KataGo GTP moves.
            before = runner.read_log_state()
            runner.click(rid="passButton")
            runner.wait_log(lambda x: x["player_passes"] > before["player_passes"] or None,
                            5, "确认整局玩家停一手按钮已响应")
            board.play("B",None)
            player_passes += 1
            runner.log(f"整局 #{action+1} 玩家停一手")
        result, move = _await_ai(runner, before, runner.args.think_timeout)
        if result == "scoring":
            end_type = "two_passes"
            break
        if result == "resign":
            raise TestFailure("AI 提前认输，未触发 final_score；这局无法用于测试数目耗时")
        if result == "pass":
            board.play("W",None)
            ai_passes += 1
            last_ai_pass = True
            runner.log(f"整局 #{action+1} AI 停一手（下一手玩家应停一手结束）")
        else:
            point = from_gtp(move,size)
            if point is None:
                raise TestFailure(f"AI 返回无效 GTP 坐标 {move!r}")
            board.play("W",point)
            ai_moves += 1
            last_ai_pass = False
            runner.log(f"整局 #{action+1} AI 落子 {move} → {point}")
    if end_type != "two_passes":
        raise TestFailure(f"整局 {max_actions} 次玩家行动内未出现连续两次停一手；未伪造结算结果")
    runner.log("连续停一手已触发 final_score，开始等待终局三个阶段")
    runner.wait_log(lambda x: x["score_summary"] > game_baseline["score_summary"]
                    and x["final_render"] > game_baseline["final_render"] or None,
                    settlement_timeout, "等待实际地盘数目完成")
    raw = runner.adb("logcat", "-d", "-v", "time", timeout=45)
    try:
        stages = score_timings(raw)
    except ValueError as exc:
        raise TestFailure("整局结算日志检查失败：" + str(exc)) from exc
    result = {"size": size, "backend": backend, "status": "PASS",
              "end_type": end_type, "black_stones_played": black_moves,
              "ai_stones_played": ai_moves, "rejected_taps": rejected,
              "player_passes": player_passes, "ai_passes": ai_passes,
              "match_elapsed_s": round(time.monotonic()-started, 3),
              "settlement": stages}
    runner.log(f"终局结算完成：总={stages['total_ms']}ms, score={stages['final_score_ms']}ms, "
               f"dead={stages['dead_stones_ms']}ms, rawNN={stages['raw_nn_to_render_ms']}ms")
    return result
