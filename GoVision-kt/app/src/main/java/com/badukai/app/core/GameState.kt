package com.badukai.app.core

/**
 * 一局对弈的状态机：管理历史、回合、贴目、让子、悔棋与回放导航。
 * 采用"从初始重建到目标步"的方式做任意跳转，简单稳健。
 * 每手落子颜色记录在 [RecordedMove.stone] 中，回放时直接取用，无需再推算。
 */
class GameState(
    val boardSize: Int = 19,
    var komi: Float = 7.5f,
    /** 让子数（0 表示分先） */
    val handicap: Int = 0,
    /** 黑方是否为人类（用于对弈模式） */
    val blackHuman: Boolean = true,
    /** 白方是否为人类 */
    val whiteHuman: Boolean = false,
) {
    private val board: Board = Board(boardSize)
    private val moves: MutableList<RecordedMove> = ArrayList()
    /** 让子的初始摆位 */
    private val setupStones: MutableList<Pair<Int, Stone>> = ArrayList()
    /** 当前指针（指向 moves 中已应用的下标，等于 moves.size 表示在末尾） */
    var cursor: Int = 0
        private set

    /** 起始行棋方：让子棋由白方先行，否则黑方先行 */
    private val startTurn: Stone = if (handicap > 0) Stone.WHITE else Stone.BLACK

    init {
        if (handicap in 1..9) applyHandicap(handicap)
    }

    val size: Int get() = boardSize

    fun board(): Board = board

    /** 让子初始摆位（只读视图） */
    fun setupStones(): List<Pair<Int, Stone>> = setupStones.toList()

    /** 当前该谁下：从 [startTurn] 起，每已完成一手（Play/Pass）交替一次 */
    fun turn(): Stone {
        val plays = moves.take(cursor).count { it.move is Move.Play || it.move is Move.Pass }
        return if (plays % 2 == 0) startTurn else startTurn.other()
    }

    private fun Stone.other(): Stone = if (this == Stone.BLACK) Stone.WHITE else Stone.BLACK

    private fun applyHandicap(stones: Int) {
        if (boardSize != 19 && boardSize != 13 && boardSize != 9) return
        val starPoints = starPoints(boardSize)
        val n = stones.coerceAtMost(starPoints.size)
        for (i in 0 until n) {
            val p = starPoints[i]
            board.setStone(p, Stone.BLACK)
            setupStones.add(p to Stone.BLACK)
        }
    }

    /** 手动添加摆位棋子（用于 SGF 的 AB/AW，不计入手数也不影响 [startTurn]） */
    fun addSetupStone(point: Int, stone: Stone) {
        if (point < 0 || point >= boardSize * boardSize) return
        board.setStone(point, stone)
        setupStones.add(point to stone)
    }

    private fun starPoints(s: Int): List<Int> {
        // 返回星位索引（含天元），让子顺序按习惯
        return when (s) {
            19 -> listOf(
                coord(3, 3), coord(15, 15), coord(3, 15), coord(15, 3),
                coord(9, 9), coord(9, 3), coord(9, 15), coord(3, 9), coord(15, 9)
            )
            13 -> listOf(coord(3, 3), coord(9, 9), coord(3, 9), coord(9, 3), coord(6, 6))
            9 -> listOf(coord(2, 2), coord(6, 6), coord(2, 6), coord(6, 2), coord(4, 4))
            else -> emptyList()
        }
    }

    private fun coord(row: Int, col: Int): Int = row * boardSize + col

    /** 落子（按当前回合颜色），成功返回结果，失败抛 [IllegalMoveException] */
    fun play(point: Int): PlayResult = play(point, turn())

    /** 按指定颜色落子（用于 SGF 回放、强制落子），成功返回结果，失败抛异常 */
    fun play(point: Int, stone: Stone): PlayResult {
        require(cursor == moves.size) { "导航非末尾时不可落子，请先回到最新" }
        val result = board.play(point, stone)
        moves.add(RecordedMove(Move.Play(point), result, stone))
        cursor = moves.size
        return result
    }

    /** 虚手（按当前回合颜色） */
    fun pass(): Move = pass(turn())

    /** 按指定颜色的虚手 */
    fun pass(stone: Stone): Move {
        require(cursor == moves.size)
        moves.add(RecordedMove(Move.Pass, null, stone))
        cursor = moves.size
        return Move.Pass
    }

    fun resign() {
        require(cursor == moves.size)
        moves.add(RecordedMove(Move.Resign, null, null))
        cursor = moves.size
    }

    /** 悔棋：移除最后一手 */
    fun undoLast(): RecordedMove? {
        if (moves.isEmpty()) return null
        val last = moves.removeAt(moves.lastIndex)
        cursor = moves.size
        rebuild()
        return last
    }

    /** 跳转到第 [index] 步之后的状态（0=初始局面） */
    fun gotoIndex(index: Int) {
        val target = index.coerceIn(0, moves.size)
        cursor = target
        rebuild()
    }

    private fun rebuild() {
        board.clear()
        for ((p, s) in setupStones) board.setStone(p, s)
        for (i in 0 until cursor) {
            val rm = moves[i]
            when (val m = rm.move) {
                is Move.Play -> {
                    val st = rm.stone ?: turnAt(i)
                    try { board.play(m.point, st) } catch (_: IllegalMoveException) {}
                }
                Move.Pass, Move.Resign -> Unit
            }
        }
    }

    /** 仅在历史缺失 stone 时兜底推算（一般不使用） */
    private fun turnAt(moveIndex: Int): Stone {
        val plays = setupStones.size + (0 until moveIndex).count {
            moves[it].move is Move.Play || moves[it].move is Move.Pass
        }
        return if (plays % 2 == 0) startTurn else startTurn.other()
    }

    fun totalMoves(): Int = moves.size
    fun currentIndex(): Int = cursor
    fun isAtEnd(): Boolean = cursor == moves.size
    fun movesList(): List<RecordedMove> = moves.toList()

    /** 上一手落子点（用于 UI 标记），无则 -1 */
    fun lastMovePoint(): Int {
        if (cursor == 0) return -1
        val m = moves[cursor - 1].move
        return if (m is Move.Play) m.point else -1
    }

    /** 双方连续 pass 视为终局 */
    fun isDoublePass(): Boolean {
        if (cursor < 2) return false
        val a = moves[cursor - 1].move
        val b = moves[cursor - 2].move
        return a is Move.Pass && b is Move.Pass
    }

    /** 终局数子（黑分, 白分） */
    fun score(): Pair<Float, Float> = board.areaScore()
}

/** 历史中的一手，含可选落子结果与该手棋的颜色（resign 为 null） */
data class RecordedMove(
    val move: Move,
    val result: PlayResult?,
    val stone: Stone? = null
)
