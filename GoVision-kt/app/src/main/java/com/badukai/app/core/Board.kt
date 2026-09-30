package com.badukai.app.core

/**
 * 围棋棋盘状态：维护落子、提子、劫禁、数子。
 * 内部用 [ByteArray] 存储（0 空 / 1 黑 / 2 白）以降低内存占用。
 */
class Board(val size: Int) {

    private val cells: ByteArray = ByteArray(size * size)
    /** 简单劫禁点（上一手提单子后形成），不可立即回提 */
    var koPoint: Int = -1
        private set

    private fun stoneAt(point: Int): Stone? = when (cells[point].toInt()) {
        1 -> Stone.BLACK
        2 -> Stone.WHITE
        else -> null
    }

    fun get(point: Int): Stone? {
        if (point < 0 || point >= cells.size) return null
        return stoneAt(point)
    }

    fun isEmpty(point: Int): Boolean = cells[point].toInt() == 0

    /** 邻接点（4 向） */
    private fun neighbors(point: Int): List<Int> {
        val col = point % size
        val row = point / size
        val result = ArrayList<Int>(4)
        if (row > 0) result.add(point - size)
        if (row < size - 1) result.add(point + size)
        if (col > 0) result.add(point - 1)
        if (col < size - 1) result.add(point + 1)
        return result
    }

    /**
     * 计算以 [seed] 为起点的连通棋块及其气。
     * 返回 (块成员集合, 气数, 气点集合)。
     */
    private fun groupAt(seed: Int): Triple<List<Int>, Int, Set<Int>> {
        val color = cells[seed]
        require(color.toInt() != 0)
        val members = ArrayList<Int>()
        val liberties = HashSet<Int>()
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(seed)
        visited.add(seed)
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            members.add(p)
            for (n in neighbors(p)) {
                when (cells[n].toInt()) {
                    0 -> liberties.add(n)
                    color.toInt() -> if (visited.add(n)) stack.addLast(n)
                    else -> Unit // 对方棋子
                }
            }
        }
        return Triple(members, liberties.size, liberties)
    }

    /**
     * 尝试落子，成功返回落子信息（含被提子列表），失败抛 [IllegalMoveException]。
     */
    fun play(point: Int, stone: Stone): PlayResult {
        if (point < 0 || point >= cells.size)
            throw IllegalMoveException("落子点越界: $point")
        if (cells[point].toInt() != 0)
            throw IllegalMoveException("该点已有棋子")
        if (point == koPoint)
            throw IllegalMoveException("违反劫禁（不可立即回提）")

        val color = if (stone == Stone.BLACK) 1 else 2
        val opponent = if (stone == Stone.BLACK) 2 else 1
        cells[point] = color.toByte()

        // 提对方无气棋块
        val captured = ArrayList<Int>()
        for (n in neighbors(point)) {
            if (cells[n].toInt() == opponent) {
                val (members, libs, _) = groupAt(n)
                if (libs == 0) {
                    for (m in members) {
                        cells[m] = 0
                        captured.add(m)
                    }
                }
            }
        }

        // 自杀检测
        val (myMembers, myLibs, _) = groupAt(point)
        if (myLibs == 0) {
            // 自杀，回滚
            cells[point] = 0
            for (c in captured) cells[c] = opponent.toByte()
            throw IllegalMoveException("禁着点（自杀）")
        }

        // 劫点记录：仅当本次提子恰好 1 颗、且落子块只有 1 子 1 气、且该气点恰为被提点
        var newKo = -1
        if (captured.size == 1 && myMembers.size == 1 && myLibs == 1) {
            newKo = captured.first()
        }

        val prevKo = koPoint
        koPoint = newKo
        return PlayResult(point, stone, captured, prevKo, newKo)
    }

    /** 回滚一手（用于悔棋 / 探索变化图） */
    fun undo(result: PlayResult) {
        val color = if (result.stone == Stone.BLACK) 1 else 2
        val opponent = if (result.stone == Stone.BLACK) 2 else 1
        cells[result.point] = 0
        for (c in result.captured) cells[c] = opponent.toByte()
        koPoint = result.prevKo
    }

    /** 放置/移除棋子（不计规则，用于摆棋盘、设让子、编辑） */
    fun setStone(point: Int, stone: Stone?) {
        if (point < 0 || point >= cells.size) return
        cells[point] = when (stone) {
            Stone.BLACK -> 1
            Stone.WHITE -> 2
            null -> 0
        }.toByte()
    }

    fun clear() {
        for (i in cells.indices) cells[i] = 0
        koPoint = -1
    }

    /** 深拷贝 */
    fun copy(): Board {
        val b = Board(size)
        System.arraycopy(cells, 0, b.cells, 0, cells.size)
        b.koPoint = koPoint
        return b
    }

    /** 区域数子（中国规则简化版：子+地）。返回 (黑分, 白分)。死活按"无气即死、区域被单方包围归该方"。 */
    fun areaScore(): Pair<Float, Float> {
        // 1. 标记死活：简化处理——两眼活棋判断较复杂，这里采用"纯区域归属"近似：
        //    被单一颜色完全包围的空区归该色；棋子本身各计 1 子。
        val owner = IntArray(cells.size) { -1 } // -1 未定, 0 空, 1 黑, 2 白
        for (i in cells.indices) owner[i] = cells[i].toInt()

        var blackArea = 0f
        var whiteArea = 0f

        // 棋子计数
        for (i in cells.indices) when (owner[i]) {
            1 -> blackArea += 1f
            2 -> whiteArea += 1f
        }

        // 空区域归属（flood fill，若只接触单色则归该色）
        val visited = BooleanArray(cells.size)
        for (i in cells.indices) {
            if (owner[i] != 0 || visited[i]) continue
            val region = ArrayList<Int>()
            val borders = HashSet<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(i)
            visited[i] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                region.add(p)
                for (n in neighbors(p)) {
                    when (owner[n]) {
                        0 -> if (visited[n].not()) { visited[n] = true; stack.addLast(n) }
                        1, 2 -> borders.add(owner[n])
                        else -> Unit
                    }
                }
            }
            if (borders.size == 1) {
                val color = borders.first()
                if (color == 1) blackArea += region.size.toFloat()
                else whiteArea += region.size.toFloat()
            }
        }
        return blackArea to whiteArea
    }

    /** 全部棋子点列表，便于 UI 绘制 */
    fun snapshot(): List<Pair<Int, Stone>> {
        val list = ArrayList<Pair<Int, Stone>>(cells.size)
        for (i in cells.indices) {
            val s = stoneAt(i) ?: continue
            list.add(i to s)
        }
        return list
    }
}

class IllegalMoveException(message: String) : RuntimeException(message)

/** 落子结果，用于回滚与历史记录 */
data class PlayResult(
    val point: Int,
    val stone: Stone,
    val captured: List<Int>,
    val prevKo: Int,
    val newKo: Int
)
