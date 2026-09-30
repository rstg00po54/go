package com.badukai.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用主数据库：仅含 [games] 一张表。
 *
 * 单例持有，禁用主线程查询以强制走协程；版本升级采用破坏式迁移
 * （[fallbackToDestructiveMigration]），因为这是开发期复刻项目，无需保留旧数据。
 */
@Database(entities = [GameEntity::class], version = 1, exportSchema = false)
abstract class GameDatabase : RoomDatabase() {
    /** 对局表的 DAO */
    abstract fun gameDao(): GameDao

    companion object {
        @Volatile
        private var INSTANCE: GameDatabase? = null

        /** 获取数据库单例，必要时构建 */
        fun get(context: Context): GameDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GameDatabase::class.java,
                    "badukai.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
