package com.badukai.app.sgf

import com.badukai.app.core.GameState
import com.badukai.app.core.Move
import com.badukai.app.core.Stone

/**
 * SGF 写出器：把 [SgfCollection] 或 [GameState] 序列化为规范文本。
 *
 * 输出遵循 SGF FF[4]：单子链式输出 `(;A;B;C)`，分支另起子树 `(;A(;B)(;C))`。
 * 属性值中的 `]` 与 `\` 会被转义。
 */
object SgfWriter {

    /**
     * 把 [GameState] 转为 SGF 集合。
     *
     * 根节点含：FF[4] GM[1] SZ[size] KM[komi] HA[handicap](>0 时) PB[黑方] PW[白方]。
     * 让子摆位用 AB[ab][cd]...；主行棋按 B[xy]/W[xy] 顺序输出；pass 用 B[]/W[]。
     * Resign 不输出节点，改在根节点 RE 属性备注（如 B+R / W+R）。
     */
    fun fromGameState(state: GameState): SgfCollection {
        val root = SgfNode()
        root.addProp("FF", "4")
        root.addProp("GM", "1")
        root.addProp("SZ", state.boardSize.toString())
        root.addProp("KM", state.komi.toString())
        if (state.handicap > 0) {
            root.addProp("HA", state.handicap.toString())
        }
        root.addProp("PB", "黑方")
        root.addProp("PW", "白方")

        // 让子摆位：按颜色分组写入 AB / AW
        val setup = state.setupStones()
        if (setup.isNotEmpty()) {
            val black = setup.filter { it.second == Stone.BLACK }
                .map { pointToSgf(it.first, state.boardSize) }
            val white = setup.filter { it.second == Stone.WHITE }
                .map { pointToSgf(it.first, state.boardSize) }
            if (black.isNotEmpty()) root.properties.add(SgfProperty("AB", black))
            if (white.isNotEmpty()) root.properties.add(SgfProperty("AW", white))
        }

        // 主行棋：按 movesList 顺序输出 B/W 节点，颜色取自 RecordedMove.stone
        var current = root
        var resignStone: Stone? = null
        for (recorded in state.movesList()) {
            val stone = recorded.stone ?: Stone.BLACK
            when (val move = recorded.move) {
                is Move.Play -> {
                    val node = SgfNode()
                    val id = if (stone == Stone.BLACK) "B" else "W"
                    node.properties.add(SgfProperty(id, listOf(pointToSgf(move.point, state.boardSize))))
                    current.children.add(node)
                    current = node
                }
                Move.Pass -> {
                    val node = SgfNode()
                    val id = if (stone == Stone.BLACK) "B" else "W"
                    node.properties.add(SgfProperty(id, listOf(""))) // B[] / W[]
                    current.children.add(node)
                    current = node
                }
                Move.Resign -> {
                    resignStone = stone
                }
            }
        }
        // Resign 在 RE 属性备注：认输方负
        if (resignStone != null) {
            root.addProp("RE", if (resignStone == Stone.BLACK) "W+R" else "B+R")
        }

        return SgfCollection(listOf(root))
    }

    /**
     * 序列化 [SgfCollection] 为 SGF 文本。
     * 单子链式输出，多子分支递归输出子树；属性值转义 `]` `\`。
     */
    fun write(collection: SgfCollection): String {
        val sb = StringBuilder()
        for ((i, root) in collection.games.withIndex()) {
            if (i > 0) sb.append('\n')
            writeTree(sb, root)
        }
        return sb.toString()
    }

    /**
     * point -> SGF 双字母小写坐标（列在前、行在后，均从 0 起 a-s）。
     * 越界或 pass(-1) 返回空串 ""。
     */
    fun pointToSgf(point: Int, size: Int): String {
        if (point < 0 || point >= size * size) return ""
        val col = point % size
        val row = point / size
        if (col > 18 || row > 18) return "" // SGF 仅支持 a-s（0-18）
        val colChar = 'a' + col
        val rowChar = 'a' + row
        return "$colChar$rowChar"
    }

    /**
     * SGF 坐标串 -> point。空串或 "tt"(size<=19) 视为 pass 返回 -1。
     * 非法坐标亦返回 -1。
     */
    fun sgfToPoint(sgf: String, size: Int): Int {
        val c = sgf.trim()
        if (c.isEmpty()) return -1
        if (c == "tt" && size <= 19) return -1
        if (c.length < 2) return -1
        val colChar = c[0]
        val rowChar = c[1]
        if (colChar < 'a' || colChar > 's' || rowChar < 'a' || rowChar > 's') return -1
        val col = colChar - 'a'
        val row = rowChar - 'a'
        if (col >= size || row >= size) return -1
        return row * size + col
    }

    // ---- 内部序列化实现 ----

    /** 写一棵子树：'(' 节点链 (分支)* ')' */
    private fun writeTree(sb: StringBuilder, node: SgfNode) {
        sb.append('(')
        var n: SgfNode? = node
        while (n != null) {
            sb.append(';')
            writeNode(sb, n)
            n = when (n.children.size) {
                0 -> null
                1 -> n.children[0] // 单子：续写主链
                else -> {
                    // 多子：每个分支另起子树
                    for (child in n.children) {
                        writeTree(sb, child)
                    }
                    null
                }
            }
        }
        sb.append(')')
    }

    /** 写一个节点的全部属性 */
    private fun writeNode(sb: StringBuilder, node: SgfNode) {
        for (p in node.properties) {
            sb.append(p.id)
            for (v in p.values) {
                sb.append('[')
                sb.append(escape(v))
                sb.append(']')
            }
        }
    }

    /** 转义属性值：`]` -> `\]`，`\` -> `\\` */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                ']' -> sb.append("\\]")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
