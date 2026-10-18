package com.kazumi.port.data.db

import android.content.Context

/**
 * Kazumi 本地数据库 - 原生 SQLite 门面
 *
 * 替代原 Room 版 KazumiDatabase。保持类名、包名及对外方法签名不变
 * （getInstance / historyDao / collectDao / searchHistoryDao /
 * shieldDao / downloadDao），因此 KazumiApp 及上层调用方零改动。
 *
 * 内部委托给 [KazumiSQLiteHelper] + [KazumiSQLiteDaos]，
 * 零 Room / KSP 注解处理，任何编译器均可编译。
 */
class KazumiDatabase private constructor(
    private val daos: KazumiSQLiteDaos
) {
    fun historyDao(): HistoryDao = daos.historyDao
    fun collectDao(): CollectDao = daos.collectDao
    fun searchHistoryDao(): SearchHistoryDao = daos.searchHistoryDao
    fun shieldDao(): ShieldDao = daos.shieldDao
    fun downloadDao(): DownloadDao = daos.downloadDao

    companion object {
        @Volatile
        private var INSTANCE: KazumiDatabase? = null

        fun getInstance(context: Context): KazumiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val helper = KazumiSQLiteHelper(context.applicationContext)
                    KazumiDatabase(KazumiSQLiteDaos(helper))
                }.also { INSTANCE = it }
            }
        }
    }
}