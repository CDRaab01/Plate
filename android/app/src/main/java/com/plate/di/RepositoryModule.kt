package com.plate.di

import com.plate.data.repository.AuthRepository
import com.plate.data.repository.AuthRepositoryImpl
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.FoodRepositoryImpl
import com.plate.data.repository.GoalRepository
import com.plate.data.repository.GoalRepositoryImpl
import com.plate.data.repository.LogRepository
import com.plate.data.repository.LogRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindFoodRepository(impl: FoodRepositoryImpl): FoodRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(impl: LogRepositoryImpl): LogRepository

    @Binds
    @Singleton
    abstract fun bindGoalRepository(impl: GoalRepositoryImpl): GoalRepository
}
