package com.plate.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun providePlateDatabase(@ApplicationContext context: Context): PlateDatabase =
        Room.databaseBuilder(context, PlateDatabase::class.java, "plate.db")
            // Room mirrors the server's source of truth, so it's safe to rebuild on schema change
            // rather than ship migrations for a cache (matches Spotter's AppModule).
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDiaryDao(db: PlateDatabase): DiaryDao = db.diaryDao()
}
