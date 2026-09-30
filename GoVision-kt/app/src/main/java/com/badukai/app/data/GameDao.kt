package com.badukai.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 对 [games] 表的访问对象。
 *
 * 写操作均为 suspend，配合协程避免阻塞 UI；列表查询返回 [Flow] 以支持响应式观察。
 */
@Dao
interface GameDao {
    /** 新增一局，返回自增 id */
    @Insert
    suspend fun insert(entity: GameEntity): Long

    /** 更新一局（按主键匹配） */
    @Update
    suspend fun update(entity: GameEntity)

    /** 删除指定实体 */
    @Delete
    suspend fun delete(entity: GameEntity)

    /** 观察全部对局，按最近更新时间倒序 */
    @Query("SELECT * FROM games ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<GameEntity>>

    /** 按 id 取单局，不存在返回 null */
    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getById(id: Long): GameEntity?

    /** 按 id 删除 */
    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 对局总数 */
    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int
}
