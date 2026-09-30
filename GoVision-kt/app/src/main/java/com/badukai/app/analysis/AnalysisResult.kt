package com.badukai.app.analysis

import com.badukai.app.core.Stone

/**
 * 单个候选落子的分析信息。
 *
 * @param move GTP 坐标（如 "Q16"）
 * @param winrate 黑方胜率（0-100，由 KataGo 的 0-10000 折算）
 * @param visits 该手的搜索访问数
 * @param pv 主变化（GTP 坐标序列）
 * @param scoreLead 黑方领先目数（KataGo），不可用时为 null
 * @param order 引擎给出的排序值
 */
data class AnalysisMove(
    val move: String,
    val winrate: Float,
    val visits: Int,
    val pv: List<String>,
    val scoreLead: Float? = null,
    val order: Int = 0
)

/**
 * 一次分析快照。
 *
 * @param moveInfos 候选落子列表（按 visits 降序）
 * @param rootWinrate 当前行棋方胜率（0-100）
 * @param visits 总访问数
 * @param scoreLead 黑方领先目数（KataGo），不可用时为 null
 */
data class AnalysisResult(
    val moveInfos: List<AnalysisMove>,
    val rootWinrate: Float,
    val visits: Int,
    val scoreLead: Float? = null
)

/**
 * 解析 KataGo `lz-analyze` / `kata-analyze` 的单行输出。
 *
 * 示例行：
 * `info move Q16 visits 800 winrate 5400 scoreLead 3.5 pv Q16 C4 Q4 info move D4 visits 200 winrate 5100 pv D4`
 *
 * 其中 winrate 为 0-10000 的黑胜率（KataGo 格式）；解析后 [AnalysisMove.winrate] 折算为 0-100，
 * [AnalysisResult.rootWinrate] 根据当前行棋方 [toMove] 转换为"当前行棋方胜率"
 * （黑方行棋时直接取黑胜率，白方行棋时取 100 - 黑胜率）。
 * 解析容错：缺字段使用默认值，无法识别的行返回 null。
 */
object AnalysisParser {

    fun parse(line: String, toMove: Stone): AnalysisResult? {
        var content = line.trim()
        if (content.isEmpty()) return null
        // 去除可能的 GTP 响应头 "="
        if (content.startsWith("=")) {
            content = content.removePrefix("=").trim()
        }
        if (!content.startsWith("info")) return null

        val tokens = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val moves = ArrayList<AnalysisMove>()

        var i = 0
        while (i < tokens.size) {
            if (tokens[i] != "info") {
                i++
                continue
            }
            i++ // 跳过 "info"

            var move = ""
            var winrate = 0f
            var visits = 0
            var scoreLead: Float? = null
            var order = 0
            val pv = ArrayList<String>()

            while (i < tokens.size && tokens[i] != "info") {
                when (tokens[i]) {
                    "move" -> {
                        if (i + 1 < tokens.size) { move = tokens[i + 1]; i += 2 } else i++
                    }
                    "winrate" -> {
                        if (i + 1 < tokens.size) {
                            winrate = tokens[i + 1].toFloatOrNull()?.let { it / 100f } ?: 0f
                            i += 2
                        } else i++
                    }
                    "visits" -> {
                        if (i + 1 < tokens.size) {
                            visits = tokens[i + 1].toIntOrNull() ?: 0
                            i += 2
                        } else i++
                    }
                    "scoreLead" -> {
                        if (i + 1 < tokens.size) {
                            scoreLead = tokens[i + 1].toFloatOrNull()
                            i += 2
                        } else i++
                    }
                    "order" -> {
                        if (i + 1 < tokens.size) {
                            order = tokens[i + 1].toIntOrNull() ?: 0
                            i += 2
                        } else i++
                    }
                    "pv" -> {
                        i++ // 跳过 "pv"，后续坐标直到下一个 "info" 或行末
                        while (i < tokens.size && tokens[i] != "info") {
                            pv.add(tokens[i])
                            i++
                        }
                    }
                    else -> {
                        // 未知字段：按 key-value 跳过；若已是末尾或下一个为 "info" 则单步前进
                        if (i + 1 < tokens.size && tokens[i + 1] != "info") i += 2 else i++
                    }
                }
            }

            if (move.isNotEmpty()) {
                moves.add(
                    AnalysisMove(
                        move = move,
                        winrate = winrate,
                        visits = visits,
                        pv = pv,
                        scoreLead = scoreLead,
                        order = order
                    )
                )
            }
        }

        if (moves.isEmpty()) return null

        // 按 visits 降序排序
        moves.sortByDescending { it.visits }

        // 根胜率：取访问最多的一手的黑胜率，按当前行棋方转换
        val topBlackWinrate = moves.first().winrate
        val rootWinrate = if (toMove == Stone.BLACK) topBlackWinrate else 100f - topBlackWinrate
        val totalVisits = moves.sumOf { it.visits }
        val rootScoreLead = moves.firstOrNull { it.scoreLead != null }?.scoreLead

        return AnalysisResult(
            moveInfos = moves,
            rootWinrate = rootWinrate,
            visits = totalVisits,
            scoreLead = rootScoreLead
        )
    }
}
