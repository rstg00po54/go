#!/usr/bin/env python3
"""Conservative, deterministic black-stone policy for endgame UI benchmarks.

This is a move-selection heuristic, not a Go engine. It only interacts with
BoardTracker's simulation; the real app must independently accept each move.
"""


def groups(board, color, stones=None):
    stones = board.stones if stones is None else stones
    seen, found = set(), []
    for position, stone in stones.items():
        if stone != color or position in seen:
            continue
        group, liberties = board.group(stones, position)
        seen.update(group)
        found.append((group, liberties))
    return found


def rank_moves(board, rejected=None, limit=6):
    """Rank real legal black moves, favoring group survival over filling points.

    Corner opening -> extensions -> protect weak groups / capture -> avoid
    self-atari, filling own eyes and hopeless enemy territory.
    """
    rejected = rejected or set()
    size = board.size
    occupied = board.stones
    black = [p for p, color in occupied.items() if color == "B"]
    white = [p for p, color in occupied.items() if color == "W"]
    opening = (2 if size <= 11 else 3)
    stars = [(opening, opening), (size-1-opening, opening),
             (opening, size-1-opening), (size-1-opening, size-1-opening)]
    endangered = [(group, next(iter(libs))) for group, libs in groups(board, "B")
                  if len(libs) == 1]
    before_atari = sum(len(group) for group, _ in endangered)
    ranked = []
    for y in range(size):
        for x in range(size):
            point = (x, y)
            if point in rejected:
                continue
            after = board.simulate("B", point)
            if after is None:
                continue
            if not black:
                opening_distance = min(abs(x-sx) + abs(y-sy) for sx, sy in stars)
                score = 100 - 12 * opening_distance
                ranked.append((point, float(score)))
                continue

            group, libs = board.group(after, point)
            remaining = len(libs)
            captured = sum(1 for pos in white if pos not in after)
            if remaining < 2 and captured == 0:
                # Letting one of our own groups die is almost never useful.
                continue
            own_neighbours = sum(occupied.get(q) == "B" for q in board.neighbours(point))
            enemy_neighbours = sum(occupied.get(q) == "W" for q in board.neighbours(point))
            near_black = min(abs(x-a) + abs(y-b) for a, b in black)
            near_white = min((abs(x-a) + abs(y-b) for a, b in white), default=size*2)
            weak_after = sum(len(g) for g, liberties in groups(board, "B", after)
                             if len(liberties) <= 1)
            rescued = max(0, before_atari-weak_after)
            opponent_atari = sum(len(g) for g, liberties in groups(board, "W", after)
                                 if len(liberties) == 1)
            score = (min(5, remaining) * 6 + captured * 28 +
                     rescued * 45 + opponent_atari * 5 -
                     weak_after * 28)
            # Extend safe groups, leaving room to build two eyes.
            score += {1: 9, 2: 17, 3: 11, 4: 0}.get(near_black, -6 * (near_black-3))
            if near_white == 1 and not captured:
                score -= 19
            elif near_white == 2:
                score -= 5
            if enemy_neighbours >= 2 and not captured:
                score -= 18 * enemy_neighbours
            if own_neighbours >= 3 and not captured:
                score -= 75  # avoid playing inside already safe eyes
            if remaining == 2:
                score -= 16
            # After the opening, prefer established friendly space to invading
            # behind solid enemy stones.
            if len(black) < size:
                score += 3 * min((abs(x-sx)+abs(y-sy) for sx,sy in stars))
                score -= 2 * max(0, near_black-2)
            ranked.append((point, float(score)))
    return sorted(ranked, key=lambda item: (-item[1], item[0][1], item[0][0]))[:limit]


def assess_final_position(area_line, board):
    """Report representativeness separately from a correctly finished game.

    Area scoring is authoritative for on-board stone/territory counts. A full
    game can PASS its endgame pipeline yet be UNREPRESENTATIVE of a contested
    two-sided territory position.
    """
    import re
    match = re.search(r"B stones=(\d+) territory=(\d+), W stones=(\d+) territory=(\d+)",
                      area_line or "")
    tracker_b = sum(v == "B" for v in board.stones.values())
    tracker_w = sum(v == "W" for v in board.stones.values())
    result = {"tracker_black_stones": tracker_b, "tracker_white_stones": tracker_w}
    if match is None:
        result.update({"benchmark_quality": "UNKNOWN", "quality_reason": "缺少终局中国数目日志"})
        return result
    black, black_area, white, white_area = map(int, match.groups())
    # Area scoring removes predicted dead stones from the live stone counts.
    # The tracker holds the pre-adjudication board, so include the dead stones
    # when checking whether the Python board followed the same actual game.
    dead_match = re.search(r"dead B=(\d+) W=(\d+)", area_line)
    dead_black, dead_white = map(int, dead_match.groups()) if dead_match else (0, 0)
    minimum_stones = 3 if board.size <= 9 else 5
    minimum_area = 2 if board.size <= 9 else 4
    plausible = (black >= minimum_stones and white >= minimum_stones
                 and black_area >= minimum_area and white_area >= minimum_area)
    result.update({"black_stones": black, "white_stones": white,
                   "black_territory": black_area, "white_territory": white_area,
                   "dead_black": dead_black, "dead_white": dead_white,
                   "tracker_matches_score": black + dead_black == tracker_b
                                            and white + dead_white == tracker_w,
                   "benchmark_quality": "REPRESENTATIVE" if plausible else "UNREPRESENTATIVE",
                   "quality_reason": ("双方均有存活棋子及地盘" if plausible else
                                      "一方棋子太少或无独立地盘，不能代表争夺激烈的终局")})
    return result
