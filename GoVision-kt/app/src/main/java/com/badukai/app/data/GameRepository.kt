package com.badukai.app.data

import com.badukai.app.core.GameState
import com.badukai.app.sgf.SgfParser
import com.badukai.app.sgf.SgfWriter
import kotlinx.coroutines.flow.Flow

/**
 * 对局仓库：在 [GameDao] 之上封装业务语义。
 *
 * - [save]/[update] 接收 [GameState]，经 [SgfWriter] 序列化为 SGF 文本落库；
 * - [importFromSgf] 直接存外部 SGF 文本，并尽量从文本中提取元信息。
 *
 * 本模块只负责存储 SGF 文本，不做 GameState 重建（重建留给上层使用 SgfParser）。
 */
class GameRepository(private val dao: GameDao) {

    /** 保存一局新对弈，返回新记录 id */
    suspend fun save(
        state: GameState,
        title: String,
        blackName: String,
        whiteName: String,
        result: String?
    ): Long {
        val sgf = SgfWriter.write(SgfWriter.fromGameState(state))
        val now = System.currentTimeMillis()
        val entity = GameEntity(
            title = title,
            blackName = blackName,
            whiteName = whiteName,
            boardSize = state.boardSize,
            komi = state.komi,
            handicap = state.handicap,
            sgf = sgf,
            result = result,
            createdAt = now,
            updatedAt = now
        )
        return dao.insert(entity)
    }

    /** 更新已有对局（按 [id] 定位），保留原 createdAt */
    suspend fun update(
        id: Long,
        state: GameState,
        title: String,
        blackName: String,
        whiteName: String,
        result: String?
    ) {
        val existing = dao.getById(id) ?: return
        val sgf = SgfWriter.write(SgfWriter.fromGameState(state))
        val updated = existing.copy(
            title = title,
            blackName = blackName,
            whiteName = whiteName,
            boardSize = state.boardSize,
            komi = state.komi,
            handicap = state.handicap,
            sgf = sgf,
            result = result,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
    }

    /** 删除指定对局 */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    /** 观察全部对局（按 updatedAt 倒序） */
    fun observeAll(): Flow<List<GameEntity>> = dao.observeAll()

    /** 按 id 取单局 */
    suspend fun getById(id: Long): GameEntity? = dao.getById(id)

    /**
     * 从外部 SGF 文本导入为一局记录，返回新记录 id。
     *
     * 直接原样存储 SGF 文本；棋盘大小/贴目/让子/黑白名/结果尽量从 SGF 根节点
     * 属性（SZ/KM/HA/PB/PW/RE）解析提取，解析失败或缺失时：
     * - 棋盘大小默认 19；
     * - 贴目默认 7.5；
     * - 让子默认 0；
     * - 黑白名默认空串；
     * - 结果默认 null。
     */
    suspend fun importFromSgf(text: String, title: String): Long {
        var boardSize = 19
        var komi = 7.5f
        var handicap = 0
        var blackName = ""
        var whiteName = ""
        var result: String? = null

        // 尝试解析以提取元信息；解析失败则保留上述默认值
        try {
            val collection = SgfParser.parse(text)
            val root = collection.games.firstOrNull()
            if (root != null) {
                root.prop("SZ")?.toIntOrNull()?.let { if (it in 1..52) boardSize = it }
                root.prop("KM")?.toFloatOrNull()?.let { komi = it }
                root.prop("HA")?.toIntOrNull()?.let { if (it >= 0) handicap = it }
                root.prop("PB")?.let { blackName = it }
                root.prop("PW")?.let { whiteName = it }
                root.prop("RE")?.let { result = it }
            }
        } catch (_: Throwable) {
            // 忽略解析错误，使用默认值
        }

        val now = System.currentTimeMillis()
        val entity = GameEntity(
            title = title,
            blackName = blackName,
            whiteName = whiteName,
            boardSize = boardSize,
            komi = komi,
            handicap = handicap,
            sgf = text,
            result = result,
            createdAt = now,
            updatedAt = now
        )
        return dao.insert(entity)
    }
}
