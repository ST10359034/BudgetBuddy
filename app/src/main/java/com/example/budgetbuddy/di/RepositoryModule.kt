package com.example.budgetbuddy.di

import com.example.budgetbuddy.data.db.dao.* // Import all DAOs
import com.example.budgetbuddy.data.repository.*
import com.example.budgetbuddy.util.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideUserRepository(userDao: UserDao): UserRepository {
        // In a real app, you might also inject a remote API service here
        return UserRepository(userDao)
    }

    @Provides
    @Singleton
    fun provideBudgetRepository(
        budgetDao: BudgetDao,
        categoryBudgetDao: CategoryBudgetDao,
        expenseRepository: ExpenseRepository
    ): BudgetRepository {
        return BudgetRepository(budgetDao, categoryBudgetDao, expenseRepository)
    }

    @Provides
    @Singleton
    fun provideExpenseRepository(expenseDao: ExpenseDao): ExpenseRepository {
        return ExpenseRepository(expenseDao)
    }

    @Provides
    @Singleton
    fun provideRewardsRepository(rewardPointsDao: RewardPointsDao, achievementDao: AchievementDao): RewardsRepository {
        return RewardsRepository(rewardPointsDao, achievementDao)
    }

    // You might create a separate AuthRepository or include auth in UserRepository
    @Provides
    @Singleton
    fun provideAuthRepository(
        userDao: UserDao,
        rewardsRepository: RewardsRepository,
        sessionManager: SessionManager
    ): AuthRepository {
        // Pass all dependencies to the constructor
        return AuthRepository(userDao, rewardsRepository, sessionManager)
    }
} 