package com.plate.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Provides
    @Singleton
    fun providePlateDatabase(@ApplicationContext context: Context): PlateDatabase =
        Room.databaseBuilder(context, PlateDatabase::class.java, "plate.db")
            .addMigrations(MIGRATION_1_2)
            // Backstop only: real migrations are preferred so local-only data (the offline
            // quick-add queue) isn't lost on a schema change.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDiaryDao(db: PlateDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideBodyMetricDao(db: PlateDatabase): BodyMetricDao = db.bodyMetricDao()
}
