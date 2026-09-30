package com.badukai.app.sgf

import com.badukai.app.core.GameState
import com.badukai.app.core.IllegalMoveException
import com.badukai.app.core.Stone

/**
 * 把 [SgfCollection] 的主行棋线重建为 [GameState]。
 * 仅取每层第一个子节点（主分支），让子摆位 AB/AW 与 B[]/W[] 按序应用。
 * 落子颜色直接取自 SGF 的 B/W 属性，不依赖 [GameState.turn]。
 */
object SgfGameLoader {

    fun load(text: String): GameState {
        val collection = runCatching { SgfParser.parse(text) }.getOrNull()
            ?: return GameState()
        val root = collection.games.firstOrNull() ?: return GameState()

        val size = root.prop("SZ")?.toIntOrNull() ?: 19
        val komi = root.prop("KM")?.toFloatOrNull() ?: 7.5f
        val handicap = root.prop("HA")?.toIntOrNull() ?: 0

        val state = GameState(boardSize = size, komi = komi, handicap = handicap,
            blackHuman = true, whiteHuman = true)

        // 若 SGF 显式给出 AB/AW 摆位，叠加放置（GameState 已按 HA 摆了默认星位）
        root.properties.firstOrNull { it.id == "AB" }?.values
            ?.forEach { v -> splitCoords(v).forEach { p -> state.addSetupStone(SgfWriter.sgfToPoint(p, size).coerceAtLeast(0), Stone.BLACK) } }
        root.properties.firstOrNull { it.id == "AW" }?.values
            ?.forEach { v -> splitCoords(v).forEach { p -> state.addSetupStone(SgfWriter.sgfToPoint(p, size).coerceAtLeast(0), Stone.WHITE) } }

        // 从第一个子节点开始，按 B[]/W[] 属性落子
        var node: SgfNode? = root.children.firstOrNull()
        while (node != null) {
            val b = node.prop("B")
            val w = node.prop("W")
            when {
                b != null -> applyMove(state, b, Stone.BLACK, size)
                w != null -> applyMove(state, w, Stone.WHITE, size)
                else -> Unit
            }
            node = node.children.firstOrNull()
        }
        // 加载完成后回到末尾
        state.gotoIndex(state.totalMoves())
        return state
    }

    private fun applyMove(state: GameState, coordValue: String, stone: Stone, size: Int) {
        val point = SgfWriter.sgfToPoint(coordValue, size)
        try {
            if (point < 0) state.pass(stone) else state.play(point, stone)
        } catch (_: IllegalMoveException) { /* 跳过非法着 */ }
    }

    private fun splitCoords(values: String): List<String> =
        values.chunked(2).filter { it.length == 2 }
}
