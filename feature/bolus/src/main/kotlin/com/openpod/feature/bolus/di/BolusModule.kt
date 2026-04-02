package com.openpod.feature.bolus.di

import com.openpod.domain.bolus.BolusSafetyValidator
import com.openpod.domain.pod.PodManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object BolusModule {

    @Provides
    @Singleton
    fun provideBolusSafetyValidator(podManager: PodManager): BolusSafetyValidator =
        BolusSafetyValidator(podManager)
}
