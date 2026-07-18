package com.plate.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plate.data.local.db.BlobCacheDao
import com.plate.data.local.db.BodyMetricDao
import com.plate.data.local.db.DiaryDao
import com.plate.data.local.db.PlateDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v1 → v2: add `body_metrics` only. Additive (not destructive) so the offline
     * `pending_quick_add` queue — the one table with un-synced local writes — survives the upgrade.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS body_metrics " +
                    "(id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, " +
                    "weightKg REAL NOT NULL, bodyfat REAL)"
            )
        }
    }

    /**
     * v2 → v3 (offline support round). Additive for the same reason as v1 → v2 — the offline
     * queues must survive:
     *  - `body_metrics` gains the write-through queue columns; every existing row came from the
     *    server, so it is seeded as already-synced (`serverId = id`, `syncPending = 0`).
     *  - `blob_cache` backs the read-through caches (goal / weight trend / weekly summary /
     *    recent foods).
     *  - `cached_day.cachedAtMs` stamps when a diary day was fetched so the stale banner can say
     *    "as of …" (0 = cached pre-v3, age unknown).
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE body_metrics ADD COLUMN serverId TEXT")
            db.execSQL("ALTER TABLE body_metrics ADD COLUMN syncPending INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE body_metrics SET serverId = id")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS blob_cache " +
                    "(`key` TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, cachedAtMs INTEGER NOT NULL)"
            )
            db.execSQL("ALTER TABLE cached_day ADD COLUMN cachedAtMs INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun providePlateDatabase(@ApplicationContext context: Context): PlateDatabase =
        Room.databaseBuilder(context, PlateDatabase::class.java, "plate.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // Backstop only: real migrations are preferred so local-only data (the offline
            // quick-add queue) isn't lost on a schema change.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDiaryDao(db: PlateDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideBodyMetricDao(db: PlateDatabase): BodyMetricDao = db.bodyMetricDao()

    @Provides
    fun provideBlobCacheDao(db: PlateDatabase): BlobCacheDao = db.blobCacheDao()
}
