package com.openpod.feature.dashboard.di

import com.openpod.feature.dashboard.BuildConfig
import com.openpod.feature.dashboard.DashboardDataSource
import com.openpod.feature.dashboard.EmulatorDashboardDataSource
import com.openpod.feature.dashboard.MockDashboardDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import timber.log.Timber

/**
 * Hilt module that wires the [DashboardDataSource] interface to its implementation.
 *
 * In emulator mode, binds [EmulatorDashboardDataSource] which polls the
 * emulator for live glucose, IOB, and pod status. Otherwise, uses
 * [MockDashboardDataSource] with static development values.
 */
@Module
@InstallIn(ViewModelComponent::class)
object DashboardModule {

    @Provides
    @ViewModelScoped
    fun provideDashboardDataSource(
        emulatorSource: EmulatorDashboardDataSource,
        mockSource: MockDashboardDataSource,
    ): DashboardDataSource {
        return if (BuildConfig.USE_EMULATOR || BuildConfig.USE_BLE) {
            Timber.i("DashboardModule: Using EmulatorDashboardDataSource")
            emulatorSource
        } else {
            Timber.i("DashboardModule: Using MockDashboardDataSource")
            mockSource
        }
    }
}
