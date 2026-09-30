package com.badukai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一局对弈在数据库中的持久化表示。
 *
 * 完整棋谱以 SGF 文本形式存于 [sgf] 字段，元信息（黑白名、棋盘大小、贴目、
 * 让子、结果）冗余存储以便列表展示与检索，无需解析 SGF 即可渲染。
 */
@Entity(tableName = "games")
data class GameEntity(
    /** 自增主键，0 表示尚未插入 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对局标题（用户可编辑） */
    val title: String,
    /** 黑方姓名 */
    val blackName: String,
    /** 白方姓名 */
    val whiteName: String,
    /** 棋盘大小（通常 9/13/19） */
    val boardSize: Int,
    /** 贴目 */
    val komi: Float,
    /** 让子数（0 表示分先） */
    val handicap: Int,
    /** 完整 SGF 文本 */
    val sgf: String,
    /** 对局结果，如 "B+2.5" / "W+R" / "B+T"，未终局为 null */
    val result: String?,
    /** 创建时间戳（毫秒） */
    val createdAt: Long,
    /** 最近更新时间戳（毫秒） */
    val updatedAt: Long
)
