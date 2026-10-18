package com.kazumi.port.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Kazumi 原生 SQLite 数据库（替代 Room）
 *
 * 背景：CodeAssist 环境的 KSP 处理器会被系统 boot classpath 的旧版
 * commons-codec 抢占（encodeHexString 缺失），Room 注解处理在此环境必崩。
 * 改用 Android 原生 SQLiteOpenHelper，零注解处理，任何编译器均可编译。
 *
 * 表结构与原 Room schema 完全一致（不含外键，Room 也未启用）。
 */
class KazumiSQLiteHelper(context: Context) :
    SQLiteOpenHelper(context, "kazumi.db", null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS histories (
                key TEXT PRIMARY KEY NOT NULL,
                bangumiId INTEGER NOT NULL,
                bangumiJson TEXT NOT NULL,
                lastWatchEpisode INTEGER NOT NULL,
                lastWatchEpisodeName TEXT NOT NULL,
                adapterName TEXT NOT NULL,
                lastWatchTime INTEGER NOT NULL,
                lastSrc TEXT NOT NULL,
                entryKind TEXT NOT NULL,
                episodePageUrl TEXT NOT NULL,
                progressJson TEXT NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_histories_adapterName_bangumiId_entryKind " +
                "ON histories(adapterName, bangumiId, entryKind)"
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS collectibles (
                bangumiId INTEGER PRIMARY KEY NOT NULL,
                bangumiJson TEXT NOT NULL,
                time INTEGER NOT NULL,
                type INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_collectibles_type ON collectibles(type)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS search_history (
                timestamp INTEGER PRIMARY KEY NOT NULL,
                keyword TEXT NOT NULL
            )"""
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS danmaku_shield (
                content TEXT PRIMARY KEY NOT NULL
            )"""
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS downloads (
                key TEXT PRIMARY KEY NOT NULL,
                bangumiId INTEGER NOT NULL,
                bangumiName TEXT NOT NULL,
                bangumiCover TEXT NOT NULL,
                pluginName TEXT NOT NULL,
                episodesJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )"""
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS download_episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                recordKey TEXT NOT NULL,
                episodeNumber INTEGER NOT NULL,
                episodeName TEXT NOT NULL,
                road INTEGER NOT NULL,
                status INTEGER NOT NULL,
                progressPercent REAL NOT NULL,
                totalSegments INTEGER NOT NULL,
                downloadedSegments INTEGER NOT NULL,
                localM3u8Path TEXT NOT NULL,
                downloadDirectory TEXT NOT NULL,
                networkM3u8Url TEXT NOT NULL,
                completedAt INTEGER,
                errorMessage TEXT NOT NULL,
                totalBytes INTEGER NOT NULL,
                episodePageUrl TEXT NOT NULL,
                danmakuData TEXT NOT NULL,
                danDanBangumiID INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_download_episodes_recordKey " +
                "ON download_episodes(recordKey)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 与 Room fallbackToDestructiveMigration 行为一致：直接重建
        db.execSQL("DROP TABLE IF EXISTS histories")
        db.execSQL("DROP TABLE IF EXISTS collectibles")
        db.execSQL("DROP TABLE IF EXISTS search_history")
        db.execSQL("DROP TABLE IF EXISTS danmaku_shield")
        db.execSQL("DROP TABLE IF EXISTS downloads")
        db.execSQL("DROP TABLE IF EXISTS download_episodes")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val DATABASE_VERSION = 1
    }
}