package com.badukai.app.ui.board

import com.badukai.app.analysis.AnalysisResult
import com.badukai.app.core.Board
import com.badukai.app.core.Stone

/**
 * 棋盘渲染模型：把 [GameState] 的当前快照 + 可选分析结果打包给 [BoardCanvas] 绘制。
 */
data class BoardRenderModel(
    val size: Int,
    val stones: List<Pair<Int, Stone>>,      // (point, stone)
    val lastMove: Int = -1,                  // 上一手落子点
    val koPoint: Int = -1,                   // 劫禁点（可选标记）
    val analysis: AnalysisResult? = null,    // 候选选点叠加
    val showCoords: Boolean = true,
    val interactive: Boolean = true,
    val showSuggestions: Boolean = true,
) {
    companion object {
        fun from(board: Board, lastMove: Int = -1, analysis: AnalysisResult? = null,
                 showCoords: Boolean = true, interactive: Boolean = true,
                 showSuggestions: Boolean = true): BoardRenderModel =
            BoardRenderModel(
                size = board.size,
                stones = board.snapshot(),
                lastMove = lastMove,
                analysis = analysis,
                showCoords = showCoords,
                interactive = interactive,
                showSuggestions = showSuggestions,
            )
    }
}

/** 星位坐标（行,列），按棋盘大小返回 */
fun starPoints(size: Int): List<Pair<Int, Int>> = when (size) {
    19 -> listOf(3 to 3, 9 to 9, 15 to 15, 3 to 15, 15 to 3,
        9 to 3, 9 to 15, 3 to 9, 15 to 9)
    13 -> listOf(3 to 3, 9 to 9, 3 to 9, 9 to 3, 6 to 6)
    9 -> listOf(2 to 2, 6 to 6, 2 to 6, 6 to 2, 4 to 4)
    else -> emptyList()
}

/** GTP 列字母（跳过 I），用于坐标标注 */
val GtpLetters = charArrayOf(
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N',
    'O', 'P', 'Q', 'R', 'S', 'T'
)
