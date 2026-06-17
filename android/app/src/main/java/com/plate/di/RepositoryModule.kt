package com.plate.di

import com.plate.data.repository.AuthRepository
import com.plate.data.repository.AuthRepositoryImpl
import com.plate.data.repository.CoachRepository
import com.plate.data.repository.CoachRepositoryImpl
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.FoodRepositoryImpl
import com.plate.data.repository.GoalRepository
import com.plate.data.repository.GoalRepositoryImpl
import com.plate.data.repository.LogRepository
import com.plate.data.repository.LogRepositoryImpl
import com.plate.data.repository.RecipeRepository
import com.plate.data.repository.RecipeRepositoryImpl
import com.plate.data.repository.SummaryRepository
import com.plate.data.repository.SummaryRepositoryImpl
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

    @Binds
    @Singleton
    abstract fun bindCoachRepository(impl: CoachRepositoryImpl): CoachRepository

    @Binds
    @Singleton
    abstract fun bindRecipeRepository(impl: RecipeRepositoryImpl): RecipeRepository

    @Binds
    @Singleton
    abstract fun bindSummaryRepository(impl: SummaryRepositoryImpl): SummaryRepository
}
