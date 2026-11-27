package com.alraad.data.di

import android.content.Context
import androidx.room.Room
import com.alraad.core.common.contracts.BookmarksRepository
import com.alraad.core.common.contracts.HistoryRepository
import com.alraad.core.common.contracts.SavedStreamsRepository
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.data.db.AlRaadDatabase
import com.alraad.data.repo.DataStoreSettingsRepository
import com.alraad.data.repo.RoomBookmarksRepository
import com.alraad.data.repo.RoomHistoryRepository
import com.alraad.data.repo.RoomSavedStreamsRepository
import dagger.Binds
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
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AlRaadDatabase = Room.databaseBuilder(context, AlRaadDatabase::class.java, "alraad.db").build()

    @Provides fun provideHistoryDao(db: AlRaadDatabase) = db.historyDao()

    @Provides fun provideBookmarkDao(db: AlRaadDatabase) = db.bookmarkDao()

    @Provides fun provideSavedStreamDao(db: AlRaadDatabase) = db.savedStreamDao()

    @Provides fun provideDiagnosticsLogDao(db: AlRaadDatabase) = db.diagnosticsLogDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds abstract fun bindBookmarksRepository(impl: RoomBookmarksRepository): BookmarksRepository

    @Binds abstract fun bindSavedStreamsRepository(impl: RoomSavedStreamsRepository): SavedStreamsRepository

    @Binds abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
